package com.kuczynski.skryba.ui.settings

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.PageSize
import com.kuczynski.skryba.data.Settings
import com.kuczynski.skryba.i18n.Lang
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.ocr.OcrEngine
import com.kuczynski.skryba.ui.common.BackTopBar
import com.kuczynski.skryba.ui.common.BusyDialog
import com.kuczynski.skryba.ui.common.SectionTitle
import com.kuczynski.skryba.ui.common.TextInputDialog
import com.kuczynski.skryba.ui.common.rememberTaskRunner
import com.kuczynski.skryba.ui.common.toast
import com.kuczynski.skryba.ui.doc.LanguageDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(nav: NavController) {
    val s by Settings.state.collectAsState()
    val context = LocalContext.current
    val runner = rememberTaskRunner()
    var langDialog by remember { mutableStateOf(false) }
    var importedModel by remember { mutableStateOf<File?>(null) }
    var language by remember { mutableStateOf(Lang.current(context)) }

    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runner.run(str(R.string.creating_backup), onDone = { _: Unit -> toast(context, str(R.string.backup_saved)) }) {
            context.contentResolver.openOutputStream(uri)!!.use { Library.exportBackup(it) }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runner.run(str(R.string.restoring_backup), onDone = { n: Int -> toast(context, str(R.string.restored_n, n)) }) {
            context.contentResolver.openInputStream(uri)!!.use { Library.importBackup(it) }
        }
    }
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runner.run(str(R.string.loading_model), onDone = { f: File -> importedModel = f }) {
            Library.importUri(context, uri, Library.tempDir)
        }
    }

    Scaffold(topBar = { BackTopBar(str(R.string.settings), { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle(str(R.string.sec_app_language))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to str(R.string.lang_system), "pl" to "Polski", "en" to "English", "de" to "Deutsch", "fr" to "Français", "pt" to "Português").forEach { (tag, label) ->
                    FilterChip(language == tag, {
                        language = tag
                        Lang.set(context, tag)
                        // Na Androidzie 13+ system sam odświeża aktywność po zmianie języka aplikacji.
                        if (Build.VERSION.SDK_INT < 33) (context as? Activity)?.recreate()
                    }, label = { Text(label) })
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))

            SectionTitle(str(R.string.sec_ocr))
            SettingRow(Icons.Default.Language, str(R.string.text_languages), s.ocrLangs.joinToString(", ") { OcrEngine.label(it) }) { langDialog = true }
            SettingRow(
                Icons.Default.FileDownload,
                str(R.string.add_lang_file),
                str(R.string.add_lang_file_sub),
            ) { importModel.launch(arrayOf("*/*")) }
            SwitchRow(str(R.string.pdf_text_layer), str(R.string.pdf_text_layer_sub), s.searchablePdf) {
                Settings.update { st -> st.copy(searchablePdf = it) }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))

            SectionTitle(str(R.string.sec_scanning))
            SwitchRow(str(R.string.live_detection), str(R.string.live_detection_sub), s.liveDetection) {
                Settings.update { st -> st.copy(liveDetection = it) }
            }
            SwitchRow(str(R.string.batch_mode), str(R.string.batch_mode_sub), s.batchMode) {
                Settings.update { st -> st.copy(batchMode = it) }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))

            SectionTitle("PDF")
            Text(str(R.string.page_size), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageSize.entries.forEach { ps ->
                    FilterChip(s.pageSize == ps, { Settings.update { it.copy(pageSize = ps) } }, label = { Text(ps.label) })
                }
            }
            Text(str(R.string.image_quality_setting, s.jpegQuality), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyLarge)
            Slider(
                s.jpegQuality.toFloat(),
                { v -> Settings.update { it.copy(jpegQuality = v.toInt()) } },
                valueRange = 40f..100f,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))

            SectionTitle(str(R.string.sec_backup))
            SettingRow(Icons.Default.Backup, str(R.string.create_backup), str(R.string.create_backup_sub)) {
                backup.launch(str(R.string.backup_file, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())))
            }
            SettingRow(Icons.Default.Restore, str(R.string.restore_backup), str(R.string.restore_backup_sub)) {
                restore.launch(arrayOf("application/zip", "application/octet-stream"))
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))

            SectionTitle(str(R.string.sec_about))
            Text(
                str(R.string.about_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 24.dp),
            )
        }
    }
    BusyDialog(runner)
    if (langDialog) LanguageDialog(onDismiss = { langDialog = false }) { langDialog = false }
    importedModel?.let { f ->
        TextInputDialog(
            str(R.string.lang_code),
            f.name.substringBefore('.'),
            str(R.string.lang_code_hint),
            str(R.string.add),
            onDismiss = { importedModel = null },
        ) { code ->
            importedModel = null
            val clean = code.trim().lowercase().replace(Regex("[^a-z_]"), "")
            if (f.length() < 10_000) {
                toast(context, str(R.string.not_tess_model))
            } else {
                OcrEngine.importLanguage(context, f, clean)
                Settings.update { it.copy(ocrLangs = (it.ocrLangs + clean).distinct()) }
                toast(context, str(R.string.lang_added, clean))
            }
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
