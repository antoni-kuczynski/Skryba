package com.kuczynski.skryba.ui.doc

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.kuczynski.skryba.R
import com.kuczynski.skryba.convert.DocxBlock
import com.kuczynski.skryba.convert.OoxmlWriter
import com.kuczynski.skryba.convert.TextLayout
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.PageSize
import com.kuczynski.skryba.data.ScanDocument
import com.kuczynski.skryba.data.Settings
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.imaging.ImageIO
import com.kuczynski.skryba.pdf.PdfOptions
import com.kuczynski.skryba.pdf.PdfTools
import com.kuczynski.skryba.scan.DocActions
import com.kuczynski.skryba.scan.ScanSession
import com.kuczynski.skryba.share.ShareServer
import com.kuczynski.skryba.share.Sharing
import com.kuczynski.skryba.ui.Routes
import com.kuczynski.skryba.ui.common.BackTopBar
import com.kuczynski.skryba.ui.common.BusyDialog
import com.kuczynski.skryba.ui.common.ConfirmDialog
import com.kuczynski.skryba.ui.common.ResultSheet
import com.kuczynski.skryba.ui.common.TextInputDialog
import com.kuczynski.skryba.ui.common.rememberTaskRunner
import com.kuczynski.skryba.ui.scan.ToolButton
import com.kuczynski.skryba.ui.shareFilesQr
import com.kuczynski.skryba.ui.shareQr
import com.kuczynski.skryba.ui.startScan
import com.kuczynski.skryba.ui.viewFile
import java.io.ByteArrayOutputStream
import java.io.File

@Composable
fun DocumentScreen(nav: NavController, docId: String) {
    val docs by Library.docs.collectAsState()
    val doc = docs.firstOrNull { it.id == docId }
    val context = LocalContext.current
    val runner = rememberTaskRunner()
    var menu by remember { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var watermarkDialog by remember { mutableStateOf(false) }
    var reorder by remember { mutableStateOf(false) }
    var signPick by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<List<File>?>(null) }

    LaunchedEffect(doc == null) { if (doc == null) nav.popBackStack() }
    if (doc == null) return

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        ScanSession.start(doc.mode, doc.id)
        runner.run(str(R.string.loading_photos), onDone = { _: Unit -> nav.navigate(Routes.REVIEW) }) { p ->
            uris.forEachIndexed { i, u -> p(i, uris.size); ScanSession.addFromUri(context, u) }
        }
    }

    fun saveToGallery() {
        runner.run(str(R.string.saving), onDone = { n: Int ->
            com.kuczynski.skryba.ui.common.toast(context, str(R.string.saved_to_gallery, n))
        }) { DocActions.saveToGallery(context, doc) }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveToGallery()
    }

    fun movePage(from: Int, to: Int) {
        if (to !in doc.pages.indices) return
        val list = doc.pages.toMutableList()
        val p = list.removeAt(from)
        list.add(to, p)
        Library.save(doc.copy(pages = list))
    }

    fun exportWord() {
        runner.run(str(R.string.creating_word), onDone = { f: File -> result = listOf(f) }) { p ->
            // OCR the pages that have not been recognized yet first.
            val d = DocActions.ocr(context, doc, doc.pages.indices.toList(), force = false) { a, b -> p(a, b + 1) }
            val blocks = ArrayList<DocxBlock>()
            d.pages.forEachIndexed { i, page ->
                if (i > 0) blocks += DocxBlock.PageBreak
                val paras = TextLayout.paragraphsFromText(page.ocrText)
                if (paras.isNotEmpty()) {
                    paras.forEach { blocks += DocxBlock.Paragraph(it) }
                } else {
                    // A page without text (e.g. a photo) goes into the document as an image.
                    val bmp = ImageIO.decode(Library.pageFile(d.id, page.processed), 1600)
                    val jpeg = ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                    blocks += DocxBlock.Image(jpeg, bmp.width, bmp.height)
                    bmp.recycle()
                }
            }
            val tmp = Library.tempFile("doc.docx")
            OoxmlWriter.writeDocx(tmp, blocks)
            Library.addFile(tmp, "${d.name}.docx", move = true)
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(doc.name, { nav.popBackStack() }, subtitle = str(R.string.pages_short, doc.pages.size)) {
                if (reorder) {
                    IconButton(onClick = { reorder = false }) { Icon(Icons.Default.Check, str(R.string.done)) }
                } else {
                    Box {
                        IconButton(onClick = { shareMenu = true }) { Icon(Icons.Default.Share, str(R.string.share)) }
                        DropdownMenu(shareMenu, { shareMenu = false }) {
                            DropdownMenuItem(text = { Text(str(R.string.as_pdf)) }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) }, onClick = {
                                shareMenu = false
                                runner.run(str(R.string.creating_pdf), onDone = { f: File -> Sharing.share(context, listOf(f), doc.name) }) { p -> DocActions.tempPdf(context, doc, p) }
                            })
                            DropdownMenuItem(text = { Text(str(R.string.as_jpg)) }, leadingIcon = { Icon(Icons.Default.Image, null) }, onClick = {
                                shareMenu = false
                                Sharing.share(context, DocActions.tempJpgs(doc), doc.name)
                            })
                            DropdownMenuItem(text = { Text(str(R.string.as_text)) }, leadingIcon = { Icon(Icons.Default.TextFields, null) }, onClick = {
                                shareMenu = false
                                runner.run(str(R.string.recognizing_text), onDone = { d: ScanDocument -> Sharing.shareText(context, d.fullText) }) { p ->
                                    DocActions.ocr(context, doc, doc.pages.indices.toList(), false, p)
                                }
                            })
                            DropdownMenuItem(text = { Text(str(R.string.link_qr_code)) }, leadingIcon = { Icon(Icons.Default.QrCode2, null) }, onClick = {
                                shareMenu = false
                                nav.shareQr(ShareServer.Share(doc.name, docIds = listOf(doc.id)))
                            })
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, str(R.string.more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(str(R.string.rename)) }, leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text(str(R.string.reorder_pages)) }, leadingIcon = { Icon(Icons.Default.SwapHoriz, null) }, onClick = { menu = false; reorder = true })
                            DropdownMenuItem(text = { Text(str(R.string.save_as_word)) }, leadingIcon = { Icon(Icons.Default.Description, null) }, onClick = { menu = false; exportWord() })
                            DropdownMenuItem(text = { Text(str(R.string.save_images_zip)) }, leadingIcon = { Icon(Icons.Default.Image, null) }, onClick = {
                                menu = false
                                runner.run(str(R.string.exporting_images), onDone = { f: File -> result = listOf(f) }) { DocActions.exportJpgZip(doc) }
                            })
                            DropdownMenuItem(text = { Text(str(R.string.save_to_gallery)) }, leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) }, onClick = {
                                menu = false
                                if (android.os.Build.VERSION.SDK_INT < 29 &&
                                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) else saveToGallery()
                            })
                            DropdownMenuItem(text = { Text(str(R.string.watermark_pdf)) }, leadingIcon = { Icon(Icons.Default.WaterDrop, null) }, onClick = { menu = false; watermarkDialog = true })
                            DropdownMenuItem(text = { Text(str(R.string.duplicate)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
                                menu = false
                                Library.duplicate(doc.id)?.let { nav.navigate(Routes.doc(it.id)) { popUpTo(Routes.HOME) } }
                            })
                            DropdownMenuItem(text = { Text(str(R.string.delete_document)) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; deleting = true })
                        }
                    }
                }
            }
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolButton(Icons.Default.AddAPhoto, str(R.string.add)) { nav.startScan(doc.mode, doc.id) }
                ToolButton(Icons.Default.PhotoLibrary, str(R.string.from_gallery)) {
                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                ToolButton(Icons.Default.PictureAsPdf, str(R.string.pdf)) { exportDialog = true }
                ToolButton(Icons.Default.TextFields, str(R.string.text)) { nav.navigate(Routes.ocr(doc.id)) }
                ToolButton(Icons.Default.Draw, str(R.string.sign)) {
                    if (doc.pages.size == 1) nav.navigate(Routes.sign(doc.id, doc.pages[0].id)) else signPick = true
                }
            }
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            itemsIndexed(doc.pages, key = { _, p -> p.id }) { i, p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(10.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .clickable(enabled = !reorder) { nav.navigate(Routes.page(doc.id, i)) },
                    ) {
                        AsyncImage(
                            model = Library.pageFile(doc.id, p.processed),
                            contentDescription = str(R.string.page_n, i + 1),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                        ) {
                            Text("${i + 1}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                    if (reorder) {
                        Row {
                            IconButton(onClick = { movePage(i, i - 1) }, enabled = i > 0) { Icon(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.earlier)) }
                            IconButton(onClick = {
                                Library.save(doc.copy(pages = doc.pages.filterIndexed { k, _ -> k != i }))
                            }, enabled = doc.pages.size > 1) { Icon(Icons.Default.Delete, str(R.string.delete)) }
                            IconButton(onClick = { movePage(i, i + 1) }, enabled = i < doc.pages.size - 1) { Icon(Icons.AutoMirrored.Filled.ArrowForward, str(R.string.later)) }
                        }
                    }
                }
            }
        }
    }

    BusyDialog(runner)
    result?.let { files -> ResultSheet(files, onOpen = { nav.viewFile(it) }, onQr = { nav.shareFilesQr(it) }, onDismiss = { result = null }) }
    if (renaming) {
        TextInputDialog(str(R.string.rename), doc.name, str(R.string.name), str(R.string.save), onDismiss = { renaming = false }) {
            Library.rename(doc.id, it)
            renaming = false
        }
    }
    if (deleting) {
        ConfirmDialog(str(R.string.delete_doc_q), str(R.string.delete_doc_text2, doc.name), str(R.string.delete), { deleting = false }) {
            deleting = false
            Library.delete(doc.id)
        }
    }
    if (signPick) {
        AlertDialog(
            onDismissRequest = { signPick = false },
            title = { Text(str(R.string.which_page_sign)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    doc.pages.forEachIndexed { i, p ->
                        TextButton(onClick = { signPick = false; nav.navigate(Routes.sign(doc.id, p.id)) }) { Text(str(R.string.page_n, i + 1)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { signPick = false }) { Text(str(R.string.cancel)) } },
        )
    }
    if (exportDialog) {
        PdfExportDialog(doc.name, onDismiss = { exportDialog = false }) { name, opts ->
            exportDialog = false
            runner.run(str(R.string.creating_pdf), onDone = { f: File -> result = listOf(f) }) { p -> DocActions.exportPdf(context, doc, name, opts, p) }
        }
    }
    if (watermarkDialog) {
        WatermarkDialog(onDismiss = { watermarkDialog = false }) { text, opacity ->
            watermarkDialog = false
            runner.run(str(R.string.adding_watermark), onDone = { f: File -> result = listOf(f) }) { p ->
                val pdf = DocActions.tempPdf(context, doc, p)
                val tmp = Library.tempFile("wm.pdf")
                PdfTools.watermark(context, pdf, text, opacity, 0x808080, tmp)
                Library.addFile(tmp, str(R.string.watermark_suffix, doc.name), move = true)
            }
        }
    }
}

@Composable
fun PdfExportDialog(name: String, onDismiss: () -> Unit, onConfirm: (String, PdfOptions) -> Unit) {
    val s = Settings.value
    var fileName by remember { mutableStateOf(name) }
    var size by remember { mutableStateOf(s.pageSize) }
    var quality by remember { mutableFloatStateOf(s.jpegQuality.toFloat()) }
    var searchable by remember { mutableStateOf(s.searchablePdf) }
    var protect by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.save_as_pdf)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(fileName, { fileName = it }, label = { Text(str(R.string.file_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(str(R.string.page_size), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PageSize.entries.forEach { ps ->
                        FilterChip(size == ps, { size = ps }, label = { Text(if (ps == PageSize.FIT) str(R.string.image) else ps.label) })
                    }
                }
                Text(str(R.string.image_quality, quality.toInt()), style = MaterialTheme.typography.labelLarge)
                Slider(quality, { quality = it }, valueRange = 40f..100f)
                Row(Modifier.clickable { searchable = !searchable }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(searchable, { searchable = it })
                    Column {
                        Text(str(R.string.searchable_text))
                        Text(str(R.string.searchable_text_sub), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { protect = !protect }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(protect, { protect = it })
                    Icon(Icons.Default.Lock, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(str(R.string.protect_password))
                }
                if (protect) {
                    OutlinedTextField(
                        password, { password = it }, label = { Text(str(R.string.password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(Library.sanitize(fileName), PdfOptions(size, quality.toInt(), searchable, if (protect) password else null))
                },
                enabled = fileName.isNotBlank() && (!protect || password.isNotEmpty()),
            ) { Text(str(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

@Composable
fun WatermarkDialog(onDismiss: () -> Unit, onConfirm: (String, Float) -> Unit) {
    var text by remember { mutableStateOf(str(R.string.watermark_default)) }
    var opacity by remember { mutableFloatStateOf(0.25f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.watermark)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text(str(R.string.text)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(str(R.string.opacity, (opacity * 100).toInt()))
                Slider(opacity, { opacity = it }, valueRange = 0.05f..0.8f)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text, opacity) }, enabled = text.isNotBlank()) { Text(str(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}
