package com.kuczynski.skryba.ui.common

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kuczynski.skryba.R
import com.kuczynski.skryba.convert.UnsupportedFormatException
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.i18n.Lang
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.pdf.PdfPasswordException
import com.kuczynski.skryba.share.Sharing
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- Zadania w tle z okienkiem postępu ----------------

class TaskRunner(private val scope: CoroutineScope, private val context: Context) {
    var label by mutableStateOf<String?>(null)
        private set
    var progress by mutableFloatStateOf(-1f)
        private set

    val busy get() = label != null

    fun <T> run(
        label: String,
        onError: (Throwable) -> Unit = {},
        onDone: (T) -> Unit,
        block: suspend (progress: (Int, Int) -> Unit) -> T,
    ) {
        if (busy) return
        this.label = label
        progress = -1f
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    block { done, total -> progress = if (total > 0) done.toFloat() / total else -1f }
                }
                this@TaskRunner.label = null
                onDone(result)
            } catch (e: Throwable) {
                this@TaskRunner.label = null
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                toast(context, errorMessage(e))
                onError(e)
            }
        }
    }
}

fun errorMessage(e: Throwable): String = when (e) {
    is PdfPasswordException -> str(R.string.err_pdf_password_ui)
    is OutOfMemoryError -> str(R.string.err_oom)
    is UnsupportedFormatException -> when (e.reason) {
        UnsupportedFormatException.Reason.OLD_FORMAT -> str(R.string.err_old_format, e.ext)
        UnsupportedFormatException.Reason.UNKNOWN_TYPE -> str(R.string.err_unknown_type, e.ext)
        UnsupportedFormatException.Reason.NOT_OOXML -> str(R.string.err_not_ooxml)
        UnsupportedFormatException.Reason.EMPTY -> str(R.string.err_empty_doc)
    }
    else -> e.message?.takeIf { it.isNotBlank() } ?: str(R.string.err_generic, e.javaClass.simpleName)
}

@Composable
fun rememberTaskRunner(): TaskRunner {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember { TaskRunner(scope, context) }
}

@Composable
fun BusyDialog(runner: TaskRunner) {
    val label = runner.label ?: return
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (runner.progress >= 0f) {
                    LinearProgressIndicator(progress = { runner.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(runner.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        },
    )
}

fun toast(context: Context, msg: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}

// ---------------- Paski i drobne elementy ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.back)) }
        },
        actions = actions,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f kB", bytes / 1024.0)
    else -> "$bytes B"
}

fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Lang.context().resources.configuration.locales[0]).format(Date(millis))

fun fileIcon(f: File): ImageVector = when (f.extension.lowercase()) {
    "pdf" -> Icons.Default.PictureAsPdf
    "jpg", "jpeg", "png", "webp" -> Icons.Default.Image
    "docx", "doc" -> Icons.Default.Description
    "xlsx", "xls", "csv" -> Icons.Default.TableChart
    "pptx", "ppt" -> Icons.Default.Slideshow
    "txt", "md" -> Icons.AutoMirrored.Filled.TextSnippet
    "zip" -> Icons.Default.FolderZip
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

// ---------------- Okna dialogowe ----------------

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String = "",
    confirm: String = str(R.string.ok),
    password: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { if (label.isNotEmpty()) Text(label) },
                singleLine = true,
                visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                trailingIcon = if (password) {
                    {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, str(R.string.show_password))
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

// ---------------- Wynik operacji: co zrobić z nowym plikiem ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultSheet(
    files: List<File>,
    title: String = if (files.size == 1) str(R.string.done) else str(R.string.done_n, files.size),
    onOpen: ((File) -> Unit)?,
    onQr: (List<File>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val first = files.firstOrNull() ?: return
    val saveOne = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Sharing.mime(first))) { uri ->
        if (uri != null) {
            runCatching { Library.copyToUri(context, first, uri) }
                .onSuccess { toast(context, str(R.string.saved_on_device)) }
                .onFailure { toast(context, errorMessage(it)) }
        }
    }
    val saveMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            runCatching { saveToTree(context, tree, files) }
                .onSuccess { toast(context, str(R.string.saved_n, files.size)) }
                .onFailure { toast(context, errorMessage(it)) }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            files.take(6).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Icon(fileIcon(f), null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatSize(f.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (files.size > 6) Text(str(R.string.and_n_more, files.size - 6), style = MaterialTheme.typography.bodySmall)
            Text(
                str(R.string.saved_in_files),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (onOpen != null && files.size == 1) {
                    SheetAction(Icons.AutoMirrored.Filled.OpenInNew, str(R.string.open)) { onDismiss(); onOpen(first) }
                }
                SheetAction(Icons.Default.Share, str(R.string.share)) { Sharing.share(context, files) }
                SheetAction(Icons.Default.QrCode2, str(R.string.link_qr)) { onDismiss(); onQr(files) }
                SheetAction(Icons.Default.Save, str(R.string.to_phone)) {
                    if (files.size == 1) saveOne.launch(first.name) else saveMany.launch(null)
                }
            }
        }
    }
}

@Composable
fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(80.dp)) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

fun saveToTree(context: Context, tree: Uri, files: List<File>) {
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    for (f in files) {
        val uri = DocumentsContract.createDocument(context.contentResolver, parent, Sharing.mime(f), f.name)
            ?: error(str(R.string.err_create_file, f.name))
        Library.copyToUri(context, f, uri)
    }
}

// ---------------- Podgląd z powiększaniem ----------------

/**
 * Obraz z powiększaniem dwoma palcami i podwójnym stuknięciem. Przy skali 1 przepuszcza
 * przeciąganie jednym palcem dalej (np. do przewijania stron w HorizontalPager).
 */
@Composable
fun ZoomableImage(bitmap: ImageBitmap, modifier: Modifier = Modifier, contentDescription: String? = null) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .clipToBounds()
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(bitmap) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap,
            contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}
