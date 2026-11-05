package com.kuczynski.skryba.ui.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.Settings
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.imaging.DocumentDetector
import com.kuczynski.skryba.imaging.ImageIO
import com.kuczynski.skryba.imaging.PageRenderer
import com.kuczynski.skryba.scan.ScanSession
import com.kuczynski.skryba.ui.Routes
import com.kuczynski.skryba.ui.common.BackTopBar
import com.kuczynski.skryba.ui.common.BusyDialog
import com.kuczynski.skryba.ui.common.rememberTaskRunner
import com.kuczynski.skryba.ui.theme.Brand
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Przycinanie strony z bieżącej sesji skanowania. */
@Composable
fun CropScreen(nav: NavController, pageId: String) {
    val page = ScanSession.find(pageId)
    if (page == null) {
        nav.popBackStack()
        return
    }
    CropContent(
        title = str(R.string.crop),
        file = page.file,
        initial = page.quad,
        confirmLabel = str(R.string.next),
        onBack = { nav.popBackStack() },
    ) { quad ->
        page.quad = quad
        if (nav.previousBackStackEntry?.destination?.route == Routes.REVIEW) {
            nav.popBackStack()
        } else {
            nav.navigate(Routes.REVIEW) { popUpTo(Routes.HOME) }
        }
    }
}

/** Ponowne przycięcie strony zapisanego dokumentu (z oryginalnego zdjęcia). */
@Composable
fun PageCropScreen(nav: NavController, docId: String, pageId: String) {
    val doc = Library.get(docId)
    val page = doc?.pages?.firstOrNull { it.id == pageId }
    val runner = rememberTaskRunner()
    if (doc == null || page == null) {
        nav.popBackStack()
        return
    }
    CropContent(
        title = str(R.string.crop_page),
        file = Library.pageFile(docId, page.original),
        initial = page.quad,
        confirmLabel = str(R.string.save),
        onBack = { nav.popBackStack() },
    ) { quad ->
        runner.run(str(R.string.processing), onDone = { _: Unit -> nav.popBackStack() }) {
            val current = Library.get(docId) ?: return@run
            val updated = PageRenderer.reprocess(docId, page.copy(quad = quad), Settings.value.jpegQuality)
            Library.save(current.copy(pages = current.pages.map { if (it.id == pageId) updated else it }))
        }
    }
    BusyDialog(runner)
}

@Composable
private fun CropContent(
    title: String,
    file: File,
    initial: FloatArray?,
    confirmLabel: String,
    onBack: () -> Unit,
    onConfirm: (FloatArray?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bitmap by produceState<android.graphics.Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { ImageIO.decode(file, 1800) }.getOrNull() }
    }
    var quad by remember { mutableStateOf(initial ?: DocumentDetector.fullQuad()) }
    var detecting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { BackTopBar(title, onBack) },
        containerColor = Color(0xFF111413),
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    val b = bitmap ?: return@TextButton
                    detecting = true
                    scope.launch {
                        val q = withContext(Dispatchers.Default) { DocumentDetector.detect(b) }
                        detecting = false
                        quad = q ?: DocumentDetector.fullQuad()
                    }
                }) {
                    Icon(Icons.Default.AutoFixHigh, null)
                    Text("  " + str(R.string.auto))
                }
                TextButton(onClick = { quad = DocumentDetector.fullQuad() }) {
                    Icon(Icons.Default.CropFree, null)
                    Text("  " + str(R.string.whole))
                }
                Button(onClick = { onConfirm(if (DocumentDetector.isFull(quad)) null else quad) }) {
                    Icon(Icons.Default.Check, null)
                    Text("  $confirmLabel")
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            val b = bitmap
            if (b == null || detecting) {
                CircularProgressIndicator()
            }
            if (b != null) {
                val img = remember(b) { b.asImageBitmap() }
                QuadEditor(img, quad, { quad = it }, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Edytor czworokąta: narożniki i środki boków można przeciągać.
 * Podczas przeciągania lupa pokazuje powiększony fragment pod palcem.
 */
@Composable
fun QuadEditor(bitmap: ImageBitmap, quad: FloatArray, onChange: (FloatArray) -> Unit, modifier: Modifier = Modifier) {
    var active by remember { mutableIntStateOf(-1) }
    val current by rememberUpdatedState(quad)
    val change by rememberUpdatedState(onChange)
    BoxWithConstraints(modifier) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val pad = with(density) { 28.dp.toPx() }
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val s = min((cw - 2 * pad) / bitmap.width, (ch - 2 * pad) / bitmap.height)
        val dw = bitmap.width * s
        val dh = bitmap.height * s
        val left = (cw - dw) / 2
        val top = (ch - dh) / 2
        val handleR = with(density) { 11.dp.toPx() }
        val touchR = with(density) { 44.dp.toPx() }
        val loupeR = with(density) { 58.dp.toPx() }

        fun pt(q: FloatArray, i: Int) = Offset(left + q[i * 2] * dw, top + q[i * 2 + 1] * dh)
        fun handle(q: FloatArray, idx: Int): Offset =
            if (idx < 4) pt(q, idx) else (pt(q, idx - 4) + pt(q, (idx - 3) % 4)) / 2f

        Canvas(
            Modifier.fillMaxSize().pointerInput(bitmap, s) {
                detectDragGestures(
                    onDragStart = { pos ->
                        var best = -1
                        var bestD = touchR
                        for (i in 0 until 8) {
                            val d = (handle(current, i) - pos).getDistance()
                            // Narożniki mają pierwszeństwo przed środkami boków.
                            val score = if (i < 4) d * 0.8f else d
                            if (score < bestD) { bestD = score; best = i }
                        }
                        active = best
                    },
                    onDrag = { c, drag ->
                        c.consume()
                        val a = active
                        if (a < 0) return@detectDragGestures
                        val q = current.copyOf()
                        fun move(i: Int) {
                            q[i * 2] = (q[i * 2] + drag.x / dw).coerceIn(0f, 1f)
                            q[i * 2 + 1] = (q[i * 2 + 1] + drag.y / dh).coerceIn(0f, 1f)
                        }
                        if (a < 4) move(a) else { move(a - 4); move((a - 3) % 4) }
                        change(q)
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                )
            },
        ) {
            drawImage(bitmap, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(dw.roundToInt(), dh.roundToInt()))
            val path = Path().apply {
                moveTo(pt(quad, 0).x, pt(quad, 0).y)
                for (i in 1..3) lineTo(pt(quad, i).x, pt(quad, i).y)
                close()
            }
            clipPath(path, ClipOp.Difference) {
                drawRect(Color.Black.copy(alpha = 0.5f), Offset(left, top), Size(dw, dh))
            }
            drawPath(path, Brand, style = Stroke(width = 3.dp.toPx()))
            for (i in 0 until 8) {
                val p = handle(quad, i)
                val r = if (i < 4) handleR else handleR * 0.65f
                drawCircle(Color.White, r, p)
                drawCircle(Brand, r, p, style = Stroke(width = 2.5.dp.toPx()))
            }
            // Lupa
            val a = active
            if (a >= 0) {
                val p = handle(quad, a)
                val center = if (p.x < cw / 2 && p.y < ch / 2.5f) Offset(cw - loupeR - 16.dp.toPx(), loupeR + 16.dp.toPx())
                else Offset(loupeR + 16.dp.toPx(), loupeR + 16.dp.toPx())
                val zoom = 2.5f
                val half = loupeR / zoom / s
                val bx = (p.x - left) / s
                val by = (p.y - top) / s
                val sx0 = bx - half
                val sy0 = by - half
                val cx0 = max(0f, sx0)
                val cy0 = max(0f, sy0)
                val cx1 = min(bitmap.width.toFloat(), bx + half)
                val cy1 = min(bitmap.height.toFloat(), by + half)
                val k = loupeR / half
                val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, loupeR)) }
                clipPath(circle) {
                    drawRect(Color.Black, center - Offset(loupeR, loupeR), Size(loupeR * 2, loupeR * 2))
                    if (cx1 > cx0 && cy1 > cy0) {
                        drawImage(
                            bitmap,
                            srcOffset = IntOffset(cx0.roundToInt(), cy0.roundToInt()),
                            srcSize = IntSize((cx1 - cx0).roundToInt().coerceAtLeast(1), (cy1 - cy0).roundToInt().coerceAtLeast(1)),
                            dstOffset = IntOffset((center.x - loupeR + (cx0 - sx0) * k).roundToInt(), (center.y - loupeR + (cy0 - sy0) * k).roundToInt()),
                            dstSize = IntSize(((cx1 - cx0) * k).roundToInt().coerceAtLeast(1), ((cy1 - cy0) * k).roundToInt().coerceAtLeast(1)),
                        )
                    }
                }
                drawCircle(Color.White, loupeR, center, style = Stroke(width = 3.dp.toPx()))
                drawLine(Brand, center - Offset(12.dp.toPx(), 0f), center + Offset(12.dp.toPx(), 0f), strokeWidth = 2.dp.toPx())
                drawLine(Brand, center - Offset(0f, 12.dp.toPx()), center + Offset(0f, 12.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
        }
    }
}
