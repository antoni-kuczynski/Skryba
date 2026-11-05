package com.kuczynski.skryba.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.exifinterface.media.ExifInterface
import com.kuczynski.skryba.R
import com.kuczynski.skryba.i18n.str
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/** "AI" tools that run locally, using classical computer vision (OpenCV). */
object AiTools {

    private fun toRgb(bmp: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        return rgb
    }

    private fun toBitmap(m: Mat): Bitmap {
        val rgba = Mat()
        when (m.channels()) {
            1 -> Imgproc.cvtColor(m, rgba, Imgproc.COLOR_GRAY2RGBA)
            3 -> Imgproc.cvtColor(m, rgba, Imgproc.COLOR_RGB2RGBA)
            else -> m.copyTo(rgba)
        }
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        rgba.release()
        return bmp
    }

    // ---------------- Old photo restoration ----------------

    /**
     * Restores an old photo: removes scratches and dust (inpainting), denoises, removes yellowing,
     * improves contrast and sharpness. [strength] 1..3.
     */
    fun restorePhoto(src: Bitmap, strength: Int, removeScratches: Boolean, colorize: Boolean): Bitmap {
        val img = ImageIO.limit(src, 1800)
        var rgb = toRgb(img)
        if (img !== src) img.recycle()

        if (removeScratches) {
            val gray = Mat()
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
            val top = Mat()
            val black = Mat()
            Imgproc.morphologyEx(gray, top, Imgproc.MORPH_TOPHAT, k)
            Imgproc.morphologyEx(gray, black, Imgproc.MORPH_BLACKHAT, k)
            val mask = Mat()
            val t = 70.0 - strength * 10
            Imgproc.threshold(top, top, t, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(black, black, t + 10, 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_or(top, black, mask)
            Imgproc.dilate(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0)))
            // A mask that is too large means it is not scratches but photo content - skip it then.
            if (Core.countNonZero(mask) < mask.total() * 0.08) {
                val out = Mat()
                Photo.inpaint(rgb, mask, out, 3.0, Photo.INPAINT_TELEA)
                rgb.release()
                rgb = out
            }
            listOf(gray, top, black, mask, k).forEach { it.release() }
        }

        val den = Mat()
        val h = 3f + strength * 3f
        Photo.fastNlMeansDenoisingColored(rgb, den, h, h, 7, 21)
        rgb.release()

        // Gray-world white balance - removes yellowing and color casts.
        val ch = ArrayList<Mat>()
        Core.split(den, ch)
        val means = ch.map { Core.mean(it).`val`[0] }
        val avg = means.average()
        ch.forEachIndexed { i, c -> if (means[i] > 1) c.convertTo(c, -1, avg / means[i], 0.0) }
        Core.merge(ch, den)
        ch.forEach { it.release() }

        val lab = Mat()
        Imgproc.cvtColor(den, lab, Imgproc.COLOR_RGB2Lab)
        val lch = ArrayList<Mat>()
        Core.split(lab, lch)
        Core.normalize(lch[0], lch[0], 0.0, 255.0, Core.NORM_MINMAX)
        Imgproc.createCLAHE(1.5 + strength * 0.5, Size(8.0, 8.0)).apply(lch[0], lch[0])
        if (colorize) {
            // Gentle revival of faded colors (a/b channels away from the center 128).
            for (i in 1..2) lch[i].convertTo(lch[i], -1, 1.35, -128 * 0.35)
        }
        Core.merge(lch, lab)
        Imgproc.cvtColor(lab, den, Imgproc.COLOR_Lab2RGB)
        lch.forEach { it.release() }
        lab.release()

        val blur = Mat()
        Imgproc.GaussianBlur(den, blur, Size(0.0, 0.0), 1.6)
        Core.addWeighted(den, 1.6, blur, -0.6, 0.0, den)
        blur.release()
        return toBitmap(den).also { den.release() }
    }

    // ---------------- Tamper detection ----------------

    data class TamperReport(
        val heatmap: Bitmap,
        val score: Int,
        val verdict: String,
        val findings: List<String>,
        val editors: List<String>,
    )

    private val EDITORS = listOf(
        "Adobe Photoshop", "Photoshop", "Lightroom", "GIMP", "Snapseed", "PicsArt", "Canva", "Pixelmator",
        "Affinity Photo", "Paint.NET", "Photopea", "Fotor", "Meitu", "Facetune", "Luminar", "Corel", "PhotoScape",
        "Inkscape", "Krita", "remini", "FaceApp",
    )

    /**
     * ELA (Error Level Analysis) + metadata. Pasted or retouched fragments
     * usually have a different JPEG compression error level than the rest of the photo.
     */
    fun analyzeTampering(file: File): TamperReport {
        val findings = ArrayList<String>()
        val editors = ArrayList<String>()
        var score = 0

        // 1) EXIF and XMP metadata.
        val exif = runCatching { ExifInterface(file) }.getOrNull()
        val software = exif?.getAttribute(ExifInterface.TAG_SOFTWARE)
        val make = exif?.getAttribute(ExifInterface.TAG_MAKE)
        val model = exif?.getAttribute(ExifInterface.TAG_MODEL)
        val dt = exif?.getAttribute(ExifInterface.TAG_DATETIME)
        val dtOrig = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        val head = runCatching {
            file.inputStream().use { s -> ByteArray(min(file.length(), 512 * 1024L).toInt()).also { s.read(it) } }
        }.getOrDefault(ByteArray(0))
        val headText = String(head, Charsets.ISO_8859_1)
        for (e in EDITORS) {
            val inSoftware = software?.contains(e, ignoreCase = true) == true
            val inXmp = headText.contains(e, ignoreCase = true)
            if ((inSoftware || inXmp) && editors.none { it.contains(e, true) || e.contains(it, true) }) editors += e
        }
        if (editors.isNotEmpty()) {
            score += 45
            findings += str(R.string.tamper_editors, editors.joinToString(", "))
        } else if (!software.isNullOrBlank()) {
            findings += str(R.string.tamper_software, software)
        }
        if (headText.contains("photoshop:History", true) || headText.contains("stEvt:action=\"saved\"", true) ||
            headText.contains("<stEvt:action>saved", true)
        ) {
            score += 10
            findings += str(R.string.tamper_xmp_history)
        }
        if (make.isNullOrBlank() && model.isNullOrBlank()) {
            score += 8
            findings += str(R.string.tamper_no_camera)
        } else {
            findings += str(R.string.tamper_camera, listOfNotNull(make, model).joinToString(" "))
        }
        if (!dt.isNullOrBlank() && !dtOrig.isNullOrBlank() && dt != dtOrig) {
            score += 15
            findings += str(R.string.tamper_dates, dt, dtOrig)
        }
        val isJpeg = head.size > 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
        if (!isJpeg) findings += str(R.string.tamper_not_jpeg)

        // 2) ELA: difference after recompressing at quality 90.
        val bmp = ImageIO.decode(file, 2000)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        val re = BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size())
        val a = toRgb(bmp)
        val b = toRgb(re)
        re.recycle()
        val diff = Mat()
        Core.absdiff(a, b, diff)
        val ch = ArrayList<Mat>()
        Core.split(diff, ch)
        val ela = Mat()
        Core.max(ch[0], ch[1], ela)
        Core.max(ela, ch[2], ela)
        ch.forEach { it.release() }

        // Statistics of 16x16 blocks: look for areas that deviate from the typical error level.
        val bs = 16
        val bw = ela.cols() / bs
        val bh = ela.rows() / bs
        val blockMeans = FloatArray(max(1, bw * bh))
        if (bw > 0 && bh > 0) {
            val small = Mat()
            Imgproc.resize(ela.submat(0, bh * bs, 0, bw * bs), small, Size(bw.toDouble(), bh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val f = Mat()
            small.convertTo(f, CvType.CV_32F)
            f.get(0, 0, blockMeans)
            small.release()
            f.release()
        }
        val sorted = blockMeans.sorted()
        val med = sorted[sorted.size / 2]
        val mad = sorted.map { abs(it - med) }.sorted()[sorted.size / 2].coerceAtLeast(0.3f)
        val thr = med + 6 * mad + 1.5f
        val outliers = blockMeans.count { it > thr }
        val frac = outliers.toFloat() / blockMeans.size
        if (isJpeg && frac in 0.01f..0.35f) {
            score += (frac * 120).toInt().coerceAtMost(35)
            findings += str(R.string.tamper_ela_outliers, "%.1f".format(frac * 100))
        } else if (isJpeg) {
            findings += str(R.string.tamper_ela_uniform)
        }

        // Heat map for the preview.
        val vis = Mat()
        Core.normalize(ela, vis, 0.0, 255.0, Core.NORM_MINMAX)
        val amp = Mat()
        vis.convertTo(amp, -1, 2.5, 0.0)
        val color = Mat()
        Imgproc.applyColorMap(amp, color, Imgproc.COLORMAP_INFERNO)
        Imgproc.cvtColor(color, color, Imgproc.COLOR_BGR2RGB)
        val heat = toBitmap(color)
        listOf(a, b, diff, ela, vis, amp, color).forEach { it.release() }
        bmp.recycle()

        score = score.coerceIn(0, 100)
        val verdict = when {
            score >= 50 -> str(R.string.tamper_verdict_high)
            score >= 25 -> str(R.string.tamper_verdict_mid)
            else -> str(R.string.tamper_verdict_low)
        }
        return TamperReport(heat, score, verdict, findings, editors)
    }

    // ---------------- Object counting (CountCam) ----------------

    data class CountResult(val centers: List<PointF>, val radius: Float)

    /**
     * Counts similar objects in a photo (pipes, coins, pills, rods...).
     * [sensitivity] 0..1: higher = separates more tightly packed objects.
     */
    fun countObjects(src: Bitmap, sensitivity: Float, darkObjects: Boolean?): CountResult {
        val img = ImageIO.limit(src, 1200)
        val scale = src.width.toFloat() / img.width
        val rgb = toRgb(img)
        if (img !== src) img.recycle()
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        rgb.release()
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val bin = Mat()
        Imgproc.threshold(gray, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        val whiteFrac = Core.countNonZero(bin).toDouble() / bin.total()
        // Objects are usually the minority class of pixels, unless the user chose otherwise.
        val invert = when (darkObjects) {
            true -> true
            false -> false
            null -> whiteFrac > 0.5
        }
        if (invert) Core.bitwise_not(bin, bin)
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_OPEN, k3, Point(-1.0, -1.0), 2)
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, k3)

        val dist = Mat()
        Imgproc.distanceTransform(bin, dist, Imgproc.DIST_L2, 5)
        val mm = Core.minMaxLoc(dist)
        // Local maxima of the distance = object centers (separates touching objects).
        val dil = Mat()
        val r = max(3.0, mm.maxVal * 0.3)
        Imgproc.dilate(dist, dil, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(r * 2 + 1, r * 2 + 1)))
        val peaks = Mat()
        Core.compare(dist, dil, peaks, Core.CMP_GE)
        val strong = Mat()
        val level = mm.maxVal * (0.15 + 0.5 * (1 - sensitivity))
        Imgproc.threshold(dist, strong, level, 255.0, Imgproc.THRESH_BINARY)
        strong.convertTo(strong, CvType.CV_8U)
        Core.bitwise_and(peaks, strong, peaks)
        Imgproc.dilate(peaks, peaks, k3, Point(-1.0, -1.0), 2)

        val labels = Mat()
        val stats = Mat()
        val cents = Mat()
        val n = Imgproc.connectedComponentsWithStats(peaks, labels, stats, cents)
        val pts = ArrayList<PointF>()
        val radii = ArrayList<Float>()
        for (i in 1 until n) {
            val cx = cents.get(i, 0)[0]
            val cy = cents.get(i, 1)[0]
            val d = dist.get(cy.toInt().coerceIn(0, dist.rows() - 1), cx.toInt().coerceIn(0, dist.cols() - 1))[0]
            if (d < 2) continue
            pts += PointF((cx * scale).toFloat(), (cy * scale).toFloat())
            radii += (d * scale).toFloat()
        }
        // Remove duplicates: maxima closer than half a radius.
        val medR = radii.sorted().getOrElse(radii.size / 2) { 10f }
        val result = ArrayList<PointF>()
        for (p in pts) {
            if (result.none { q -> sqrt((p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y)) < medR * 0.8f }) result += p
        }
        listOf(gray, bin, k3, dist, dil, peaks, strong, labels, stats, cents).forEach { it.release() }
        return CountResult(result, medR)
    }

    // ---------------- Mega scan (stitching several photos) ----------------

    class StitchException(msg: String) : Exception(msg)

    /**
     * Stitches several overlapping photos of a large document (poster, map, A3)
     * into one image. The photos must be taken in order, with about 30% overlap.
     */
    fun stitch(images: List<Bitmap>, progress: (Int, Int) -> Unit = { _, _ -> }): Bitmap {
        require(images.size >= 2) { str(R.string.stitch_need_two) }
        val work = images.map { ImageIO.limit(it, 1600) }
        val mats = work.map { toRgb(it) }
        val orb = ORB.create(4000)
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val kps = ArrayList<MatOfKeyPoint>()
        val descs = ArrayList<Mat>()
        for (m in mats) {
            val g = Mat()
            Imgproc.cvtColor(m, g, Imgproc.COLOR_RGB2GRAY)
            val kp = MatOfKeyPoint()
            val d = Mat()
            orb.detectAndCompute(g, Mat(), kp, d)
            kps += kp
            descs += d
            g.release()
        }
        // Homographies of each photo relative to the first one (chained).
        val toFirst = ArrayList<Mat>()
        toFirst += Mat.eye(3, 3, CvType.CV_64F)
        for (i in 1 until mats.size) {
            progress(i, mats.size)
            val knn = ArrayList<MatOfDMatch>()
            matcher.knnMatch(descs[i], descs[i - 1], knn, 2)
            val src = ArrayList<Point>()
            val dst = ArrayList<Point>()
            val kpA = kps[i].toArray()
            val kpB = kps[i - 1].toArray()
            for (m in knn) {
                val arr = m.toArray()
                if (arr.size == 2 && arr[0].distance < 0.75f * arr[1].distance) {
                    src += kpA[arr[0].queryIdx].pt
                    dst += kpB[arr[0].trainIdx].pt
                }
            }
            if (src.size < 15) throw StitchException(str(R.string.stitch_too_little, i, i + 1))
            val h = Calib3d.findHomography(MatOfPoint2f(*src.toTypedArray()), MatOfPoint2f(*dst.toTypedArray()), Calib3d.RANSAC, 4.0)
            if (h.empty()) throw StitchException(str(R.string.stitch_no_match, i + 1))
            val total = Mat()
            Core.gemm(toFirst[i - 1], h, 1.0, Mat(), 0.0, total)
            toFirst += total
        }
        // Canvas size from the corners after transformation.
        var minX = 0.0
        var minY = 0.0
        var maxX = 0.0
        var maxY = 0.0
        mats.forEachIndexed { i, m ->
            val corners = MatOfPoint2f(
                Point(0.0, 0.0), Point(m.cols().toDouble(), 0.0),
                Point(m.cols().toDouble(), m.rows().toDouble()), Point(0.0, m.rows().toDouble()),
            )
            val out = MatOfPoint2f()
            Core.perspectiveTransform(corners, out, toFirst[i])
            for (p in out.toArray()) {
                minX = min(minX, p.x); minY = min(minY, p.y)
                maxX = max(maxX, p.x); maxY = max(maxY, p.y)
            }
        }
        val w = ceil(maxX - minX).toInt()
        val hgt = ceil(maxY - minY).toInt()
        if (w <= 0 || hgt <= 0 || w > 12000 || hgt > 12000) throw StitchException(str(R.string.stitch_failed))
        val shift = Mat.eye(3, 3, CvType.CV_64F)
        shift.put(0, 2, -floor(minX))
        shift.put(1, 2, -floor(minY))
        val canvas = Mat(hgt, w, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        val filled = Mat(hgt, w, CvType.CV_8UC1, Scalar(0.0))
        mats.forEachIndexed { i, m ->
            val t = Mat()
            Core.gemm(shift, toFirst[i], 1.0, Mat(), 0.0, t)
            val warped = Mat()
            Imgproc.warpPerspective(m, warped, t, Size(w.toDouble(), hgt.toDouble()))
            val mask = Mat(m.rows(), m.cols(), CvType.CV_8UC1, Scalar(255.0))
            val wm = Mat()
            Imgproc.warpPerspective(mask, wm, t, Size(w.toDouble(), hgt.toDouble()))
            Imgproc.erode(wm, wm, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
            // A new photo fills only empty spots - no "ghosts" in the overlaps.
            val empty = Mat()
            Core.bitwise_not(filled, empty)
            Core.bitwise_and(wm, empty, wm)
            warped.copyTo(canvas, wm)
            Core.bitwise_or(filled, wm, filled)
            listOf(t, warped, mask, wm, empty).forEach { it.release() }
        }
        progress(mats.size, mats.size)
        // Trim black edges.
        val nz = Mat()
        Core.findNonZero(filled, nz)
        val rect = Imgproc.boundingRect(nz)
        val cropped = Mat(canvas, rect)
        val bmp = toBitmap(cropped)
        listOf(canvas, filled, nz).forEach { it.release() }
        mats.forEach { it.release() }
        work.forEachIndexed { i, b -> if (b !== images[i]) b.recycle() }
        return bmp
    }
}
