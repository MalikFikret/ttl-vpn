package com.malikfikret.ttlvpn

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// The delegate guarantees one DataStore instance per process; two instances on the
// same file would corrupt it. A corrupt file is reset to defaults instead of crashing.
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

object TtlSettings {
    const val DEFAULT_TTL = 63
    val TTL_RANGE = 1..255

    private const val TAG = "TtlSettings"
    private val TTL_KEY = intPreferencesKey("ttl")

    // Anything outside the valid range falls back to the default, so a bad stored
    // value can never reach the engine.
    fun ttl(context: Context): Flow<Int> =
        context.applicationContext.settingsDataStore.data
            .catch { e ->
                if (e !is IOException) throw e
                Log.e(TAG, "Failed to read settings; using defaults", e)
                emit(emptyPreferences())
            }
            .map { prefs -> prefs[TTL_KEY]?.takeIf { it in TTL_RANGE } ?: DEFAULT_TTL }
            .distinctUntilChanged()

    // Throws IOException if the write fails.
    suspend fun setTtl(context: Context, ttl: Int) {
        require(ttl in TTL_RANGE) { "TTL out of range: $ttl" }
        context.applicationContext.settingsDataStore.edit { it[TTL_KEY] = ttl }
    }
}
