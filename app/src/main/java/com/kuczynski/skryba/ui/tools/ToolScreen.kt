package com.kuczynski.skryba.ui.tools

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kuczynski.skryba.R
import com.kuczynski.skryba.convert.Converters
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.ScanDocument
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.pdf.PdfTools
import com.kuczynski.skryba.scan.DocActions
import com.kuczynski.skryba.ui.Handoff
import com.kuczynski.skryba.ui.InputKind
import com.kuczynski.skryba.ui.Routes
import com.kuczynski.skryba.ui.Tool
import com.kuczynski.skryba.ui.common.BackTopBar
import com.kuczynski.skryba.ui.common.BusyDialog
import com.kuczynski.skryba.ui.common.ResultSheet
import com.kuczynski.skryba.ui.common.TextInputDialog
import com.kuczynski.skryba.ui.common.fileIcon
import com.kuczynski.skryba.ui.common.formatSize
import com.kuczynski.skryba.ui.common.rememberTaskRunner
import com.kuczynski.skryba.ui.common.toast
import com.kuczynski.skryba.ui.pdfNeedsPassword
import com.kuczynski.skryba.ui.shareFilesQr
import com.kuczynski.skryba.ui.viewFile
import java.io.File

private val NAV_TOOLS = setOf(Tool.ORGANIZE, Tool.ROTATE, Tool.DELETE_PAGES, Tool.SIGN, Tool.E_SIGNATURE)

@Composable
fun ToolScreen(nav: NavController, tool: Tool) {
    val context = LocalContext.current
    val runner = rememberTaskRunner()
    val inputs = remember { mutableStateListOf<Handoff.PdfInput>() }
    var result by remember { mutableStateOf<List<File>?>(null) }
    var libraryPicker by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf<Handoff.PdfInput?>(null) }

    // Tool options
    var outName by remember { mutableStateOf("") }
    var splitEach by remember { mutableStateOf(true) }
    var ranges by remember { mutableStateOf("") }
    var wmText by remember { mutableStateOf(str(R.string.wm_confidential)) }
    var wmOpacity by remember { mutableFloatStateOf(0.25f) }
    var wmColor by remember { mutableIntStateOf(0x808080) }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var level by remember { mutableIntStateOf(1) }
    var dpi by remember { mutableIntStateOf(150) }

    fun accept(input: Handoff.PdfInput) {
        if (tool in NAV_TOOLS) {
            Handoff.pdf = input
            if (tool == Tool.SIGN || tool == Tool.E_SIGNATURE) nav.navigate(Routes.PDF_SIGN) { popUpTo(Routes.tool(tool)) { inclusive = true } }
            else nav.navigate(Routes.organize(tool)) { popUpTo(Routes.tool(tool)) { inclusive = true } }
            return
        }
        if (!tool.multi) inputs.clear()
        inputs += input
        if (outName.isBlank()) outName = input.label.substringBeforeLast('.')
    }

    fun addFile(f: File, label: String) {
        val input = Handoff.PdfInput(f, null, label)
        if (tool.input == InputKind.PDF && tool != Tool.UNLOCK && pdfNeedsPassword(f)) askPassword = input else accept(input)
    }

    val pickDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importUris(context, uris, runner) { list -> list.forEach { (f, name) -> addFile(f, name) } }
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        importUris(context, uris, runner) { list -> list.forEach { (f, name) -> addFile(f, name) } }
    }

    fun pickFromDevice() {
        if (tool.input == InputKind.IMAGES) pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        else pickDocs.launch(tool.input.mimes)
    }

    fun output(tmp: File, name: String): File = Library.addFile(tmp, name, move = true)

    fun run() {
        val ins = inputs.toList()
        val base = Library.sanitize(outName.ifBlank { ins.firstOrNull()?.label?.substringBeforeLast('.') ?: str(R.string.file_default) })
        runner.run(tool.title, onDone = { files: List<File> -> result = files }) { p ->
            val first = ins.first()
            when (tool) {
                Tool.PDF_TO_WORD -> listOf(Converters.pdfToWord(context, first.file, first.password, p))
                Tool.PDF_TO_EXCEL -> listOf(Converters.pdfToExcel(context, first.file, first.password, p))
                Tool.PDF_TO_PPT -> listOf(Converters.pdfToPowerPoint(first.file, first.password, p))
                Tool.PDF_TO_JPG -> listOf(Converters.pdfToJpg(first.file, first.password, dpi, p))
                Tool.WORD_TO_PDF, Tool.EXCEL_TO_PDF, Tool.PPT_TO_PDF -> listOf(Converters.officeToPdf(first.file, p))
                Tool.JPG_TO_PDF -> listOf(Converters.imagesToPdf(context, ins.map { it.file }, base, p))
                Tool.MERGE -> {
                    val tmp = Library.tempFile("merge.pdf")
                    PdfTools.merge(ins.map { it.file }, tmp, ins.filter { it.password != null }.associate { it.file to it.password!! })
                    listOf(output(tmp, "$base.pdf"))
                }
                Tool.SPLIT -> {
                    val count = PdfTools.pageCount(first.file, first.password)
                    val rs = if (splitEach) (0 until count).map { it..it } else parseRanges(ranges, count)
                    require(rs.isNotEmpty()) { str(R.string.err_ranges) }
                    val tmps = rs.indices.map { Library.tempFile("split_$it.pdf") }
                    PdfTools.split(first.file, rs, tmps, first.password)
                    rs.mapIndexed { i, r ->
                        val label = if (r.first == r.last) "${r.first + 1}" else "${r.first + 1}-${r.last + 1}"
                        output(tmps[i], str(R.string.split_suffix, base, label))
                    }
                }
                Tool.WATERMARK -> {
                    val tmp = Library.tempFile("wm.pdf")
                    PdfTools.watermark(context, first.file, wmText, wmOpacity, wmColor, tmp, first.password)
                    listOf(output(tmp, str(R.string.watermark_suffix, base)))
                }
                Tool.PROTECT -> {
                    val tmp = Library.tempFile("protect.pdf")
                    PdfTools.protect(first.file, password, tmp, first.password)
                    listOf(output(tmp, str(R.string.protected_suffix, base)))
                }
                Tool.UNLOCK -> {
                    val tmp = Library.tempFile("unlock.pdf")
                    PdfTools.unlock(first.file, password, tmp)
                    listOf(output(tmp, str(R.string.unlocked_suffix, base)))
                }
                Tool.COMPRESS -> {
                    val (q, dim) = when (level) { 0 -> 0.8f to 2400; 1 -> 0.6f to 1600; else -> 0.4f to 1100 }
                    val tmp = Library.tempFile("compress.pdf")
                    PdfTools.compress(first.file, q, dim, tmp, first.password)
                    val before = first.file.length()
                    val after = tmp.length()
                    if (after >= before) {
                        toast(context, str(R.string.already_compressed, formatSize(before)))
                    } else {
                        toast(context, str(R.string.compressed_from_to, formatSize(before), formatSize(after)))
                    }
                    listOf(output(tmp, str(R.string.compressed_suffix, base)))
                }
                else -> emptyList()
            }
        }
    }

    val canRun = inputs.isNotEmpty() && when (tool) {
        Tool.MERGE -> inputs.size >= 2
        Tool.PROTECT -> password.length >= 4 && password == password2
        Tool.UNLOCK -> password.isNotEmpty()
        Tool.WATERMARK -> wmText.isNotBlank()
        Tool.SPLIT -> splitEach || ranges.isNotBlank()
        else -> true
    }

    Scaffold(
        topBar = { BackTopBar(tool.title, { nav.popBackStack() }) },
        bottomBar = {
            if (tool !in NAV_TOOLS) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Button(onClick = { run() }, enabled = canRun && !runner.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text("  ${actionLabel(tool)}")
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(tool.icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(tool.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(tool.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            Text(
                if (tool.multi) str(R.string.files_n, inputs.size) else str(R.string.file),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            inputs.forEachIndexed { i, input ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(fileIcon(input.file), null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(input.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatSize(input.file.length()), style = MaterialTheme.typography.bodySmall)
                            if (input.password != null) Icon(Icons.Default.Lock, null, Modifier.size(14.dp).padding(start = 4.dp))
                        }
                    }
                    if (tool.multi) {
                        IconButton(onClick = { if (i > 0) inputs.add(i - 1, inputs.removeAt(i)) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, str(R.string.move_up)) }
                        IconButton(onClick = { if (i < inputs.size - 1) inputs.add(i + 1, inputs.removeAt(i)) }, enabled = i < inputs.size - 1) {
                            Icon(Icons.Default.ArrowDownward, str(R.string.move_down))
                        }
                    }
                    IconButton(onClick = { inputs.removeAt(i) }) { Icon(Icons.Default.Close, str(R.string.remove)) }
                }
            }
            if (tool.multi || inputs.isEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { pickFromDevice() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PhoneAndroid, null)
                        Text(str(R.string.from_phone))
                    }
                    OutlinedButton(onClick = { libraryPicker = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FolderOpen, null)
                        Text(str(R.string.from_skryba))
                    }
                }
            } else {
                TextButton(onClick = { pickFromDevice() }) { Text(str(R.string.change_file)) }
            }

            // ---- Options ----
            when (tool) {
                Tool.MERGE, Tool.JPG_TO_PDF -> if (inputs.isNotEmpty()) {
                    OutlinedTextField(outName, { outName = it }, label = { Text(str(R.string.output_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Tool.SPLIT -> {
                    Text(str(R.string.split_mode), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(splitEach, { splitEach = true }, label = { Text(str(R.string.each_page)) })
                        FilterChip(!splitEach, { splitEach = false }, label = { Text(str(R.string.ranges)) })
                    }
                    if (!splitEach) {
                        OutlinedTextField(
                            ranges, { ranges = it }, label = { Text(str(R.string.ranges_hint)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Tool.WATERMARK -> {
                    OutlinedTextField(wmText, { wmText = it }, label = { Text(str(R.string.wm_text)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(str(R.string.opacity, (wmOpacity * 100).toInt()))
                    Slider(wmOpacity, { wmOpacity = it }, valueRange = 0.05f..0.8f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0x808080 to str(R.string.color_gray), 0xC62828 to str(R.string.color_red), 0x1565C0 to str(R.string.color_blue)).forEach { (c, n) ->
                            FilterChip(wmColor == c, { wmColor = c }, label = { Text(n) })
                        }
                    }
                }
                Tool.PROTECT -> {
                    PasswordField(password, { password = it }, str(R.string.password_min))
                    PasswordField(password2, { password2 = it }, str(R.string.password_repeat))
                    if (password2.isNotEmpty() && password != password2) Text(str(R.string.passwords_differ), color = MaterialTheme.colorScheme.error)
                    Text(str(R.string.password_warning), style = MaterialTheme.typography.bodySmall)
                }
                Tool.UNLOCK -> PasswordField(password, { password = it }, str(R.string.current_password))
                Tool.COMPRESS -> {
                    Text(str(R.string.compression_level), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(str(R.string.level_light), str(R.string.level_medium), str(R.string.level_strong)).forEachIndexed { i, n -> FilterChip(level == i, { level = i }, label = { Text(n) }) }
                    }
                    Text(str(R.string.compress_info), style = MaterialTheme.typography.bodySmall)
                }
                Tool.PDF_TO_JPG -> {
                    Text(str(R.string.image_quality_title), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(100 to str(R.string.q_low), 150 to str(R.string.q_medium), 300 to str(R.string.q_high)).forEach { (d, n) -> FilterChip(dpi == d, { dpi = d }, label = { Text(n) }) }
                    }
                }
                Tool.PDF_TO_WORD, Tool.PDF_TO_EXCEL -> Text(
                    str(R.string.ocr_scans_info),
                    style = MaterialTheme.typography.bodySmall,
                )
                Tool.WORD_TO_PDF, Tool.EXCEL_TO_PDF, Tool.PPT_TO_PDF -> Text(
                    str(R.string.office_formats_info),
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> {}
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    BusyDialog(runner)
    result?.let { files -> ResultSheet(files, onOpen = { nav.viewFile(it) }, onQr = { nav.shareFilesQr(it) }, onDismiss = { result = null }) }
    if (libraryPicker) {
        LibraryPicker(tool.input, onDismiss = { libraryPicker = false }, onDoc = { d ->
            libraryPicker = false
            runner.run(str(R.string.preparing_pdf), onDone = { f: File -> accept(Handoff.PdfInput(f, null, "${d.name}.pdf")) }) { p ->
                DocActions.tempPdf(context, d, p)
            }
        }) { f ->
            libraryPicker = false
            addFile(f, f.name)
        }
    }
    askPassword?.let { input ->
        TextInputDialog(str(R.string.password_protected), "", str(R.string.password_for, input.label), str(R.string.open), password = true, onDismiss = { askPassword = null }) { pw ->
            askPassword = null
            if (runCatching { PdfTools.pageCount(input.file, pw) }.isSuccess) accept(input.copy(password = pw))
            else toast(context, str(R.string.wrong_password))
        }
    }
}

private fun actionLabel(t: Tool) = when (t) {
    Tool.MERGE -> str(R.string.act_merge)
    Tool.SPLIT -> str(R.string.act_split)
    Tool.WATERMARK -> str(R.string.act_watermark)
    Tool.PROTECT -> str(R.string.act_protect)
    Tool.UNLOCK -> str(R.string.act_unlock)
    Tool.COMPRESS -> str(R.string.act_compress)
    else -> str(R.string.act_convert)
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "1-3, 5, 7-9" -> index ranges counted from 0. */
fun parseRanges(text: String, count: Int): List<IntRange> = text.split(',', ';', ' ')
    .map { it.trim() }.filter { it.isNotEmpty() }
    .mapNotNull { part ->
        val bits = part.split('-').map { it.trim().toIntOrNull() }
        when {
            bits.size == 1 && bits[0] != null -> (bits[0]!! - 1)..(bits[0]!! - 1)
            bits.size == 2 && bits[0] != null && bits[1] != null -> (bits[0]!! - 1)..(bits[1]!! - 1)
            bits.size == 2 && bits[0] != null && bits[1] == null -> (bits[0]!! - 1) until count
            else -> null
        }
    }
    .map { r -> r.first.coerceIn(0, count - 1)..r.last.coerceIn(0, count - 1) }
    .filter { it.first <= it.last }

/** Copies files picked in the system picker to the temp directory. */
fun importUris(context: Context, uris: List<Uri>, runner: com.kuczynski.skryba.ui.common.TaskRunner, onDone: (List<Pair<File, String>>) -> Unit) {
    if (uris.isEmpty()) return
    runner.run(str(R.string.loading), onDone = onDone) { p ->
        uris.mapIndexed { i, u ->
            p(i, uris.size)
            val name = Library.displayName(context, u) ?: "${str(R.string.file_default)}_$i"
            val dir = File(Library.tempDir, "in_${System.nanoTime()}").apply { mkdirs() }
            Library.importUri(context, u, dir) to name
        }
    }
}

/** Picking a file from the app library (scanned documents and files). */
@Composable
fun LibraryPicker(kind: InputKind, onDismiss: () -> Unit, onDoc: (ScanDocument) -> Unit, onFile: (File) -> Unit) {
    val allDocs by Library.docs.collectAsState()
    val allFiles by Library.files.collectAsState()
    val docs = if (kind == InputKind.PDF) allDocs else emptyList()
    val files = allFiles.filter { it.extension.lowercase() in kind.extensions }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.choose_from_library)) },
        text = {
            if (docs.isEmpty() && files.isEmpty()) {
                Text(str(R.string.no_matching_files, kind.label))
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(docs, key = { "d" + it.id }) { d ->
                        Row(Modifier.fillMaxWidth().clickable { onDoc(d) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DocumentScanner, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(str(R.string.scan_pages, d.pages.size), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    items(files, key = { "f" + it.path }) { f ->
                        Row(Modifier.fillMaxWidth().clickable { onFile(f) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(fileIcon(f), null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatSize(f.length()), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}
