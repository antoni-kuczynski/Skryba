package com.kuczynski.skryba.convert

import android.content.Context
import android.graphics.Bitmap
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.Settings
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.ocr.OcrEngine
import com.kuczynski.skryba.pdf.PageWords
import com.kuczynski.skryba.pdf.PdfExport
import com.kuczynski.skryba.pdf.PdfOptions
import com.kuczynski.skryba.pdf.PdfRender
import com.kuczynski.skryba.pdf.PdfTools
import com.kuczynski.skryba.pdf.WordBox
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

typealias Progress = (done: Int, total: Int) -> Unit

/** Conversions between formats. All results go to the "Files" tab. */
object Converters {

    fun baseName(f: File) = f.name.substringBeforeLast('.')

    private fun jpegBytes(bmp: Bitmap, q: Int = 85): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    /**
     * Words with positions for each PDF page. Pages without a text layer (scans)
     * are recognized with OCR.
     */
    fun pageWords(context: Context, pdf: File, password: String?, progress: Progress): List<PageWords> {
        val fromPdf = runCatching { PdfTools.extractWords(pdf, password) }.getOrDefault(emptyList())
        val count = PdfRender.pageCount(pdf, password)
        return (0 until count).map { i ->
            progress(i, count)
            val p = fromPdf.getOrNull(i)
            if (p != null && p.words.sumOf { it.text.length } >= 15) {
                p
            } else {
                val bmp = PdfRender.render(pdf, i, dpi = 220, password = password)
                val words = OcrEngine.recognizeWords(context, bmp)
                // Pixels at 220 dpi -> points.
                val s = 72f / 220f
                val pw = PageWords(bmp.width * s, bmp.height * s, words.map {
                    WordBox(it.text, it.box.left * s, it.box.top * s, it.box.width() * s, it.box.height() * s)
                })
                bmp.recycle()
                pw
            }
        }.also { progress(count, count) }
    }

    fun pdfToWord(context: Context, pdf: File, password: String?, progress: Progress): File {
        val pages = pageWords(context, pdf, password, progress)
        val blocks = ArrayList<DocxBlock>()
        pages.forEachIndexed { i, p ->
            if (i > 0) blocks += DocxBlock.PageBreak
            val paras = TextLayout.paragraphs(p)
            if (paras.isEmpty()) blocks += DocxBlock.Paragraph("")
            for (para in paras) {
                blocks += if (para.heading > 0) DocxBlock.Paragraph(para.text, heading = para.heading)
                else DocxBlock.Paragraph(para.text)
            }
        }
        val tmp = Library.tempFile("word.docx")
        OoxmlWriter.writeDocx(tmp, blocks)
        return Library.addFile(tmp, baseName(pdf) + ".docx", move = true)
    }

    fun pdfToExcel(context: Context, pdf: File, password: String?, progress: Progress): File {
        val pages = pageWords(context, pdf, password, progress)
        val sheets = pages.mapIndexed { i, p -> Sheet(str(R.string.sheet_page, i + 1), TextLayout.table(p)) }
        val tmp = Library.tempFile("excel.xlsx")
        OoxmlWriter.writeXlsx(tmp, sheets)
        return Library.addFile(tmp, baseName(pdf) + ".xlsx", move = true)
    }

    fun pdfToPowerPoint(pdf: File, password: String?, progress: Progress): File {
        val n = PdfRender.pageCount(pdf, password)
        val slides = (0 until n).map { i ->
            progress(i, n)
            val bmp = PdfRender.render(pdf, i, dpi = 150, password = password)
            Slide(jpegBytes(bmp), bmp.width, bmp.height).also { bmp.recycle() }
        }
        progress(n, n)
        val tmp = Library.tempFile("ppt.pptx")
        OoxmlWriter.writePptx(tmp, slides)
        return Library.addFile(tmp, baseName(pdf) + ".pptx", move = true)
    }

    /** PDF -> JPG: one page gives a .jpg file, more pages give a .zip archive of images. */
    fun pdfToJpg(pdf: File, password: String?, dpi: Int, progress: Progress): File {
        val n = PdfRender.pageCount(pdf, password)
        val base = baseName(pdf)
        if (n == 1) {
            val bmp = PdfRender.render(pdf, 0, dpi = dpi, password = password)
            val tmp = Library.tempFile("page.jpg")
            tmp.writeBytes(jpegBytes(bmp, 92))
            bmp.recycle()
            progress(1, 1)
            return Library.addFile(tmp, "$base.jpg", move = true)
        }
        val tmp = Library.tempFile("pages.zip")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            for (i in 0 until n) {
                progress(i, n)
                val bmp = PdfRender.render(pdf, i, dpi = dpi, password = password)
                zip.putNextEntry(ZipEntry("${base}_${(i + 1).toString().padStart(3, '0')}.jpg"))
                zip.write(jpegBytes(bmp, 92))
                zip.closeEntry()
                bmp.recycle()
            }
        }
        progress(n, n)
        return Library.addFile(tmp, str(R.string.jpg_zip_suffix, base), move = true)
    }

    fun imagesToPdf(context: Context, images: List<File>, name: String, progress: Progress): File {
        val tmp = Library.tempFile("images.pdf")
        val s = Settings.value
        PdfExport.imagesToPdf(context, images, tmp, PdfOptions(s.pageSize, s.jpegQuality, s.searchablePdf), progress)
        return Library.addFile(tmp, "$name.pdf", move = true)
    }

    fun isOffice(f: File) = f.extension.lowercase() in setOf("docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "ppsx", "potx", "txt", "csv", "md")

    /** Word / Excel / PowerPoint / text -> PDF. */
    fun officeToPdf(file: File, progress: Progress): File {
        progress(0, 2)
        val tmp = Library.tempFile("office.pdf")
        when (file.extension.lowercase()) {
            "docx", "docm", "dotx" -> OfficeRenderer.renderFlow(OfficeReader.readDocx(file), tmp)
            "xlsx", "xlsm", "xltx" -> OfficeRenderer.renderSheets(OfficeReader.readXlsx(file), tmp)
            "pptx", "ppsx", "potx" -> OfficeRenderer.renderPresentation(OfficeReader.readPptx(file), tmp)
            "csv" -> OfficeRenderer.renderSheets(listOf(SheetData(baseName(file), parseCsv(file.readText()), emptyMap())), tmp)
            "txt", "md" -> OfficeRenderer.renderText(file.readText(), tmp)
            "doc", "xls", "ppt" -> throw UnsupportedFormatException(UnsupportedFormatException.Reason.OLD_FORMAT, file.extension)
            else -> throw UnsupportedFormatException(UnsupportedFormatException.Reason.UNKNOWN_TYPE, file.extension)
        }
        progress(2, 2)
        return Library.addFile(tmp, baseName(file) + ".pdf", move = true)
    }

    fun parseCsv(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val sep = listOf(';', ',', '\t').maxBy { c -> firstLine.count { it == c } }
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == sep -> { row += cell.toString(); cell.setLength(0) }
                !quoted && ch == '\n' -> {
                    row += cell.toString(); cell.setLength(0)
                    rows += row; row = ArrayList()
                }
                ch == '\r' && !quoted -> {}
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }

    /** Text recognized from photos -> Word document. */
    fun textToWord(text: String, name: String): File {
        val blocks = ArrayList<DocxBlock>()
        text.split("\u000C").forEachIndexed { i, pageText ->
            if (i > 0) blocks += DocxBlock.PageBreak
            TextLayout.paragraphsFromText(pageText).forEach { blocks += DocxBlock.Paragraph(it) }
        }
        val tmp = Library.tempFile("text.docx")
        OoxmlWriter.writeDocx(tmp, blocks)
        return Library.addFile(tmp, "$name.docx", move = true)
    }

    fun textToTxt(text: String, name: String): File {
        val tmp = Library.tempFile("text.txt")
        tmp.writeText(text)
        return Library.addFile(tmp, "$name.txt", move = true)
    }
}
