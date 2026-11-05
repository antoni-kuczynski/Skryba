package com.kuczynski.skryba.convert

import com.kuczynski.skryba.pdf.PageWords
import com.kuczynski.skryba.pdf.WordBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Analiza układu tekstu ze słów z położeniem (z PDF albo z OCR):
 * linie, akapity, nagłówki i tabele. Czysty Kotlin, testowalny bez Androida.
 */
object TextLayout {

    class Line(val words: List<WordBox>) {
        val x = words.minOf { it.x }
        val y = words.minOf { it.y }
        val right = words.maxOf { it.x + it.w }
        val bottom = words.maxOf { it.y + it.h }
        val h = median(words.map { it.h })
        val text = words.joinToString(" ") { it.text }
    }

    fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    fun groupLines(words: List<WordBox>): List<Line> {
        val sorted = words.filter { it.text.isNotBlank() }.sortedBy { it.y + it.h / 2 }
        val lines = ArrayList<MutableList<WordBox>>()
        val centers = ArrayList<Float>()
        val heights = ArrayList<Float>()
        for (w in sorted) {
            val c = w.y + w.h / 2
            var idx = -1
            // Szukamy wśród ostatnich linii (słowa są posortowane po wysokości).
            for (i in lines.indices.reversed().take(4)) {
                if (abs(centers[i] - c) < 0.5f * max(heights[i], w.h)) { idx = i; break }
            }
            if (idx == -1) {
                lines += mutableListOf(w)
                centers += c
                heights += w.h
            } else {
                lines[idx] += w
                val n = lines[idx].size
                centers[idx] = (centers[idx] * (n - 1) + c) / n
                heights[idx] = max(heights[idx], w.h)
            }
        }
        return lines.map { l -> Line(l.sortedBy { it.x }) }.sortedBy { it.y }
    }

    data class Paragraph(val text: String, val heading: Int)

    fun paragraphs(page: PageWords): List<Paragraph> {
        val lines = groupLines(page.words)
        if (lines.isEmpty()) return emptyList()
        val medianH = median(lines.map { it.h }).coerceAtLeast(1f)
        val maxRight = lines.maxOf { it.right }
        val minX = lines.minOf { it.x }
        val out = ArrayList<Paragraph>()
        var cur = ArrayList<Line>()

        fun flush() {
            if (cur.isEmpty()) return
            val sb = StringBuilder()
            for (l in cur) {
                val t = l.text.trim()
                if (sb.isEmpty()) {
                    sb.append(t)
                } else if (sb.endsWith("-") && t.firstOrNull()?.isLowerCase() == true) {
                    sb.setLength(sb.length - 1)
                    sb.append(t)
                } else {
                    sb.append(' ').append(t)
                }
            }
            val h = median(cur.map { it.h })
            val level = when {
                h > medianH * 1.7f -> 1
                h > medianH * 1.3f -> 2
                else -> 0
            }
            out += Paragraph(sb.toString(), if (level > 0 && sb.length < 150) level else 0)
            cur = ArrayList()
        }

        for (line in lines) {
            val prev = cur.lastOrNull()
            if (prev != null) {
                val gap = line.y - prev.bottom
                val sizeChange = abs(line.h - prev.h) > 0.25f * medianH
                val prevShort = prev.right < maxRight - (maxRight - minX) * 0.2f
                val endsSentence = prev.text.trimEnd().lastOrNull()?.let { it in ".:!?;" } ?: false
                val indented = line.x > prev.x + medianH * 1.5f
                if (gap > 0.8f * medianH || sizeChange || (prevShort && endsSentence) || (indented && endsSentence)) flush()
            }
            cur += line
        }
        flush()
        return out
    }

    /** Odtwarza tabelę: komórki rozdzielone dużymi odstępami, kolumny dopasowane po pozycji x. */
    fun table(page: PageWords): List<List<String>> {
        val lines = groupLines(page.words)
        if (lines.isEmpty()) return emptyList()
        data class Cell(val x: Float, val right: Float, val text: String)

        val rows = lines.map { line ->
            val cells = ArrayList<Cell>()
            var start = line.words.first()
            var right = start.x + start.w
            val sb = StringBuilder(start.text)
            for (w in line.words.drop(1)) {
                val gap = w.x - right
                if (gap > max(w.h, line.h) * 0.9f) {
                    cells += Cell(start.x, right, sb.toString())
                    sb.setLength(0)
                    start = w
                    sb.append(w.text)
                } else {
                    sb.append(' ').append(w.text)
                }
                right = w.x + w.w
            }
            cells += Cell(start.x, right, sb.toString())
            cells
        }
        // Liczba kolumn = najczęstsza liczba komórek w wierszu (wśród wierszy z co najmniej 2 komórkami).
        // Położenie kolumn = mediany z "pełnych" wierszy. Dzięki temu nagłówek wyrównany do lewej
        // i liczby wyrównane do prawej trafiają do tej samej kolumny.
        val multi = rows.filter { it.size >= 2 }
        val k = multi.groupingBy { it.size }.eachCount().entries
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })?.key ?: 1
        val full = rows.filter { it.size == k }
        val cols = (0 until k).map { c ->
            floatArrayOf(median(full.map { it[c].x }), median(full.map { it[c].right }))
        }
        return rows.map { cells ->
            val row = Array(k) { "" }
            if (cells.size == k) {
                cells.forEachIndexed { i, c -> row[i] = c.text }
            } else {
                for (c in cells) {
                    var best = 0
                    var bestScore = -Float.MAX_VALUE
                    cols.forEachIndexed { i, col ->
                        // Największa część wspólna, a przy jej braku najmniejsza odległość.
                        val overlap = min(c.right, col[1]) - max(c.x, col[0])
                        if (overlap > bestScore) { bestScore = overlap; best = i }
                    }
                    row[best] = if (row[best].isEmpty()) c.text else row[best] + " " + c.text
                }
            }
            row.toList()
        }.let { trimEmptyColumns(it) }
    }

    private fun trimEmptyColumns(rows: List<List<String>>): List<List<String>> {
        if (rows.isEmpty()) return rows
        val n = rows.maxOf { it.size }
        val keep = (0 until n).filter { c -> rows.any { it.getOrElse(c) { "" }.isNotBlank() } }
        return rows.map { r -> keep.map { r.getOrElse(it) { "" } } }
    }

    /** Akapity z gołego tekstu (gdy nie ma pozycji słów). */
    fun paragraphsFromText(text: String): List<String> {
        val lines = text.replace("\r", "").split('\n')
        val maxLen = lines.maxOfOrNull { it.trim().length } ?: 0
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty()) {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) }
                continue
            }
            if (sb.isNotEmpty()) {
                if (sb.endsWith("-")) sb.setLength(sb.length - 1) else sb.append(' ')
            }
            sb.append(l)
            if (l.length < min(maxLen, 80) * 0.6 && l.last() in ".:!?") {
                out += sb.toString()
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}
