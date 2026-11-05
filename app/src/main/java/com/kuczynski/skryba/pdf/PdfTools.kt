package com.kuczynski.skryba.pdf

import android.content.Context
import android.graphics.Bitmap
import com.kuczynski.skryba.R
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.imaging.ImageIO
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Word with a position on the page (in points, origin at the top left corner). */
data class WordBox(val text: String, val x: Float, val y: Float, val w: Float, val h: Float)

data class PageWords(val width: Float, val height: Float, val words: List<WordBox>)

class PdfPasswordException : Exception(str(R.string.err_pdf_password))

/** Operations on PDF files (PdfBox, offline). */
object PdfTools {

    private const val FONT_ASSET = "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf"

    fun loadFont(context: Context, doc: PDDocument): PDType0Font =
        context.assets.open(FONT_ASSET).use { PDType0Font.load(doc, it) }

    fun open(file: File, password: String? = null): PDDocument = try {
        if (password.isNullOrEmpty()) PDDocument.load(file, MemoryUsageSetting.setupMixed(64L shl 20))
        else PDDocument.load(file, password, MemoryUsageSetting.setupMixed(64L shl 20))
    } catch (e: InvalidPasswordException) {
        throw PdfPasswordException()
    }

    /** Whether opening the file requires a password. */
    fun needsPassword(file: File): Boolean = try {
        PDDocument.load(file).close()
        false
    } catch (e: InvalidPasswordException) {
        true
    }

    fun pageCount(file: File, password: String? = null): Int = open(file, password).use { it.numberOfPages }

    /** Saves with any encryption removed (files opened with a password are saved without one). */
    private fun PDDocument.saveClean(out: File) {
        if (isEncrypted) isAllSecurityToBeRemoved = true
        save(out)
    }

    fun merge(inputs: List<File>, out: File, passwords: Map<File, String> = emptyMap()) {
        // Encrypted files are first decrypted to temporary files.
        val prepared = inputs.map { f ->
            val pw = passwords[f]
            if (pw != null) {
                val tmp = File(out.parentFile, "dec_${f.name}")
                open(f, pw).use { it.saveClean(tmp) }
                tmp
            } else f
        }
        val m = PDFMergerUtility()
        m.destinationFileName = out.path
        prepared.forEach { m.addSource(it) }
        m.mergeDocuments(MemoryUsageSetting.setupMixed(64L shl 20))
        prepared.filter { it !in inputs }.forEach { it.delete() }
    }

    /** Splits a PDF into parts. [ranges] are page index ranges counted from 0. */
    fun split(input: File, ranges: List<IntRange>, outFiles: List<File>, password: String? = null) {
        open(input, password).use { src ->
            ranges.forEachIndexed { i, r ->
                PDDocument().use { d ->
                    for (p in r) if (p in 0 until src.numberOfPages) d.importPage(src.getPage(p))
                    d.save(outFiles[i])
                }
            }
        }
    }

    /**
     * General reorganization: page order [order] (indexes from the original, missing = removed)
     * and an extra rotation for each position [rotations] (degrees, multiple of 90).
     */
    fun organize(input: File, order: List<Int>, rotations: List<Int>, out: File, password: String? = null) {
        open(input, password).use { src ->
            PDDocument().use { dst ->
                order.forEachIndexed { pos, idx ->
                    val p = src.getPage(idx)
                    val imported = dst.importPage(p)
                    imported.rotation = ((p.rotation + rotations.getOrElse(pos) { 0 }) % 360 + 360) % 360
                }
                dst.save(out)
            }
        }
    }

    fun watermark(
        context: Context,
        input: File,
        text: String,
        opacity: Float,
        color: Int,
        out: File,
        password: String? = null,
    ) {
        open(input, password).use { doc ->
            val font = loadFont(context, doc)
            val safe = encodable(font, text).ifBlank { "SKRYBA" }
            for (page in doc.pages) {
                val box = page.cropBox
                val w = box.width
                val h = box.height
                val diag = hypot(w, h)
                val unitWidth = font.getStringWidth(safe) / 1000f
                val fontSize = min(0.65f * diag / unitWidth, h * 0.25f)
                val tw = unitWidth * fontSize
                val angle = atan2(h, w).toDouble()
                val cx = box.lowerLeftX + w / 2
                val cy = box.lowerLeftY + h / 2
                val c = cos(angle).toFloat()
                val s = sin(angle).toFloat()
                // Text center in the page center: shift by half the width along the rotated axis.
                val ox = -tw / 2f
                val oy = -fontSize / 3f
                val tx = cx + ox * c - oy * s
                val ty = cy + ox * s + oy * c
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    val gs = PDExtendedGraphicsState()
                    gs.nonStrokingAlphaConstant = opacity
                    gs.strokingAlphaConstant = opacity
                    cs.setGraphicsStateParameters(gs)
                    cs.setNonStrokingColor(
                        ((color shr 16) and 0xff) / 255f,
                        ((color shr 8) and 0xff) / 255f,
                        (color and 0xff) / 255f,
                    )
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    cs.setTextMatrix(Matrix(c, s, -s, c, tx, ty))
                    cs.showText(safe)
                    cs.endText()
                }
            }
            doc.saveClean(out)
        }
    }

    fun protect(input: File, userPassword: String, out: File, currentPassword: String? = null) {
        open(input, currentPassword).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val ap = AccessPermission()
            val policy = StandardProtectionPolicy(userPassword, userPassword, ap)
            policy.encryptionKeyLength = 256
            doc.protect(policy)
            doc.save(out)
        }
    }

    fun unlock(input: File, password: String, out: File) {
        open(input, password).use { it.saveClean(out) }
    }

    /**
     * Compression: re-encodes images in the PDF as JPEG with the given quality and limited resolution.
     * Text and vector graphics stay unchanged.
     */
    fun compress(input: File, quality: Float, maxDim: Int, out: File, password: String? = null) {
        open(input, password).use { doc ->
            val done = HashMap<COSKey, PDImageXObject>()
            for (page in doc.pages) {
                page.resources?.let { compressResources(doc, it, quality, maxDim, done, 0) }
            }
            doc.saveClean(out)
        }
    }

    private class COSKey(val obj: Any) {
        override fun equals(other: Any?) = other is COSKey && other.obj === obj
        override fun hashCode() = System.identityHashCode(obj)
    }

    private fun compressResources(
        doc: PDDocument,
        res: PDResources,
        quality: Float,
        maxDim: Int,
        done: HashMap<COSKey, PDImageXObject>,
        depth: Int,
    ) {
        if (depth > 5) return
        for (name in res.xObjectNames.toList()) {
            val xo = runCatching { res.getXObject(name) }.getOrNull() ?: continue
            when (xo) {
                is PDImageXObject -> {
                    val key = COSKey(xo.cosObject)
                    val replacement = done[key] ?: run {
                        // Skip masks and small images - not worth it, and easy to break something.
                        if (xo.isStencil || xo.width * xo.height < 200 * 200) return@run null
                        val bmp = runCatching { xo.image }.getOrNull() ?: return@run null
                        val scaled = ImageIO.limit(bmp, maxDim)
                        val hasAlpha = xo.cosObject.containsKey(COSName.SMASK)
                        val img = if (hasAlpha) LosslessFactory.createFromImage(doc, scaled)
                        else JPEGFactory.createFromImage(doc, scaled, quality)
                        if (scaled !== bmp) scaled.recycle()
                        bmp.recycle()
                        img
                    }
                    if (replacement != null) {
                        done[key] = replacement
                        res.put(name, replacement)
                    }
                }
                is PDFormXObject -> xo.resources?.let { compressResources(doc, it, quality, maxDim, done, depth + 1) }
            }
        }
    }

    /**
     * Overlays an image (e.g. a signature) on a page. The rectangle [nx],[ny],[nw],[nh] is normalized to
     * the page as displayed (including rotation), origin at the top left corner.
     */
    fun stampImage(
        input: File,
        pageIndex: Int,
        image: Bitmap,
        nx: Float, ny: Float, nw: Float, nh: Float,
        out: File,
        password: String? = null,
    ) {
        open(input, password).use { doc ->
            val page = doc.getPage(pageIndex)
            val rot = ((page.rotation % 360) + 360) % 360
            val box = page.cropBox
            val bw = box.width
            val bh = box.height
            fun map(u: Float, v: Float): Pair<Float, Float> = when (rot) {
                90 -> box.lowerLeftX + v * bw to box.lowerLeftY + u * bh
                180 -> box.lowerLeftX + (1 - u) * bw to box.lowerLeftY + v * bh
                270 -> box.upperRightX - v * bw to box.upperRightY - u * bh
                else -> box.lowerLeftX + u * bw to box.lowerLeftY + (1 - v) * bh
            }
            val (x1, y1) = map(nx, ny)
            val (x2, y2) = map(nx + nw, ny + nh)
            val upright = if (rot == 0) image else ImageIO.rotate(image, 360 - rot)
            val pdImg = LosslessFactory.createFromImage(doc, upright)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.drawImage(pdImg, min(x1, x2), min(y1, y2), kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1))
            }
            if (upright !== image) upright.recycle()
            doc.saveClean(out)
        }
    }

    fun extractText(input: File, password: String? = null): List<String> = open(input, password).use { doc ->
        val stripper = PDFTextStripper().apply { sortByPosition = true }
        (1..doc.numberOfPages).map { p ->
            stripper.startPage = p
            stripper.endPage = p
            stripper.getText(doc).replace("\r", "").trim()
        }
    }

    /** Words with positions for each page (to rebuild tables in Excel). */
    fun extractWords(input: File, password: String? = null): List<PageWords> = open(input, password).use { doc ->
        (1..doc.numberOfPages).map { p ->
            val words = ArrayList<WordBox>()
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    // PdfBox calls this for line fragments; we split further on spaces.
                    var cur = StringBuilder()
                    var first: TextPosition? = null
                    var last: TextPosition? = null
                    fun flush() {
                        val f = first
                        val l = last
                        if (cur.isNotBlank() && f != null && l != null) {
                            val x = f.xDirAdj
                            val w = l.xDirAdj + l.widthDirAdj - x
                            val h = max(f.heightDir, 1f)
                            words += WordBox(cur.toString(), x, f.yDirAdj - h, w, h)
                        }
                        cur = StringBuilder()
                        first = null
                        last = null
                    }
                    for (tp in textPositions) {
                        val u = tp.unicode ?: continue
                        if (u.isBlank()) {
                            flush()
                        } else {
                            if (first == null) first = tp
                            last = tp
                            cur.append(u)
                        }
                    }
                    flush()
                }
            }
            stripper.sortByPosition = true
            stripper.startPage = p
            stripper.endPage = p
            stripper.getText(doc)
            val page = doc.getPage(p - 1)
            val rot = page.rotation % 180 != 0
            PageWords(
                if (rot) page.cropBox.height else page.cropBox.width,
                if (rot) page.cropBox.width else page.cropBox.height,
                words,
            )
        }
    }

    /** Keeps only characters that can be written with the given font (e.g. no emoji). */
    fun encodable(font: PDFont, text: String): String {
        if (runCatching { font.encode(text) }.isSuccess) return text
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            if (runCatching { font.encode(s) }.isSuccess) sb.append(s)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
