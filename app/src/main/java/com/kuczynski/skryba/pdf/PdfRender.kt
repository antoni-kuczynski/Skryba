package com.kuczynski.skryba.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Rendering PDF pages to bitmaps. By default the system PdfRenderer (fast),
 * and PdfBox for password-protected files.
 */
object PdfRender {

    fun pageCount(file: File, password: String? = null): Int = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }
    } catch (e: SecurityException) {
        PdfTools.pageCount(file, password)
    }

    /** Page aspect ratios (width/height) without rendering. */
    fun pageAspects(file: File, password: String? = null): List<Float> = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                (0 until r.pageCount).map { i -> r.openPage(i).use { it.width.toFloat() / it.height } }
            }
        }
    } catch (e: SecurityException) {
        PdfTools.open(file, password).use { d ->
            d.pages.map { p ->
                val b = p.cropBox
                if (p.rotation % 180 != 0) b.height / b.width else b.width / b.height
            }
        }
    }

    /** Renders a page so the longer side has [maxSide] pixels (or at the given dpi). */
    fun render(file: File, index: Int, maxSide: Int = 1400, dpi: Int? = null, password: String? = null): Bitmap = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(index).use { page ->
                    val scale = dpi?.let { it / 72f } ?: (maxSide.toFloat() / max(page.width, page.height))
                    val w = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val h = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    bmp
                }
            }
        }
    } catch (e: SecurityException) {
        PdfTools.open(file, password).use { d ->
            val p = d.getPage(index)
            val b = p.cropBox
            val longest = max(b.width, b.height)
            val scale = dpi?.let { it / 72f } ?: (maxSide / longest)
            PDFRenderer(d).renderImage(index, scale)
        }
    }
}
