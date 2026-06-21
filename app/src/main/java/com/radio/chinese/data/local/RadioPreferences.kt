package com.radio.chinese.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "radio_settings")

@Singleton
class RadioPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    // Theme mode: 0 = system, 1 = light, 2 = dark
    val themeMode: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: 0
    }

    // Last played station ID
    val lastPlayedStationId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_STATION]
    }

    // Sleep timer minutes (0 = disabled)
    val sleepTimerMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_SLEEP_TIMER] ?: 0
    }

    suspend fun setThemeMode(mode: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
        }
    }

    suspend fun setLastPlayedStationId(stationId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_STATION] = stationId
        }
    }

    suspend fun setSleepTimerMinutes(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_SLEEP_TIMER] = minutes
        }
    }

    // Invalid station IDs (user-marked as unreachable)
    val invalidStationIds: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_INVALID_STATIONS] ?: emptySet()
    }

    // Custom user-added stations (JSON encoded)
    val customStationsJson: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_CUSTOM_STATIONS]
    }

    suspend fun setInvalidStationIds(ids: Set<String>) {
        dataStore.edit { prefs ->
            prefs[KEY_INVALID_STATIONS] = ids
        }
    }

    suspend fun setCustomStationsJson(json: String?) {
        dataStore.edit { prefs ->
            if (json != null) {
                prefs[KEY_CUSTOM_STATIONS] = json
            } else {
                prefs.remove(KEY_CUSTOM_STATIONS)
            }
        }
    }

    // ========== 戏曲播放位置 ==========

    /** 保存戏曲播放位置: key=fileId, value=positionMs */
    val operaPlayPositions: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_OPERA_PLAY_POSITIONS]
    }

    /** 上次播放的戏曲fileId */
    val lastOperaFileId: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_OPERA_FILE_ID] ?: 0L
    }

    suspend fun saveOperaPlayPosition(fileId: Long, positionMs: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_OPERA_FILE_ID] = fileId
            val raw = prefs[KEY_OPERA_PLAY_POSITIONS] ?: "{}"
            val posMap: MutableMap<String, Long> = try {
                val obj = Json.parseToJsonElement(raw).jsonObject
                val result = mutableMapOf<String, Long>()
                obj.forEach { (key, value) ->
                    result[key] = value.jsonPrimitive.content.toLongOrNull() ?: 0L
                }
                result
            } catch (_: Exception) {
                mutableMapOf()
            }
            posMap[fileId.toString()] = positionMs
            if (posMap.size > 20) {
                val keysToRemove = posMap.keys.toList().take(posMap.size - 20)
                keysToRemove.forEach { posMap.remove(it) }
            }
            val jsonEntries = posMap.entries.associate { (k, v) -> k to JsonPrimitive(v) }
            prefs[KEY_OPERA_PLAY_POSITIONS] = JsonObject(jsonEntries).toString()
        }
    }

    suspend fun getOperaPlayPosition(fileId: Long): Long {
        val raw = dataStore.data.map { it[KEY_OPERA_PLAY_POSITIONS] ?: "{}" }.first()
        return try {
            val map = Json.parseToJsonElement(raw).jsonObject
            map[fileId.toString()]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // ========== 戏曲分享提取码 ==========

    val operaSharePassword: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_OPERA_SHARE_PASSWORD] ?: ""
    }

    suspend fun saveOperaSharePassword(password: String) {
        dataStore.edit { prefs ->
            prefs[KEY_OPERA_SHARE_PASSWORD] = password
        }
    }

    // ========== 123云盘账号登录Token ==========

    val pan123AuthToken: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PAN123_AUTH_TOKEN] ?: ""
    }

    suspend fun savePan123AuthToken(token: String) {
        dataStore.edit { prefs ->
            prefs[KEY_PAN123_AUTH_TOKEN] = token
        }
    }

    // ========== WebDAV 凭证 ==========

    val webDavServerUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_WEBDAV_SERVER_URL] ?: ""
    }

    val webDavUsername: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_WEBDAV_USERNAME] ?: ""
    }

    val webDavPassword: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_WEBDAV_PASSWORD] ?: ""
    }

    suspend fun saveWebDavCredentials(serverUrl: String, username: String, password: String) {
        dataStore.edit { prefs ->
            prefs[KEY_WEBDAV_SERVER_URL] = serverUrl
            prefs[KEY_WEBDAV_USERNAME] = username
            prefs[KEY_WEBDAV_PASSWORD] = password
        }
    }

    suspend fun clearWebDavCredentials() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_WEBDAV_SERVER_URL)
            prefs.remove(KEY_WEBDAV_USERNAME)
            prefs.remove(KEY_WEBDAV_PASSWORD)
        }
    }

    companion object {
        private val KEY_THEME_MODE = intPreferencesKey("theme_mode")
        private val KEY_LAST_STATION = stringPreferencesKey("last_played_station")
        private val KEY_SLEEP_TIMER = intPreferencesKey("sleep_timer_minutes")
        private val KEY_INVALID_STATIONS = stringSetPreferencesKey("invalid_station_ids")
        private val KEY_CUSTOM_STATIONS = stringPreferencesKey("custom_stations_json")
        private val KEY_OPERA_PLAY_POSITIONS = stringPreferencesKey("opera_play_positions")
        private val KEY_LAST_OPERA_FILE_ID = longPreferencesKey("last_opera_file_id")
        private val KEY_OPERA_SHARE_PASSWORD = stringPreferencesKey("opera_share_password")
        private val KEY_PAN123_AUTH_TOKEN = stringPreferencesKey("pan123_auth_token")
        private val KEY_WEBDAV_SERVER_URL = stringPreferencesKey("webdav_server_url")
        private val KEY_WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
        private val KEY_WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
    }
}
