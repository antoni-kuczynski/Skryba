package com.kuczynski.skryba.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kuczynski.skryba.R
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.share.ShareServer
import com.kuczynski.skryba.share.Sharing
import com.kuczynski.skryba.ui.Handoff
import com.kuczynski.skryba.ui.common.BackTopBar
import com.kuczynski.skryba.ui.common.toast
import kotlinx.coroutines.delay

@Composable
fun QrShareScreen(nav: NavController) {
    val share = remember { Handoff.share }
    val context = LocalContext.current
    val view = LocalView.current
    if (share == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    var token by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val t = runCatching { ShareServer.share(share) }.getOrNull()
        token = t
        view.keepScreenOn = true
        ShareServer.onUpload = { f -> toast(context, str(R.string.received_file, f.name)) }
        onDispose {
            view.keepScreenOn = false
            ShareServer.onUpload = null
            t?.let { ShareServer.unshare(it) }
        }
    }
    // Adres może się zmienić po podłączeniu do Wi-Fi lub włączeniu hotspotu.
    LaunchedEffect(token) {
        val t = token ?: return@LaunchedEffect
        while (true) {
            url = ShareServer.url(t)
            delay(3000)
        }
    }

    Scaffold(topBar = { BackTopBar(str(R.string.share_via_link), { nav.popBackStack() }, subtitle = share.title) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val u = url
            if (token == null) {
                Text(str(R.string.share_start_failed), style = MaterialTheme.typography.titleMedium)
            } else if (u == null) {
                Icon(Icons.Default.WifiOff, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text(str(R.string.no_local_network), style = MaterialTheme.typography.titleLarge)
                Text(
                    str(R.string.no_local_network_text),
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(android.content.Intent(AndroidSettings.ACTION_WIFI_SETTINGS)) }
                }) { Text(str(R.string.wifi_settings)) }
            } else {
                val qr = remember(u) { Sharing.qr(u).asImageBitmap() }
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 4.dp) {
                    Image(qr, str(R.string.qr_code), Modifier.padding(16.dp).size(260.dp))
                }
                Text(str(R.string.scan_with_other), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(u, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText(str(R.string.link), u))
                        toast(context, str(R.string.link_copied))
                    }) { Icon(Icons.Default.ContentCopy, null); Text(str(R.string.copy_link)) }
                    Button(onClick = { Sharing.shareText(context, u) }) { Icon(Icons.Default.Share, null); Text(str(R.string.send_link)) }
                }
                Spacer(Modifier.height(4.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(str(R.string.how_it_works), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        val lines = buildList {
                            add(str(R.string.how1))
                            add(str(R.string.how2))
                            add(str(R.string.how3))
                            if (share.allowUpload) add(str(R.string.how4))
                        }
                        lines.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                    }
                }
            }
        }
    }
}
