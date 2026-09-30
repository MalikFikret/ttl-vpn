package com.malikfikret.ttlvpn

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

enum class AppLanguage(val tag: String?) {
    System(null),
    English("en"),
    Turkish("tr"),
    Arabic("ar");

    companion object {
        fun fromLocale(locale: Locale?): AppLanguage =
            entries.firstOrNull { it.tag != null && it.tag == locale?.language } ?: System
    }
}

// Per-app language without AppCompat.
// - Android 13+: LocaleManager. The system stores the choice (it also shows in system
//   Settings > Apps > TTL VPN > Language, via locales_config) and applies it to the whole
//   process, services included, so localized() is a no-op there.
// - Android 7-12: stored in DataStore; activities wrap their base context, and the
//   service, tile and widget build their text from localized(), which always reflects
//   the latest choice.
object AppLanguages {
    private const val TAG = "AppLanguages"

    // Android 7-12 only: read once per process (bounded blocking read), then cached.
    @Volatile
    private var cached: AppLanguage? = null

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) AppLanguage.System else AppLanguage.fromLocale(locales[0])
        } else {
            cached ?: readStored(context).also { cached = it }
        }

    // Android 13+: the system recreates activities itself. Android 7-12: the caller must
    // recreate its activity afterwards. Throws IOException if saving fails (7-12).
    suspend fun set(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(language.tag.orEmpty())
        } else {
            AppSettings.setLanguage(context, language)
            cached = language
        }
        // Placed widgets render their own text; redraw them in the new language.
        TtlWidgetProvider.updateAll(context)
    }

    // A context whose resources use the chosen language (and its layout direction).
    fun localized(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val tag = current(context).tag ?: return context
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag))) // Also sets the layout direction
        return context.createConfigurationContext(config)
    }

    // Main-thread disk read on Android 7-12 (normally a few ms), bounded so a slow
    // DataStore can never stall startup; falls back to System.
    private fun readStored(context: Context): AppLanguage {
        val language = try {
            runBlocking { withTimeoutOrNull(200) { AppSettings.language(context).first() } }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read the language setting", e)
            null
        }
        return language ?: AppLanguage.System
    }
}
