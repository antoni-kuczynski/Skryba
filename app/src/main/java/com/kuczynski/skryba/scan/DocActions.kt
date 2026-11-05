package com.kuczynski.skryba.scan

import android.content.Context
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.FilterType
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.Page
import com.kuczynski.skryba.data.ScanDocument
import com.kuczynski.skryba.data.ScanMode
import com.kuczynski.skryba.data.Settings
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.imaging.ImageIO
import com.kuczynski.skryba.ocr.OcrEngine
import com.kuczynski.skryba.pdf.PdfExport
import com.kuczynski.skryba.pdf.PdfOptions
import com.kuczynski.skryba.pdf.PdfRender
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Operacje na zeskanowanych dokumentach: eksport, OCR. */
object DocActions {

    fun pageFiles(doc: ScanDocument): List<File> = doc.pages.map { Library.pageFile(doc.id, it.processed) }

    fun exportPdf(context: Context, doc: ScanDocument, name: String, options: PdfOptions, progress: (Int, Int) -> Unit): File {
        val tmp = Library.tempFile("export.pdf")
        PdfExport.imagesToPdf(context, pageFiles(doc), tmp, options, progress)
        return Library.addFile(tmp, "$name.pdf", move = true)
    }

    /** PDF w katalogu tymczasowym (do udostępnienia albo jako wejście narzędzi). */
    fun tempPdf(context: Context, doc: ScanDocument, progress: (Int, Int) -> Unit = { _, _ -> }): File {
        val f = File(Library.tempDir, Library.sanitize(doc.name) + ".pdf")
        val s = Settings.value
        PdfExport.imagesToPdf(context, pageFiles(doc), f, PdfOptions(s.pageSize, s.jpegQuality, s.searchablePdf), progress)
        return f
    }

    /** Kopie stron jako JPG o czytelnych nazwach (do udostępnienia). */
    fun tempJpgs(doc: ScanDocument, indices: List<Int>? = null): List<File> {
        val base = Library.sanitize(doc.name)
        val pages = indices?.map { doc.pages[it] } ?: doc.pages
        return pages.mapIndexed { i, p ->
            val idx = indices?.get(i)?.plus(1) ?: (i + 1)
            val f = File(Library.tempDir, if (doc.pages.size == 1) "$base.jpg" else "${base}_$idx.jpg")
            Library.pageFile(doc.id, p.processed).copyTo(f, overwrite = true)
            f
        }
    }

    fun exportJpgZip(doc: ScanDocument): File {
        val tmp = Library.tempFile("jpg.zip")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            tempJpgs(doc).forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return Library.addFile(tmp, str(R.string.jpg_zip_suffix, doc.name), move = true)
    }

    /** Tworzy dokument ze stron PDF (każda strona jako obraz), żeby móc go edytować jak skan. */
    fun importPdf(pdf: File, password: String?, name: String, progress: (Int, Int) -> Unit): ScanDocument {
        val id = Library.newId()
        val n = PdfRender.pageCount(pdf, password)
        val pages = (0 until n).map { i ->
            progress(i, n)
            val bmp = PdfRender.render(pdf, i, dpi = 200, password = password)
            val name1 = "o_${Library.newId()}.jpg"
            ImageIO.saveJpeg(bmp, Library.pageFile(id, name1), 92)
            bmp.recycle()
            Page(Library.newId(), name1, name1, null, FilterType.ORIGINAL, 0)
        }
        progress(n, n)
        val now = System.currentTimeMillis()
        val doc = ScanDocument(id, name, now, now, ScanMode.DOCUMENT, pages)
        Library.save(doc)
        return doc
    }

    /** Rozpoznaje tekst na stronach i zapisuje go w dokumencie (pozwala też wyszukiwać po treści). */
    fun ocr(
        context: Context,
        doc: ScanDocument,
        pages: List<Int>,
        force: Boolean,
        progress: (Int, Int) -> Unit,
    ): ScanDocument {
        var current = doc
        pages.forEachIndexed { n, i ->
            progress(n, pages.size)
            val p = current.pages[i]
            if (!force && p.ocrText.isNotBlank()) return@forEachIndexed
            val bmp = ImageIO.decode(Library.pageFile(doc.id, p.processed), 3000)
            val text = OcrEngine.recognize(context, bmp)
            bmp.recycle()
            val updated = current.pages.toMutableList()
            updated[i] = p.copy(ocrText = text.ifBlank { " " })
            current = current.copy(pages = updated)
            Library.save(current)
        }
        progress(pages.size, pages.size)
        return Library.get(doc.id) ?: current
    }
}
