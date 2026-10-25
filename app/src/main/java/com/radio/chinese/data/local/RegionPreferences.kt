package com.radio.chinese.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 地区 / 本地优先 / 索引开关偏好。
 */
internal class RegionPreferences(private val store: DataStore<Preferences>) {

    companion object {
        val KEY_LOCAL_FIRST = booleanPreferencesKey("local_first_enabled")
        val KEY_WEBDAV_INDEX_ENABLED = booleanPreferencesKey("webdav_index_enabled")
        val KEY_CACHED_REGION = stringPreferencesKey("cached_region")
        val KEY_MANUAL_REGION = stringPreferencesKey("manual_region")
    }

    val localFirstEnabled: Flow<Boolean> = store.data.map { it[KEY_LOCAL_FIRST] ?: true }
    val webdavIndexEnabled: Flow<Boolean> = store.data.map { it[KEY_WEBDAV_INDEX_ENABLED] ?: true }
    val cachedRegion: Flow<String?> = store.data.map { it[KEY_CACHED_REGION] }
    val manualRegion: Flow<String?> = store.data.map { it[KEY_MANUAL_REGION] }

    suspend fun setLocalFirstEnabled(enabled: Boolean) { store.edit { it[KEY_LOCAL_FIRST] = enabled } }
    suspend fun setWebdavIndexEnabled(enabled: Boolean) { store.edit { it[KEY_WEBDAV_INDEX_ENABLED] = enabled } }
    suspend fun setCachedRegion(value: String?) {
        store.edit { prefs -> if (value == null) prefs.remove(KEY_CACHED_REGION) else prefs[KEY_CACHED_REGION] = value }
    }
    suspend fun setManualRegion(value: String?) {
        store.edit { prefs -> if (value == null) prefs.remove(KEY_MANUAL_REGION) else prefs[KEY_MANUAL_REGION] = value }
    }
}
