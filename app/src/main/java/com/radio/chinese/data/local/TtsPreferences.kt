package com.radio.chinese.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * TTS / 线控播报相关偏好。
 */
internal class TtsPreferences(private val store: DataStore<Preferences>) {

    companion object {
        val KEY_VOICE_ANNOUNCE = booleanPreferencesKey("voice_announce_enabled")
        val KEY_REVERSE_WHEEL = booleanPreferencesKey("reverse_wheel_direction")
        val KEY_PREFERRED_TTS_ENGINE = stringPreferencesKey("preferred_tts_engine")
        val KEY_PREFERRED_VOICE = stringPreferencesKey("preferred_voice")
    }

    val voiceAnnounceEnabled: Flow<Boolean> = store.data.map { it[KEY_VOICE_ANNOUNCE] ?: true }
    val preferredTtsEngine: Flow<String?> = store.data.map { it[KEY_PREFERRED_TTS_ENGINE] }
    val preferredVoice: Flow<String?> = store.data.map { it[KEY_PREFERRED_VOICE] }
    val reverseWheelDirection: Flow<Boolean> = store.data.map { it[KEY_REVERSE_WHEEL] ?: false }

    suspend fun setVoiceAnnounceEnabled(enabled: Boolean) { store.edit { it[KEY_VOICE_ANNOUNCE] = enabled } }
    suspend fun setPreferredTtsEngine(pkg: String?) {
        store.edit { prefs -> if (pkg == null) prefs.remove(KEY_PREFERRED_TTS_ENGINE) else prefs[KEY_PREFERRED_TTS_ENGINE] = pkg }
    }
    suspend fun setPreferredVoice(name: String?) {
        store.edit { prefs -> if (name == null) prefs.remove(KEY_PREFERRED_VOICE) else prefs[KEY_PREFERRED_VOICE] = name }
    }
    suspend fun setReverseWheelDirection(enabled: Boolean) { store.edit { it[KEY_REVERSE_WHEEL] = enabled } }
}
