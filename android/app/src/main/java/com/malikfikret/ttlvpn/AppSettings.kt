package com.malikfikret.ttlvpn

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// The delegate guarantees one DataStore instance per process; two instances on the
// same file would corrupt it. A corrupt file is reset to defaults instead of crashing.
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

enum class ThemeMode { System, Light, Dark }

object AppSettings {
    const val DEFAULT_TTL = 63
    val TTL_RANGE = 1..255

    private const val TAG = "AppSettings"
    private val TTL_KEY = intPreferencesKey("ttl")
    private val THEME_KEY = stringPreferencesKey("theme")
    private val QS_TILE_ADDED_KEY = booleanPreferencesKey("qs_tile_added")

    // For fire-and-forget writes from short-lived components (the tile service can be
    // unbound right after a callback, which would cancel a write in its own scope).
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun data(context: Context): Flow<Preferences> =
        context.applicationContext.settingsDataStore.data.catch { e ->
            if (e !is IOException) throw e
            Log.e(TAG, "Failed to read settings; using defaults", e)
            emit(emptyPreferences())
        }

    // Anything outside the valid range falls back to the default, so a bad stored
    // value can never reach the engine.
    fun ttl(context: Context): Flow<Int> =
        data(context)
            .map { prefs -> prefs[TTL_KEY]?.takeIf { it in TTL_RANGE } ?: DEFAULT_TTL }
            .distinctUntilChanged()

    // Throws IOException if the write fails.
    suspend fun setTtl(context: Context, ttl: Int) {
        require(ttl in TTL_RANGE) { "TTL out of range: $ttl" }
        context.applicationContext.settingsDataStore.edit { it[TTL_KEY] = ttl }
    }

    // Stored by enum name; an unknown name (e.g. from a future version) means System.
    fun themeMode(context: Context): Flow<ThemeMode> =
        data(context)
            .map { prefs ->
                ThemeMode.entries.firstOrNull { it.name == prefs[THEME_KEY] } ?: ThemeMode.System
            }
            .distinctUntilChanged()

    // Throws IOException if the write fails.
    suspend fun setThemeMode(context: Context, mode: ThemeMode) {
        context.applicationContext.settingsDataStore.edit { it[THEME_KEY] = mode.name }
    }

    // Tracked from TileService callbacks, only to hide the "Add to Quick Settings" button.
    // Android offers no query for it, so this is best effort (e.g. lost on clear data).
    fun qsTileAdded(context: Context): Flow<Boolean> =
        data(context).map { it[QS_TILE_ADDED_KEY] ?: false }.distinctUntilChanged()

    fun setQsTileAdded(context: Context, added: Boolean) {
        val appContext = context.applicationContext
        writeScope.launch {
            try {
                appContext.settingsDataStore.edit { it[QS_TILE_ADDED_KEY] = added }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to save tile state", e)
            }
        }
    }
}
