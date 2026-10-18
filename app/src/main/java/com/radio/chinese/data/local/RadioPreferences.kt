package com.radio.chinese.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.radio.chinese.ui.theme.AppTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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

    /**
     * 当前主题。读取侧一次合并新旧两键：有 theme_key 用 theme_key，没有才按
     * 旧 theme_mode 映射——这就是升级用户的原样迁移，不需要回写。
     *
     * 不回写 theme_key：回写要额外背一个「冷启动即写盘」的副作用和一个调用点，
     * 而合并结论已经定死在 AppTheme.fromStoredValues 这一处。
     *
     * data 层引 ui.theme.AppTheme 的取舍同本目录 SourceAvailabilityStore 里的取色函数：
     * 为 UI 服务的取值逻辑留在这里，多一个类型比多一处判定划算。
     */
    val theme: Flow<AppTheme> = dataStore.data
        .catch { e ->
            // 根 Composable 的取色入口，Flow 一旦终止主题就再也回不来。
            // 读盘 IO 错误降级成「无偏好」= 跟随系统；其他异常照常抛出，不吞。
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            AppTheme.fromStoredValues(prefs[KEY_THEME_KEY], prefs[KEY_THEME_MODE] ?: 0)
        }

    // Last played station ID
    val lastPlayedStationId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_STATION]
    }

    // Sleep timer minutes (0 = disabled)
    val sleepTimerMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_SLEEP_TIMER] ?: 0
    }

    // 电台最近播放：用分隔串而不是 stringSet，因为 stringSet 无序，会丢掉「最近」的顺序
    val radioRecentStationIds: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[KEY_RECENT_STATIONS]?.split(SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()
    }

    /** 记录一次播放：置顶去重，最多留 50 条 */
    suspend fun addRecentStationId(stationId: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_RECENT_STATIONS]?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
            val updated = (listOf(stationId) + current.filter { it != stationId }).take(50)
            prefs[KEY_RECENT_STATIONS] = updated.joinToString(SEPARATOR)
        }
    }

    // 字号档位：standard / large / extra_large
    val fontScaleKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_FONT_SCALE] ?: "standard"
    }

    suspend fun setFontScaleKey(key: String) {
        dataStore.edit { prefs ->
            prefs[KEY_FONT_SCALE] = key
        }
    }

    /** 界面形态：0 = 自动判定，1 = 强制手机布局，2 = 强制车机布局 */
    val uiMode: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_UI_MODE] ?: 0
    }

    suspend fun setUiMode(mode: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_UI_MODE] = mode
        }
    }

    /** 开机后是否恢复播放会话（默认关：只挂“已就绪”通知，不出声） */
    val bootAutoStart: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_BOOT_AUTOSTART] ?: false
    }

    suspend fun setBootAutoStart(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_BOOT_AUTOSTART] = enabled
        }
    }

    suspend fun setThemeKey(key: String) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME_KEY] = key
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

    // ========== 戏曲授权码验证状态 ==========

    /** 授权码是否已验证通过 */
    val operaAuthVerified: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_OPERA_AUTH_VERIFIED] ?: false
    }

    suspend fun setOperaAuthVerified(verified: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_OPERA_AUTH_VERIFIED] = verified
        }
    }

    // ========== 车机按键映射 ==========

    /**
     * 功能→绑定的 JSON 对象串，形如 {"next_station":"key:17"}。
     * 用串而不是 stringSet，因为要保住「哪个功能绑了哪个键」的配对关系。
     */
    val keyBindingsJson: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_KEY_BINDINGS]
    }

    suspend fun setKeyBindingsJson(json: String?) {
        dataStore.edit { prefs ->
            if (json != null) {
                prefs[KEY_KEY_BINDINGS] = json
            } else {
                prefs.remove(KEY_KEY_BINDINGS)
            }
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
        private val KEY_THEME_KEY = stringPreferencesKey("theme_key")
        /** 旧明暗开关（0/1/2）。只作 theme_key 缺失时的迁移来源，不再有新写入。 */
        private val KEY_THEME_MODE = intPreferencesKey("theme_mode")
        private val KEY_LAST_STATION = stringPreferencesKey("last_played_station")
        private val KEY_SLEEP_TIMER = intPreferencesKey("sleep_timer_minutes")
        private val KEY_RECENT_STATIONS = stringPreferencesKey("radio_recent_station_ids")
        private val KEY_FONT_SCALE = stringPreferencesKey("font_scale")
        private val KEY_UI_MODE = intPreferencesKey("ui_mode")
        private val KEY_BOOT_AUTOSTART = booleanPreferencesKey("boot_auto_start")
        private val KEY_INVALID_STATIONS = stringSetPreferencesKey("invalid_station_ids")
        private val KEY_CUSTOM_STATIONS = stringPreferencesKey("custom_stations_json")
        private val KEY_OPERA_PLAY_POSITIONS = stringPreferencesKey("opera_play_positions")
        private val KEY_LAST_OPERA_FILE_ID = longPreferencesKey("last_opera_file_id")
        private val KEY_OPERA_SHARE_PASSWORD = stringPreferencesKey("opera_share_password")
        private val KEY_OPERA_AUTH_VERIFIED = booleanPreferencesKey("opera_auth_verified")
        private val KEY_PAN123_AUTH_TOKEN = stringPreferencesKey("pan123_auth_token")
        private val KEY_WEBDAV_SERVER_URL = stringPreferencesKey("webdav_server_url")
        private val KEY_WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
        private val KEY_WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
        private val KEY_KEY_BINDINGS = stringPreferencesKey("car_key_bindings_json")
        // 戏曲收藏：收藏的分类名集合
        private val KEY_OPERA_FAV_CATEGORIES = stringSetPreferencesKey("opera_fav_categories")
        // 戏曲收藏：收藏的文件ID集合
        private val KEY_OPERA_FAV_FILES = stringSetPreferencesKey("opera_fav_files")

        // 收听增强：线控播报 + 本地优先
        private val KEY_VOICE_ANNOUNCE = booleanPreferencesKey("voice_announce_enabled")
        private val KEY_PREFERRED_TTS_ENGINE = stringPreferencesKey("preferred_tts_engine")
        private val KEY_PREFERRED_VOICE = stringPreferencesKey("preferred_voice")
        private val KEY_LOCAL_FIRST = booleanPreferencesKey("local_first_enabled")
        private val KEY_WEBDAV_INDEX_ENABLED = booleanPreferencesKey("webdav_index_enabled")
        private val KEY_CACHED_REGION = stringPreferencesKey("cached_region")
        private val KEY_MANUAL_REGION = stringPreferencesKey("manual_region")

        /** radioRecentStationIds 的分隔符 */
        private const val SEPARATOR = ","
    }

    // ========== 戏曲收藏 ==========

    /** 收藏的分类名集合 */
    val operaFavoriteCategories: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_OPERA_FAV_CATEGORIES] ?: emptySet()
    }

    /** 收藏的文件ID集合 */
    val operaFavoriteFiles: Flow<Set<Long>> = dataStore.data.map { prefs ->
        prefs[KEY_OPERA_FAV_FILES]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    suspend fun setOperaFavoriteCategories(categories: Set<String>) {
        dataStore.edit { prefs ->
            prefs[KEY_OPERA_FAV_CATEGORIES] = categories
        }
    }

    suspend fun setOperaFavoriteFiles(fileIds: Set<Long>) {
        dataStore.edit { prefs ->
            prefs[KEY_OPERA_FAV_FILES] = fileIds.map { it.toString() }.toSet()
        }
    }

    // ========== 收听增强：线控播报 + 本地优先 ==========

    /** 线控切台播报开关，默认开（新功能主路径）。 */
    val voiceAnnounceEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_VOICE_ANNOUNCE] ?: true }
    val preferredTtsEngine: Flow<String?> = dataStore.data.map { it[KEY_PREFERRED_TTS_ENGINE] }
    val preferredVoice: Flow<String?> = dataStore.data.map { it[KEY_PREFERRED_VOICE] }
    /** 本地节目排序优先，默认开。 */
    val localFirstEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_LOCAL_FIRST] ?: true }

    /**
     * 允许为网络源建立节目索引，默认开。
     *
     * 2026-10-18 语义变更：索引不再自动跑（包括不再限定 Wi-Fi），
     * 这个开关也不再意味着“自动”，而是“允许不允许弹确认、允不允许用户手动起”。
     */
    val webdavIndexEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_WEBDAV_INDEX_ENABLED] ?: true }
    val cachedRegion: Flow<String?> = dataStore.data.map { it[KEY_CACHED_REGION] }
    val manualRegion: Flow<String?> = dataStore.data.map { it[KEY_MANUAL_REGION] }

    suspend fun setVoiceAnnounceEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_VOICE_ANNOUNCE] = enabled }
    }

    suspend fun setPreferredTtsEngine(pkg: String?) {
        dataStore.edit { prefs -> if (pkg == null) prefs.remove(KEY_PREFERRED_TTS_ENGINE) else prefs[KEY_PREFERRED_TTS_ENGINE] = pkg }
    }

    suspend fun setPreferredVoice(name: String?) {
        dataStore.edit { prefs -> if (name == null) prefs.remove(KEY_PREFERRED_VOICE) else prefs[KEY_PREFERRED_VOICE] = name }
    }

    suspend fun setLocalFirstEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_LOCAL_FIRST] = enabled }
    }

    suspend fun setWebdavIndexEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_WEBDAV_INDEX_ENABLED] = enabled }
    }

    suspend fun setCachedRegion(value: String?) {
        dataStore.edit { prefs -> if (value == null) prefs.remove(KEY_CACHED_REGION) else prefs[KEY_CACHED_REGION] = value }
    }

    suspend fun setManualRegion(value: String?) {
        dataStore.edit { prefs -> if (value == null) prefs.remove(KEY_MANUAL_REGION) else prefs[KEY_MANUAL_REGION] = value }
    }
}
