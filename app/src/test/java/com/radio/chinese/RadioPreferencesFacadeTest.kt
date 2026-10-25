package com.radio.chinese

import com.radio.chinese.data.local.RadioPreferences
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 RadioPreferences 门面拆分后公开 API 成员数量与名称完整。
 * 用反射枚举 Flow 属性（kotlinx.coroutines.flow.Flow 泛型参数），
 * 确认 30+ 个公开成员一个都没丢。
 */
class RadioPreferencesFacadeTest {

    @Test
    fun `all public Flow properties exist on facade class`() {
        val clazz = RadioPreferences::class.java
        val flowProps = clazz.declaredFields.filter { field ->
            kotlinx.coroutines.flow.Flow::class.java.isAssignableFrom(field.type)
        }.map { it.name }

        // 按域列举必须的 Flow 属性（共 30 个）
        val expected = listOf(
            // Settings
            "theme", "fontScaleKey", "uiMode", "bootAutoStart",
            // Playback
            "lastPlayedStationId", "sleepTimerMinutes", "operaSpeed",
            "radioRecentStationIds", "invalidStationIds", "customStationsJson",
            "operaPlayPositions", "lastOperaFileId",
            // TTS
            "voiceAnnounceEnabled", "preferredTtsEngine", "preferredVoice", "reverseWheelDirection",
            // WebDAV
            "webDavServerUrl", "webDavUsername", "webDavPassword",
            "operaSharePassword", "pan123AuthToken", "operaAuthVerified",
            "operaFavoriteCategories", "operaFavoriteFiles",
            // KeyBinding
            "keyBindingsJson",
            // Region
            "localFirstEnabled", "webdavIndexEnabled", "cachedRegion", "manualRegion",
        )

        val missing = expected.filter { it !in flowProps }
        assertTrue("RadioPreferences 门面缺失 Flow 属性: $missing", missing.isEmpty())

        // 验证 suspend 方法数量
        val methods = clazz.declaredMethods.map { it.name }
        val required = listOf(
            "setThemeKey", "setFontScaleKey", "setUiMode", "setBootAutoStart",
            "setLastPlayedStationId", "setSleepTimerMinutes", "setOperaSpeed",
            "addRecentStationId", "setInvalidStationIds", "setCustomStationsJson",
            "saveOperaPlayPosition", "getOperaPlayPosition",
            "setVoiceAnnounceEnabled", "setPreferredTtsEngine", "setPreferredVoice", "setReverseWheelDirection",
            "saveWebDavCredentials", "clearWebDavCredentials",
            "saveOperaSharePassword", "savePan123AuthToken", "setOperaAuthVerified",
            "setOperaFavoriteCategories", "setOperaFavoriteFiles",
            "setKeyBindingsJson",
            "setLocalFirstEnabled", "setWebdavIndexEnabled", "setCachedRegion", "setManualRegion",
        )
        val missingMethods = required.filter { it !in methods }
        assertTrue("RadioPreferences 门面缺失方法: $missingMethods", missingMethods.isEmpty())
    }
}
