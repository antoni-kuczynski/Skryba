package com.kuczynski.skryba.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.kuczynski.skryba.data.FilterType
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.Overlay
import com.kuczynski.skryba.data.Page
import java.io.File

/** Składa gotową stronę: oryginał -> prostowanie -> filtr -> obrót -> nakładki (podpisy). */
object PageRenderer {

    const val MAX_SIDE = 3200

    fun render(
        original: File,
        quad: FloatArray?,
        filter: FilterType,
        rotation: Int,
        overlays: List<Overlay> = emptyList(),
        maxSide: Int = MAX_SIDE,
    ): Bitmap {
        val src = ImageIO.decode(original, maxSide)
        val warped = DocumentDetector.warp(src, quad)
        if (warped !== src) src.recycle()
        val filtered = Filters.apply(warped, filter)
        if (filtered !== warped) warped.recycle()
        val rotated = ImageIO.rotate(filtered, rotation)
        if (rotated !== filtered) filtered.recycle()
        return applyOverlays(rotated, overlays)
    }

    fun applyOverlays(base: Bitmap, overlays: List<Overlay>): Bitmap {
        if (overlays.isEmpty()) return base
        val out = if (base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888, true).also { base.recycle() }
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for (o in overlays) {
            val f = File(Library.signaturesDir, o.image)
            val img = BitmapFactory.decodeFile(f.path) ?: continue
            val r = RectF(o.x * out.width, o.y * out.height, (o.x + o.w) * out.width, (o.y + o.h) * out.height)
            c.drawBitmap(img, null, r, paint)
            img.recycle()
        }
        return out
    }

    /** Przelicza stronę dokumentu i zapisuje nowy plik (nowa nazwa = brak problemów z cache miniatur). */
    fun reprocess(docId: String, page: Page, quality: Int): Page {
        val original = Library.pageFile(docId, page.original)
        val bmp = render(original, page.quad, page.filter, page.rotation, page.overlays)
        val name = "p_${Library.newId()}.jpg"
        ImageIO.saveJpeg(bmp, Library.pageFile(docId, name), quality)
        bmp.recycle()
        return page.copy(processed = name)
    }
}
