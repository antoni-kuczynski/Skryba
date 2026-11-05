package com.kuczynski.skryba.ui.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.ScanMode
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.imaging.AiTools
import com.kuczynski.skryba.imaging.ImageIO
import com.kuczynski.skryba.scan.ScanSession
import com.kuczynski.skryba.share.Sharing
import com.kuczynski.skryba.ui.Routes
import com.kuczynski.skryba.ui.Tool
import com.kuczynski.skryba.ui.common.BackTopBar
import com.kuczynski.skryba.ui.common.BusyDialog
import com.kuczynski.skryba.ui.common.ResultSheet
import com.kuczynski.skryba.ui.common.TaskRunner
import com.kuczynski.skryba.ui.common.rememberTaskRunner
import com.kuczynski.skryba.ui.shareFilesQr
import com.kuczynski.skryba.ui.viewFile
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.delay

@Composable
fun AiToolScreen(nav: NavController, tool: Tool) {
    when (tool) {
        Tool.RESTORE -> RestoreScreen(nav)
        Tool.TAMPER, Tool.PHOTOSHOP -> TamperScreen(nav, tool)
        Tool.COUNT -> CountScreen(nav)
        Tool.MEGA_SCAN -> MegaScanScreen(nav)
        else -> LaunchedEffect(Unit) { nav.popBackStack() }
    }
}

// ---------------- Wybór zdjęcia: galeria, plik albo aparat ----------------

@Composable
private fun ImageSourceButtons(runner: TaskRunner, preferFiles: Boolean = false, onPicked: (File) -> Unit) {
    val context = LocalContext.current
    val tempPhoto = remember { File(Library.tempDir, "photo_${System.nanoTime()}.jpg") }
    fun copy(uri: Uri) {
        runner.run(str(R.string.loading), onDone = onPicked) {
            val dir = File(Library.tempDir, "ai_${System.nanoTime()}").apply { mkdirs() }
            Library.importUri(context, uri, dir)
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(::copy) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::copy) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) onPicked(tempPhoto) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (preferFiles) {
            OutlinedButton(onClick = { files.launch(arrayOf("image/*")) }, Modifier.weight(1f)) {
                Icon(Icons.Default.FolderOpen, null); Text(str(R.string.src_file))
            }
        }
        OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f)) {
            Icon(Icons.Default.PhotoLibrary, null); Text(str(R.string.src_gallery))
        }
        OutlinedButton(onClick = { camera.launch(Sharing.uri(context, tempPhoto)) }, Modifier.weight(1f)) {
            Icon(Icons.Default.CameraAlt, null); Text(str(R.string.src_camera))
        }
    }
}

private fun strengthLabel(s: Int) = when (s) {
    1 -> str(R.string.str_gentle)
    2 -> str(R.string.str_medium)
    else -> str(R.string.str_strong)
}

private fun saveBitmapToLibrary(bmp: Bitmap, name: String): File {
    val tmp = Library.tempFile("ai_out.jpg")
    ImageIO.saveJpeg(bmp, tmp, 92)
    return Library.addFile(tmp, name, move = true)
}

@Composable
private fun InfoCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(14.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
    }
}

// ---------------- Renowacja zdjęć ----------------

@Composable
private fun RestoreScreen(nav: NavController) {
    val runner = rememberTaskRunner()
    var source by remember { mutableStateOf<File?>(null) }
    var before by remember { mutableStateOf<Bitmap?>(null) }
    var after by remember { mutableStateOf<Bitmap?>(null) }
    var strength by remember { mutableIntStateOf(2) }
    var scratches by remember { mutableStateOf(true) }
    var colorize by remember { mutableStateOf(true) }
    var split by remember { mutableFloatStateOf(0.5f) }
    var result by remember { mutableStateOf<List<File>?>(null) }

    fun process() {
        val f = source ?: return
        runner.run(str(R.string.restoring), onDone = { pair: Pair<Bitmap, Bitmap> -> before = pair.first; after = pair.second }) {
            val src = ImageIO.decode(f, 1800)
            src to AiTools.restorePhoto(src, strength, scratches, colorize)
        }
    }

    Scaffold(topBar = { BackTopBar(Tool.RESTORE.title, { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(str(R.string.restore_info))
            ImageSourceButtons(runner) { f -> source = f; before = null; after = null; process() }
            val b = before
            val a = after
            if (b != null && a != null) {
                Box(Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height).clip(RoundedCornerShape(12.dp))) {
                    Image(a.asImageBitmap(), str(R.string.after), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    Image(
                        b.asImageBitmap(), str(R.string.before), contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().drawWithContent {
                            clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
                            drawLine(Color.White, Offset(size.width * split, 0f), Offset(size.width * split, size.height), 4f)
                        },
                    )
                    Text(str(R.string.before), color = Color.White, modifier = Modifier.align(Alignment.TopStart).background(Color(0x88000000)).padding(4.dp))
                    Text(str(R.string.after), color = Color.White, modifier = Modifier.align(Alignment.TopEnd).background(Color(0x88000000)).padding(4.dp))
                }
                Slider(split, { split = it })
            }
            if (source != null) {
                Text(str(R.string.strength, strengthLabel(strength)), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..3).forEach { s -> FilterChip(strength == s, { strength = s; process() }, label = { Text(strengthLabel(s)) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.remove_scratches), Modifier.weight(1f)); Switch(scratches, { scratches = it; process() })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.revive_colors), Modifier.weight(1f)); Switch(colorize, { colorize = it; process() })
                }
                Button(onClick = {
                    val res = after ?: return@Button
                    runner.run(str(R.string.saving), onDone = { f: File -> result = listOf(f) }) {
                        saveBitmapToLibrary(res, str(R.string.restored_suffix, source?.nameWithoutExtension ?: str(R.string.photo_default)))
                    }
                }, enabled = after != null, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Save, null); Text(str(R.string.save_result))
                }
            }
        }
    }
    BusyDialog(runner)
    result?.let { files -> ResultSheet(files, onOpen = { nav.viewFile(it) }, onQr = { nav.shareFilesQr(it) }, onDismiss = { result = null }) }
}

// ---------------- Wykrywanie manipulacji / Photoshopa ----------------

@Composable
private fun TamperScreen(nav: NavController, tool: Tool) {
    val runner = rememberTaskRunner()
    var report by remember { mutableStateOf<AiTools.TamperReport?>(null) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var showHeat by remember { mutableStateOf(true) }

    Scaffold(topBar = { BackTopBar(tool.title, { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(
                if (tool == Tool.PHOTOSHOP) str(R.string.photoshop_info)
                else str(R.string.tamper_info)
            )
            ImageSourceButtons(runner, preferFiles = true) { f ->
                runner.run(str(R.string.analyzing_photo), onDone = { r: Pair<AiTools.TamperReport, Bitmap> -> report = r.first; original = r.second }) {
                    AiTools.analyzeTampering(f) to ImageIO.decode(f, 1600)
                }
            }
            val r = report
            if (r != null) {
                val color = when {
                    r.score >= 50 -> MaterialTheme.colorScheme.errorContainer
                    r.score >= 25 -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
                Card(colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        Text(r.verdict, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { r.score / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(str(R.string.score, r.score), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        if (tool == Tool.PHOTOSHOP) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (r.editors.isEmpty()) str(R.string.no_ps_traces)
                                else str(R.string.found_editors, r.editors.joinToString(", ")),
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Text(str(R.string.details), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                r.findings.forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(showHeat, { showHeat = true }, label = { Text(str(R.string.ela_map)) })
                    FilterChip(!showHeat, { showHeat = false }, label = { Text(str(R.string.original)) })
                }
                val img = if (showHeat) r.heatmap else original
                img?.let {
                    Image(
                        it.asImageBitmap(), null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height).clip(RoundedCornerShape(12.dp)),
                    )
                }
                Text(
                    str(R.string.ela_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    BusyDialog(runner)
}

// ---------------- Licznik obiektów ----------------

@Composable
private fun CountScreen(nav: NavController) {
    val runner = rememberTaskRunner()
    var source by remember { mutableStateOf<Bitmap?>(null) }
    val points = remember { mutableStateListOf<PointF>() }
    var radius by remember { mutableFloatStateOf(20f) }
    var sensitivity by remember { mutableFloatStateOf(0.5f) }
    var polarity by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<List<File>?>(null) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(source, sensitivity, polarity, version) {
        val src = source ?: return@LaunchedEffect
        delay(250)
        // Poprzednie liczenie musi się skończyć, inaczej ostatnia zmiana suwaka zostałaby pominięta.
        while (runner.busy) delay(100)
        runner.run(str(R.string.counting), onDone = { r: AiTools.CountResult -> points.clear(); points.addAll(r.centers); radius = r.radius }) {
            AiTools.countObjects(src, sensitivity, when (polarity) { 1 -> true; 2 -> false; else -> null })
        }
    }

    Scaffold(topBar = { BackTopBar(Tool.COUNT.title, { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(str(R.string.count_info))
            ImageSourceButtons(runner) { f ->
                runner.run(str(R.string.loading), onDone = { b: Bitmap -> source = b; version++ }) { ImageIO.decode(f, 1600) }
            }
            val src = source
            if (src != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${points.size}", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(str(R.string.objects_counted), style = MaterialTheme.typography.titleMedium)
                }
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(src.width.toFloat() / src.height).clip(RoundedCornerShape(12.dp))) {
                    val scale = min(constraints.maxWidth.toFloat() / src.width, constraints.maxHeight.toFloat() / src.height)
                    Image(src.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    androidx.compose.foundation.Canvas(
                        Modifier.fillMaxSize().pointerInput(src, scale) {
                            detectTapGestures { pos ->
                                val p = PointF(pos.x / scale, pos.y / scale)
                                val hit = points.indexOfFirst { q -> sqrt((q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)) < radius }
                                if (hit >= 0) points.removeAt(hit) else points.add(p)
                            }
                        },
                    ) {
                        points.forEach { p ->
                            val c = Offset(p.x * scale, p.y * scale)
                            drawCircle(Color(0x5500E5C0), radius * scale, c)
                            drawCircle(Color(0xFF00C2A8), radius * scale, c, style = Stroke(3f))
                            drawCircle(Color.White, 4f, c)
                        }
                    }
                }
                Text(str(R.string.count_sensitivity), style = MaterialTheme.typography.titleSmall)
                Slider(sensitivity, { sensitivity = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(str(R.string.auto), str(R.string.dark_objects), str(R.string.light_objects)).forEachIndexed { i, n -> FilterChip(polarity == i, { polarity = i }, label = { Text(n) }) }
                }
                Button(onClick = {
                    runner.run(str(R.string.saving), onDone = { f: File -> result = listOf(f) }) {
                        val out = src.copy(Bitmap.Config.ARGB_8888, true)
                        val c = Canvas(out)
                        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFF00C2A8.toInt() }
                        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = radius.coerceIn(14f, 48f); textAlign = Paint.Align.CENTER; setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK) }
                        points.forEachIndexed { i, p ->
                            c.drawCircle(p.x, p.y, radius, stroke)
                            c.drawText("${i + 1}", p.x, p.y + text.textSize / 3, text)
                        }
                        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = out.width / 12f; setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK) }
                        c.drawText(str(R.string.total_n, points.size), out.width * 0.04f, big.textSize * 1.3f, big)
                        saveBitmapToLibrary(out, str(R.string.counter_file, points.size)).also { out.recycle() }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Text(str(R.string.save_marked)) }
            }
        }
    }
    BusyDialog(runner)
    result?.let { files -> ResultSheet(files, onOpen = { nav.viewFile(it) }, onQr = { nav.shareFilesQr(it) }, onDismiss = { result = null }) }
}

// ---------------- Mega skan ----------------

@Composable
private fun MegaScanScreen(nav: NavController) {
    val context = LocalContext.current
    val runner = rememberTaskRunner()
    val images = remember { mutableStateListOf<File>() }
    var stitched by remember { mutableStateOf<Bitmap?>(null) }
    var tempPhoto by remember { mutableStateOf(File(Library.tempDir, "mega_${System.nanoTime()}.jpg")) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        runner.run(str(R.string.loading), onDone = { list: List<File> -> images.addAll(list); stitched = null }) {
            uris.map { u ->
                val dir = File(Library.tempDir, "mega_${System.nanoTime()}").apply { mkdirs() }
                Library.importUri(context, u, dir)
            }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            images.add(tempPhoto)
            stitched = null
            tempPhoto = File(Library.tempDir, "mega_${System.nanoTime()}.jpg")
        }
    }

    Scaffold(topBar = { BackTopBar(Tool.MEGA_SCAN.title, { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(str(R.string.mega_info))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f)) {
                    Icon(Icons.Default.AddPhotoAlternate, null); Text(str(R.string.src_gallery))
                }
                OutlinedButton(onClick = { camera.launch(Sharing.uri(context, tempPhoto)) }, Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraAlt, null); Text(str(R.string.src_camera))
                }
            }
            if (images.isNotEmpty()) {
                Text(str(R.string.photos_order, images.size), style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(images) { i, f ->
                        Box {
                            coil3.compose.AsyncImage(
                                f, null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(90.dp).clip(RoundedCornerShape(10.dp)),
                            )
                            IconButton(onClick = { images.removeAt(i); stitched = null }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp).background(Color(0x99000000), RoundedCornerShape(50))) {
                                Icon(Icons.Default.Close, str(R.string.remove), tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                Button(onClick = {
                    runner.run(str(R.string.stitching), onDone = { b: Bitmap -> stitched = b }) { p ->
                        val bmps = images.map { ImageIO.decode(it, 2000) }
                        AiTools.stitch(bmps, p).also { bmps.forEach { b -> b.recycle() } }
                    }
                }, enabled = images.size >= 2, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Panorama, null); Text(str(R.string.stitch_button))
                }
            }
            stitched?.let { b ->
                Image(
                    b.asImageBitmap(), null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).aspectRatio(b.width.toFloat() / b.height).clip(RoundedCornerShape(12.dp)),
                )
                Button(onClick = {
                    ScanSession.start(ScanMode.DOCUMENT)
                    runner.run(str(R.string.preparing), onDone = { _: Unit -> nav.navigate(Routes.REVIEW) { popUpTo(Routes.HOME) } }) {
                        ScanSession.addBitmap(b, detect = false)
                        Unit
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(str(R.string.next_crop_save)) }
            }
        }
    }
    BusyDialog(runner)
}
