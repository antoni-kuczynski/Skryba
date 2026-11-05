package com.kuczynski.skryba.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Rysuje model dokumentu Office do PDF (systemowy PdfDocument, tekst wektorowy). */
object OfficeRenderer {

    private fun headingSize(level: Int): Float? = when (level) {
        1 -> 20f
        2 -> 16f
        3 -> 14f
        in 4..6 -> 12f
        else -> null
    }

    private fun spannable(
        paras: List<Para>,
        defaultSize: Float,
        scale: Float = 1f,
        defaultColor: Int = Color.BLACK,
    ): CharSequence {
        val sb = SpannableStringBuilder()
        paras.forEachIndexed { i, p ->
            if (i > 0) sb.append('\n')
            val pStart = sb.length
            if (p.bullet) {
                val st = sb.length
                sb.append("•  ")
                val size = (p.runs.firstOrNull()?.style?.sizePt ?: defaultSize) * scale
                sb.setSpan(AbsoluteSizeSpan(size.roundToInt().coerceAtLeast(1)), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            for (r in p.runs) {
                val start = sb.length
                sb.append(r.text)
                val end = sb.length
                if (start == end) continue
                val size = (r.style.sizePt ?: headingSize(p.heading) ?: defaultSize) * scale
                sb.setSpan(AbsoluteSizeSpan(size.roundToInt().coerceAtLeast(1)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val bold = r.style.bold || p.heading > 0
                val tf = when {
                    bold && r.style.italic -> Typeface.BOLD_ITALIC
                    bold -> Typeface.BOLD
                    r.style.italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                if (tf != Typeface.NORMAL) sb.setSpan(StyleSpan(tf), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.style.underline) sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(r.style.color ?: defaultColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (sb.length == pStart) {
                // Pusty akapit: zachowujemy wysokość linii.
                sb.append(" ")
                val size = ((p.runs.firstOrNull()?.style?.sizePt ?: defaultSize) * scale).roundToInt().coerceAtLeast(1)
                sb.setSpan(AbsoluteSizeSpan(size), pStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val align = when (p.align) {
                Align.CENTER -> Layout.Alignment.ALIGN_CENTER
                Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            sb.setSpan(AlignmentSpan.Standard(align), pStart, sb.length, Spanned.SPAN_PARAGRAPH)
        }
        return sb
    }

    private fun layout(text: CharSequence, width: Int, justify: Boolean = false, baseSize: Float = 11f): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = baseSize
            color = Color.BLACK
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.12f)
            .setIncludePad(false)
            .setJustificationMode(if (justify) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
            .build()
    }

    /** Zarządza kolejnymi stronami PdfDocument i pozycją kursora w pionie. */
    private class Pager(
        val pdf: PdfDocument,
        var pageW: Float,
        var pageH: Float,
        val ml: Float, val mt: Float, val mr: Float, val mb: Float,
    ) {
        var page: PdfDocument.Page? = null
        var number = 0
        var y = 0f
        val canvas: Canvas get() = page!!.canvas
        val contentW get() = pageW - ml - mr
        val bottom get() = pageH - mb

        fun newPage() {
            page?.let { pdf.finishPage(it) }
            number++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW.roundToInt(), pageH.roundToInt(), number).create())
            y = mt
        }

        fun ensure() {
            if (page == null) newPage()
        }

        fun finish() {
            page?.let { pdf.finishPage(it) }
            page = null
        }

        /** Rysuje układ tekstu, dzieląc go między strony w miejscach przejścia linii. */
        fun drawLayout(l: StaticLayout, x: Float) {
            ensure()
            var line = 0
            while (line < l.lineCount) {
                val available = bottom - y
                var last = line - 1
                while (last + 1 < l.lineCount && l.getLineBottom(last + 1) - l.getLineTop(line) <= available) last++
                if (last < line) {
                    if (y > mt + 1f) {
                        newPage()
                        continue
                    }
                    last = line // Linia wyższa niż strona - rysujemy i tak.
                }
                val top = l.getLineTop(line).toFloat()
                val h = l.getLineBottom(last) - top
                canvas.save()
                canvas.clipRect(x, y, x + l.width, y + h)
                canvas.translate(x, y - top)
                l.draw(canvas)
                canvas.restore()
                y += h
                line = last + 1
                if (line < l.lineCount) newPage()
            }
        }
    }

    // ---------------- Dokument (Word, TXT) ----------------

    fun renderFlow(doc: FlowDocument, out: File) {
        val pdf = PdfDocument()
        try {
            val pg = Pager(pdf, doc.pageWidthPt, doc.pageHeightPt, doc.marginLeft, doc.marginTop, doc.marginRight, doc.marginBottom)
            pg.newPage()
            for (b in doc.blocks) {
                when (b) {
                    is FlowBlock.PageBreak -> pg.newPage()
                    is FlowBlock.P -> {
                        val p = b.para
                        if (p.heading in 1..2 && pg.y > pg.mt) pg.y += 6f
                        val l = layout(spannable(listOf(p), doc.defaultSizePt), (pg.contentW - p.indentPt).roundToInt(), p.align == Align.JUSTIFY, doc.defaultSizePt)
                        // Nagłówek nie powinien zostać sam na dole strony.
                        if (p.heading > 0 && pg.y + l.height + 30f > pg.bottom && pg.y > pg.mt) pg.newPage()
                        pg.drawLayout(l, pg.ml + p.indentPt)
                        pg.y += min(p.spaceAfterPt, 24f)
                    }
                    is FlowBlock.Img -> {
                        val bmp = decodeLimited(b.bytes, 1600) ?: continue
                        var w = b.widthPt
                        var h = b.heightPt
                        val maxH = pg.bottom - pg.mt
                        if (w > pg.contentW) { h *= pg.contentW / w; w = pg.contentW }
                        if (h > maxH) { w *= maxH / h; h = maxH }
                        if (pg.y + h > pg.bottom) pg.newPage()
                        val x = pg.ml + (pg.contentW - w) / 2
                        pg.canvas.drawBitmap(bmp, null, RectF(x, pg.y, x + w, pg.y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                        bmp.recycle()
                        pg.y += h + 6f
                    }
                    is FlowBlock.Table -> drawTable(pg, b, doc.defaultSizePt)
                }
            }
            pg.finish()
            out.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun drawTable(pg: Pager, t: FlowBlock.Table, defaultSize: Float) {
        val cols = max(t.rows.maxOfOrNull { it.size } ?: 0, t.colWidthsPt.size)
        if (cols == 0) return
        val raw = if (t.colWidthsPt.size >= cols) t.colWidthsPt.take(cols) else List(cols) { pg.contentW / cols }
        val sum = raw.sum().coerceAtLeast(1f)
        val widths = raw.map { it * min(1f, pg.contentW / sum) }
        val pad = 4f
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.6f
            color = Color.GRAY
        }
        pg.y += 2f
        for (row in t.rows) {
            val layouts = (0 until cols).map { c ->
                val paras = row.getOrNull(c).orEmpty()
                layout(spannable(paras.ifEmpty { listOf(Para(emptyList())) }, defaultSize), (widths[c] - 2 * pad).roundToInt(), baseSize = defaultSize)
            }
            val rowH = (layouts.maxOf { it.height } + 2 * pad)
            if (pg.y + rowH > pg.bottom && pg.y > pg.mt + 1f) pg.newPage()
            var x = pg.ml
            for (c in 0 until cols) {
                val l = layouts[c]
                pg.canvas.save()
                pg.canvas.clipRect(x, pg.y, x + widths[c], min(pg.y + rowH, pg.bottom + pg.mb))
                pg.canvas.translate(x + pad, pg.y + pad)
                l.draw(pg.canvas)
                pg.canvas.restore()
                pg.canvas.drawRect(x, pg.y, x + widths[c], pg.y + rowH, line)
                x += widths[c]
            }
            pg.y += rowH
        }
        pg.y += 8f
    }

    fun renderText(text: String, out: File) {
        val paras = text.replace("\r", "").split('\n').map { Para(listOf(TextRun(it, RunStyle())), spaceAfterPt = 0f) }
        renderFlow(FlowDocument(blocks = paras.map { FlowBlock.P(it) }), out)
    }

    // ---------------- Arkusze (Excel) ----------------

    fun renderSheets(sheets: List<SheetData>, out: File) {
        val pdf = PdfDocument()
        try {
            val fontSize = 8.5f
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fontSize
                color = Color.BLACK
            }
            val bold = TextPaint(paint).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 13f }
            val grid = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.4f
                color = Color.rgb(170, 170, 170)
            }
            var pageNo = 0
            for (sheet in sheets) {
                val cols = sheet.rows.maxOfOrNull { it.size } ?: 0
                val widths = FloatArray(cols) { c ->
                    val measured = sheet.rows.take(400).maxOfOrNull { paint.measureText(it.getOrElse(c) { "" }) } ?: 0f
                    (measured + 8f).coerceIn(28f, 200f)
                }
                val total = widths.sum()
                val margin = 28f
                val landscape = total > 595f - 2 * margin
                val pw = if (landscape) 842f else 595f
                val ph = if (landscape) 595f else 842f
                val scale = if (total > pw - 2 * margin) max(0.45f, (pw - 2 * margin) / total) else 1f
                val rowH = 13f
                var r = 0
                var first = true
                do {
                    pageNo++
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw.toInt(), ph.toInt(), pageNo).create())
                    val c = page.canvas
                    var y = margin
                    if (first) {
                        c.drawText(sheet.name, margin, y + 12f, bold)
                        y += 22f
                        first = false
                    }
                    c.save()
                    c.translate(margin, y)
                    c.scale(scale, scale)
                    var yy = 0f
                    val maxY = (ph - margin - y) / scale
                    while (r < sheet.rows.size && yy + rowH <= maxY) {
                        var x = 0f
                        val row = sheet.rows[r]
                        for (ci in 0 until cols) {
                            val v = row.getOrElse(ci) { "" }
                            c.drawRect(x, yy, x + widths[ci], yy + rowH, grid)
                            if (v.isNotEmpty()) {
                                val txt = TextUtils.ellipsize(v, paint, widths[ci] - 6f, TextUtils.TruncateAt.END).toString()
                                val isNum = v.toDoubleOrNull() != null
                                val tx = if (isNum) x + widths[ci] - 3f - paint.measureText(txt) else x + 3f
                                c.drawText(txt, tx, yy + rowH - 3.5f, paint)
                            }
                            x += widths[ci]
                        }
                        yy += rowH
                        r++
                    }
                    c.restore()
                    pdf.finishPage(page)
                } while (r < sheet.rows.size)
            }
            if (pageNo == 0) {
                pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()))
            }
            out.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    // ---------------- Prezentacje (PowerPoint) ----------------

    fun renderPresentation(p: Presentation, out: File) {
        val pdf = PdfDocument()
        try {
            p.slides.forEachIndexed { i, slide ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(p.widthPt.roundToInt(), p.heightPt.roundToInt(), i + 1).create())
                val c = page.canvas
                val bg = slide.background ?: Color.WHITE
                c.drawColor(bg)
                val darkBg = luminance(bg) < 0.4
                val fill = Paint(Paint.ANTI_ALIAS_FLAG)
                for (s in slide.shapes) {
                    val rect = RectF(s.x, s.y, s.x + s.w, s.y + s.h)
                    s.fill?.let {
                        fill.color = it
                        c.drawRect(rect, fill)
                    }
                    s.image?.let { bytes ->
                        decodeLimited(bytes, 1800)?.let { bmp ->
                            c.drawBitmap(bmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
                            bmp.recycle()
                        }
                    }
                    if (s.paragraphs.isNotEmpty()) {
                        val shapeDark = s.fill?.let { luminance(it) < 0.4 } ?: darkBg
                        val color = if (shapeDark) Color.WHITE else Color.BLACK
                        val innerW = (s.w - 14.4f).roundToInt().coerceAtLeast(10)
                        val innerH = s.h - 7.2f
                        var scale = 1f
                        var l = layout(spannable(s.paragraphs, 18f, scale, color), innerW)
                        // Automatyczne zmniejszanie tekstu, gdy nie mieści się w ramce.
                        var tries = 0
                        while (l.height > innerH && tries < 6 && innerH > 10) {
                            scale *= 0.88f
                            l = layout(spannable(s.paragraphs, 18f, scale, color), innerW)
                            tries++
                        }
                        val dy = if (s.anchorCenter) max(0f, (innerH - l.height) / 2f) else 0f
                        c.save()
                        c.translate(s.x + 7.2f, s.y + 3.6f + dy)
                        l.draw(c)
                        c.restore()
                    }
                }
                pdf.finishPage(page)
            }
            if (p.slides.isEmpty()) pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(720, 540, 1).create()))
            out.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun luminance(c: Int): Double =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    private fun decodeLimited(bytes: ByteArray, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }
}
