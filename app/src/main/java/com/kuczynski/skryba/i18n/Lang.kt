package com.kuczynski.skryba.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Język aplikacji: "" = jak w telefonie, "pl" albo "en".
 * Na Androidzie 13+ korzystamy z systemowego języka per aplikacja (LocaleManager),
 * na starszych podmieniamy konfigurację kontekstu.
 */
object Lang {
    private const val PREFS = "settings"
    private const val KEY = "language"

    @Volatile
    private var ctx: Context? = null

    /** Kontekst, z którego czytane są teksty (aktywność, a bez niej aplikacja). */
    fun attach(context: Context) {
        ctx = context
    }

    fun context(): Context = ctx ?: error("Lang not initialized")

    fun saved(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val list = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (list.isEmpty) "" else list[0].language
        }
        return saved(context)
    }

    fun set(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
    }

    /** Dla Androida < 13: kontekst z wybranym językiem (wywoływane w attachBaseContext). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = saved(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val conf = Configuration(base.resources.configuration)
        conf.setLocales(LocaleList(locale))
        return base.createConfigurationContext(conf)
    }

    /** Czy interfejs jest po polsku (do tekstów generowanych w kodzie, np. nazw języków OCR). */
    val isPolish: Boolean
        get() = context().resources.configuration.locales[0].language == "pl"
}

fun str(@StringRes id: Int): String = Lang.context().getString(id)

fun str(@StringRes id: Int, vararg args: Any): String = Lang.context().getString(id, *args)
