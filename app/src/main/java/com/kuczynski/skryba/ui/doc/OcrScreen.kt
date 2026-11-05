package com.kuczynski.skryba.ui.doc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kuczynski.skryba.R
import com.kuczynski.skryba.convert.Converters
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.ScanDocument
import com.kuczynski.skryba.data.Settings
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.ocr.OcrEngine
import com.kuczynski.skryba.scan.DocActions
import com.kuczynski.skryba.share.Sharing
import com.kuczynski.skryba.ui.common.BackTopBar
import com.kuczynski.skryba.ui.common.BusyDialog
import com.kuczynski.skryba.ui.common.ResultSheet
import com.kuczynski.skryba.ui.common.errorMessage
import com.kuczynski.skryba.ui.common.rememberTaskRunner
import com.kuczynski.skryba.ui.common.toast
import com.kuczynski.skryba.ui.scan.ToolButton
import com.kuczynski.skryba.ui.shareFilesQr
import com.kuczynski.skryba.ui.viewFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OcrScreen(nav: NavController, docId: String, pageIndex: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberTaskRunner()
    val doc = remember { Library.get(docId) }
    if (doc == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val indices = if (pageIndex in doc.pages.indices) listOf(pageIndex) else doc.pages.indices.toList()
    var text by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var working by remember { mutableStateOf(true) }
    var langDialog by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<List<File>?>(null) }
    var runId by remember { mutableStateOf(0 to false) }
    val settings by Settings.state.collectAsState()

    LaunchedEffect(runId) {
        working = true
        progress = 0f
        val res = withContext(Dispatchers.Default) {
            runCatching {
                val current = Library.get(docId) ?: doc
                DocActions.ocr(context, current, indices, force = runId.second) { d, t -> progress = if (t > 0) d.toFloat() / t else 0f }
            }
        }
        working = false
        res.onSuccess { d: ScanDocument ->
            text = indices.joinToString("\n\n") { d.pages[it].ocrText.trim() }.trim()
        }.onFailure { toast(context, errorMessage(it)) }
    }

    val title = if (indices.size == 1 && doc.pages.size > 1) str(R.string.page_text_n, indices[0] + 1) else str(R.string.recognized_text)
    Scaffold(
        topBar = {
            BackTopBar(title, { nav.popBackStack() }, subtitle = settings.ocrLangs.joinToString(", ") { OcrEngine.label(it) }) {
                IconButton(onClick = { langDialog = true }) { Icon(Icons.Default.Language, str(R.string.languages)) }
                IconButton(onClick = { runId = runId.first + 1 to true }, enabled = !working) { Icon(Icons.Default.Refresh, str(R.string.recognize_again)) }
            }
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolButton(Icons.Default.ContentCopy, str(R.string.copy), enabled = text.isNotBlank()) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(str(R.string.text), text))
                    toast(context, str(R.string.copied))
                }
                ToolButton(Icons.Default.Share, str(R.string.share), enabled = text.isNotBlank()) { Sharing.shareText(context, text) }
                ToolButton(Icons.AutoMirrored.Filled.TextSnippet, str(R.string.save_txt), enabled = text.isNotBlank()) {
                    runner.run(str(R.string.saving), onDone = { f: File -> result = listOf(f) }) { Converters.textToTxt(text, doc.name) }
                }
                ToolButton(Icons.Default.Description, str(R.string.save_word), enabled = text.isNotBlank()) {
                    runner.run(str(R.string.saving), onDone = { f: File -> result = listOf(f) }) { Converters.textToWord(text, doc.name) }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            if (working) {
                Text(str(R.string.ocr_running), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else if (text.isBlank()) {
                Text(
                    str(R.string.ocr_no_text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                enabled = !working,
            )
        }
    }
    BusyDialog(runner)
    result?.let { files -> ResultSheet(files, onOpen = { nav.viewFile(it) }, onQr = { nav.shareFilesQr(it) }, onDismiss = { result = null }) }
    if (langDialog) {
        LanguageDialog(onDismiss = { langDialog = false }) {
            langDialog = false
            scope.launch { runId = runId.first + 1 to true }
        }
    }
}

/** Wybór języków OCR (zapisywany w ustawieniach). */
@Composable
fun LanguageDialog(onDismiss: () -> Unit, onApply: () -> Unit) {
    val context = LocalContext.current
    val available = remember { OcrEngine.availableLanguages(context) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(Settings.value.ocrLangs) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.text_languages)) },
        text = {
            Column {
                Text(
                    str(R.string.text_languages_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(available) { code ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (code in selected) selected.remove(code) else selected.add(code)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(code in selected, { if (it) selected.add(code) else selected.remove(code) })
                            Text(OcrEngine.label(code))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Settings.update { it.copy(ocrLangs = selected.toList()) }
                onApply()
            }, enabled = selected.isNotEmpty()) { Text(str(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}
