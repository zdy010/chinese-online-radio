package com.radio.chinese.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 电台/戏曲播放状态偏好：最后播放台、最近列表、倍速、断点续播、无效标记、自定义源。
 */
internal class PlaybackPreferences(private val store: DataStore<Preferences>) {

    companion object {
        val KEY_LAST_STATION = stringPreferencesKey("last_played_station")
        val KEY_SLEEP_TIMER = intPreferencesKey("sleep_timer_minutes")
        val KEY_OPERA_SPEED = floatPreferencesKey("opera_speed")
        val KEY_RECENT_STATIONS = stringPreferencesKey("radio_recent_station_ids")
        val KEY_INVALID_STATIONS = stringSetPreferencesKey("invalid_station_ids")
        val KEY_CUSTOM_STATIONS = stringPreferencesKey("custom_stations_json")
        val KEY_OPERA_PLAY_POSITIONS = stringPreferencesKey("opera_play_positions")
        val KEY_LAST_OPERA_FILE_ID = longPreferencesKey("last_opera_file_id")
        private const val SEPARATOR = ","
    }

    val lastPlayedStationId: Flow<String?> = store.data.map { it[KEY_LAST_STATION] }
    val sleepTimerMinutes: Flow<Int> = store.data.map { it[KEY_SLEEP_TIMER] ?: 0 }
    val operaSpeed: Flow<Float> = store.data.map { it[KEY_OPERA_SPEED] ?: 1f }
    val invalidStationIds: Flow<Set<String>> = store.data.map { it[KEY_INVALID_STATIONS] ?: emptySet() }
    val customStationsJson: Flow<String?> = store.data.map { it[KEY_CUSTOM_STATIONS] }
    val lastOperaFileId: Flow<Long> = store.data.map { it[KEY_LAST_OPERA_FILE_ID] ?: 0L }

    val radioRecentStationIds: Flow<List<String>> = store.data.map { prefs ->
        prefs[KEY_RECENT_STATIONS]?.split(SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()
    }

    val operaPlayPositions: Flow<String?> = store.data.map { it[KEY_OPERA_PLAY_POSITIONS] }

    suspend fun setLastPlayedStationId(stationId: String) { store.edit { it[KEY_LAST_STATION] = stationId } }
    suspend fun setSleepTimerMinutes(minutes: Int) { store.edit { it[KEY_SLEEP_TIMER] = minutes } }
    suspend fun setOperaSpeed(speed: Float) { store.edit { it[KEY_OPERA_SPEED] = speed } }
    suspend fun setInvalidStationIds(ids: Set<String>) { store.edit { it[KEY_INVALID_STATIONS] = ids } }

    suspend fun setCustomStationsJson(json: String?) {
        store.edit { prefs ->
            if (json != null) prefs[KEY_CUSTOM_STATIONS] = json else prefs.remove(KEY_CUSTOM_STATIONS)
        }
    }

    suspend fun addRecentStationId(stationId: String) {
        store.edit { prefs ->
            val current = prefs[KEY_RECENT_STATIONS]?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
            val updated = (listOf(stationId) + current.filter { it != stationId }).take(50)
            prefs[KEY_RECENT_STATIONS] = updated.joinToString(SEPARATOR)
        }
    }

    suspend fun saveOperaPlayPosition(fileId: Long, positionMs: Long) {
        store.edit { prefs ->
            prefs[KEY_LAST_OPERA_FILE_ID] = fileId
            val raw = prefs[KEY_OPERA_PLAY_POSITIONS] ?: "{}"
            val posMap: MutableMap<String, Long> = try {
                val obj = Json.parseToJsonElement(raw).jsonObject
                val result = mutableMapOf<String, Long>()
                obj.forEach { (key, value) ->
                    result[key] = value.jsonPrimitive.content.toLongOrNull() ?: 0L
                }
                result
            } catch (_: Exception) { mutableMapOf() }
            posMap[fileId.toString()] = positionMs
            if (posMap.size > 20) {
                posMap.keys.toList().take(posMap.size - 20).forEach { posMap.remove(it) }
            }
            val jsonEntries = posMap.entries.associate { (k, v) -> k to JsonPrimitive(v) }
            prefs[KEY_OPERA_PLAY_POSITIONS] = JsonObject(jsonEntries).toString()
        }
    }

    suspend fun getOperaPlayPosition(fileId: Long): Long {
        val raw = store.data.map { it[KEY_OPERA_PLAY_POSITIONS] ?: "{}" }.first()
        return try {
            val map = Json.parseToJsonElement(raw).jsonObject
            map[fileId.toString()]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }
    }
}
