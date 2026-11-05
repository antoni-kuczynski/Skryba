package com.kuczynski.skryba

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuczynski.skryba.convert.Converters
import com.kuczynski.skryba.convert.DocxBlock
import com.kuczynski.skryba.convert.OfficeReader
import com.kuczynski.skryba.convert.OoxmlWriter
import com.kuczynski.skryba.convert.Sheet
import com.kuczynski.skryba.convert.Slide
import com.kuczynski.skryba.data.FilterType
import com.kuczynski.skryba.data.PageSize
import com.kuczynski.skryba.imaging.AiTools
import com.kuczynski.skryba.imaging.DocumentDetector
import com.kuczynski.skryba.imaging.Filters
import com.kuczynski.skryba.imaging.ImageIO
import com.kuczynski.skryba.ocr.OcrEngine
import com.kuczynski.skryba.pdf.PdfExport
import com.kuczynski.skryba.pdf.PdfOptions
import com.kuczynski.skryba.pdf.PdfRender
import com.kuczynski.skryba.pdf.PdfTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/** Checks the tools on a real Android device (OpenCV, Tesseract, PdfBox, PdfRenderer). */
@RunWith(AndroidJUnit4::class)
class ToolsInstrumentedTest {

    companion object {
        lateinit var ctx: Context
        lateinit var dir: File
        lateinit var pdf: File

        private fun page(lines: List<String>): Bitmap {
            val b = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(Color.WHITE)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f }
            lines.forEachIndexed { i, l -> c.drawText(l, 100f, 200f + i * 80f, p) }
            return b
        }

        @BeforeClass
        @JvmStatic
        fun setup() {
            ctx = InstrumentationRegistry.getInstrumentation().targetContext
            dir = File(ctx.cacheDir, "itest").apply { deleteRecursively(); mkdirs() }
            val img = File(dir, "page.jpg")
            ImageIO.saveJpeg(page(listOf("Faktura numer 15", "Nazwa    Ilosc    Cena", "Papier    10    25,00", "Olowek    3    4,50")), img)
            pdf = File(dir, "doc.pdf")
            PdfExport.imagesToPdf(ctx, listOf(img), pdf, PdfOptions(PageSize.A4, 85, searchable = true))
        }
    }

    @Test
    fun ocrAndSearchablePdf() {
        val text = PdfTools.extractText(pdf).joinToString(" ")
        assertTrue("Brak tekstu OCR w PDF: $text", text.contains("Faktura"))
        val words = OcrEngine.recognize(ctx, page(listOf("Zażółć gęślą jaźń")), listOf("pol"))
        assertTrue(words, words.contains("gęślą"))
    }

    @Test
    fun pdfToOffice() {
        val docx = Converters.pdfToWord(ctx, pdf, null) { _, _ -> }
        val d = OfficeReader.readDocx(docx)
        assertTrue(d.blocks.isNotEmpty())
        val xlsx = Converters.pdfToExcel(ctx, pdf, null) { _, _ -> }
        val rows = OfficeReader.readXlsx(xlsx)[0].rows
        assertTrue("Za mało wierszy: $rows", rows.size >= 3)
        val words = Converters.pageWords(ctx, pdf, null) { _, _ -> }[0].words.joinToString { "${it.text}@${it.x.toInt()}+${it.w.toInt()}h${it.h.toInt()}" }
        assertTrue("Tabela: $rows\nSłowa: $words", rows.any { r -> r.count { it.isNotBlank() } >= 3 })
        val pptx = Converters.pdfToPowerPoint(pdf, null) { _, _ -> }
        assertEquals(1, OfficeReader.readPptx(pptx).slides.size)
        val jpg = Converters.pdfToJpg(pdf, null, 100) { _, _ -> }
        assertTrue(jpg.name.endsWith(".jpg"))
        listOf(docx, xlsx, pptx, jpg).forEach { it.delete() }
    }

    @Test
    fun officeToPdf() {
        val jpeg = ByteArrayOutputStream().also { page(listOf("x")).compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val docx = File(dir, "a.docx")
        OoxmlWriter.writeDocx(docx, listOf(DocxBlock.Paragraph("Tytuł", heading = 1), DocxBlock.Paragraph("Treść ".repeat(400)), DocxBlock.Table(listOf(listOf("a", "b"))), DocxBlock.Image(jpeg, 1240, 1754)))
        val xlsx = File(dir, "a.xlsx")
        OoxmlWriter.writeXlsx(xlsx, listOf(Sheet("S", (1..200).map { listOf("w$it", "$it", "x") })))
        val pptx = File(dir, "a.pptx")
        OoxmlWriter.writePptx(pptx, listOf(Slide(jpeg, 1240, 1754)))
        for (f in listOf(docx, xlsx, pptx)) {
            val out = Converters.officeToPdf(f) { _, _ -> }
            assertTrue("${f.name}: brak stron", PdfRender.pageCount(out) >= 1)
            out.delete()
        }
    }

    @Test
    fun pdfEditing() {
        val merged = File(dir, "merged.pdf")
        PdfTools.merge(listOf(pdf, pdf), merged)
        assertEquals(2, PdfTools.pageCount(merged))

        val parts = listOf(File(dir, "p1.pdf"), File(dir, "p2.pdf"))
        PdfTools.split(merged, listOf(0..0, 1..1), parts)
        assertEquals(1, PdfTools.pageCount(parts[1]))

        val org = File(dir, "org.pdf")
        PdfTools.organize(merged, listOf(1), listOf(90), org)
        PdfTools.open(org).use { assertEquals(1, it.numberOfPages); assertEquals(90, it.getPage(0).rotation) }

        val wm = File(dir, "wm.pdf")
        PdfTools.watermark(ctx, pdf, "KOPIA ŁÓDŹ", 0.3f, 0x808080, wm)
        assertTrue(PdfTools.extractText(wm)[0].contains("KOPIA"))

        val locked = File(dir, "locked.pdf")
        PdfTools.protect(pdf, "tajne123", locked)
        assertTrue(PdfTools.needsPassword(locked))
        assertEquals(1, PdfRender.pageCount(locked, "tajne123"))
        val unlocked = File(dir, "unlocked.pdf")
        PdfTools.unlock(locked, "tajne123", unlocked)
        assertFalse(PdfTools.needsPassword(unlocked))

        val small = File(dir, "small.pdf")
        PdfTools.compress(pdf, 0.4f, 800, small)
        assertTrue(small.length() < pdf.length())

        val signed = File(dir, "signed.pdf")
        val sig = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        PdfTools.stampImage(org, 0, sig, 0.6f, 0.8f, 0.3f, 0.1f, signed)
        assertEquals(1, PdfTools.pageCount(signed))
        assertNotNull(PdfRender.render(signed, 0, 400))
    }

    @Test
    fun imaging() {
        // A white sheet at an angle on a dark background.
        val photo = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val c = Canvas(photo)
        c.drawColor(Color.rgb(60, 40, 30))
        val path = android.graphics.Path().apply { moveTo(150f, 120f); lineTo(780f, 180f); lineTo(820f, 1080f); lineTo(100f, 1050f); close() }
        c.drawPath(path, Paint().apply { color = Color.WHITE })
        val q = DocumentDetector.detect(photo)
        assertNotNull("Nie wykryto kartki", q)
        assertEquals("quad ${q!!.contentToString()}", 150f / 900, q[0], 0.03f)
        assertEquals("quad ${q.contentToString()}", 120f / 1200, q[1], 0.03f)
        val warped = DocumentDetector.warp(photo, q)
        assertTrue("warp ${warped.width}x${warped.height}", warped.width in 600..760)
        for (f in FilterType.entries) assertNotNull(Filters.apply(warped, f))
        val (l, r) = Filters.splitBook(warped)
        assertEquals("split", warped.width, l.width + r.width)
        assertEquals("id", 2480, Filters.composeIdCard(l, r).width)
    }

    @Test
    fun aiTools() {
        // 7 dark circles on a light background.
        val b = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(230, 230, 220))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 60) }
        listOf(100 to 100, 300 to 120, 500 to 100, 700 to 150, 150 to 400, 400 to 450, 650 to 420).forEach { (x, y) -> c.drawCircle(x.toFloat(), y.toFloat(), 50f, p) }
        val res = AiTools.countObjects(b, 0.5f, null)
        assertEquals(7, res.centers.size)

        val restored = AiTools.restorePhoto(b, 2, removeScratches = true, colorize = true)
        assertTrue(restored.width > 0)

        val f = File(dir, "photo.jpg")
        ImageIO.saveJpeg(b, f, 95)
        val report = AiTools.analyzeTampering(f)
        assertTrue(report.score in 0..100)

        // Two overlapping texture fragments -> stitching.
        val tex = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tex)
        tc.drawColor(Color.WHITE)
        val rnd = java.util.Random(7)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(900) {
            tp.color = Color.rgb(rnd.nextInt(200), rnd.nextInt(200), rnd.nextInt(200))
            tc.drawRect(rnd.nextInt(1200).toFloat(), rnd.nextInt(800).toFloat(), rnd.nextInt(1200).toFloat().coerceAtMost(1200f), rnd.nextInt(800).toFloat(), tp.apply { style = Paint.Style.STROKE; strokeWidth = 3f })
        }
        val a = Bitmap.createBitmap(tex, 0, 0, 750, 800)
        val bb = Bitmap.createBitmap(tex, 450, 0, 750, 800)
        val st = AiTools.stitch(listOf(a, bb))
        assertTrue("Szerokość ${st.width}", st.width in 1150..1250)
    }

    @Test
    fun zipOfPages() {
        val merged = File(dir, "m2.pdf")
        PdfTools.merge(listOf(pdf, pdf), merged)
        val z = Converters.pdfToJpg(merged, null, 72) { _, _ -> }
        ZipFile(z).use { assertEquals(2, it.size()) }
        z.delete()
    }
}
