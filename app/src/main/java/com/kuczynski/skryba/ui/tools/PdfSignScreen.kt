package com.kuczynski.skryba.ui.tools

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.pdf.PdfRender
import com.kuczynski.skryba.pdf.PdfTools
import com.kuczynski.skryba.ui.Handoff
import com.kuczynski.skryba.ui.common.BackTopBar
import com.kuczynski.skryba.ui.common.BusyDialog
import com.kuczynski.skryba.ui.common.ResultSheet
import com.kuczynski.skryba.ui.common.rememberTaskRunner
import com.kuczynski.skryba.ui.doc.NRect
import com.kuczynski.skryba.ui.doc.SignaturePlacer
import com.kuczynski.skryba.ui.doc.SignatureStrip
import com.kuczynski.skryba.ui.doc.initialRect
import com.kuczynski.skryba.ui.doc.listSignatures
import com.kuczynski.skryba.ui.shareFilesQr
import com.kuczynski.skryba.ui.viewFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PdfSignScreen(nav: NavController) {
    val input = remember { Handoff.pdf }
    val runner = rememberTaskRunner()
    if (input == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    var pageIndex by remember { mutableIntStateOf(0) }
    val count by produceState(0) {
        value = withContext(Dispatchers.IO) { runCatching { PdfRender.pageCount(input.file, input.password) }.getOrDefault(0) }
    }
    val pageBmp by produceState<ImageBitmap?>(null, pageIndex) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching { PdfRender.render(input.file, pageIndex, 1600, password = input.password).asImageBitmap() }.getOrNull()
        }
    }
    var sigFile by remember { mutableStateOf(listSignatures().firstOrNull()) }
    val sigBmp by produceState<ImageBitmap?>(null, sigFile) {
        value = sigFile?.let { f -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path)?.asImageBitmap() } }
    }
    var rect by remember { mutableStateOf<NRect?>(null) }
    var resetKey by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<List<File>?>(null) }
    val pb = pageBmp
    val sb = sigBmp
    if (rect == null && pb != null && sb != null) rect = initialRect(pb, sb)

    Scaffold(
        topBar = { BackTopBar(str(R.string.sign_pdf), { nav.popBackStack() }, subtitle = input.label) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
                SignatureStrip(sigFile) { sigFile = it; rect = null; resetKey++ }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { pageIndex--; rect = null; resetKey++ }, enabled = pageIndex > 0) { Icon(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.previous)) }
                    Text(str(R.string.page_slash, pageIndex + 1, count.coerceAtLeast(1)))
                    IconButton(onClick = { pageIndex++; rect = null; resetKey++ }, enabled = pageIndex < count - 1) { Icon(Icons.AutoMirrored.Filled.ArrowForward, str(R.string.next_page)) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        val r = rect ?: return@Button
                        val f = sigFile ?: return@Button
                        runner.run(str(R.string.inserting_signature), onDone = { out: File -> result = listOf(out) }) {
                            val sig = BitmapFactory.decodeFile(f.path) ?: error(str(R.string.err_load_signature))
                            val tmp = Library.tempFile("signed.pdf")
                            PdfTools.stampImage(input.file, pageIndex, sig, r.x, r.y, r.w, r.h, tmp, input.password)
                            sig.recycle()
                            Library.addFile(tmp, str(R.string.signed_suffix, input.label.substringBeforeLast('.')), move = true)
                        }
                    }, enabled = rect != null && sigFile != null) {
                        Icon(Icons.Default.Check, null)
                        Text(str(R.string.insert))
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Color(0xFF1A1D1C)).padding(12.dp), contentAlignment = Alignment.Center) {
            if (pb == null) {
                CircularProgressIndicator()
            } else {
                key(resetKey) {
                    SignaturePlacer(pb, sb, rect ?: NRect(0.6f, 0.75f, 0.3f, 0.1f), { rect = it }, Modifier.fillMaxSize())
                }
            }
        }
    }
    BusyDialog(runner)
    result?.let { files -> ResultSheet(files, onOpen = { nav.viewFile(it) }, onQr = { nav.shareFilesQr(it) }, onDismiss = { result = null }) }
}
