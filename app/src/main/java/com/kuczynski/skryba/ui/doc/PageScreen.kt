package com.kuczynski.skryba.ui.doc

import android.graphics.Bitmap
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
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.FilterType
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.Page
import com.kuczynski.skryba.data.Settings
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.imaging.ImageIO
import com.kuczynski.skryba.imaging.PageRenderer
import com.kuczynski.skryba.scan.DocActions
import com.kuczynski.skryba.share.Sharing
import com.kuczynski.skryba.ui.Routes
import com.kuczynski.skryba.ui.common.BackTopBar
import com.kuczynski.skryba.ui.common.BusyDialog
import com.kuczynski.skryba.ui.common.ConfirmDialog
import com.kuczynski.skryba.ui.common.ZoomableImage
import com.kuczynski.skryba.ui.common.rememberTaskRunner
import com.kuczynski.skryba.ui.scan.ToolButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PageScreen(nav: NavController, docId: String, startIndex: Int) {
    val docs by Library.docs.collectAsState()
    val doc = docs.firstOrNull { it.id == docId }
    val context = LocalContext.current
    val runner = rememberTaskRunner()
    var deleting by remember { mutableStateOf(false) }
    val pager = rememberPagerState(startIndex.coerceAtLeast(0)) { doc?.pages?.size ?: 0 }

    LaunchedEffect(doc == null) { if (doc == null) nav.popBackStack() }
    if (doc == null || doc.pages.isEmpty()) return
    val index = pager.currentPage.coerceIn(0, doc.pages.size - 1)
    val page = doc.pages[index]

    fun update(transform: (Page) -> Page) {
        runner.run(str(R.string.processing_page), onDone = { _: Unit -> }) {
            val current = Library.get(docId) ?: return@run
            val target = current.pages.firstOrNull { it.id == page.id } ?: return@run
            val updated = PageRenderer.reprocess(docId, transform(target), Settings.value.jpegQuality)
            Library.save(current.copy(pages = current.pages.map { if (it.id == page.id) updated else it }))
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(str(R.string.page_x_of_y, index + 1, doc.pages.size), { nav.popBackStack() }, subtitle = doc.name) {
                Button(onClick = { nav.popBackStack() }, modifier = Modifier.padding(end = 8.dp)) { Text(str(R.string.ok)) }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(FilterType.entries) { f ->
                        FilterChip(
                            selected = page.filter == f,
                            onClick = { if (page.filter != f) update { it.copy(filter = f) } },
                            label = { Text(f.label) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToolButton(Icons.Default.Crop, str(R.string.crop)) { nav.navigate(Routes.pageCrop(docId, page.id)) }
                    ToolButton(Icons.AutoMirrored.Filled.RotateRight, str(R.string.rotate)) { update { it.copy(rotation = (it.rotation + 90) % 360) } }
                    ToolButton(Icons.Default.TextFields, str(R.string.text)) { nav.navigate(Routes.ocr(docId, index)) }
                    ToolButton(Icons.Default.Draw, str(R.string.sign)) { nav.navigate(Routes.sign(docId, page.id)) }
                    ToolButton(Icons.Default.Share, str(R.string.share)) { Sharing.share(context, DocActions.tempJpgs(doc, listOf(index))) }
                    ToolButton(Icons.Default.Delete, str(R.string.delete)) { deleting = true }
                }
            }
        },
    ) { pad ->
        HorizontalPager(pager, Modifier.padding(pad).fillMaxSize().background(Color(0xFF1A1D1C))) { i ->
            val p = doc.pages.getOrNull(i) ?: return@HorizontalPager
            val file = Library.pageFile(docId, p.processed)
            val bmp by produceState<Bitmap?>(null, file) {
                value = withContext(Dispatchers.IO) { runCatching { ImageIO.decode(file, 2600) }.getOrNull() }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b == null) CircularProgressIndicator() else ZoomableImage(b.asImageBitmap(), Modifier.fillMaxSize())
            }
        }
    }
    BusyDialog(runner)
    if (deleting) {
        ConfirmDialog(str(R.string.delete_page_q), str(R.string.delete_page_text, index + 1), str(R.string.delete), { deleting = false }) {
            deleting = false
            if (doc.pages.size == 1) {
                Library.delete(docId)
                nav.popBackStack(Routes.HOME, false)
            } else {
                Library.save(doc.copy(pages = doc.pages.filter { it.id != page.id }))
            }
        }
    }
}
