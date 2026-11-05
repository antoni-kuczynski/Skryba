package com.kuczynski.skryba.imaging

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Detects the edges of a sheet in a photo. Returns 8 numbers (TL, TR, BR, BL) normalized to 0..1,
 * or null when no sensible quadrilateral was found.
 */
object DocumentDetector {

    private const val WORK_SIZE = 480.0

    fun detect(src: Bitmap): FloatArray? {
        val scale = min(1.0, WORK_SIZE / max(src.width, src.height))
        val small = if (scale < 1.0) {
            Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true)
        } else src
        val rgba = Mat()
        Utils.bitmapToMat(small, rgba)
        val pts = try {
            detectMat(rgba)
        } finally {
            rgba.release()
            if (small !== src) small.recycle()
        } ?: return null
        val w = small.width.toFloat()
        val h = small.height.toFloat()
        return floatArrayOf(
            (pts[0].x / w).toFloat(), (pts[0].y / h).toFloat(),
            (pts[1].x / w).toFloat(), (pts[1].y / h).toFloat(),
            (pts[2].x / w).toFloat(), (pts[2].y / h).toFloat(),
            (pts[3].x / w).toFloat(), (pts[3].y / h).toFloat(),
        ).map { it.coerceIn(0f, 1f) }.toFloatArray()
    }

    private fun detectMat(rgba: Mat): Array<Point>? {
        val area = rgba.rows().toDouble() * rgba.cols()
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        // 1) Canny edges with thresholds computed from the median brightness.
        val median = median(gray)
        val edges = Mat()
        Imgproc.Canny(gray, edges, max(0.0, 0.66 * median), min(255.0, 1.33 * median))
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        var quad = findQuad(edges, area)

        // 2) If there is no result: Otsu thresholding (light sheet on a dark background).
        if (quad == null) {
            val bin = Mat()
            Imgproc.threshold(gray, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(
                bin, bin, Imgproc.MORPH_CLOSE,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)),
            )
            quad = findQuad(bin, area)
            bin.release()
        }

        // 3) If there is still none: color gradient (sheet on a similarly bright but colored background).
        if (quad == null) {
            val hsv = Mat()
            Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)
            val channels = ArrayList<Mat>()
            Core.split(hsv, channels)
            val sat = channels[1]
            Imgproc.GaussianBlur(sat, sat, Size(5.0, 5.0), 0.0)
            val bin = Mat()
            Imgproc.threshold(sat, bin, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
            quad = findQuad(bin, area)
            bin.release()
            hsv.release()
            channels.forEach { it.release() }
        }

        gray.release()
        edges.release()
        return quad?.let { order(it) }
    }

    private fun findQuad(bin: Mat, imgArea: Double): Array<Point>? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(bin.clone(), contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }.take(8)
        var best: Array<Point>? = null
        var bestArea = 0.0
        for (c in sorted) {
            val cArea = Imgproc.contourArea(c)
            if (cArea < imgArea * 0.12) break
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            var candidate: Array<Point>? = null
            // First a plain contour simplification, then a convex hull with growing tolerance.
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            if (approx.total() == 4L) candidate = approx.toArray()
            if (candidate == null) {
                val hullIdx = MatOfInt()
                Imgproc.convexHull(c, hullIdx)
                val pts = c.toArray()
                val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray())
                var eps = 0.02
                while (eps <= 0.1 && candidate == null) {
                    val a = MatOfPoint2f()
                    Imgproc.approxPolyDP(hull, a, eps * Imgproc.arcLength(hull, true), true)
                    if (a.total() == 4L) candidate = a.toArray()
                    eps += 0.01
                }
            }
            if (candidate != null) {
                val qa = Imgproc.contourArea(MatOfPoint2f(*candidate))
                val convex = Imgproc.isContourConvex(MatOfPoint(*candidate))
                if (convex && qa > imgArea * 0.12 && qa > bestArea && !touchesAllBorders(candidate, bin)) {
                    best = candidate
                    bestArea = qa
                }
            }
            if (best != null) break
        }
        contours.forEach { it.release() }
        return best
    }

    /** Rejects a "quadrilateral" that is just the frame of the whole photo. */
    private fun touchesAllBorders(p: Array<Point>, m: Mat): Boolean {
        val w = m.cols() - 1.0
        val h = m.rows() - 1.0
        val margin = 3.0
        return p.count { (it.x <= margin || it.x >= w - margin) && (it.y <= margin || it.y >= h - margin) } >= 4
    }

    private fun median(gray: Mat): Double {
        val bytes = ByteArray((gray.total() * gray.channels()).toInt())
        gray.get(0, 0, bytes)
        val hist = IntArray(256)
        for (b in bytes) hist[b.toInt() and 0xff]++
        var acc = 0
        val half = bytes.size / 2
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= half) return i.toDouble()
        }
        return 127.0
    }

    /** Orders the points: top left, top right, bottom right, bottom left. */
    fun order(p: Array<Point>): Array<Point> {
        val tl = p.minBy { it.x + it.y }
        val br = p.maxBy { it.x + it.y }
        val tr = p.minBy { it.y - it.x }
        val bl = p.maxBy { it.y - it.x }
        return arrayOf(tl, tr, br, bl)
    }

    /** Default quadrilateral with a small margin, used when detection fails. */
    fun fullQuad(): FloatArray = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    fun isFull(q: FloatArray?): Boolean = q == null || q.contentEquals(fullQuad())

    /** Cuts out and straightens the quadrilateral [quad] (normalized) from the image. */
    fun warp(src: Bitmap, quad: FloatArray?): Bitmap {
        if (quad == null || isFull(quad)) return src
        val w = src.width
        val h = src.height
        val p = Array(4) { Point(quad[it * 2] * (w - 1).toDouble(), quad[it * 2 + 1] * (h - 1).toDouble()) }
        val (tl, tr, br, bl) = p
        val outW = max(hypot(br.x - bl.x, br.y - bl.y), hypot(tr.x - tl.x, tr.y - tl.y)).roundToInt().coerceAtLeast(16)
        val outH = max(hypot(tr.x - br.x, tr.y - br.y), hypot(tl.x - bl.x, tl.y - bl.y)).roundToInt().coerceAtLeast(16)
        val srcPts = MatOfPoint2f(tl, tr, br, bl)
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1.0, 0.0), Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0),
        )
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val inMat = Mat()
        Utils.bitmapToMat(src, inMat)
        val out = Mat(outH, outW, CvType.CV_8UC4)
        Imgproc.warpPerspective(inMat, out, m, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, bmp)
        inMat.release()
        out.release()
        m.release()
        return bmp
    }
}
