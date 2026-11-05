package com.kuczynski.skryba.ui.doc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.Overlay
import com.kuczynski.skryba.data.Settings
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.imaging.ImageIO
import com.kuczynski.skryba.imaging.PageRenderer
import com.kuczynski.skryba.ui.common.BackTopBar
import com.kuczynski.skryba.ui.common.BusyDialog
import com.kuczynski.skryba.ui.common.ConfirmDialog
import com.kuczynski.skryba.ui.common.rememberTaskRunner
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Normalizowany prostokąt podpisu na stronie (lewy górny róg + rozmiar, 0..1). */
data class NRect(val x: Float, val y: Float, val w: Float, val h: Float)

// ---------------- Rysowanie nowego podpisu ----------------

@Composable
fun SignaturePadDialog(onDismiss: () -> Unit, onSaved: (File) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableStateOf(Color(0xFF0B2A6B)) }
    var width by remember { mutableFloatStateOf(6f) }
    var padSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(str(R.string.draw_signature), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(12.dp)).background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                ) {
                    Canvas(
                        Modifier.fillMaxSize()
                            .pointerInput(Unit) {
                                padSize = size
                                detectDragGestures(
                                    onDragStart = { current = listOf(it) },
                                    onDrag = { c, _ -> c.consume(); current = current + c.position },
                                    onDragEnd = { if (current.size > 1) strokes += current; current = emptyList() },
                                )
                            },
                    ) {
                        drawLine(Color.LightGray, Offset(24f, size.height * 0.75f), Offset(size.width - 24f, size.height * 0.75f), 2f)
                        for (s in strokes + listOf(current)) {
                            if (s.size < 2) continue
                            val path = Path().apply {
                                moveTo(s[0].x, s[0].y)
                                for (i in 1 until s.size) {
                                    val mid = (s[i - 1] + s[i]) / 2f
                                    quadraticTo(s[i - 1].x, s[i - 1].y, mid.x, mid.y)
                                }
                                lineTo(s.last().x, s.last().y)
                            }
                            drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    if (strokes.isEmpty() && current.isEmpty()) {
                        Text(str(R.string.sign_here), color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(Color(0xFF0B2A6B), Color.Black, Color(0xFFB00020)).forEach { c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(c)
                                .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .combinedClickable(onClick = { color = c }),
                        )
                    }
                    Slider(width, { width = it }, valueRange = 3f..14f, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { strokes.clear() }) { Text(str(R.string.clear)) }
                    TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) }
                    Button(onClick = {
                        val f = saveSignature(strokes.toList(), padSize.width, padSize.height, color.toArgb(), width)
                        if (f != null) onSaved(f)
                    }, enabled = strokes.isNotEmpty()) { Text(str(R.string.save)) }
                }
            }
        }
    }
}

/** Zapisuje podpis jako przezroczysty PNG przycięty do narysowanych linii (2x rozdzielczość). */
private fun saveSignature(strokes: List<List<Offset>>, w: Int, h: Int, color: Int, width: Float): File? {
    if (strokes.isEmpty() || w <= 0 || h <= 0) return null
    val scale = 2f
    val all = strokes.flatten()
    val pad = width * 2
    val minX = max(0f, all.minOf { it.x } - pad)
    val minY = max(0f, all.minOf { it.y } - pad)
    val maxX = min(w.toFloat(), all.maxOf { it.x } + pad)
    val maxY = min(h.toFloat(), all.maxOf { it.y } + pad)
    val bw = ((maxX - minX) * scale).roundToInt().coerceAtLeast(4)
    val bh = ((maxY - minY) * scale).roundToInt().coerceAtLeast(4)
    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width * scale
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (s in strokes) {
        val path = android.graphics.Path()
        fun px(o: Offset) = (o.x - minX) * scale
        fun py(o: Offset) = (o.y - minY) * scale
        path.moveTo(px(s[0]), py(s[0]))
        for (i in 1 until s.size) {
            val mx = (px(s[i - 1]) + px(s[i])) / 2
            val my = (py(s[i - 1]) + py(s[i])) / 2
            path.quadTo(px(s[i - 1]), py(s[i - 1]), mx, my)
        }
        path.lineTo(px(s.last()), py(s.last()))
        c.drawPath(path, paint)
    }
    val f = File(Library.signaturesDir, "sig_${Library.newId()}.png")
    ImageIO.savePng(bmp, f)
    bmp.recycle()
    return f
}

// ---------------- Wybór zapisanego podpisu ----------------

@Composable
fun SignatureStrip(selected: File?, onSelect: (File) -> Unit) {
    var signatures by remember { mutableStateOf(listSignatures()) }
    var drawing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<File?>(null) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Surface(
                onClick = { drawing = true },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(width = 110.dp, height = 60.dp),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, null)
                    Text(str(R.string.new_signature))
                }
            }
        }
        items(signatures, key = { it.name }) { f ->
            val bmp by produceState<ImageBitmap?>(null, f) {
                value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }
            }
            Box(
                Modifier.size(width = 110.dp, height = 60.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
                    .border(if (f == selected) 3.dp else 1.dp, if (f == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { onSelect(f) }, onLongClick = { deleting = f })
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                bmp?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
    if (drawing) {
        SignaturePadDialog(onDismiss = { drawing = false }) { f ->
            drawing = false
            signatures = listSignatures()
            onSelect(f)
        }
    }
    deleting?.let { f ->
        ConfirmDialog(str(R.string.delete_signature_q), str(R.string.delete_signature_text), str(R.string.delete), { deleting = null }) {
            f.delete()
            signatures = listSignatures()
            deleting = null
        }
    }
}

fun listSignatures(): List<File> =
    Library.signaturesDir.listFiles().orEmpty().filter { it.name.startsWith("sig_") }.sortedByDescending { it.lastModified() }

// ---------------- Umieszczanie podpisu na stronie ----------------

/** Tło (strona) z podpisem, który można przesuwać jednym palcem i skalować dwoma. */
@Composable
fun SignaturePlacer(page: ImageBitmap, signature: ImageBitmap?, rect: NRect, onRect: (NRect) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val s = min(cw / page.width, ch / page.height)
        val dw = page.width * s
        val dh = page.height * s
        val left = (cw - dw) / 2
        val top = (ch - dh) / 2
        // Aktualny prostokąt bez restartowania obsługi gestu przy każdej zmianie.
        val currentRect by rememberUpdatedState(rect)
        val update by rememberUpdatedState(onRect)
        Box(Modifier.fillMaxSize()) {
            Image(page, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            if (signature != null) {
                val wPx = rect.w * dw
                val hPx = rect.h * dh
                Box(
                    Modifier
                        .offset { IntOffset((left + rect.x * dw).roundToInt(), (top + rect.y * dh).roundToInt()) }
                        .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                        .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                        .pointerInput(dw, dh) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val r = currentRect
                                val nw = (r.w * zoom).coerceIn(0.05f, 1f)
                                val nh = nw * r.h / r.w
                                val nx = (r.x + pan.x / dw - (nw - r.w) / 2).coerceIn(0f, 1f - nw)
                                val ny = (r.y + pan.y / dh - (nh - r.h) / 2).coerceIn(0f, max(0f, 1f - nh))
                                update(NRect(nx, ny, nw, nh))
                            }
                        },
                ) {
                    Image(signature, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Startowe położenie podpisu: prawa dolna część strony, zachowane proporcje. */
fun initialRect(page: ImageBitmap, sig: ImageBitmap): NRect {
    val w = 0.35f
    val h = w * page.width / page.height * sig.height / sig.width
    return NRect(0.58f, (0.82f - h).coerceAtLeast(0f), w, h)
}

// ---------------- Ekran: podpis na stronie dokumentu ----------------

@Composable
fun SignPageScreen(nav: NavController, docId: String, pageId: String) {
    val doc = Library.get(docId)
    val page = doc?.pages?.firstOrNull { it.id == pageId }
    val runner = rememberTaskRunner()
    if (doc == null || page == null) {
        nav.popBackStack()
        return
    }
    val pageBmp by produceState<ImageBitmap?>(null, page.processed) {
        value = withContext(Dispatchers.IO) { ImageIO.decode(Library.pageFile(docId, page.processed), 2000).asImageBitmap() }
    }
    var sigFile by remember { mutableStateOf(listSignatures().firstOrNull()) }
    val sigBmp by produceState<ImageBitmap?>(null, sigFile) {
        value = sigFile?.let { f -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path)?.asImageBitmap() } }
    }
    var rect by remember { mutableStateOf<NRect?>(null) }
    var key by remember { mutableIntStateOf(0) }
    if (rect == null && pageBmp != null && sigBmp != null) rect = initialRect(pageBmp!!, sigBmp!!)

    Scaffold(
        topBar = { BackTopBar(str(R.string.sign_page), { nav.popBackStack() }, subtitle = str(R.string.sign_hint)) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
                SignatureStrip(sigFile) { f -> sigFile = f; rect = null; key++ }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (page.overlays.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            runner.run(str(R.string.removing_signatures), onDone = { _: Unit -> nav.popBackStack() }) {
                                val current = Library.get(docId) ?: return@run
                                val target = current.pages.first { it.id == pageId }
                                val updated = PageRenderer.reprocess(docId, target.copy(overlays = emptyList()), Settings.value.jpegQuality)
                                Library.save(current.copy(pages = current.pages.map { if (it.id == pageId) updated else it }))
                            }
                        }) {
                            Icon(Icons.Default.LayersClear, null)
                            Text(str(R.string.remove_signatures))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        val r = rect ?: return@Button
                        val f = sigFile ?: return@Button
                        runner.run(str(R.string.inserting_signature), onDone = { _: Unit -> nav.popBackStack() }) {
                            val current = Library.get(docId) ?: return@run
                            val target = current.pages.first { it.id == pageId }
                            // Podpis zapisujemy jako nakładkę - zostaje po zmianie filtra czy przycięcia.
                            val updated = PageRenderer.reprocess(
                                docId,
                                target.copy(overlays = target.overlays + Overlay(f.name, r.x, r.y, r.w, r.h)),
                                Settings.value.jpegQuality,
                            )
                            Library.save(current.copy(pages = current.pages.map { if (it.id == pageId) updated else it }))
                        }
                    }, enabled = rect != null && sigFile != null) {
                        Icon(Icons.Default.Check, null)
                        Text(str(R.string.insert_signature))
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Color(0xFF1A1D1C)).padding(12.dp), contentAlignment = Alignment.Center) {
            val pb = pageBmp
            val r = rect
            if (pb == null) {
                CircularProgressIndicator()
            } else {
                androidx.compose.runtime.key(key) {
                    SignaturePlacer(pb, sigBmp, r ?: NRect(0.6f, 0.75f, 0.3f, 0.1f), { rect = it }, Modifier.fillMaxSize())
                }
                if (sigFile == null) {
                    Surface(shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.TopCenter)) {
                        Text(str(R.string.add_signature_hint), Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
    BusyDialog(runner)
}
