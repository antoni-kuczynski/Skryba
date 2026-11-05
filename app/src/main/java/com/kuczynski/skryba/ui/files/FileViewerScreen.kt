package com.kuczynski.skryba.ui.files

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.kuczynski.skryba.R
import com.kuczynski.skryba.convert.Converters
import com.kuczynski.skryba.convert.OfficeReader
import com.kuczynski.skryba.convert.OfficeRenderer
import com.kuczynski.skryba.convert.SheetData
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.ScanDocument
import com.kuczynski.skryba.data.ScanMode
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.imaging.ImageIO
import com.kuczynski.skryba.pdf.PdfRender
import com.kuczynski.skryba.pdf.PdfTools
import com.kuczynski.skryba.scan.DocActions
import com.kuczynski.skryba.scan.ScanSession
import com.kuczynski.skryba.share.Sharing
import com.kuczynski.skryba.ui.Handoff
import com.kuczynski.skryba.ui.Routes
import com.kuczynski.skryba.ui.common.BackTopBar
import com.kuczynski.skryba.ui.common.BusyDialog
import com.kuczynski.skryba.ui.common.ResultSheet
import com.kuczynski.skryba.ui.common.TextInputDialog
import com.kuczynski.skryba.ui.common.ZoomableImage
import com.kuczynski.skryba.ui.common.errorMessage
import com.kuczynski.skryba.ui.common.formatSize
import com.kuczynski.skryba.ui.common.rememberTaskRunner
import com.kuczynski.skryba.ui.common.toast
import com.kuczynski.skryba.ui.shareFilesQr
import com.kuczynski.skryba.ui.tools.PdfThumb
import com.kuczynski.skryba.ui.viewFile
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
private val OFFICE_EXT = setOf("docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "ppsx", "potx", "csv")
private val TEXT_EXT = setOf("txt", "md", "html", "json", "xml", "log")

@Composable
fun FileViewerScreen(nav: NavController) {
    val file = remember { Handoff.viewFile }
    val context = LocalContext.current
    val runner = rememberTaskRunner()
    var menu by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<List<File>?>(null) }
    if (file == null || !file.exists()) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val ext = file.extension.lowercase()
    var password by remember { mutableStateOf<String?>(null) }
    var needsPassword by remember { mutableStateOf(ext == "pdf" && runCatching { PdfTools.needsPassword(file) }.getOrDefault(false)) }

    Scaffold(
        topBar = {
            BackTopBar(file.name, { nav.popBackStack() }, subtitle = formatSize(file.length())) {
                IconButton(onClick = { Sharing.share(context, listOf(file)) }) { Icon(Icons.Default.Share, str(R.string.share)) }
                IconButton(onClick = { nav.shareFilesQr(listOf(file)) }) { Icon(Icons.Default.QrCode2, str(R.string.link_qr)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, str(R.string.more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(str(R.string.open_in_other)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }, onClick = {
                            menu = false
                            Sharing.openExternal(context, file)
                        })
                        if (ext == "pdf") {
                            DropdownMenuItem(text = { Text(str(R.string.edit_as_scan)) }, leadingIcon = { Icon(Icons.Default.DocumentScanner, null) }, onClick = {
                                menu = false
                                runner.run(str(R.string.importing_pages), onDone = { d: ScanDocument -> nav.navigate(Routes.doc(d.id)) }) { p ->
                                    DocActions.importPdf(file, password, file.nameWithoutExtension, p)
                                }
                            })
                        }
                        if (ext in IMAGE_EXT) {
                            DropdownMenuItem(text = { Text(str(R.string.scan_this_photo)) }, leadingIcon = { Icon(Icons.Default.DocumentScanner, null) }, onClick = {
                                menu = false
                                ScanSession.start(ScanMode.DOCUMENT)
                                runner.run(str(R.string.loading), onDone = { _: Unit -> nav.navigate(Routes.REVIEW) }) {
                                    val bmp = ImageIO.decode(file, 4000)
                                    ScanSession.addBitmap(bmp, detect = true)
                                    bmp.recycle()
                                }
                            })
                        }
                        if (ext in OFFICE_EXT || ext in setOf("txt", "md")) {
                            DropdownMenuItem(text = { Text(str(R.string.convert_to_pdf)) }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) }, onClick = {
                                menu = false
                                runner.run(str(R.string.converting_to_pdf), onDone = { f: File -> result = listOf(f) }) { p -> Converters.officeToPdf(file, p) }
                            })
                        }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                ext == "pdf" && needsPassword && password == null -> {
                    TextInputDialog(str(R.string.password_protected), "", str(R.string.password), str(R.string.open), password = true, onDismiss = { nav.popBackStack() }) { pw ->
                        if (runCatching { PdfTools.pageCount(file, pw) }.isSuccess) {
                            password = pw
                            needsPassword = false
                        } else {
                            toast(context, str(R.string.wrong_password))
                        }
                    }
                }
                ext == "pdf" -> PdfPages(file, password)
                ext in IMAGE_EXT -> {
                    val bmp by produceState<Bitmap?>(null, file) {
                        value = withContext(Dispatchers.IO) { runCatching { ImageIO.decode(file, 3000) }.getOrNull() }
                    }
                    bmp?.let { ZoomableImage(it.asImageBitmap(), Modifier.fillMaxSize().background(Color(0xFF1A1D1C))) }
                        ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                ext in OFFICE_EXT -> OfficePreview(file)
                ext in TEXT_EXT -> TextPreview(file)
                ext == "zip" -> ZipPreview(file)
                else -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(str(R.string.no_preview), style = MaterialTheme.typography.titleMedium)
                    Text(str(R.string.no_preview_sub), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    BusyDialog(runner)
    result?.let { files -> ResultSheet(files, onOpen = { nav.popBackStack(); nav.viewFile(it) }, onQr = { nav.shareFilesQr(it) }, onDismiss = { result = null }) }
}

@Composable
private fun PdfPages(file: File, password: String?) {
    val aspects by produceState<List<Float>?>(null, file, password) {
        value = withContext(Dispatchers.IO) { runCatching { PdfRender.pageAspects(file, password) }.getOrNull() }
    }
    var zoomPage by remember { mutableIntStateOf(-1) }
    val list = aspects
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(list.size) { i ->
            Surface(shadowElevation = 2.dp, color = Color.White, modifier = Modifier.fillMaxWidth().aspectRatio(list[i]).clickable { zoomPage = i }) {
                PdfThumb(file, i, password, Modifier.fillMaxSize(), maxSide = 1400)
            }
        }
    }
    if (zoomPage >= 0) {
        Dialog(onDismissRequest = { zoomPage = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val bmp by produceState<Bitmap?>(null, zoomPage) {
                value = withContext(Dispatchers.IO) { runCatching { PdfRender.render(file, zoomPage, 2600, password = password) }.getOrNull() }
            }
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { zoomPage = -1 }, contentAlignment = Alignment.Center) {
                bmp?.let { ZoomableImage(it.asImageBitmap(), Modifier.fillMaxSize()) } ?: CircularProgressIndicator()
            }
        }
    }
}

/** Podgląd Word/Excel/PowerPoint: renderowanie do tymczasowego PDF. */
@Composable
private fun OfficePreview(file: File) {
    val state by produceState<Result<File>?>(null, file) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val out = File(Library.tempDir, "preview_${file.nameWithoutExtension.hashCode()}_${file.lastModified()}.pdf")
                if (!out.exists()) {
                    when (file.extension.lowercase()) {
                        "docx", "docm", "dotx" -> OfficeRenderer.renderFlow(OfficeReader.readDocx(file), out)
                        "xlsx", "xlsm", "xltx" -> OfficeRenderer.renderSheets(OfficeReader.readXlsx(file), out)
                        "pptx", "ppsx", "potx" -> OfficeRenderer.renderPresentation(OfficeReader.readPptx(file), out)
                        "csv" -> OfficeRenderer.renderSheets(listOf(SheetData(file.nameWithoutExtension, Converters.parseCsv(file.readText()), emptyMap())), out)
                    }
                }
                out
            }
        }
    }
    val s = state
    when {
        s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        s.isSuccess -> PdfPages(s.getOrThrow(), null)
        else -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(str(R.string.preview_failed), style = MaterialTheme.typography.titleMedium)
            Text(errorMessage(s.exceptionOrNull()!!), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TextPreview(file: File) {
    val text by produceState("", file) {
        value = withContext(Dispatchers.IO) { runCatching { file.readText().take(500_000) }.getOrDefault("") }
    }
    SelectionContainer {
        Text(
            text,
            fontFamily = if (file.extension == "txt" || file.extension == "md") FontFamily.Default else FontFamily.Monospace,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}

@Composable
private fun ZipPreview(file: File) {
    val entries by produceState<List<Pair<String, Long>>>(emptyList(), file) {
        value = withContext(Dispatchers.IO) {
            runCatching { ZipFile(file).use { z -> z.entries().toList().filter { !it.isDirectory }.map { it.name to it.size } } }.getOrDefault(emptyList())
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { Text(str(R.string.archive_contents, entries.size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        items(entries) { (n, size) ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(n)
                    Text(formatSize(size), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
