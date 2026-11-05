package com.kuczynski.skryba.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CoPresent
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.ScanMode
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.pdf.PdfTools
import com.kuczynski.skryba.scan.ScanSession
import com.kuczynski.skryba.share.ShareServer
import java.io.File

enum class ToolGroup(private val titleRes: Int) {
    SCAN(R.string.group_scan),
    CONVERT(R.string.group_convert),
    EDIT(R.string.group_edit),
    AI(R.string.group_ai),
    ;

    val title: String get() = str(titleRes)
}

/** Rodzaj plików wejściowych narzędzia. */
enum class InputKind(val mimes: Array<String>, val extensions: Set<String>, private val name0: String) {
    PDF(arrayOf("application/pdf"), setOf("pdf"), "PDF"),
    IMAGES(arrayOf("image/*"), setOf("jpg", "jpeg", "png", "webp"), ""),
    WORD(
        arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword", "text/plain"),
        setOf("docx", "docm", "dotx", "doc", "txt", "md"), "Word",
    ),
    EXCEL(
        arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/csv", "text/comma-separated-values"),
        setOf("xlsx", "xlsm", "xltx", "xls", "csv"), "Excel",
    ),
    POWERPOINT(
        arrayOf("application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-powerpoint"),
        setOf("pptx", "ppsx", "potx", "ppt"), "PowerPoint",
    ),
    NONE(emptyArray(), emptySet(), ""),
    ;

    val label: String get() = if (this == IMAGES) str(R.string.input_images) else name0
}

enum class Tool(
    private val titleRes: Int,
    private val subtitleRes: Int,
    val group: ToolGroup,
    val icon: ImageVector,
    val input: InputKind = InputKind.NONE,
    val multi: Boolean = false,
    val scanMode: ScanMode? = null,
) {
    SCAN_DOC(R.string.tool_scan_doc, R.string.tool_scan_doc_sub, ToolGroup.SCAN, Icons.Default.DocumentScanner, scanMode = ScanMode.DOCUMENT),
    SCAN_ID(R.string.tool_scan_id, R.string.tool_scan_id_sub, ToolGroup.SCAN, Icons.Default.Badge, scanMode = ScanMode.ID_CARD),
    SCAN_BOOK(R.string.tool_scan_book, R.string.tool_scan_book_sub, ToolGroup.SCAN, Icons.AutoMirrored.Filled.MenuBook, scanMode = ScanMode.BOOK),
    SCAN_TEST(R.string.tool_scan_test, R.string.tool_scan_test_sub, ToolGroup.SCAN, Icons.Default.School, scanMode = ScanMode.TEST_PAPER),
    SCAN_WHITEBOARD(R.string.tool_scan_whiteboard, R.string.tool_scan_whiteboard_sub, ToolGroup.SCAN, Icons.Default.Dashboard, scanMode = ScanMode.WHITEBOARD),
    SCAN_SLIDES(R.string.tool_scan_slides, R.string.tool_scan_slides_sub, ToolGroup.SCAN, Icons.Default.CoPresent, scanMode = ScanMode.SLIDES),
    IMAGE_TO_TEXT(R.string.tool_image_to_text, R.string.tool_image_to_text_sub, ToolGroup.SCAN, Icons.Default.TextFields, scanMode = ScanMode.TEXT),

    PDF_TO_WORD(R.string.tool_pdf_to_word, R.string.tool_pdf_to_word_sub, ToolGroup.CONVERT, Icons.Default.Description, InputKind.PDF),
    WORD_TO_PDF(R.string.tool_word_to_pdf, R.string.tool_word_to_pdf_sub, ToolGroup.CONVERT, Icons.Default.PictureAsPdf, InputKind.WORD),
    PDF_TO_EXCEL(R.string.tool_pdf_to_excel, R.string.tool_pdf_to_excel_sub, ToolGroup.CONVERT, Icons.Default.TableChart, InputKind.PDF),
    EXCEL_TO_PDF(R.string.tool_excel_to_pdf, R.string.tool_excel_to_pdf_sub, ToolGroup.CONVERT, Icons.Default.PictureAsPdf, InputKind.EXCEL),
    PDF_TO_PPT(R.string.tool_pdf_to_ppt, R.string.tool_pdf_to_ppt_sub, ToolGroup.CONVERT, Icons.Default.Slideshow, InputKind.PDF),
    PPT_TO_PDF(R.string.tool_ppt_to_pdf, R.string.tool_ppt_to_pdf_sub, ToolGroup.CONVERT, Icons.Default.PictureAsPdf, InputKind.POWERPOINT),
    PDF_TO_JPG(R.string.tool_pdf_to_jpg, R.string.tool_pdf_to_jpg_sub, ToolGroup.CONVERT, Icons.Default.Image, InputKind.PDF),
    JPG_TO_PDF(R.string.tool_jpg_to_pdf, R.string.tool_jpg_to_pdf_sub, ToolGroup.CONVERT, Icons.Default.PictureAsPdf, InputKind.IMAGES, multi = true),

    MERGE(R.string.tool_merge, R.string.tool_merge_sub, ToolGroup.EDIT, Icons.AutoMirrored.Filled.MergeType, InputKind.PDF, multi = true),
    SPLIT(R.string.tool_split, R.string.tool_split_sub, ToolGroup.EDIT, Icons.AutoMirrored.Filled.CallSplit, InputKind.PDF),
    ORGANIZE(R.string.tool_organize, R.string.tool_organize_sub, ToolGroup.EDIT, Icons.Default.Dashboard, InputKind.PDF),
    ROTATE(R.string.tool_rotate, R.string.tool_rotate_sub, ToolGroup.EDIT, Icons.AutoMirrored.Filled.RotateRight, InputKind.PDF),
    DELETE_PAGES(R.string.tool_delete_pages, R.string.tool_delete_pages_sub, ToolGroup.EDIT, Icons.Default.Delete, InputKind.PDF),
    WATERMARK(R.string.tool_watermark, R.string.tool_watermark_sub, ToolGroup.EDIT, Icons.Default.WaterDrop, InputKind.PDF),
    PROTECT(R.string.tool_protect, R.string.tool_protect_sub, ToolGroup.EDIT, Icons.Default.Lock, InputKind.PDF),
    UNLOCK(R.string.tool_unlock, R.string.tool_unlock_sub, ToolGroup.EDIT, Icons.Default.LockOpen, InputKind.PDF),
    SIGN(R.string.tool_sign, R.string.tool_sign_sub, ToolGroup.EDIT, Icons.Default.Draw, InputKind.PDF),
    COMPRESS(R.string.tool_compress, R.string.tool_compress_sub, ToolGroup.EDIT, Icons.Default.Compress, InputKind.PDF),

    E_SIGNATURE(R.string.tool_e_signature, R.string.tool_e_signature_sub, ToolGroup.AI, Icons.Default.Draw, InputKind.PDF),
    RESTORE(R.string.tool_restore, R.string.tool_restore_sub, ToolGroup.AI, Icons.Default.AutoFixHigh),
    TAMPER(R.string.tool_tamper, R.string.tool_tamper_sub, ToolGroup.AI, Icons.Default.ImageSearch),
    PHOTOSHOP(R.string.tool_photoshop, R.string.tool_photoshop_sub, ToolGroup.AI, Icons.Default.ImageSearch),
    COUNT(R.string.tool_count, R.string.tool_count_sub, ToolGroup.AI, Icons.Default.Pin),
    MEGA_SCAN(R.string.tool_mega_scan, R.string.tool_mega_scan_sub, ToolGroup.AI, Icons.Default.Panorama),
    ;

    val title: String get() = str(titleRes)
    val subtitle: String get() = str(subtitleRes)
}

/** Dane przekazywane między ekranami (zbyt duże albo nieserializowalne do trasy nawigacji). */
object Handoff {
    data class PdfInput(val file: File, val password: String?, val label: String)

    var pdf: PdfInput? = null
    var viewFile: File? = null
    var share: ShareServer.Share? = null
}

object Routes {
    const val HOME = "home"
    const val CAMERA = "camera"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
    const val QR = "qr"
    const val VIEW = "view"
    const val PDF_SIGN = "pdfsign"

    fun crop(pageId: String) = "crop/$pageId"
    fun doc(id: String) = "doc/$id"
    fun page(docId: String, index: Int) = "page/$docId/$index"
    fun pageCrop(docId: String, pageId: String) = "pagecrop/$docId/$pageId"
    fun ocr(docId: String, page: Int = -1) = "ocr/$docId?page=$page"
    fun sign(docId: String, pageId: String) = "sign/$docId/$pageId"
    fun tool(t: Tool) = "tool/${t.name}"
    fun organize(t: Tool) = "organize/${t.name}"
    fun ai(t: Tool) = "ai/${t.name}"
}

fun NavController.startScan(mode: ScanMode, targetDocId: String? = null) {
    ScanSession.start(mode, targetDocId)
    navigate(Routes.CAMERA)
}

fun NavController.openTool(t: Tool) {
    when {
        t.scanMode != null -> startScan(t.scanMode)
        t.group == ToolGroup.AI && t.input == InputKind.NONE -> navigate(Routes.ai(t))
        else -> navigate(Routes.tool(t))
    }
}

fun NavController.shareQr(share: ShareServer.Share) {
    Handoff.share = share
    navigate(Routes.QR)
}

fun NavController.shareFilesQr(files: List<File>) =
    shareQr(ShareServer.Share(if (files.size == 1) files[0].name else str(R.string.shared_files), files = files))

fun NavController.viewFile(f: File) {
    Handoff.viewFile = f
    navigate(Routes.VIEW)
}

/** Sprawdza czy PDF wymaga hasła (bez rzucania wyjątków przy uszkodzonych plikach). */
fun pdfNeedsPassword(f: File): Boolean = runCatching { PdfTools.needsPassword(f) }.getOrDefault(false)
