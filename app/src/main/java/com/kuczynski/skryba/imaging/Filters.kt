package com.kuczynski.skryba.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.kuczynski.skryba.data.FilterType
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Filters that improve the look of a scan. All OpenCV, no network. */
object Filters {

    fun apply(src: Bitmap, filter: FilterType): Bitmap {
        if (filter == FilterType.ORIGINAL) return src
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        val out: Mat = when (filter) {
            FilterType.ORIGINAL -> rgb
            FilterType.AUTO -> auto(rgb)
            FilterType.MAGIC -> magic(rgb, saturation = 1.15)
            FilterType.GRAY -> gray(rgb)
            FilterType.BW -> blackWhite(rgb)
            FilterType.LIGHTEN -> lighten(rgb)
            FilterType.WHITEBOARD -> magic(rgb, saturation = 1.8, strong = true)
            FilterType.NO_INK -> noInk(rgb)
        }
        val bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
        val outRgba = Mat()
        if (out.channels() == 1) {
            Imgproc.cvtColor(out, outRgba, Imgproc.COLOR_GRAY2RGBA)
        } else {
            Imgproc.cvtColor(out, outRgba, Imgproc.COLOR_RGB2RGBA)
        }
        Utils.matToBitmap(outRgba, bmp)
        outRgba.release()
        if (out !== rgb) out.release()
        rgb.release()
        return bmp
    }

    // ---------- Helpers ----------

    /** Estimates the background (page lighting): downscale, dilation removes text, median smooths. */
    private fun background(plane: Mat): Mat {
        val f = 0.25
        val small = Mat()
        Imgproc.resize(plane, small, Size(), f, f, Imgproc.INTER_AREA)
        Imgproc.dilate(small, small, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0)))
        Imgproc.medianBlur(small, small, 21)
        val bg = Mat()
        Imgproc.resize(small, bg, plane.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        return bg
    }

    /** Divides the channel by the background: paper becomes white, shadows disappear. */
    private fun flatten(plane: Mat): Mat {
        val bg = background(plane)
        val out = Mat()
        Core.divide(plane, bg, out, 255.0)
        bg.release()
        return out
    }

    private fun contrastLut(lo: Int, hi: Int, gamma: Double): Mat {
        val lut = Mat(1, 256, CvType.CV_8UC1)
        val data = ByteArray(256)
        for (i in 0 until 256) {
            val t = ((i - lo).toDouble() / (hi - lo)).coerceIn(0.0, 1.0)
            data[i] = (255.0 * t.pow(gamma)).roundToInt().coerceIn(0, 255).toByte()
        }
        lut.put(0, 0, data)
        return lut
    }

    private fun applyLut(m: Mat, lo: Int, hi: Int, gamma: Double) {
        val lut = contrastLut(lo, hi, gamma)
        Core.LUT(m, lut, m)
        lut.release()
    }

    private fun boostSaturation(rgb: Mat, factor: Double) {
        if (factor == 1.0) return
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val ch = ArrayList<Mat>()
        Core.split(hsv, ch)
        ch[1].convertTo(ch[1], -1, factor, 0.0)
        Core.merge(ch, hsv)
        Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
        ch.forEach { it.release() }
        hsv.release()
    }

    private fun sharpen(m: Mat, amount: Double, sigma: Double) {
        val blur = Mat()
        Imgproc.GaussianBlur(m, blur, Size(0.0, 0.0), sigma)
        Core.addWeighted(m, 1 + amount, blur, -amount, 0.0, m)
        blur.release()
    }

    // ---------- Filters ----------

    /** Automatic levels, CLAHE on lightness and light sharpening. Good for photos and ID cards. */
    private fun auto(rgb: Mat): Mat {
        val out = rgb.clone()
        val ch = ArrayList<Mat>()
        Core.split(out, ch)
        for (c in ch) {
            val (lo, hi) = percentiles(c, 0.005, 0.995)
            if (hi - lo > 10) applyLut(c, lo, hi, 1.0)
        }
        Core.merge(ch, out)
        ch.forEach { it.release() }
        val lab = Mat()
        Imgproc.cvtColor(out, lab, Imgproc.COLOR_RGB2Lab)
        val lch = ArrayList<Mat>()
        Core.split(lab, lch)
        val clahe = Imgproc.createCLAHE(1.6, Size(8.0, 8.0))
        clahe.apply(lch[0], lch[0])
        Core.merge(lch, lab)
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        lch.forEach { it.release() }
        lab.release()
        sharpen(out, 0.5, 1.5)
        return out
    }

    /** "Magic color": removes shadows and background, keeps the colors of stamps and underlines. */
    private fun magic(rgb: Mat, saturation: Double, strong: Boolean = false): Mat {
        val ch = ArrayList<Mat>()
        Core.split(rgb, ch)
        val flat = ch.map { c -> flatten(c).also { c.release() } }
        val out = Mat()
        Core.merge(flat, out)
        flat.forEach { it.release() }
        applyLut(out, if (strong) 40 else 25, if (strong) 225 else 240, if (strong) 1.5 else 1.3)
        boostSaturation(out, saturation)
        sharpen(out, 0.4, 1.2)
        return out
    }

    private fun gray(rgb: Mat): Mat {
        val g = Mat()
        Imgproc.cvtColor(rgb, g, Imgproc.COLOR_RGB2GRAY)
        val flat = flatten(g)
        g.release()
        applyLut(flat, 20, 240, 1.25)
        sharpen(flat, 0.4, 1.2)
        return flat
    }

    private fun blackWhite(rgb: Mat): Mat {
        val g = Mat()
        Imgproc.cvtColor(rgb, g, Imgproc.COLOR_RGB2GRAY)
        val flat = flatten(g)
        g.release()
        val block = (min(flat.cols(), flat.rows()) / 30).let { if (it % 2 == 0) it + 1 else it }.coerceIn(15, 75)
        val out = Mat()
        Imgproc.adaptiveThreshold(flat, out, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0)
        flat.release()
        // Remove isolated noise dots.
        Imgproc.medianBlur(out, out, 3)
        return out
    }

    private fun lighten(rgb: Mat): Mat {
        val out = Mat()
        rgb.convertTo(out, -1, 1.12, 28.0)
        return out
    }

    /**
     * "Test paper" mode: removes answers written with a blue or red pen,
     * keeps black print. The result is grayscale.
     */
    private fun noInk(rgb: Mat): Mat {
        val colored = magic(rgb, saturation = 1.0)
        val hsv = Mat()
        Imgproc.cvtColor(colored, hsv, Imgproc.COLOR_RGB2HSV)
        val blue = Mat()
        val red1 = Mat()
        val red2 = Mat()
        Core.inRange(hsv, Scalar(90.0, 50.0, 40.0), Scalar(140.0, 255.0, 255.0), blue)
        Core.inRange(hsv, Scalar(0.0, 60.0, 40.0), Scalar(12.0, 255.0, 255.0), red1)
        Core.inRange(hsv, Scalar(160.0, 60.0, 40.0), Scalar(180.0, 255.0, 255.0), red2)
        val mask = Mat()
        Core.bitwise_or(blue, red1, mask)
        Core.bitwise_or(mask, red2, mask)
        Imgproc.dilate(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)))
        colored.setTo(Scalar(255.0, 255.0, 255.0), mask)
        val g = Mat()
        Imgproc.cvtColor(colored, g, Imgproc.COLOR_RGB2GRAY)
        applyLut(g, 30, 230, 1.2)
        listOf(hsv, blue, red1, red2, mask, colored).forEach { it.release() }
        return g
    }

    private fun percentiles(c: Mat, low: Double, high: Double): Pair<Int, Int> {
        val small = Mat()
        val f = min(1.0, 600.0 / max(c.cols(), c.rows()))
        Imgproc.resize(c, small, Size(), f, f, Imgproc.INTER_AREA)
        val bytes = ByteArray(small.total().toInt())
        small.get(0, 0, bytes)
        small.release()
        val hist = IntArray(256)
        for (b in bytes) hist[b.toInt() and 0xff]++
        val n = bytes.size
        var acc = 0
        var lo = 0
        var hi = 255
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= n * low) { lo = i; break }
        }
        acc = 0
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= n * high) { hi = i; break }
        }
        return lo to hi
    }

    // ---------- Special modes ----------

    /** Splits a book spread into two pages at the spine (the darkest column in the middle). */
    fun splitBook(src: Bitmap): Pair<Bitmap, Bitmap> {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val g = Mat()
        Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val col = Mat()
        Core.reduce(g, col, 0, Core.REDUCE_AVG, CvType.CV_32F)
        g.release()
        val w = src.width
        val values = FloatArray(w)
        col.get(0, 0, values)
        col.release()
        val from = (w * 0.35).toInt()
        val to = (w * 0.65).toInt()
        var bestX = w / 2
        var best = Float.MAX_VALUE
        val r = max(2, w / 200)
        for (x in from until to) {
            var s = 0f
            var n = 0
            for (k in x - r..x + r) if (k in 0 until w) { s += values[k]; n++ }
            val v = s / n
            if (v < best) { best = v; bestX = x }
        }
        val left = Bitmap.createBitmap(src, 0, 0, bestX, src.height)
        val right = Bitmap.createBitmap(src, bestX, 0, w - bestX, src.height)
        return left to right
    }

    /**
     * Places the front and back of an ID card on one A4 page (300 dpi) at the real card size,
     * like a photocopy of the ID.
     */
    fun composeIdCard(front: Bitmap, back: Bitmap?): Bitmap {
        val pageW = 2480
        val pageH = 3508
        val cardW = 1011f * 1.15f
        val cardH = 638f * 1.15f
        val out = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        fun place(b: Bitmap, top: Float) {
            // The card is landscape; rotate when the photo is portrait.
            val bmp = if (b.height > b.width) ImageIO.rotate(b, 90) else b
            val s = min(cardW / bmp.width, cardH / bmp.height)
            val w = bmp.width * s
            val h = bmp.height * s
            val left = (pageW - w) / 2f
            c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(left, top, left + w, top + h), paint)
        }
        place(front, pageH * 0.14f)
        if (back != null) place(back, pageH * 0.52f)
        return out
    }
}
