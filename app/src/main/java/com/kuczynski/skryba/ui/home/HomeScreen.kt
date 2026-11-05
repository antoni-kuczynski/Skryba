package com.kuczynski.skryba.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.ScanDocument
import com.kuczynski.skryba.data.ScanMode
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.scan.DocActions
import com.kuczynski.skryba.scan.ScanSession
import com.kuczynski.skryba.share.ShareServer
import com.kuczynski.skryba.share.Sharing
import com.kuczynski.skryba.ui.Routes
import com.kuczynski.skryba.ui.Tool
import com.kuczynski.skryba.ui.ToolGroup
import com.kuczynski.skryba.ui.common.BusyDialog
import com.kuczynski.skryba.ui.common.ConfirmDialog
import com.kuczynski.skryba.ui.common.EmptyState
import com.kuczynski.skryba.ui.common.ResultSheet
import com.kuczynski.skryba.ui.common.TextInputDialog
import com.kuczynski.skryba.ui.common.fileIcon
import com.kuczynski.skryba.ui.common.formatDate
import com.kuczynski.skryba.ui.common.formatSize
import com.kuczynski.skryba.ui.common.rememberTaskRunner
import com.kuczynski.skryba.ui.common.toast
import com.kuczynski.skryba.ui.openTool
import com.kuczynski.skryba.ui.shareFilesQr
import com.kuczynski.skryba.ui.shareQr
import com.kuczynski.skryba.ui.startScan
import com.kuczynski.skryba.ui.viewFile
import java.io.File

private enum class Tab(private val labelRes: Int) {
    DOCS(R.string.tab_docs), TOOLS(R.string.tab_tools), FILES(R.string.tab_files);

    val label: String get() = str(labelRes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController) {
    var tab by rememberSaveable { mutableStateOf(Tab.DOCS) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val runner = rememberTaskRunner()

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        ScanSession.start(ScanMode.DOCUMENT)
        runner.run(str(R.string.loading_photos), onDone = { _: Unit -> nav.navigate(Routes.REVIEW) }) { p ->
            uris.forEachIndexed { i, u -> p(i, uris.size); ScanSession.addFromUri(context, u) }
        }
    }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        runner.run(str(R.string.importing), onDone = { n: Int -> toast(context, str(R.string.imported_n, n)); tab = Tab.FILES }) {
            uris.count { u -> runCatching { Library.importUri(context, u) }.isSuccess }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(str(R.string.search_hint)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Default.DocumentScanner, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
                            Spacer(Modifier.width(10.dp))
                            Text("Skryba", fontWeight = FontWeight.Bold)
                        }
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Default.Close, str(R.string.close)) }
                    } else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, str(R.string.search)) }
                        IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Default.Settings, str(R.string.settings)) }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.DOCS -> Icons.Default.DocumentScanner
                                    Tab.TOOLS -> Icons.Default.Apps
                                    Tab.FILES -> Icons.Default.Folder
                                },
                                null,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.DOCS) {
                ExtendedFloatingActionButton(
                    onClick = { nav.startScan(ScanMode.DOCUMENT) },
                    icon = { Icon(Icons.Default.CameraAlt, null) },
                    text = { Text(str(R.string.scan)) },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.DOCS -> DocumentsTab(nav, query, onImport = {
                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                Tab.TOOLS -> ToolsTab(nav)
                Tab.FILES -> FilesTab(nav, query, onImport = { importFiles.launch(arrayOf("*/*")) })
            }
        }
    }
    BusyDialog(runner)
}

// ---------------- Dokumenty ----------------

@Composable
private fun DocumentsTab(nav: NavController, query: String, onImport: () -> Unit) {
    val docs by Library.docs.collectAsState()
    val context = LocalContext.current
    val runner = rememberTaskRunner()
    var renaming by remember { mutableStateOf<ScanDocument?>(null) }
    var deleting by remember { mutableStateOf<ScanDocument?>(null) }
    var result by remember { mutableStateOf<List<File>?>(null) }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) docs else docs.filter { it.name.lowercase().contains(q) || it.fullText.lowercase().contains(q) }

    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Icons.Default.CameraAlt, str(R.string.scan), Modifier.weight(1f)) { nav.startScan(ScanMode.DOCUMENT) }
                QuickAction(Icons.Default.PhotoLibrary, str(R.string.from_gallery), Modifier.weight(1f), onImport)
                QuickAction(Icons.Default.QrCode2, str(R.string.to_computer), Modifier.weight(1f)) {
                    nav.shareQr(ShareServer.Share(str(R.string.library_title), library = true, allowUpload = true))
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                if (q.isEmpty()) {
                    EmptyState(Icons.Default.DocumentScanner, str(R.string.no_documents), str(R.string.no_documents_text))
                } else {
                    EmptyState(Icons.Default.Search, str(R.string.no_results), str(R.string.no_match, query))
                }
            }
        }
        items(shown, key = { it.id }) { d ->
            DocRow(
                doc = d,
                query = q,
                onClick = { nav.navigate(Routes.doc(d.id)) },
                onRename = { renaming = d },
                onDelete = { deleting = d },
                onDuplicate = { Library.duplicate(d.id) },
                onSharePdf = {
                    runner.run(str(R.string.creating_pdf), onDone = { f: File -> Sharing.share(context, listOf(f)) }) { p -> DocActions.tempPdf(context, d, p) }
                },
                onQr = { nav.shareQr(ShareServer.Share(d.name, docIds = listOf(d.id))) },
            )
        }
    }
    renaming?.let { d ->
        TextInputDialog(str(R.string.rename), d.name, str(R.string.name), str(R.string.save), onDismiss = { renaming = null }) {
            Library.rename(d.id, it)
            renaming = null
        }
    }
    deleting?.let { d ->
        ConfirmDialog(str(R.string.delete_doc_q), str(R.string.delete_doc_text, d.name), str(R.string.delete), { deleting = null }) {
            Library.delete(d.id)
            deleting = null
        }
    }
    result?.let { files -> ResultSheet(files, onOpen = { nav.viewFile(it) }, onQr = { nav.shareFilesQr(it) }, onDismiss = { result = null }) }
    BusyDialog(runner)
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun DocRow(
    doc: ScanDocument,
    query: String,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onSharePdf: () -> Unit,
    onQr: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val first = doc.pages.firstOrNull()
        Box(
            Modifier.width(56.dp).aspectRatio(0.75f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (first != null) {
                AsyncImage(
                    model = Library.pageFile(doc.id, first.processed),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatDate(doc.modified)}  |  ${str(R.string.pages_short, doc.pages.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (query.isNotEmpty() && !doc.name.lowercase().contains(query)) {
                val text = doc.fullText
                val i = text.lowercase().indexOf(query)
                if (i >= 0) {
                    val snippet = text.substring((i - 30).coerceAtLeast(0), (i + query.length + 40).coerceAtMost(text.length)).replace('\n', ' ')
                    Text("...$snippet...", style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, str(R.string.more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(str(R.string.share_pdf)) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; onSharePdf() })
                DropdownMenuItem(text = { Text(str(R.string.link_qr)) }, leadingIcon = { Icon(Icons.Default.QrCode2, null) }, onClick = { menu = false; onQr() })
                DropdownMenuItem(text = { Text(str(R.string.rename)) }, leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(str(R.string.duplicate)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text(str(R.string.delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

// ---------------- Narzędzia ----------------

@Composable
private fun ToolsTab(nav: NavController) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        ToolGroup.entries.forEach { g ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "h_${g.name}") {
                Text(
                    g.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp),
                )
            }
            items(Tool.entries.filter { it.group == g }, key = { it.name }) { t -> ToolCard(t) { nav.openTool(t) } }
        }
    }
}

@Composable
private fun ToolCard(t: Tool, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().height(128.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(t.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.height(10.dp))
            Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- Pliki ----------------

@Composable
private fun FilesTab(nav: NavController, query: String, onImport: () -> Unit) {
    val files by Library.files.collectAsState()
    var renaming by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var actions by remember { mutableStateOf<File?>(null) }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) files else files.filter { it.name.lowercase().contains(q) }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(
                    onClick = { nav.shareQr(ShareServer.Share(str(R.string.library_title), library = true, allowUpload = true)) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(str(R.string.access_computer), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                str(R.string.access_computer_text),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Surface(onClick = onImport, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FileUpload, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(str(R.string.import_files), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                EmptyState(
                    Icons.Default.PictureAsPdf,
                    if (q.isEmpty()) str(R.string.no_files) else str(R.string.no_results),
                    if (q.isEmpty()) str(R.string.no_files_text) else str(R.string.no_match, query),
                )
            }
        }
        items(shown, key = { it.path }) { f ->
            Row(
                Modifier.fillMaxWidth().clickable { nav.viewFile(f) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(fileIcon(f), null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${formatSize(f.length())}  |  ${formatDate(f.lastModified())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FileMenu(onActions = { actions = f }, onRename = { renaming = f }, onDelete = { deleting = f })
            }
        }
    }
    renaming?.let { f ->
        TextInputDialog(str(R.string.rename), f.nameWithoutExtension, str(R.string.name), str(R.string.save), onDismiss = { renaming = null }) {
            Library.renameFile(f, it)
            renaming = null
        }
    }
    deleting?.let { f ->
        ConfirmDialog(str(R.string.delete_file_q), str(R.string.delete_file_text, f.name), str(R.string.delete), { deleting = null }) {
            Library.deleteFile(f)
            deleting = null
        }
    }
    actions?.let { f ->
        ResultSheet(listOf(f), title = f.name, onOpen = { nav.viewFile(it) }, onQr = { nav.shareFilesQr(it) }, onDismiss = { actions = null })
    }
}

@Composable
private fun FileMenu(onActions: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, str(R.string.more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(str(R.string.share_save)) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; onActions() })
            DropdownMenuItem(text = { Text(str(R.string.rename)) }, leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text(str(R.string.delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
        }
    }
}
