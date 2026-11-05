package com.kuczynski.skryba.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.ScanMode
import com.kuczynski.skryba.data.Settings
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.imaging.DocumentDetector
import com.kuczynski.skryba.imaging.ImageIO
import com.kuczynski.skryba.scan.ScanSession
import com.kuczynski.skryba.ui.Routes
import com.kuczynski.skryba.ui.common.ConfirmDialog
import com.kuczynski.skryba.ui.common.toast
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen(nav: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings by Settings.state.collectAsState()
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA) }

    var torch by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    val quadFlow = remember { MutableStateFlow<FloatArray?>(null) }
    val liveQuad by quadFlow.collectAsState()
    val lastDetect = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val batch = settings.batchMode
    val mode = ScanSession.mode

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
    val ratio43 = remember {
        ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(ratio43)
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(hasPermission, settings.liveDetection) {
        if (!hasPermission) return@LaunchedEffect
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().setResolutionSelector(ratio43).build()
        preview.surfaceProvider = previewView.surfaceProvider
        val useCases = mutableListOf(preview, imageCapture)
        if (settings.liveDetection) {
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastDetect.get() > 180) {
                    lastDetect.set(now)
                    runCatching {
                        val bmp = proxy.toBitmap()
                        val upright = ImageIO.rotate(bmp, proxy.imageInfo.rotationDegrees)
                        quadFlow.value = DocumentDetector.detect(upright)
                        if (upright !== bmp) upright.recycle()
                        bmp.recycle()
                    }
                }
                proxy.close()
            }
            useCases += analysis
        }
        provider.unbindAll()
        camera = runCatching {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
        }.onFailure { toast(context, str(R.string.err_camera_start, it.message.orEmpty())) }.getOrNull()
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    fun afterAdd(pageId: String) {
        when {
            mode == ScanMode.ID_CARD -> if (ScanSession.pages.size % 2 == 0) {
                nav.navigate(Routes.REVIEW) { popUpTo(Routes.HOME) }
            }
            batch -> {}
            else -> nav.navigate(Routes.crop(pageId))
        }
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        capturing = true
        scope.launch {
            withContext(Dispatchers.Default) { uris.forEach { runCatching { ScanSession.addFromUri(context, it) } } }
            capturing = false
            if (uris.size == 1 && !batch && mode != ScanMode.ID_CARD) {
                ScanSession.pages.lastOrNull()?.let { nav.navigate(Routes.crop(it.id)) }
            } else {
                nav.navigate(Routes.REVIEW) { popUpTo(Routes.HOME) }
            }
        }
    }

    fun capture() {
        if (capturing) return
        capturing = true
        val file = File(Library.sessionDir, "raw_${Library.newId()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    scope.launch {
                        val page = withContext(Dispatchers.Default) { runCatching { ScanSession.addCapture(file) }.getOrNull() }
                        capturing = false
                        if (page != null) afterAdd(page.id) else toast(context, str(R.string.err_save_photo))
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    toast(context, str(R.string.err_camera, exception.message.orEmpty()))
                }
            },
        )
    }

    BackHandler {
        if (ScanSession.pages.isNotEmpty() && nav.previousBackStackEntry?.destination?.route == Routes.HOME) confirmExit = true
        else nav.popBackStack()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = 64.dp, bottom = 200.dp)) {
                AndroidView({ previewView }, Modifier.fillMaxSize())
                // Obrys wykrytej kartki na podglądzie (podgląd i analiza mają proporcje 4:3).
                val q = liveQuad
                Canvas(Modifier.fillMaxSize()) {
                    val ar = 3f / 4f
                    val w: Float
                    val h: Float
                    if (size.width / size.height > ar) { h = size.height; w = h * ar } else { w = size.width; h = w / ar }
                    val left = (size.width - w) / 2
                    val top = (size.height - h) / 2
                    if (mode == ScanMode.ID_CARD) {
                        val cw = w * 0.86f
                        val ch = cw / 1.586f
                        drawRoundRect(
                            Color.White.copy(alpha = 0.8f),
                            Offset(left + (w - cw) / 2, top + (h - ch) / 2),
                            androidx.compose.ui.geometry.Size(cw, ch),
                            androidx.compose.ui.geometry.CornerRadius(24f, 24f),
                            style = Stroke(width = 4f),
                        )
                    }
                    if (q != null) {
                        val path = Path().apply {
                            moveTo(left + q[0] * w, top + q[1] * h)
                            for (i in 1..3) lineTo(left + q[i * 2] * w, top + q[i * 2 + 1] * h)
                            close()
                        }
                        drawPath(path, Color(0x3300E5C0))
                        drawPath(path, Color(0xFF00E5C0), style = Stroke(width = 6f))
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(str(R.string.camera_permission), color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text(str(R.string.allow)) }
                Spacer(Modifier.height(8.dp))
                Text(str(R.string.camera_permission_alt), color = Color.White.copy(alpha = 0.7f))
            }
        }

        // Górny pasek
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (ScanSession.pages.isNotEmpty() && nav.previousBackStackEntry?.destination?.route == Routes.HOME) confirmExit = true
                else nav.popBackStack()
            }) { Icon(Icons.Default.Close, str(R.string.close), tint = Color.White) }
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = { Settings.update { it.copy(batchMode = !it.batchMode) } },
                shape = RoundedCornerShape(50),
                color = if (batch) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ViewCarousel, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (batch) str(R.string.batch) else str(R.string.single), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
            IconButton(onClick = { torch = !torch }) {
                Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, str(R.string.torch), tint = Color.White)
            }
        }

        // Dolny panel: tryby, migawka, galeria, gotowe
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black).navigationBarsPadding().padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (mode == ScanMode.ID_CARD) (if (ScanSession.pages.size % 2 == 0) str(R.string.id_front) else str(R.string.id_back))
                else mode.hint,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
            )
            val listState = rememberLazyListState()
            LaunchedEffect(Unit) { listState.scrollToItem((ScanMode.entries.indexOf(mode) - 1).coerceAtLeast(0)) }
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(ScanMode.entries) { _, m ->
                    val selected = m == mode
                    Text(
                        m.label,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Color.White else Color.Transparent)
                            .clickable(enabled = ScanSession.pages.isEmpty() || m == mode) { ScanSession.mode = m }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.size(56.dp),
                ) { Icon(Icons.Default.PhotoLibrary, str(R.string.gallery), tint = Color.White, modifier = Modifier.size(30.dp)) }

                Box(
                    Modifier.size(78.dp).border(BorderStroke(4.dp, Color.White), CircleShape).padding(7.dp)
                        .clip(CircleShape).background(if (capturing) Color.Gray else Color.White)
                        .clickable(enabled = hasPermission && !capturing) { capture() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (capturing) CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.primary)
                }

                val last = ScanSession.pages.lastOrNull()
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    if (last != null) {
                        BadgedBox(badge = { Badge { Text("${ScanSession.pages.size}") } }) {
                            AsyncImage(
                                model = last.file,
                                contentDescription = str(R.string.done),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, Color.White, RoundedCornerShape(8.dp))
                                    .clickable { nav.navigate(Routes.REVIEW) { popUpTo(Routes.HOME) } },
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmExit) {
        ConfirmDialog(str(R.string.discard_scan_q), str(R.string.discard_photos_text, ScanSession.pages.size), str(R.string.discard), { confirmExit = false }) {
            confirmExit = false
            ScanSession.start(ScanSession.mode)
            nav.popBackStack()
        }
    }
}
