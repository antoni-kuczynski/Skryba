package com.kuczynski.skryba.ui.tools

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.pdf.PdfRender
import com.kuczynski.skryba.pdf.PdfTools
import com.kuczynski.skryba.ui.Handoff
import com.kuczynski.skryba.ui.Tool
import com.kuczynski.skryba.ui.common.BackTopBar
import com.kuczynski.skryba.ui.common.BusyDialog
import com.kuczynski.skryba.ui.common.ResultSheet
import com.kuczynski.skryba.ui.common.rememberTaskRunner
import com.kuczynski.skryba.ui.scan.ToolButton
import com.kuczynski.skryba.ui.shareFilesQr
import com.kuczynski.skryba.ui.viewFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PageItem(val original: Int, val rotation: Int, val key: Int)

@Composable
fun OrganizeScreen(nav: NavController, tool: Tool) {
    val input = remember { Handoff.pdf }
    val runner = rememberTaskRunner()
    if (input == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val items = remember { mutableStateListOf<PageItem>() }
    val selected = remember { mutableStateListOf<Int>() }
    var result by remember { mutableStateOf<List<File>?>(null) }
    val count by produceState(-1) {
        value = withContext(Dispatchers.IO) { runCatching { PdfRender.pageCount(input.file, input.password) }.getOrDefault(0) }
    }
    LaunchedEffect(count) {
        if (count > 0 && items.isEmpty()) items.addAll((0 until count).map { PageItem(it, 0, it) })
    }

    fun rotateSelected(deg: Int) {
        for (i in items.indices) if (items[i].key in selected) items[i] = items[i].copy(rotation = (items[i].rotation + deg + 360) % 360)
    }

    fun move(delta: Int) {
        val k = selected.singleOrNull() ?: return
        val i = items.indexOfFirst { it.key == k }
        val j = i + delta
        if (i < 0 || j !in items.indices) return
        items.add(j, items.removeAt(i))
    }

    val hint = when (tool) {
        Tool.ROTATE -> str(R.string.hint_rotate)
        Tool.DELETE_PAGES -> str(R.string.hint_delete)
        else -> str(R.string.hint_organize)
    }

    Scaffold(
        topBar = {
            BackTopBar(tool.title, { nav.popBackStack() }, subtitle = input.label) {
                IconButton(onClick = {
                    if (selected.size == items.size) selected.clear() else { selected.clear(); selected.addAll(items.map { it.key }) }
                }) { Icon(Icons.Default.SelectAll, str(R.string.select_all)) }
                Button(
                    onClick = {
                        val order = items.map { it.original }
                        val rots = items.map { it.rotation }
                        runner.run(str(R.string.saving), onDone = { f: File -> result = listOf(f) }) {
                            val tmp = Library.tempFile("organize.pdf")
                            PdfTools.organize(input.file, order, rots, tmp, input.password)
                            Library.addFile(tmp, str(R.string.modified_suffix, input.label.substringBeforeLast('.')), move = true)
                        }
                    },
                    enabled = items.isNotEmpty(),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(Icons.Default.Save, null)
                    Text(str(R.string.save_spaced))
                }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding()) {
                Text(
                    if (selected.isEmpty()) hint else str(R.string.selected_n, selected.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val any = selected.isNotEmpty()
                    ToolButton(Icons.AutoMirrored.Filled.RotateLeft, str(R.string.rotate_left), any) { rotateSelected(-90) }
                    ToolButton(Icons.AutoMirrored.Filled.RotateRight, str(R.string.rotate_right), any) { rotateSelected(90) }
                    if (tool != Tool.ROTATE && tool != Tool.DELETE_PAGES) {
                        ToolButton(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.earlier), selected.size == 1) { move(-1) }
                        ToolButton(Icons.AutoMirrored.Filled.ArrowForward, str(R.string.later), selected.size == 1) { move(1) }
                    }
                    ToolButton(Icons.Default.Delete, str(R.string.delete), any && selected.size < items.size) {
                        items.removeAll { it.key in selected }
                        selected.clear()
                    }
                }
            }
        },
    ) { pad ->
        if (count < 0) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(pad).fillMaxSize(),
            ) {
                itemsIndexed(items, key = { _, it -> it.key }) { pos, item ->
                    val isSel = item.key in selected
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(10.dp))
                                .border(if (isSel) 3.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .clickable { if (isSel) selected.remove(item.key) else selected.add(item.key) },
                            contentAlignment = Alignment.Center,
                        ) {
                            PdfThumb(input.file, item.original, input.password, Modifier.fillMaxSize().padding(6.dp).rotate(item.rotation.toFloat()))
                            Icon(
                                if (isSel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                null,
                                tint = if (isSel) MaterialTheme.colorScheme.primary else Color.Gray,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                            )
                        }
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(top = 4.dp)) {
                            Text("${pos + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
        }
    }
    BusyDialog(runner)
    result?.let { files -> ResultSheet(files, onOpen = { nav.viewFile(it) }, onQr = { nav.shareFilesQr(it) }, onDismiss = { result = null }) }
}

@Composable
fun PdfThumb(file: File, index: Int, password: String?, modifier: Modifier = Modifier, maxSide: Int = 420) {
    val bmp by produceState<Bitmap?>(null, file, index) {
        value = withContext(Dispatchers.IO) { runCatching { PdfRender.render(file, index, maxSide, password = password) }.getOrNull() }
    }
    val b = bmp
    if (b == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.padding(24.dp)) }
    } else {
        Image(b.asImageBitmap(), str(R.string.page_n, index + 1), contentScale = ContentScale.Fit, modifier = modifier)
    }
}
