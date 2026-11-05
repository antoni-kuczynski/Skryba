package com.kuczynski.skryba.data

import android.content.Context
import android.content.SharedPreferences
import com.kuczynski.skryba.R
import com.kuczynski.skryba.i18n.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PageSize(val widthPt: Float, val heightPt: Float) {
    A4(595.28f, 841.89f),
    LETTER(612f, 792f),
    FIT(0f, 0f),
    ;

    val label: String
        get() = when (this) {
            A4 -> "A4"
            LETTER -> "Letter"
            FIT -> str(R.string.pagesize_fit)
        }
}

data class AppSettings(
    val ocrLangs: List<String> = listOf("eng"),
    val pageSize: PageSize = PageSize.A4,
    val jpegQuality: Int = 85,
    val liveDetection: Boolean = true,
    val batchMode: Boolean = false,
    val searchablePdf: Boolean = false,
    val sharePort: Int = 8765,
)

object Settings {
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state
    val value: AppSettings get() = _state.value

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _state.value = AppSettings(
            ocrLangs = prefs.getString("ocrLangs", "eng")!!.split('+').filter { it.isNotBlank() },
            pageSize = runCatching { PageSize.valueOf(prefs.getString("pageSize", "A4")!!) }.getOrDefault(PageSize.A4),
            jpegQuality = prefs.getInt("jpegQuality", 85),
            liveDetection = prefs.getBoolean("liveDetection", true),
            batchMode = prefs.getBoolean("batchMode", false),
            searchablePdf = prefs.getBoolean("searchablePdf", false),
            sharePort = prefs.getInt("sharePort", 8765),
        )
    }

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        _state.value = s
        prefs.edit()
            .putString("ocrLangs", s.ocrLangs.joinToString("+"))
            .putString("pageSize", s.pageSize.name)
            .putInt("jpegQuality", s.jpegQuality)
            .putBoolean("liveDetection", s.liveDetection)
            .putBoolean("batchMode", s.batchMode)
            .putBoolean("searchablePdf", s.searchablePdf)
            .putInt("sharePort", s.sharePort)
            .apply()
    }
}
