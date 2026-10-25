package com.radio.chinese.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.radio.chinese.domain.model.AppTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "radio_settings")

/**
 * 用户偏好外观（Facade）。
 *
 * 按域拆分到 6 个 internal 子类，本类保持统一入口，所有外部注入点无需改动。
 * 新代码若只需某一域，可直接注入对应子类（通过 RadioPreferences 暴露的 delegate 属性）。
 *
 * 域划分：
 * - [SettingsPreferences]   主题 / 字号 / 界面形态 / 开机自启
 * - [PlaybackPreferences]   电台/戏曲播放状态（最近、倍速、断点、无效标记、自定义源）
 * - [TtsPreferences]        线控播报 / TTS 引擎 / 音色 / 滚轮反转
 * - [WebDavPreferences]     WebDAV 凭证 / 云盘 Token / 戏曲授权与收藏
 * - [KeyBindingPreferences] 车机按键映射
 * - [RegionPreferences]     地区 / 本地优先 / 索引开关
 */
@Singleton
class RadioPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    // ========== 子域实例 ==========
    private val settings = SettingsPreferences(dataStore)
    private val playback = PlaybackPreferences(dataStore)
    private val tts = TtsPreferences(dataStore)
    private val webDav = WebDavPreferences(dataStore)
    private val keyBinding = KeyBindingPreferences(dataStore)
    private val region = RegionPreferences(dataStore)

    // ========== Settings 域 ==========
    val theme: Flow<AppTheme> = settings.theme
    val fontScaleKey: Flow<String> = settings.fontScaleKey
    val uiMode: Flow<Int> = settings.uiMode
    val bootAutoStart: Flow<Boolean> = settings.bootAutoStart

    suspend fun setThemeKey(key: String) = settings.setThemeKey(key)
    suspend fun setFontScaleKey(key: String) = settings.setFontScaleKey(key)
    suspend fun setUiMode(mode: Int) = settings.setUiMode(mode)
    suspend fun setBootAutoStart(enabled: Boolean) = settings.setBootAutoStart(enabled)

    // ========== Playback 域 ==========
    val lastPlayedStationId: Flow<String?> = playback.lastPlayedStationId
    val sleepTimerMinutes: Flow<Int> = playback.sleepTimerMinutes
    val operaSpeed: Flow<Float> = playback.operaSpeed
    val radioRecentStationIds: Flow<List<String>> = playback.radioRecentStationIds
    val invalidStationIds: Flow<Set<String>> = playback.invalidStationIds
    val customStationsJson: Flow<String?> = playback.customStationsJson
    val operaPlayPositions: Flow<String?> = playback.operaPlayPositions
    val lastOperaFileId: Flow<Long> = playback.lastOperaFileId

    suspend fun setLastPlayedStationId(stationId: String) = playback.setLastPlayedStationId(stationId)
    suspend fun setSleepTimerMinutes(minutes: Int) = playback.setSleepTimerMinutes(minutes)
    suspend fun setOperaSpeed(speed: Float) = playback.setOperaSpeed(speed)
    suspend fun addRecentStationId(stationId: String) = playback.addRecentStationId(stationId)
    suspend fun setInvalidStationIds(ids: Set<String>) = playback.setInvalidStationIds(ids)
    suspend fun setCustomStationsJson(json: String?) = playback.setCustomStationsJson(json)
    suspend fun saveOperaPlayPosition(fileId: Long, positionMs: Long) = playback.saveOperaPlayPosition(fileId, positionMs)
    suspend fun getOperaPlayPosition(fileId: Long): Long = playback.getOperaPlayPosition(fileId)

    // ========== TTS 域 ==========
    val voiceAnnounceEnabled: Flow<Boolean> = tts.voiceAnnounceEnabled
    val preferredTtsEngine: Flow<String?> = tts.preferredTtsEngine
    val preferredVoice: Flow<String?> = tts.preferredVoice
    val reverseWheelDirection: Flow<Boolean> = tts.reverseWheelDirection

    suspend fun setVoiceAnnounceEnabled(enabled: Boolean) = tts.setVoiceAnnounceEnabled(enabled)
    suspend fun setPreferredTtsEngine(pkg: String?) = tts.setPreferredTtsEngine(pkg)
    suspend fun setPreferredVoice(name: String?) = tts.setPreferredVoice(name)
    suspend fun setReverseWheelDirection(enabled: Boolean) = tts.setReverseWheelDirection(enabled)

    // ========== WebDAV 域 ==========
    val webDavServerUrl: Flow<String> = webDav.webDavServerUrl
    val webDavUsername: Flow<String> = webDav.webDavUsername
    val webDavPassword: Flow<String> = webDav.webDavPassword
    val operaSharePassword: Flow<String> = webDav.operaSharePassword
    val pan123AuthToken: Flow<String> = webDav.pan123AuthToken
    val operaAuthVerified: Flow<Boolean> = webDav.operaAuthVerified
    val operaFavoriteCategories: Flow<Set<String>> = webDav.operaFavoriteCategories
    val operaFavoriteFiles: Flow<Set<Long>> = webDav.operaFavoriteFiles

    suspend fun saveWebDavCredentials(serverUrl: String, username: String, password: String) =
        webDav.saveWebDavCredentials(serverUrl, username, password)
    suspend fun clearWebDavCredentials() = webDav.clearWebDavCredentials()
    suspend fun saveOperaSharePassword(password: String) = webDav.saveOperaSharePassword(password)
    suspend fun savePan123AuthToken(token: String) = webDav.savePan123AuthToken(token)
    suspend fun setOperaAuthVerified(verified: Boolean) = webDav.setOperaAuthVerified(verified)
    suspend fun setOperaFavoriteCategories(categories: Set<String>) = webDav.setOperaFavoriteCategories(categories)
    suspend fun setOperaFavoriteFiles(fileIds: Set<Long>) = webDav.setOperaFavoriteFiles(fileIds)

    // ========== KeyBinding 域 ==========
    val keyBindingsJson: Flow<String?> = keyBinding.keyBindingsJson
    suspend fun setKeyBindingsJson(json: String?) = keyBinding.setKeyBindingsJson(json)

    // ========== Region 域 ==========
    val localFirstEnabled: Flow<Boolean> = region.localFirstEnabled
    val webdavIndexEnabled: Flow<Boolean> = region.webdavIndexEnabled
    val cachedRegion: Flow<String?> = region.cachedRegion
    val manualRegion: Flow<String?> = region.manualRegion

    suspend fun setLocalFirstEnabled(enabled: Boolean) = region.setLocalFirstEnabled(enabled)
    suspend fun setWebdavIndexEnabled(enabled: Boolean) = region.setWebdavIndexEnabled(enabled)
    suspend fun setCachedRegion(value: String?) = region.setCachedRegion(value)
    suspend fun setManualRegion(value: String?) = region.setManualRegion(value)
}
