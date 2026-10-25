package com.radio.chinese.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.radio.chinese.domain.model.AppTheme
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * 应用级 UI 设置：主题、字号、界面形态、开机自启。
 * 与 RadioPreferences 共享同一 DataStore 实例，本类只负责键的读写。
 */
internal class SettingsPreferences(private val store: DataStore<Preferences>) {

    companion object {
        val KEY_THEME_KEY = stringPreferencesKey("theme_key")
        val KEY_THEME_MODE = intPreferencesKey("theme_mode")
        val KEY_FONT_SCALE = stringPreferencesKey("font_scale")
        val KEY_UI_MODE = intPreferencesKey("ui_mode")
        val KEY_BOOT_AUTOSTART = booleanPreferencesKey("boot_auto_start")
    }

    val theme: Flow<AppTheme> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            AppTheme.fromStoredValues(prefs[KEY_THEME_KEY], prefs[KEY_THEME_MODE] ?: 0)
        }

    val fontScaleKey: Flow<String> = store.data.map { it[KEY_FONT_SCALE] ?: "standard" }

    val uiMode: Flow<Int> = store.data.map { it[KEY_UI_MODE] ?: 0 }

    val bootAutoStart: Flow<Boolean> = store.data.map { it[KEY_BOOT_AUTOSTART] ?: false }

    suspend fun setThemeKey(key: String) { store.edit { it[KEY_THEME_KEY] = key } }
    suspend fun setFontScaleKey(key: String) { store.edit { it[KEY_FONT_SCALE] = key } }
    suspend fun setUiMode(mode: Int) { store.edit { it[KEY_UI_MODE] = mode } }
    suspend fun setBootAutoStart(enabled: Boolean) { store.edit { it[KEY_BOOT_AUTOSTART] = enabled } }
}
