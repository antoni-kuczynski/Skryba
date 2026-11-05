package com.kuczynski.skryba.convert

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

// ---------- Model niezależny od formatu (rysowany potem do PDF) ----------

data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val sizePt: Float? = null,
    val color: Int? = null,
)

data class TextRun(val text: String, val style: RunStyle)

enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

data class Para(
    val runs: List<TextRun>,
    val align: Align = Align.LEFT,
    val heading: Int = 0,
    val bullet: Boolean = false,
    val indentPt: Float = 0f,
    val spaceAfterPt: Float = 6f,
) {
    val plainText: String get() = runs.joinToString("") { it.text }
}

sealed class FlowBlock {
    data class P(val para: Para) : FlowBlock()
    data class Img(val bytes: ByteArray, val widthPt: Float, val heightPt: Float) : FlowBlock()
    data class Table(val rows: List<List<List<Para>>>, val colWidthsPt: List<Float>) : FlowBlock()
    data object PageBreak : FlowBlock()
}

data class FlowDocument(
    val pageWidthPt: Float = 595.28f,
    val pageHeightPt: Float = 841.89f,
    val marginLeft: Float = 56.7f,
    val marginTop: Float = 56.7f,
    val marginRight: Float = 56.7f,
    val marginBottom: Float = 56.7f,
    val defaultSizePt: Float = 11f,
    val blocks: List<FlowBlock>,
)

data class SheetData(val name: String, val rows: List<List<String>>, val colWidthsPt: Map<Int, Float>)

data class SlideShape(
    val x: Float, val y: Float, val w: Float, val h: Float,
    val fill: Int? = null,
    val paragraphs: List<Para> = emptyList(),
    val image: ByteArray? = null,
    val isTitle: Boolean = false,
    val anchorCenter: Boolean = false,
)

data class SlideData(val shapes: List<SlideShape>, val background: Int?)

data class Presentation(val widthPt: Float, val heightPt: Float, val slides: List<SlideData>)

/** Błąd formatu bez tekstu dla użytkownika (ten moduł nie zależy od Androida; komunikat tworzy UI). */
class UnsupportedFormatException(val reason: Reason, val ext: String = "") : Exception("${reason.name} $ext") {
    enum class Reason { NOT_OOXML, EMPTY, OLD_FORMAT, UNKNOWN_TYPE }
}

/**
 * Odczyt plików Office Open XML do prostego modelu. Używa tylko DOM z JDK/Androida.
 * Stare binarne formaty (.doc, .xls, .ppt) nie są obsługiwane.
 */
object OfficeReader {

    private const val EMU_PER_PT = 12700f

    // ---------- Wspólne narzędzia XML/ZIP ----------

    private class Package(file: File) : AutoCloseable {
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw UnsupportedFormatException(UnsupportedFormatException.Reason.NOT_OOXML)
        }

        fun has(path: String) = zip.getEntry(path.removePrefix("/")) != null

        fun bytes(path: String): ByteArray? = zip.getEntry(path.removePrefix("/"))?.let { e ->
            zip.getInputStream(e).use { it.readBytes() }
        }

        fun xml(path: String): Element? = zip.getEntry(path.removePrefix("/"))?.let { e ->
            zip.getInputStream(e).use { input ->
                val f = DocumentBuilderFactory.newInstance()
                f.isNamespaceAware = true
                runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                f.newDocumentBuilder().parse(input).documentElement
            }
        }

        /** Relacje części: id -> pełna ścieżka w paczce. */
        fun rels(partPath: String): Map<String, String> {
            val dir = partPath.substringBeforeLast('/', "")
            val name = partPath.substringAfterLast('/')
            val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/$name.rels"
            val root = xml(relsPath) ?: return emptyMap()
            return root.kids("Relationship").associate { r ->
                val target = r.getAttribute("Target")
                val mode = r.getAttribute("TargetMode")
                r.getAttribute("Id") to if (mode == "External") target else resolve(dir, target)
            }
        }

        override fun close() = zip.close()
    }

    private fun resolve(dir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = (if (dir.isEmpty()) emptyList() else dir.split('/')).toMutableList()
        for (seg in target.split('/')) {
            when (seg) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> {}
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }

    private fun Element.kids(local: String? = null): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) {
            if (n.nodeType == Node.ELEMENT_NODE && (local == null || n.localName == local)) out += n as Element
            n = n.nextSibling
        }
        return out
    }

    private fun Element.kid(local: String): Element? {
        var n = firstChild
        while (n != null) {
            if (n.nodeType == Node.ELEMENT_NODE && n.localName == local) return n as Element
            n = n.nextSibling
        }
        return null
    }

    private fun Element.path(vararg locals: String): Element? {
        var e: Element? = this
        for (l in locals) e = e?.kid(l) ?: return null
        return e
    }

    private fun Element.descendants(local: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(e: Element) {
            for (k in e.kids()) {
                if (k.localName == local) out += k
                walk(k)
            }
        }
        walk(this)
        return out
    }

    /** Atrybut po nazwie lokalnej, bez względu na prefiks przestrzeni nazw. */
    private fun Element.attr(local: String): String? {
        val a = attributes
        for (i in 0 until a.length) {
            val n = a.item(i)
            if ((n.localName ?: n.nodeName) == local) return n.nodeValue
        }
        return null
    }

    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** Atrybut z przestrzeni nazw relacji (r:id, r:embed) - odróżnia go od zwykłego "id". */
    private fun Element.rel(local: String): String? = getAttributeNS(NS_R, local).takeIf { it.isNotEmpty() }

    private fun parseColor(hex: String?): Int? {
        if (hex == null || hex.length != 6 || hex == "auto") return null
        return hex.toIntOrNull(16)?.let { it or (0xff shl 24) }
    }

    private fun onOff(e: Element?): Boolean {
        if (e == null) return false
        val v = e.attr("val") ?: return true
        return v != "0" && v != "false" && v != "none"
    }

    // ---------------- DOCX ----------------

    private data class StyleInfo(val heading: Int, val style: RunStyle, val align: Align?)

    fun readDocx(file: File): FlowDocument = Package(file).use { pkg ->
        val docPath = pkg.rels("").entries.firstOrNull { pkg.has(it.value) && it.value.endsWith(".xml") && it.value.contains("document") }?.value
            ?: "word/document.xml"
        val root = pkg.xml(docPath) ?: throw UnsupportedFormatException(UnsupportedFormatException.Reason.EMPTY)
        val rels = pkg.rels(docPath)
        val stylesRoot = pkg.xml("word/styles.xml")
        var defaultSize = 11f
        val styles = HashMap<String, StyleInfo>()
        if (stylesRoot != null) {
            stylesRoot.path("docDefaults", "rPrDefault", "rPr", "sz")?.attr("val")?.toFloatOrNull()?.let { defaultSize = it / 2f }
            for (s in stylesRoot.kids("style")) {
                val id = s.attr("styleId") ?: continue
                val name = s.kid("name")?.attr("val")?.lowercase().orEmpty()
                val level = Regex("heading (\\d)").find(name)?.groupValues?.get(1)?.toInt()
                    ?: s.path("pPr", "outlineLvl")?.attr("val")?.toIntOrNull()?.plus(1)
                    ?: if (name == "title") 1 else 0
                val rpr = s.kid("rPr")
                val st = RunStyle(
                    bold = onOff(rpr?.kid("b")),
                    italic = onOff(rpr?.kid("i")),
                    sizePt = rpr?.kid("sz")?.attr("val")?.toFloatOrNull()?.div(2f),
                    color = parseColor(rpr?.kid("color")?.attr("val")),
                )
                val jc = s.path("pPr", "jc")?.attr("val")
                styles[id] = StyleInfo(level, st, jc?.let { alignOf(it) })
            }
        }
        val body = root.kid("body") ?: throw UnsupportedFormatException(UnsupportedFormatException.Reason.EMPTY)
        val blocks = ArrayList<FlowBlock>()
        var pageW = 595.28f
        var pageH = 841.89f
        var ml = 56.7f
        var mt = 56.7f
        var mr = 56.7f
        var mb = 56.7f
        body.kid("sectPr")?.let { sp ->
            sp.kid("pgSz")?.let { pg ->
                pg.attr("w")?.toFloatOrNull()?.let { pageW = it / 20f }
                pg.attr("h")?.toFloatOrNull()?.let { pageH = it / 20f }
            }
            sp.kid("pgMar")?.let { m ->
                m.attr("left")?.toFloatOrNull()?.let { ml = it / 20f }
                m.attr("right")?.toFloatOrNull()?.let { mr = it / 20f }
                m.attr("top")?.toFloatOrNull()?.let { mt = it / 20f }
                m.attr("bottom")?.toFloatOrNull()?.let { mb = it / 20f }
            }
        }

        fun parseParagraph(p: Element, out: MutableList<FlowBlock>) {
            val ppr = p.kid("pPr")
            val styleId = ppr?.kid("pStyle")?.attr("val")
            val si = styleId?.let { styles[it] }
            val align = ppr?.kid("jc")?.attr("val")?.let { alignOf(it) } ?: si?.align ?: Align.LEFT
            val bullet = ppr?.kid("numPr") != null
            val indent = ppr?.kid("ind")?.let { (it.attr("left") ?: it.attr("start"))?.toFloatOrNull()?.div(20f) } ?: 0f
            val after = ppr?.kid("spacing")?.attr("after")?.toFloatOrNull()?.div(20f) ?: 6f
            val runs = ArrayList<TextRun>()
            var pageBreakAfter = false

            fun flushPara() {
                out += FlowBlock.P(Para(ArrayList(runs), align, si?.heading ?: 0, bullet, indent, after))
                runs.clear()
            }

            fun walk(e: Element) {
                for (k in e.kids()) {
                    when (k.localName) {
                        "r" -> {
                            val rpr = k.kid("rPr")
                            val base = si?.style ?: RunStyle()
                            val st = RunStyle(
                                bold = rpr?.kid("b")?.let { onOff(it) } ?: base.bold,
                                italic = rpr?.kid("i")?.let { onOff(it) } ?: base.italic,
                                underline = rpr?.kid("u")?.let { onOff(it) } ?: false,
                                sizePt = rpr?.kid("sz")?.attr("val")?.toFloatOrNull()?.div(2f) ?: base.sizePt,
                                color = parseColor(rpr?.kid("color")?.attr("val")) ?: base.color,
                            )
                            for (c in k.kids()) {
                                when (c.localName) {
                                    "t" -> runs += TextRun(c.textContent, st)
                                    "tab" -> runs += TextRun("\t", st)
                                    "br" -> if (c.attr("type") == "page") pageBreakAfter = true else runs += TextRun("\n", st)
                                    "cr" -> runs += TextRun("\n", st)
                                    "drawing", "pict", "object" -> {
                                        val blip = c.descendants("blip").firstOrNull()
                                            ?: c.descendants("imagedata").firstOrNull()
                                        val rid = blip?.rel("embed") ?: blip?.rel("id")
                                        val path = rid?.let { rels[it] }
                                        val bytes = path?.let { pkg.bytes(it) }
                                        if (bytes != null) {
                                            val ext = c.descendants("extent").firstOrNull()
                                            val w = ext?.attr("cx")?.toFloatOrNull()?.div(EMU_PER_PT) ?: 200f
                                            val h = ext?.attr("cy")?.toFloatOrNull()?.div(EMU_PER_PT) ?: 150f
                                            if (runs.isNotEmpty()) flushPara()
                                            out += FlowBlock.Img(bytes, w, h)
                                        }
                                    }
                                }
                            }
                        }
                        "hyperlink", "ins", "smartTag", "sdt", "sdtContent", "fldSimple", "customXml" -> walk(k)
                        "del", "pPr" -> {}
                    }
                }
            }
            walk(p)
            if (runs.isNotEmpty() || out.lastOrNull() !is FlowBlock.Img) flushPara()
            if (pageBreakAfter) out += FlowBlock.PageBreak
        }

        fun parseTable(t: Element): FlowBlock.Table {
            val widths = t.kid("tblGrid")?.kids("gridCol")?.map { (it.attr("w")?.toFloatOrNull() ?: 1000f) / 20f }.orEmpty()
            val rows = t.kids("tr").map { tr ->
                tr.kids("tc").map { tc ->
                    val cellBlocks = ArrayList<FlowBlock>()
                    for (k in tc.kids()) {
                        when (k.localName) {
                            "p" -> parseParagraph(k, cellBlocks)
                            "tbl" -> k.descendants("p").forEach { parseParagraph(it, cellBlocks) }
                        }
                    }
                    cellBlocks.filterIsInstance<FlowBlock.P>().map { it.para }
                }
            }
            return FlowBlock.Table(rows, widths)
        }

        fun walkBody(e: Element) {
            for (k in e.kids()) {
                when (k.localName) {
                    "p" -> parseParagraph(k, blocks)
                    "tbl" -> blocks += parseTable(k)
                    "sdt" -> k.kid("sdtContent")?.let { walkBody(it) }
                    "customXml" -> walkBody(k)
                }
            }
        }
        walkBody(body)
        FlowDocument(pageW, pageH, ml, mt, mr, mb, defaultSize, blocks)
    }

    private fun alignOf(v: String) = when (v) {
        "center" -> Align.CENTER
        "right", "end" -> Align.RIGHT
        "both", "distribute" -> Align.JUSTIFY
        else -> Align.LEFT
    }

    // ---------------- XLSX ----------------

    fun readXlsx(file: File, maxRows: Int = 5000, maxCols: Int = 60): List<SheetData> = Package(file).use { pkg ->
        val wbPath = "xl/workbook.xml"
        val wb = pkg.xml(wbPath) ?: throw UnsupportedFormatException(UnsupportedFormatException.Reason.EMPTY)
        val rels = pkg.rels(wbPath)
        val shared = pkg.xml("xl/sharedStrings.xml")?.kids("si")?.map { si ->
            si.descendants("t").filter { t -> (t.parentNode as? Element)?.localName != "rPh" }.joinToString("") { it.textContent }
        }.orEmpty()
        val dateStyles = readDateStyles(pkg.xml("xl/styles.xml"))
        val sheets = wb.kid("sheets")?.kids("sheet").orEmpty()
        sheets.mapNotNull { s ->
            val name = s.attr("name") ?: "Arkusz"
            val rid = s.rel("id") ?: return@mapNotNull null
            val path = rels[rid] ?: return@mapNotNull null
            val root = pkg.xml(path) ?: return@mapNotNull null
            val widths = HashMap<Int, Float>()
            root.kid("cols")?.kids("col")?.forEach { c ->
                val min = c.attr("min")?.toIntOrNull() ?: return@forEach
                val max = c.attr("max")?.toIntOrNull() ?: min
                val w = c.attr("width")?.toFloatOrNull() ?: return@forEach
                for (i in min..minOf(max, maxCols)) widths[i - 1] = w * 5.6f
            }
            val grid = HashMap<Int, HashMap<Int, String>>()
            var maxR = -1
            var maxC = -1
            var rowCounter = 0
            for (row in root.kid("sheetData")?.kids("row").orEmpty()) {
                val r = (row.attr("r")?.toIntOrNull() ?: (rowCounter + 1)) - 1
                rowCounter = r + 1
                if (r >= maxRows) break
                var colCounter = 0
                for (c in row.kids("c")) {
                    val ref = c.attr("r")
                    val col = ref?.let { colIndex(it) } ?: colCounter
                    colCounter = col + 1
                    if (col >= maxCols) continue
                    val t = c.attr("t")
                    val v = c.kid("v")?.textContent
                    val text = when (t) {
                        "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }
                        "inlineStr" -> c.kid("is")?.descendants("t")?.joinToString("") { it.textContent }
                        "b" -> if (v == "1") "TRUE" else "FALSE"
                        "str", "e" -> v
                        else -> v?.let { formatNumber(it, c.attr("s")?.toIntOrNull()?.let { s -> s in dateStyles } == true) }
                    } ?: continue
                    if (text.isEmpty()) continue
                    grid.getOrPut(r) { HashMap() }[col] = text
                    if (r > maxR) maxR = r
                    if (col > maxC) maxC = col
                }
            }
            val rows = (0..maxR).map { r -> (0..maxC).map { c -> grid[r]?.get(c).orEmpty() } }
            SheetData(name, rows, widths)
        }
    }

    private fun readDateStyles(styles: Element?): Set<Int> {
        if (styles == null) return emptySet()
        val customDate = HashSet<Int>()
        styles.kid("numFmts")?.kids("numFmt")?.forEach { f ->
            val id = f.attr("numFmtId")?.toIntOrNull() ?: return@forEach
            val code = f.attr("formatCode").orEmpty().lowercase().replace(Regex("\\[[^]]*]|\"[^\"]*\""), "")
            if (code.contains('y') || (code.contains('d') && code.contains('m'))) customDate += id
        }
        val out = HashSet<Int>()
        styles.kid("cellXfs")?.kids("xf")?.forEachIndexed { i, xf ->
            val id = xf.attr("numFmtId")?.toIntOrNull() ?: 0
            if (id in 14..22 || id in customDate) out += i
        }
        return out
    }

    private fun formatNumber(v: String, isDate: Boolean): String {
        val d = v.toDoubleOrNull() ?: return v
        if (isDate && d > 0 && d < 2958466) {
            // Excel liczy dni od 1899-12-30.
            val millis = ((d - 25569.0) * 86400000.0).toLong()
            val fmt = if (d % 1.0 == 0.0) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm"
            return SimpleDateFormat(fmt, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
        }
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        val s = "%.10f".format(Locale.US, d).trimEnd('0').trimEnd('.')
        return s
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return n - 1
    }

    // ---------------- PPTX ----------------

    fun readPptx(file: File): Presentation = Package(file).use { pkg ->
        val presPath = "ppt/presentation.xml"
        val pres = pkg.xml(presPath) ?: throw UnsupportedFormatException(UnsupportedFormatException.Reason.EMPTY)
        val rels = pkg.rels(presPath)
        val sz = pres.kid("sldSz")
        val w = (sz?.attr("cx")?.toFloatOrNull() ?: 9144000f) / EMU_PER_PT
        val h = (sz?.attr("cy")?.toFloatOrNull() ?: 6858000f) / EMU_PER_PT
        val slides = pres.kid("sldIdLst")?.kids("sldId").orEmpty().mapNotNull { sid ->
            val path = sid.rel("id")?.let { rels[it] } ?: return@mapNotNull null
            readSlide(pkg, path, w, h)
        }
        Presentation(w, h, slides)
    }

    private data class Xfrm(val x: Float, val y: Float, val w: Float, val h: Float)

    private fun xfrmOf(spPr: Element?): Xfrm? {
        val x = spPr?.kid("xfrm") ?: return null
        val off = x.kid("off") ?: return null
        val ext = x.kid("ext") ?: return null
        return Xfrm(
            (off.attr("x")?.toFloatOrNull() ?: 0f) / EMU_PER_PT,
            (off.attr("y")?.toFloatOrNull() ?: 0f) / EMU_PER_PT,
            (ext.attr("cx")?.toFloatOrNull() ?: 0f) / EMU_PER_PT,
            (ext.attr("cy")?.toFloatOrNull() ?: 0f) / EMU_PER_PT,
        )
    }

    private fun readSlide(pkg: Package, path: String, slideW: Float, slideH: Float): SlideData? {
        val root = pkg.xml(path) ?: return null
        val rels = pkg.rels(path)
        val layoutPath = rels.values.firstOrNull { it.contains("slideLayouts/") }
        val masterPath = layoutPath?.let { lp -> pkg.rels(lp).values.firstOrNull { it.contains("slideMasters/") } }
        val layoutRoot = layoutPath?.let { pkg.xml(it) }
        val masterRoot = masterPath?.let { pkg.xml(it) }

        fun placeholderXfrm(type: String?, idx: String?): Xfrm? {
            for (r in listOfNotNull(layoutRoot, masterRoot)) {
                for (sp in r.descendants("sp")) {
                    val ph = sp.path("nvSpPr", "nvPr", "ph") ?: continue
                    val pt = ph.attr("type")
                    val pi = ph.attr("idx")
                    val sameType = (pt ?: "body") == (type ?: "body") ||
                        (type == "ctrTitle" && pt == "title") || (type == "subTitle" && pt == "body")
                    if ((idx != null && pi == idx) || (idx == null && sameType)) {
                        xfrmOf(sp.kid("spPr"))?.let { return it }
                    }
                }
            }
            return null
        }

        fun bgColor(r: Element?): Int? = r?.path("cSld", "bg", "bgPr", "solidFill", "srgbClr")?.attr("val")?.let { parseColor(it) }
        val background = bgColor(root) ?: bgColor(layoutRoot) ?: bgColor(masterRoot)

        val shapes = ArrayList<SlideShape>()

        fun walk(tree: Element, map: (Xfrm) -> Xfrm) {
            for (k in tree.kids()) {
                when (k.localName) {
                    "sp" -> {
                        val ph = k.path("nvSpPr", "nvPr", "ph")
                        val phType = ph?.attr("type")
                        val isTitle = phType == "title" || phType == "ctrTitle"
                        val spPr = k.kid("spPr")
                        val xf = xfrmOf(spPr) ?: if (ph != null) placeholderXfrm(phType, ph.attr("idx")) else null
                        val fallback = when {
                            isTitle -> Xfrm(slideW * 0.05f, slideH * 0.05f, slideW * 0.9f, slideH * 0.18f)
                            else -> Xfrm(slideW * 0.05f, slideH * 0.26f, slideW * 0.9f, slideH * 0.66f)
                        }
                        val box = map(xf ?: fallback)
                        val fill = spPr?.path("solidFill", "srgbClr")?.attr("val")?.let { parseColor(it) }
                        val body = k.kid("txBody")
                        val defSize = if (isTitle) 36f else if (phType == "subTitle") 22f else if (ph != null) 20f else 18f
                        val paras = body?.kids("p")?.map { readPptPara(it, defSize, isTitle) }.orEmpty()
                        val anchor = body?.kid("bodyPr")?.attr("anchor")
                        if (fill != null || paras.any { it.plainText.isNotBlank() }) {
                            shapes += SlideShape(box.x, box.y, box.w, box.h, fill, paras, null, isTitle, anchor == "ctr" || (isTitle && anchor == null))
                        }
                    }
                    "pic" -> {
                        val rid = k.path("blipFill", "blip")?.rel("embed")
                        val bytes = rid?.let { rels[it] }?.let { pkg.bytes(it) }
                        val xf = xfrmOf(k.kid("spPr"))
                        if (bytes != null && xf != null) {
                            val b = map(xf)
                            shapes += SlideShape(b.x, b.y, b.w, b.h, image = bytes)
                        }
                    }
                    "grpSp" -> {
                        val x = k.path("grpSpPr", "xfrm")
                        val off = x?.kid("off")
                        val ext = x?.kid("ext")
                        val chOff = x?.kid("chOff")
                        val chExt = x?.kid("chExt")
                        if (off != null && ext != null && chOff != null && chExt != null) {
                            val ox = (off.attr("x")?.toFloatOrNull() ?: 0f) / EMU_PER_PT
                            val oy = (off.attr("y")?.toFloatOrNull() ?: 0f) / EMU_PER_PT
                            val ex = (ext.attr("cx")?.toFloatOrNull() ?: 1f) / EMU_PER_PT
                            val ey = (ext.attr("cy")?.toFloatOrNull() ?: 1f) / EMU_PER_PT
                            val cx = (chOff.attr("x")?.toFloatOrNull() ?: 0f) / EMU_PER_PT
                            val cy = (chOff.attr("y")?.toFloatOrNull() ?: 0f) / EMU_PER_PT
                            val cex = ((chExt.attr("cx")?.toFloatOrNull() ?: 1f) / EMU_PER_PT).coerceAtLeast(0.01f)
                            val cey = ((chExt.attr("cy")?.toFloatOrNull() ?: 1f) / EMU_PER_PT).coerceAtLeast(0.01f)
                            val sx = ex / cex
                            val sy = ey / cey
                            walk(k) { c -> map(Xfrm(ox + (c.x - cx) * sx, oy + (c.y - cy) * sy, c.w * sx, c.h * sy)) }
                        } else {
                            walk(k, map)
                        }
                    }
                    "graphicFrame" -> {
                        // Tabele w prezentacji: tekst komórek wiersz po wierszu.
                        val xf = k.kid("xfrm")?.let { x ->
                            val off = x.kid("off")
                            val ext = x.kid("ext")
                            if (off != null && ext != null) Xfrm(
                                (off.attr("x")?.toFloatOrNull() ?: 0f) / EMU_PER_PT,
                                (off.attr("y")?.toFloatOrNull() ?: 0f) / EMU_PER_PT,
                                (ext.attr("cx")?.toFloatOrNull() ?: 0f) / EMU_PER_PT,
                                (ext.attr("cy")?.toFloatOrNull() ?: 0f) / EMU_PER_PT,
                            ) else null
                        }
                        val rows = k.descendants("tr")
                        if (xf != null && rows.isNotEmpty()) {
                            val paras = rows.map { tr ->
                                Para(listOf(TextRun(tr.descendants("tc").joinToString("   |   ") { tc ->
                                    tc.descendants("t").joinToString("") { it.textContent }
                                }, RunStyle(sizePt = 14f))))
                            }
                            val b = map(xf)
                            shapes += SlideShape(b.x, b.y, b.w, b.h, null, paras)
                        }
                    }
                }
            }
        }
        root.path("cSld", "spTree")?.let { walk(it) { x -> x } }
        return SlideData(shapes, background)
    }

    private fun readPptPara(p: Element, defSize: Float, isTitle: Boolean): Para {
        val ppr = p.kid("pPr")
        val align = when (ppr?.attr("algn")) {
            "ctr" -> Align.CENTER
            "r" -> Align.RIGHT
            "just" -> Align.JUSTIFY
            else -> Align.LEFT
        }
        val bullet = ppr?.kid("buChar") != null || (ppr?.attr("lvl") != null && ppr.kid("buNone") == null && !isTitle)
        val runs = ArrayList<TextRun>()
        for (k in p.kids()) {
            when (k.localName) {
                "r", "fld" -> {
                    val rpr = k.kid("rPr")
                    val st = RunStyle(
                        bold = rpr?.attr("b") == "1" || isTitle,
                        italic = rpr?.attr("i") == "1",
                        underline = rpr?.attr("u")?.let { it != "none" } ?: false,
                        sizePt = rpr?.attr("sz")?.toFloatOrNull()?.div(100f) ?: defSize,
                        color = rpr?.path("solidFill", "srgbClr")?.attr("val")?.let { parseColor(it) },
                    )
                    runs += TextRun(k.kid("t")?.textContent.orEmpty(), st)
                }
                "br" -> runs += TextRun("\n", RunStyle(sizePt = defSize))
            }
        }
        if (runs.isEmpty()) {
            val size = p.kid("endParaRPr")?.attr("sz")?.toFloatOrNull()?.div(100f) ?: defSize
            runs += TextRun("", RunStyle(sizePt = size))
        }
        return Para(runs, align, if (isTitle) 1 else 0, bullet, 0f, 2f)
    }
}
