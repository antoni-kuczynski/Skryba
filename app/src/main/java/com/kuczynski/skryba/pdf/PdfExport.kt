package com.kuczynski.skryba.pdf

import android.content.Context
import android.graphics.Bitmap
import com.kuczynski.skryba.data.PageSize
import com.kuczynski.skryba.imaging.ImageIO
import com.kuczynski.skryba.ocr.OcrEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class PdfOptions(
    val pageSize: PageSize = PageSize.A4,
    val quality: Int = 85,
    /** Niewidoczna warstwa tekstu z OCR - PDF da się przeszukiwać i kopiować z niego tekst. */
    val searchable: Boolean = false,
    val password: String? = null,
)

/** Tworzenie PDF z obrazów stron. */
object PdfExport {

    fun imagesToPdf(
        context: Context,
        images: List<File>,
        out: File,
        options: PdfOptions,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        PDDocument().use { pd ->
            val font = if (options.searchable) PdfTools.loadFont(context, pd) else null
            images.forEachIndexed { i, f ->
                progress(i, images.size)
                val bmp = ImageIO.decode(f, 3500)
                addImagePage(context, pd, bmp, options, font)
                bmp.recycle()
            }
            progress(images.size, images.size)
            options.password?.takeIf { it.isNotEmpty() }?.let { pw ->
                val policy = StandardProtectionPolicy(pw, pw, AccessPermission())
                policy.encryptionKeyLength = 256
                pd.protect(policy)
            }
            pd.save(out)
        }
    }

    private fun addImagePage(context: Context, pd: PDDocument, bmp: Bitmap, o: PdfOptions, font: PDType0Font?) {
        val landscape = bmp.width > bmp.height
        val (pw, ph) = when (o.pageSize) {
            PageSize.FIT -> {
                // Dłuższy bok jak w A4, proporcje z obrazu.
                val s = 841.89f / max(bmp.width, bmp.height)
                bmp.width * s to bmp.height * s
            }
            else -> if (landscape) o.pageSize.heightPt to o.pageSize.widthPt else o.pageSize.widthPt to o.pageSize.heightPt
        }
        val page = PDPage(PDRectangle(pw, ph))
        pd.addPage(page)
        val img = JPEGFactory.createFromImage(pd, bmp, o.quality / 100f)
        val s = min(pw / bmp.width, ph / bmp.height)
        val dw = bmp.width * s
        val dh = bmp.height * s
        val x = (pw - dw) / 2
        val y = (ph - dh) / 2
        PDPageContentStream(pd, page).use { cs ->
            cs.drawImage(img, x, y, dw, dh)
            if (font != null) {
                val words = runCatching { OcrEngine.recognizeWords(context, bmp) }.getOrDefault(emptyList())
                if (words.isNotEmpty()) {
                    cs.beginText()
                    cs.setRenderingMode(RenderingMode.NEITHER)
                    for (w in words) {
                        val text = PdfTools.encodable(font, w.text)
                        if (text.isBlank()) continue
                        val fontSize = max(1f, w.box.height() * s)
                        val tw = font.getStringWidth(text) / 1000f * fontSize
                        val boxW = w.box.width() * s
                        cs.setFont(font, fontSize)
                        cs.setHorizontalScaling(if (tw > 0) (boxW / tw * 100f).coerceIn(10f, 500f) else 100f)
                        val tx = x + w.box.left * s
                        val ty = y + dh - w.box.bottom * s + fontSize * 0.15f
                        cs.setTextMatrix(Matrix.getTranslateInstance(tx, ty))
                        cs.showText("$text ")
                    }
                    cs.endText()
                }
            }
        }
    }
}
