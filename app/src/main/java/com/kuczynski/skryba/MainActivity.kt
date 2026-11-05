package com.kuczynski.skryba

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.IntentCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.ScanMode
import com.kuczynski.skryba.i18n.Lang
import com.kuczynski.skryba.scan.ScanSession
import com.kuczynski.skryba.ui.Handoff
import com.kuczynski.skryba.ui.Routes
import com.kuczynski.skryba.ui.Tool
import com.kuczynski.skryba.ui.doc.DocumentScreen
import com.kuczynski.skryba.ui.doc.OcrScreen
import com.kuczynski.skryba.ui.doc.PageScreen
import com.kuczynski.skryba.ui.doc.SignPageScreen
import com.kuczynski.skryba.ui.files.FileViewerScreen
import com.kuczynski.skryba.ui.files.QrShareScreen
import com.kuczynski.skryba.ui.home.HomeScreen
import com.kuczynski.skryba.ui.scan.CameraScreen
import com.kuczynski.skryba.ui.scan.CropScreen
import com.kuczynski.skryba.ui.scan.PageCropScreen
import com.kuczynski.skryba.ui.scan.ReviewScreen
import com.kuczynski.skryba.ui.settings.SettingsScreen
import com.kuczynski.skryba.ui.theme.SkrybaTheme
import com.kuczynski.skryba.ui.tools.AiToolScreen
import com.kuczynski.skryba.ui.tools.OrganizeScreen
import com.kuczynski.skryba.ui.tools.PdfSignScreen
import com.kuczynski.skryba.ui.tools.ToolScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** Plik lub obrazy przekazane z innej aplikacji (Udostępnij / Otwórz w). */
    private val incoming = mutableStateOf<Intent?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Teksty czytamy z kontekstu aktywności - ma aktualny język interfejsu.
        Lang.attach(this)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming.value = intent
        setContent { SkrybaTheme { AppNav(incoming.value) { incoming.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent
    }
}

@Composable
private fun AppNav(incoming: Intent?, consumed: () -> Unit) {
    val nav = rememberNavController()

    val scope = rememberCoroutineScope()

    LaunchedEffect(incoming) {
        val intent = incoming ?: return@LaunchedEffect
        // consumed() zmienia klucz efektu i anulowałby go, więc praca idzie w osobnym zakresie.
        consumed()
        scope.launch { handleIncoming(intent, nav) }
    }

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
    ) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.CAMERA) { CameraScreen(nav) }
        composable("crop/{pageId}") { CropScreen(nav, it.arguments?.getString("pageId").orEmpty()) }
        composable(Routes.REVIEW) { ReviewScreen(nav) }
        composable("doc/{id}") { DocumentScreen(nav, it.arguments?.getString("id").orEmpty()) }
        composable("page/{docId}/{index}") {
            PageScreen(nav, it.arguments?.getString("docId").orEmpty(), it.arguments?.getString("index")?.toIntOrNull() ?: 0)
        }
        composable("pagecrop/{docId}/{pageId}") {
            PageCropScreen(nav, it.arguments?.getString("docId").orEmpty(), it.arguments?.getString("pageId").orEmpty())
        }
        composable("ocr/{docId}?page={page}", arguments = listOf(navArgument("page") { type = NavType.IntType; defaultValue = -1 })) {
            OcrScreen(nav, it.arguments?.getString("docId").orEmpty(), it.arguments?.getInt("page") ?: -1)
        }
        composable("sign/{docId}/{pageId}") {
            SignPageScreen(nav, it.arguments?.getString("docId").orEmpty(), it.arguments?.getString("pageId").orEmpty())
        }
        composable("tool/{tool}") { ToolScreen(nav, Tool.valueOf(it.arguments?.getString("tool").orEmpty())) }
        composable("organize/{tool}") { OrganizeScreen(nav, Tool.valueOf(it.arguments?.getString("tool").orEmpty())) }
        composable("ai/{tool}") { AiToolScreen(nav, Tool.valueOf(it.arguments?.getString("tool").orEmpty())) }
        composable(Routes.PDF_SIGN) { PdfSignScreen(nav) }
        composable(Routes.VIEW) { FileViewerScreen(nav) }
        composable(Routes.QR) { QrShareScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
    }
}

/** Plik lub zdjęcia przekazane przez "Udostępnij" / "Otwórz w" z innej aplikacji. */
private suspend fun handleIncoming(intent: Intent, nav: NavHostController) {
    val ctx = Library.appContext
    val uris: List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        else -> emptyList()
    }
    if (uris.isEmpty()) return
    val type = intent.type ?: ctx.contentResolver.getType(uris[0]) ?: ""
    if (type.startsWith("image/")) {
        // Zdjęcia z innej aplikacji trafiają do nowego skanu.
        ScanSession.start(ScanMode.DOCUMENT)
        withContext(Dispatchers.Default) { uris.forEach { runCatching { ScanSession.addFromUri(ctx, it) }.onFailure { e -> e.printStackTrace() } } }
        if (ScanSession.pages.isNotEmpty()) nav.navigate(Routes.REVIEW)
    } else {
        val files = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { Library.importUri(ctx, it) }.getOrNull() } }
        files.firstOrNull()?.let {
            Handoff.viewFile = it
            nav.navigate(Routes.VIEW)
        }
    }
}
