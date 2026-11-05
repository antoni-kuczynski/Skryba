package com.kuczynski.skryba.convert

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Blok treści dokumentu Word. */
sealed class DocxBlock {
    data class Paragraph(
        val text: String,
        val bold: Boolean = false,
        val sizePt: Float = 11f,
        val heading: Int = 0,
        val center: Boolean = false,
    ) : DocxBlock()

    data class Image(val jpeg: ByteArray, val widthPx: Int, val heightPx: Int) : DocxBlock()
    data class Table(val rows: List<List<String>>) : DocxBlock()
    data object PageBreak : DocxBlock()
}

data class Sheet(val name: String, val rows: List<List<String>>)

data class Slide(val jpeg: ByteArray, val widthPx: Int, val heightPx: Int, val notes: String = "")

/**
 * Minimalny, poprawny zapis plików Office Open XML (docx, xlsx, pptx) bez zewnętrznych bibliotek.
 * Pliki otwierają się w Wordzie, Excelu, PowerPoincie, LibreOffice i Google Docs.
 */
object OoxmlWriter {

    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    private const val REL_IMAGE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
    private const val REL_STYLES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                // Znaki sterujące są niedozwolone w XML 1.0.
                ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> {}
                ch == '￾' || ch == '￿' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private class Zip(out: OutputStream) : AutoCloseable {
        val z = ZipOutputStream(out.buffered())
        fun text(name: String, content: String) = bytes(name, content.toByteArray(Charsets.UTF_8))
        fun bytes(name: String, data: ByteArray) {
            z.putNextEntry(ZipEntry(name))
            z.write(data)
            z.closeEntry()
        }
        override fun close() = z.close()
    }

    private fun rels(vararg items: Triple<String, String, String>): String = buildString {
        append(XML_HEAD)
        append("<Relationships xmlns=\"$NS_REL\">")
        for ((id, type, target) in items) {
            append("<Relationship Id=\"$id\" Type=\"$type\" Target=\"${esc(target)}\"/>")
        }
        append("</Relationships>")
    }

    // ---------------- DOCX ----------------

    fun writeDocx(file: File, blocks: List<DocxBlock>) = file.outputStream().use { writeDocx(it, blocks) }

    fun writeDocx(out: OutputStream, blocks: List<DocxBlock>) {
        val images = blocks.filterIsInstance<DocxBlock.Image>()
        Zip(out).use { zip ->
            zip.text(
                "[Content_Types].xml",
                XML_HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                    "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                    "</Types>",
            )
            zip.text("_rels/.rels", rels(Triple("rId1", REL_DOC, "word/document.xml")))
            val docRels = mutableListOf(Triple("rId1", REL_STYLES, "styles.xml"))
            images.forEachIndexed { i, _ -> docRels += Triple("rIdImg${i + 1}", REL_IMAGE, "media/image${i + 1}.jpeg") }
            zip.text("word/_rels/document.xml.rels", rels(*docRels.toTypedArray()))
            images.forEachIndexed { i, img -> zip.bytes("word/media/image${i + 1}.jpeg", img.jpeg) }
            zip.text("word/styles.xml", DOCX_STYLES)
            zip.text("word/document.xml", docxBody(blocks))
        }
    }

    private fun docxBody(blocks: List<DocxBlock>): String = buildString {
        append(XML_HEAD)
        append(
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                "xmlns:r=\"$NS_R\" " +
                "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
                "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
                "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>",
        )
        var imgIndex = 0
        // Szerokość obszaru tekstu A4 z marginesami 2 cm = 17 cm.
        val maxWidthEmu = 6120000L
        val maxHeightEmu = 8900000L
        for (b in blocks) {
            when (b) {
                is DocxBlock.Paragraph -> {
                    for (line in b.text.split('\n')) {
                        append("<w:p>")
                        if (b.heading > 0 || b.center) {
                            append("<w:pPr>")
                            if (b.heading > 0) append("<w:pStyle w:val=\"Heading${b.heading.coerceIn(1, 6)}\"/>")
                            if (b.center) append("<w:jc w:val=\"center\"/>")
                            append("</w:pPr>")
                        }
                        if (line.isNotEmpty()) {
                            append("<w:r>")
                            if (b.bold || b.sizePt != 11f) {
                                append("<w:rPr>")
                                if (b.bold) append("<w:b/>")
                                if (b.sizePt != 11f) append("<w:sz w:val=\"${(b.sizePt * 2).toInt()}\"/>")
                                append("</w:rPr>")
                            }
                            val parts = line.split('\t')
                            parts.forEachIndexed { i, part ->
                                if (i > 0) append("<w:tab/>")
                                append("<w:t xml:space=\"preserve\">").append(esc(part)).append("</w:t>")
                            }
                            append("</w:r>")
                        }
                        append("</w:p>")
                    }
                }
                is DocxBlock.PageBreak -> append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                is DocxBlock.Image -> {
                    imgIndex++
                    var cx = maxWidthEmu
                    var cy = cx * b.heightPx / b.widthPx.coerceAtLeast(1)
                    if (cy > maxHeightEmu) {
                        cy = maxHeightEmu
                        cx = cy * b.widthPx / b.heightPx.coerceAtLeast(1)
                    }
                    append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>")
                    append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
                    append("<wp:extent cx=\"$cx\" cy=\"$cy\"/>")
                    append("<wp:docPr id=\"$imgIndex\" name=\"Image $imgIndex\"/>")
                    append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
                    append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$imgIndex\" name=\"image$imgIndex.jpeg\"/><pic:cNvPicPr/></pic:nvPicPr>")
                    append("<pic:blipFill><a:blip r:embed=\"rIdImg$imgIndex\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                    append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>")
                    append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>")
                    append("</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
                }
                is DocxBlock.Table -> {
                    val cols = b.rows.maxOfOrNull { it.size } ?: 0
                    if (cols == 0) continue
                    val colW = 9638 / cols
                    append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/>")
                    append("<w:tblBorders>")
                    for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
                        append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>")
                    }
                    append("</w:tblBorders></w:tblPr><w:tblGrid>")
                    repeat(cols) { append("<w:gridCol w:w=\"$colW\"/>") }
                    append("</w:tblGrid>")
                    for (row in b.rows) {
                        append("<w:tr>")
                        for (c in 0 until cols) {
                            append("<w:tc><w:tcPr><w:tcW w:w=\"$colW\" w:type=\"dxa\"/></w:tcPr><w:p>")
                            val v = row.getOrElse(c) { "" }
                            if (v.isNotEmpty()) append("<w:r><w:t xml:space=\"preserve\">${esc(v)}</w:t></w:r>")
                            append("</w:p></w:tc>")
                        }
                        append("</w:tr>")
                    }
                    append("</w:tbl><w:p/>")
                }
            }
        }
        append(
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
                "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/>" +
                "</w:sectPr>",
        )
        append("</w:body></w:document>")
    }

    private val DOCX_STYLES = XML_HEAD +
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>" +
        "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"pl-PL\"/></w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        (1..3).joinToString("") { i ->
            val size = when (i) { 1 -> 32; 2 -> 28; else -> 24 }
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$i\"><w:name w:val=\"heading $i\"/><w:basedOn w:val=\"Normal\"/>" +
                "<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"${i - 1}\"/></w:pPr>" +
                "<w:rPr><w:b/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/><w:color w:val=\"1F3864\"/></w:rPr></w:style>"
        } +
        "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:tblPr><w:tblCellMar>" +
        "<w:left w:w=\"108\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
        "</w:styles>"

    // ---------------- XLSX ----------------

    fun writeXlsx(file: File, sheets: List<Sheet>) = file.outputStream().use { writeXlsx(it, sheets) }

    fun writeXlsx(out: OutputStream, sheets: List<Sheet>) {
        val list = sheets.ifEmpty { listOf(Sheet("Sheet1", emptyList())) }
        val names = uniqueSheetNames(list.map { it.name })
        Zip(out).use { zip ->
            zip.text(
                "[Content_Types].xml",
                XML_HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                    list.indices.joinToString("") {
                        "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    } +
                    "</Types>",
            )
            zip.text("_rels/.rels", rels(Triple("rId1", REL_DOC, "xl/workbook.xml")))
            zip.text(
                "xl/workbook.xml",
                XML_HEAD + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"$NS_R\"><sheets>" +
                    names.mapIndexed { i, n -> "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") +
                    "</sheets></workbook>",
            )
            val wbRels = list.indices.map {
                Triple("rId${it + 1}", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet", "worksheets/sheet${it + 1}.xml")
            } + Triple("rId${list.size + 1}", REL_STYLES, "styles.xml")
            zip.text("xl/_rels/workbook.xml.rels", rels(*wbRels.toTypedArray()))
            zip.text(
                "xl/styles.xml",
                XML_HEAD + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                    "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
                    "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
                    "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                    "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                    "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
                    "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>" +
                    "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
                    "</styleSheet>",
            )
            list.forEachIndexed { i, s -> zip.text("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
    }

    private val NUMBER = Regex("^-?(0|[1-9][0-9]{0,14})([.,][0-9]+)?$")

    private fun sheetXml(s: Sheet): String = buildString {
        append(XML_HEAD)
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        val cols = s.rows.maxOfOrNull { it.size } ?: 0
        if (cols > 0) {
            append("<cols>")
            for (c in 0 until cols) {
                val maxLen = s.rows.maxOf { it.getOrElse(c) { "" }.length }
                val width = (maxLen * 1.1 + 2).coerceIn(8.0, 60.0)
                append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${"%.1f".format(java.util.Locale.US, width)}\" customWidth=\"1\"/>")
            }
            append("</cols>")
        }
        append("<sheetData>")
        s.rows.forEachIndexed { r, row ->
            append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, v ->
                if (v.isEmpty()) return@forEachIndexed
                val ref = colName(c) + (r + 1)
                val trimmed = v.trim()
                if (NUMBER.matches(trimmed)) {
                    append("<c r=\"$ref\"><v>${trimmed.replace(',', '.')}</v></c>")
                } else {
                    append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    fun colName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun uniqueSheetNames(names: List<String>): List<String> {
        val used = HashSet<String>()
        return names.mapIndexed { i, raw ->
            var base = raw.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().ifEmpty { "Sheet${i + 1}" }.take(28)
            var n = base
            var k = 2
            while (!used.add(n.lowercase())) n = "$base $k".also { k++ }
            n
        }
    }

    // ---------------- PPTX ----------------

    fun writePptx(file: File, slides: List<Slide>) = file.outputStream().use { writePptx(it, slides) }

    /** Każdy slajd zawiera jeden obraz na całej powierzchni (wierne odwzorowanie wyglądu strony). */
    fun writePptx(out: OutputStream, slides: List<Slide>) {
        require(slides.isNotEmpty())
        // Rozmiar slajdu z proporcji pierwszej strony; szerokość 10 cali.
        val first = slides.first()
        val cx = 9144000L
        val cy = (cx * first.heightPx / first.widthPx.coerceAtLeast(1)).coerceIn(914400L, 51206400L)
        val P = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"$NS_R\" " +
            "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
        val emptyTree = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
        Zip(out).use { zip ->
            zip.text(
                "[Content_Types].xml",
                XML_HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
                    "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
                    "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
                    "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
                    "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
                    slides.indices.joinToString("") {
                        "<Override PartName=\"/ppt/slides/slide${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
                    } +
                    "</Types>",
            )
            zip.text("_rels/.rels", rels(Triple("rId1", REL_DOC, "ppt/presentation.xml")))
            zip.text(
                "ppt/presentation.xml",
                XML_HEAD + "<p:presentation $P saveSubsetFonts=\"1\">" +
                    "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
                    "<p:sldIdLst>" + slides.indices.joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 3}\"/>" } + "</p:sldIdLst>" +
                    "<p:sldSz cx=\"$cx\" cy=\"$cy\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>" +
                    "<p:defaultTextStyle><a:defPPr><a:defRPr lang=\"pl-PL\"/></a:defPPr></p:defaultTextStyle>" +
                    "</p:presentation>",
            )
            val presRels = mutableListOf(
                Triple("rId1", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster", "slideMasters/slideMaster1.xml"),
                Triple("rId2", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme", "theme/theme1.xml"),
            )
            slides.indices.forEach {
                presRels += Triple("rId${it + 3}", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide", "slides/slide${it + 1}.xml")
            }
            zip.text("ppt/_rels/presentation.xml.rels", rels(*presRels.toTypedArray()))
            zip.text(
                "ppt/slideMasters/slideMaster1.xml",
                XML_HEAD + "<p:sldMaster $P><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg>" +
                    "<p:spTree>$emptyTree</p:spTree></p:cSld>" +
                    "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" " +
                    "accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
                    "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
                    "<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz=\"4400\"/></a:lvl1pPr></p:titleStyle>" +
                    "<p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"2800\"/></a:lvl1pPr></p:bodyStyle>" +
                    "<p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle></p:txStyles>" +
                    "</p:sldMaster>",
            )
            zip.text(
                "ppt/slideMasters/_rels/slideMaster1.xml.rels",
                rels(
                    Triple("rId1", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout", "../slideLayouts/slideLayout1.xml"),
                    Triple("rId2", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme", "../theme/theme1.xml"),
                ),
            )
            zip.text(
                "ppt/slideLayouts/slideLayout1.xml",
                XML_HEAD + "<p:sldLayout $P type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>$emptyTree</p:spTree></p:cSld>" +
                    "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>",
            )
            zip.text(
                "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
                rels(Triple("rId1", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster", "../slideMasters/slideMaster1.xml")),
            )
            zip.text("ppt/theme/theme1.xml", THEME)
            slides.forEachIndexed { i, s ->
                val n = i + 1
                zip.bytes("ppt/media/image$n.jpeg", s.jpeg)
                // Obraz wpasowany w slajd z zachowaniem proporcji.
                val scale = minOf(cx.toDouble() / s.widthPx, cy.toDouble() / s.heightPx)
                val w = (s.widthPx * scale).toLong()
                val h = (s.heightPx * scale).toLong()
                val x = (cx - w) / 2
                val y = (cy - h) / 2
                zip.text(
                    "ppt/slides/slide$n.xml",
                    XML_HEAD + "<p:sld $P><p:cSld><p:spTree>$emptyTree" +
                        "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Page $n\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
                        "<p:blipFill><a:blip r:embed=\"rId2\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
                        "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>" +
                        "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>",
                )
                zip.text(
                    "ppt/slides/_rels/slide$n.xml.rels",
                    rels(
                        Triple("rId1", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout", "../slideLayouts/slideLayout1.xml"),
                        Triple("rId2", REL_IMAGE, "../media/image$n.jpeg"),
                    ),
                )
            }
        }
    }

    private val THEME = XML_HEAD +
        "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Skryba\"><a:themeElements>" +
        "<a:clrScheme name=\"Skryba\">" +
        "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
        "<a:dk2><a:srgbClr val=\"1F3864\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"0F7B6C\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>" +
        "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
        "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>" +
        "<a:fontScheme name=\"Skryba\"><a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
        "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
        "<a:fmtScheme name=\"Skryba\"><a:fillStyleLst>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "</a:fillStyleLst><a:lnStyleLst>" +
        "<a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>" +
        "<a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>" +
        "<a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>" +
        "</a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle>" +
        "<a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"
}
