package com.radio.chinese.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.radio.chinese.domain.model.AppTheme
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * 应用级 UI 设置：主题、字号、界面形态、开机自启。
 * 与 RadioPreferences 共享同一 DataStore 实例，本类只负责键的读写。
 */
class SettingsPreferences(private val store: DataStore<Preferences>) {

    companion object {
        val KEY_THEME_KEY = stringPreferencesKey("theme_key")
        val KEY_THEME_MODE = intPreferencesKey("theme_mode")
        val KEY_FONT_SCALE = stringPreferencesKey("font_scale")
        val KEY_UI_MODE = intPreferencesKey("ui_mode")
        val KEY_BOOT_AUTOSTART = booleanPreferencesKey("boot_auto_start")
        val KEY_IPTV_ADULT = booleanPreferencesKey("iptv_adult_enabled")
        val KEY_IPTV_ALL_LANGUAGES = booleanPreferencesKey("iptv_all_languages")
        val KEY_IPTV_VPN_ONLY = booleanPreferencesKey("iptv_vpn_only_enabled")
        val KEY_IPTV_LANGUAGE_FILTER = stringSetPreferencesKey("iptv_language_filter")
        val KEY_PLAYABILITY = stringPreferencesKey("playability_json")
        val KEY_PLAYABILITY_HIDE = intPreferencesKey("playability_hide_threshold")
    }

    val theme: Flow<AppTheme> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            AppTheme.fromStoredValues(prefs[KEY_THEME_KEY], prefs[KEY_THEME_MODE] ?: 0)
        }

    val fontScaleKey: Flow<String> = store.data.map { it[KEY_FONT_SCALE] ?: "standard" }

    val uiMode: Flow<Int> = store.data.map { it[KEY_UI_MODE] ?: 0 }

    val bootAutoStart: Flow<Boolean> = store.data.map { it[KEY_BOOT_AUTOSTART] ?: false }

    /**
     * 默认 false：开关没被显式打开过就绝不把成人频道放进列表与搜索。
     *
     * 设置页的成人开关已移除（用户要求），这里保留 key/flow/setter 是有意的：
     * DataStore 里可能有旧值，删除 key 是无损的但会牵动门面反射测试；
     * ViewModel 也不再读它，判定固定走 IptvAdult 的默认隐藏。请勿当成活开关接回 UI。
     */
    val iptvAdultEnabled: Flow<Boolean> = store.data.map { it[KEY_IPTV_ADULT] ?: false }

    /** 默认 false：只显示中文与英语频道，其他语言要显式打开 */
    val iptvAllLanguages: Flow<Boolean> = store.data.map { it[KEY_IPTV_ALL_LANGUAGES] ?: false }

    /** 默认 false：不显示「只有 VPN/代理才能访问」的频道 */
    val iptvVpnOnlyEnabled: Flow<Boolean> = store.data.map { it[KEY_IPTV_VPN_ONLY] ?: false }

    /** 分类页语言多选。空集 = 不额外过滤，沿用 iptvAllLanguages 的默认口径 */
    val iptvLanguageFilter: Flow<Set<String>> = store.data.map {
        it[KEY_IPTV_LANGUAGE_FILTER] ?: emptySet()
    }

    /** 可用性评分 JSON（只存有播放记录的键） */
    val playabilityJson: Flow<String> = store.data.map { it[KEY_PLAYABILITY] ?: "" }

    /** 连续失败达到该次数后隐藏；0 = 不隐藏（默认） */
    val playabilityHideThreshold: Flow<Int> = store.data.map { it[KEY_PLAYABILITY_HIDE] ?: 0 }

    suspend fun setThemeKey(key: String) { store.edit { it[KEY_THEME_KEY] = key } }
    suspend fun setFontScaleKey(key: String) { store.edit { it[KEY_FONT_SCALE] = key } }
    suspend fun setUiMode(mode: Int) { store.edit { it[KEY_UI_MODE] = mode } }
    suspend fun setBootAutoStart(enabled: Boolean) { store.edit { it[KEY_BOOT_AUTOSTART] = enabled } }
    suspend fun setIptvAdultEnabled(enabled: Boolean) { store.edit { it[KEY_IPTV_ADULT] = enabled } }
    suspend fun setIptvAllLanguages(enabled: Boolean) { store.edit { it[KEY_IPTV_ALL_LANGUAGES] = enabled } }
    suspend fun setIptvVpnOnlyEnabled(enabled: Boolean) { store.edit { it[KEY_IPTV_VPN_ONLY] = enabled } }
    suspend fun setIptvLanguageFilter(codes: Set<String>) { store.edit { it[KEY_IPTV_LANGUAGE_FILTER] = codes } }
    suspend fun setPlayabilityJson(json: String) { store.edit { it[KEY_PLAYABILITY] = json } }
    suspend fun setPlayabilityHideThreshold(threshold: Int) { store.edit { it[KEY_PLAYABILITY_HIDE] = threshold } }
}
