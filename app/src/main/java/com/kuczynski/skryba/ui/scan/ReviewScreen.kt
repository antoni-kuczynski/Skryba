package com.kuczynski.skryba.ui.scan

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.FilterType
import com.kuczynski.skryba.data.ScanDocument
import com.kuczynski.skryba.data.ScanMode
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.imaging.PageRenderer
import com.kuczynski.skryba.scan.ScanSession
import com.kuczynski.skryba.scan.SessionPage
import com.kuczynski.skryba.ui.Routes
import com.kuczynski.skryba.ui.common.BackTopBar
import com.kuczynski.skryba.ui.common.BusyDialog
import com.kuczynski.skryba.ui.common.ConfirmDialog
import com.kuczynski.skryba.ui.common.rememberTaskRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReviewScreen(nav: NavController) {
    val pages = ScanSession.pages
    val runner = rememberTaskRunner()
    val scope = rememberCoroutineScope()
    var confirmExit by remember { mutableStateOf(false) }
    val pager = rememberPagerState { pages.size }
    val mode = ScanSession.mode

    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(pages.size) {
        // Po zapisie sesja jest pusta, ale wtedy nawigacją zajmuje się save().
        if (pages.isEmpty() && !saving) nav.popBackStack(Routes.HOME, false)
    }
    BackHandler { confirmExit = true }

    fun save() {
        saving = true
        runner.run(str(R.string.saving_document), onDone = { doc: ScanDocument ->
            if (mode == ScanMode.TEXT) {
                nav.navigate(Routes.ocr(doc.id)) { popUpTo(Routes.HOME) }
            } else {
                nav.navigate(Routes.doc(doc.id)) { popUpTo(Routes.HOME) }
            }
        }, onError = { saving = false }) { p -> ScanSession.save(p) }
    }

    val current: SessionPage? = pages.getOrNull(pager.currentPage.coerceAtMost(pages.size - 1))

    Scaffold(
        topBar = {
            BackTopBar(
                title = if (pages.isEmpty()) str(R.string.doc_prefix_scan) else str(R.string.page_x_of_y, pager.currentPage + 1, pages.size),
                subtitle = mode.label,
                onBack = { confirmExit = true },
            ) {
                Button(onClick = { save() }, enabled = pages.isNotEmpty(), modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Default.Check, null)
                    Text("  " + if (mode == ScanMode.TEXT) str(R.string.recognize) else str(R.string.save))
                }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
                if (mode == ScanMode.BOOK) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(str(R.string.split_spread), Modifier.weight(1f))
                        Switch(ScanSession.splitBook, { ScanSession.splitBook = it })
                    }
                }
                if (mode == ScanMode.ID_CARD) {
                    Text(
                        str(R.string.id_compose_info),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(FilterType.entries) { f ->
                        FilterChip(
                            selected = current?.filter == f,
                            onClick = { current?.filter = f },
                            label = { Text(f.label) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToolButton(Icons.Default.Crop, str(R.string.crop)) { current?.let { nav.navigate(Routes.crop(it.id)) } }
                    ToolButton(Icons.AutoMirrored.Filled.RotateLeft, str(R.string.rotate_left)) { current?.let { it.rotation = (it.rotation + 270) % 360 } }
                    ToolButton(Icons.AutoMirrored.Filled.RotateRight, str(R.string.rotate_right)) { current?.let { it.rotation = (it.rotation + 90) % 360 } }
                    ToolButton(Icons.Default.DoneAll, str(R.string.filter_for_all)) { current?.let { c -> pages.forEach { it.filter = c.filter } } }
                    ToolButton(Icons.Default.Delete, str(R.string.delete)) { current?.let { ScanSession.remove(it) } }
                    ToolButton(Icons.Default.AddAPhoto, str(R.string.add)) { nav.navigate(Routes.CAMERA) }
                }
            }
        },
    ) { pad ->
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 24.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) { i ->
            val p = pages.getOrNull(i) ?: return@HorizontalPager
            PagePreview(p)
        }
    }
    BusyDialog(runner)
    if (confirmExit) {
        ConfirmDialog(str(R.string.discard_scan_q), str(R.string.discard_scan_text), str(R.string.discard), { confirmExit = false }) {
            confirmExit = false
            scope.launch {
                ScanSession.start(mode)
                nav.popBackStack(Routes.HOME, false)
            }
        }
    }
}

@Composable
private fun PagePreview(p: SessionPage) {
    val key = ScanSession.previewKey(p)
    // Stan produceState nie jest resetowany przy zmianie klucza, więc przy każdej zmianie
    // (filtr, obrót, przycięcie) bierzemy podgląd z pamięci albo liczymy go od nowa.
    // Do czasu przeliczenia zostaje poprzedni obraz z kółkiem postępu.
    var busy by remember { mutableStateOf(false) }
    val bmp by produceState<Bitmap?>(ScanSession.previews[key], key) {
        val cached = synchronized(ScanSession.previews) { ScanSession.previews[key] }
        if (cached != null) {
            value = cached
            return@produceState
        }
        busy = true
        val fresh = withContext(Dispatchers.Default) {
            runCatching { PageRenderer.render(p.file, p.quad, p.filter, p.rotation, maxSide = 1400) }.getOrNull()
        }
        if (fresh != null) {
            synchronized(ScanSession.previews) {
                // Prosty limit pamięci podglądów.
                if (ScanSession.previews.size > 16) ScanSession.previews.clear()
                ScanSession.previews[key] = fresh
            }
            value = fresh
        }
        busy = false
    }
    Box(Modifier.fillMaxSize().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Surface(shadowElevation = 6.dp, color = Color.White) {
                Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit)
            }
        }
        if (b == null || busy) CircularProgressIndicator()
    }
}

@Composable
fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}
