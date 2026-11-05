package com.kuczynski.skryba.convert

import com.kuczynski.skryba.pdf.PageWords
import com.kuczynski.skryba.pdf.WordBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class OoxmlTest {

    private val outDir = File(System.getProperty("ooxml.out") ?: "build/ooxml-test").apply { mkdirs() }

    private fun jpeg(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(15, 123, 108)
        g.fillRect(0, 0, w, h)
        g.color = java.awt.Color.WHITE
        g.fillRect(w / 4, h / 4, w / 2, h / 2)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    @Test
    fun docxRoundTrip() {
        val f = File(outDir, "test.docx")
        OoxmlWriter.writeDocx(
            f,
            listOf(
                DocxBlock.Paragraph("Zażółć gęślą jaźń", heading = 1),
                DocxBlock.Paragraph("Zwykły akapit <z> & znakami\tpo tabulatorze"),
                DocxBlock.Table(listOf(listOf("A", "B"), listOf("1", "2"))),
                DocxBlock.Image(jpeg(400, 300), 400, 300),
                DocxBlock.PageBreak,
                DocxBlock.Paragraph("Druga strona"),
            ),
        )
        val doc = OfficeReader.readDocx(f)
        val texts = doc.blocks.filterIsInstance<FlowBlock.P>().map { it.para.plainText }
        assertTrue(texts.contains("Zażółć gęślą jaźń"))
        assertEquals(1, doc.blocks.filterIsInstance<FlowBlock.P>().first { it.para.plainText.startsWith("Zażółć") }.para.heading)
        assertTrue(texts.any { it.startsWith("Zwykły akapit <z> & znakami\t") })
        assertEquals(1, doc.blocks.count { it is FlowBlock.Table })
        assertEquals(1, doc.blocks.count { it is FlowBlock.Img })
        assertEquals(1, doc.blocks.count { it is FlowBlock.PageBreak })
    }

    @Test
    fun xlsxRoundTrip() {
        val f = File(outDir, "test.xlsx")
        OoxmlWriter.writeXlsx(
            f,
            listOf(
                Sheet("Strona 1", listOf(listOf("Nazwa", "Ilość", "Cena"), listOf("Jabłka", "3", "2,50"), listOf("Kod", "007", ""))),
                Sheet("Strona 1", listOf(listOf("x"))),
            ),
        )
        val sheets = OfficeReader.readXlsx(f)
        assertEquals(2, sheets.size)
        assertEquals("Jabłka", sheets[0].rows[1][0])
        assertEquals("3", sheets[0].rows[1][1])
        assertEquals("2.5", sheets[0].rows[1][2])
        assertEquals("007", sheets[0].rows[2][1])
        assertTrue(sheets[1].name != sheets[0].name)
    }

    @Test
    fun pptxRoundTrip() {
        val f = File(outDir, "test.pptx")
        OoxmlWriter.writePptx(f, listOf(Slide(jpeg(800, 600), 800, 600), Slide(jpeg(600, 800), 600, 800)))
        val p = OfficeReader.readPptx(f)
        assertEquals(2, p.slides.size)
        assertEquals(720f, p.widthPt, 0.5f)
        assertTrue(p.slides.all { s -> s.shapes.any { it.image != null } })
    }

    @Test
    fun tableFromWords() {
        val words = listOf(
            WordBox("Imię", 50f, 100f, 30f, 10f), WordBox("Kwota", 300f, 100f, 35f, 10f),
            WordBox("Jan", 50f, 120f, 20f, 10f), WordBox("Kowalski", 74f, 120f, 50f, 10f), WordBox("100,00", 300f, 120f, 40f, 10f),
            WordBox("Anna", 50f, 140f, 30f, 10f), WordBox("50", 310f, 140f, 15f, 10f),
        )
        val t = TextLayout.table(PageWords(595f, 842f, words))
        assertEquals(listOf("Imię", "Kwota"), t[0])
        assertEquals(listOf("Jan Kowalski", "100,00"), t[1])
        assertEquals(listOf("Anna", "50"), t[2])
    }

    @Test
    fun tableWithOverlappingColumns() {
        // A left-aligned header overlaps right-aligned numbers.
        fun w(t: String, x: Int, wd: Int, y: Int) = WordBox(t, x.toFloat(), y.toFloat(), wd.toFloat(), 10f)
        val words = listOf(
            w("Faktura", 49, 66, 50), w("numer", 124, 58, 50), w("15", 190, 21, 50),
            w("Nazwa", 49, 60, 80), w("Ilosc", 135, 42, 80), w("Cena", 201, 45, 80),
            w("Papier", 49, 58, 100), w("10", 131, 20, 100), w("25,00", 175, 49, 100),
            w("Olowek", 49, 67, 120), w("3", 139, 9, 120), w("4,50", 171, 38, 120),
        )
        val t = TextLayout.table(PageWords(595f, 842f, words))
        assertEquals(listOf("Nazwa", "Ilosc", "Cena"), t[1])
        assertEquals(listOf("Papier", "10", "25,00"), t[2])
        assertEquals(listOf("Olowek", "3", "4,50"), t[3])
    }

    @Test
    fun paragraphsFromWords() {
        val words = listOf(
            WordBox("Tytuł", 50f, 50f, 80f, 24f),
            WordBox("Pierwsza", 50f, 100f, 50f, 10f), WordBox("linia", 104f, 100f, 30f, 10f),
            WordBox("druga.", 50f, 113f, 40f, 10f),
            WordBox("Nowy", 50f, 140f, 30f, 10f), WordBox("akapit.", 84f, 140f, 40f, 10f),
        )
        val p = TextLayout.paragraphs(PageWords(595f, 842f, words))
        assertEquals(3, p.size)
        assertEquals(1, p[0].heading)
        assertEquals("Pierwsza linia druga.", p[1].text)
        assertEquals("Nowy akapit.", p[2].text)
    }

    @Test
    fun csv() {
        val rows = Converters.parseCsv("a;b;c\n1;\"x;y\";3\n")
        assertEquals(listOf("1", "x;y", "3"), rows[1])
    }
}
