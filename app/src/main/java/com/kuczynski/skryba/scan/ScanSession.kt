package com.kuczynski.skryba.scan

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.FilterType
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.Page
import com.kuczynski.skryba.data.ScanDocument
import com.kuczynski.skryba.data.ScanMode
import com.kuczynski.skryba.data.Settings
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.imaging.DocumentDetector
import com.kuczynski.skryba.imaging.Filters
import com.kuczynski.skryba.imaging.ImageIO
import com.kuczynski.skryba.imaging.PageRenderer
import java.io.File

/** A page being scanned (before it is saved to a document). */
class SessionPage(val id: String, val file: File, quad: FloatArray?, filter: FilterType) {
    var quad by mutableStateOf(quad)
    var filter by mutableStateOf(filter)
    var rotation by mutableStateOf(0)
}

/** Current scan session: photos taken with the camera or imported from the gallery. */
object ScanSession {
    var mode by mutableStateOf(ScanMode.DOCUMENT)
    var targetDocId: String? = null
    var splitBook by mutableStateOf(true)
    val pages = mutableStateListOf<SessionPage>()

    /** Lower resolution previews, key = page id + parameters. */
    val previews = HashMap<String, Bitmap>()

    fun start(mode: ScanMode, targetDocId: String? = null) {
        this.mode = mode
        this.targetDocId = targetDocId
        splitBook = true
        pages.clear()
        previews.clear()
        Library.sessionDir.listFiles()?.forEach { it.delete() }
    }

    fun find(id: String) = pages.firstOrNull { it.id == id }

    /** Normalizes the photo (EXIF rotation, size), detects the sheet edges and adds a page. */
    fun addCapture(raw: File): SessionPage {
        val bmp = ImageIO.decode(raw, 4000)
        val file = File(Library.sessionDir, "s_${Library.newId()}.jpg")
        ImageIO.saveJpeg(bmp, file, 92)
        if (raw != file) raw.delete()
        val quad = DocumentDetector.detect(bmp)
        bmp.recycle()
        val page = SessionPage(Library.newId(), file, quad, mode.defaultFilter)
        pages += page
        return page
    }

    fun addFromUri(context: Context, uri: Uri): SessionPage {
        val bmp = ImageIO.decodeUri(context, uri, 4000)
        val file = File(Library.sessionDir, "s_${Library.newId()}.jpg")
        ImageIO.saveJpeg(bmp, file, 92)
        val quad = DocumentDetector.detect(bmp)
        bmp.recycle()
        val page = SessionPage(Library.newId(), file, quad, mode.defaultFilter)
        pages += page
        return page
    }

    fun addBitmap(bmp: Bitmap, detect: Boolean): SessionPage {
        val file = File(Library.sessionDir, "s_${Library.newId()}.jpg")
        ImageIO.saveJpeg(bmp, file, 92)
        val page = SessionPage(Library.newId(), file, if (detect) DocumentDetector.detect(bmp) else null, mode.defaultFilter)
        pages += page
        return page
    }

    fun remove(p: SessionPage) {
        pages.remove(p)
        p.file.delete()
    }

    fun previewKey(p: SessionPage) = "${p.id}|${p.quad?.contentToString()}|${p.filter}|${p.rotation}"

    fun docPrefix(mode: ScanMode) = when (mode) {
        ScanMode.DOCUMENT -> str(R.string.doc_prefix_scan)
        else -> mode.label
    }

    /** Saves the session as a new document or appends pages to an existing one. */
    fun save(progress: (Int, Int) -> Unit): ScanDocument {
        val existing = targetDocId?.let { Library.get(it) }
        val docId = existing?.id ?: Library.newId()
        val quality = Settings.value.jpegQuality
        val newPages = ArrayList<Page>()
        val total = pages.size
        val list = pages.toList()

        fun savePage(originalBmp: Bitmap, filter: FilterType): Page {
            val oName = "o_${Library.newId()}.jpg"
            ImageIO.saveJpeg(originalBmp, Library.pageFile(docId, oName), 92)
            val processed = Filters.apply(originalBmp, filter)
            val pName = "p_${Library.newId()}.jpg"
            ImageIO.saveJpeg(processed, Library.pageFile(docId, pName), quality)
            if (processed !== originalBmp) processed.recycle()
            return Page(Library.newId(), oName, pName, null, filter, 0)
        }

        when {
            mode == ScanMode.ID_CARD -> {
                list.chunked(2).forEachIndexed { i, pair ->
                    progress(i * 2, total)
                    val front = PageRenderer.render(pair[0].file, pair[0].quad, pair[0].filter, pair[0].rotation, maxSide = 2000)
                    val back = pair.getOrNull(1)?.let { PageRenderer.render(it.file, it.quad, it.filter, it.rotation, maxSide = 2000) }
                    val composed = Filters.composeIdCard(front, back)
                    front.recycle()
                    back?.recycle()
                    newPages += savePage(composed, FilterType.ORIGINAL)
                    composed.recycle()
                }
            }
            mode == ScanMode.BOOK && splitBook -> {
                list.forEachIndexed { i, p ->
                    progress(i, total)
                    val src = ImageIO.decode(p.file, PageRenderer.MAX_SIDE)
                    val warped = DocumentDetector.warp(src, p.quad)
                    if (warped !== src) src.recycle()
                    val rotated = ImageIO.rotate(warped, p.rotation)
                    if (rotated !== warped) warped.recycle()
                    val (left, right) = Filters.splitBook(rotated)
                    newPages += savePage(left, p.filter)
                    newPages += savePage(right, p.filter)
                    left.recycle()
                    right.recycle()
                    rotated.recycle()
                }
            }
            else -> {
                list.forEachIndexed { i, p ->
                    progress(i, total)
                    val oName = "o_${Library.newId()}.jpg"
                    val target = Library.pageFile(docId, oName)
                    if (!p.file.renameTo(target)) p.file.copyTo(target, overwrite = true)
                    val page = Page(Library.newId(), oName, "", p.quad, p.filter, p.rotation)
                    newPages += PageRenderer.reprocess(docId, page, quality)
                }
            }
        }
        progress(total, total)
        val now = System.currentTimeMillis()
        val doc = existing?.copy(pages = existing.pages + newPages)
            ?: ScanDocument(docId, Library.defaultName(docPrefix(mode)), now, now, mode, newPages)
        Library.save(doc)
        pages.clear()
        previews.clear()
        Library.sessionDir.listFiles()?.forEach { it.delete() }
        return Library.get(docId) ?: doc
    }
}
