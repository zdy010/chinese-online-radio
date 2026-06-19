package com.radio.chinese.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    companion object {
        private val KEY_THEME_MODE = intPreferencesKey("theme_mode")
        private val KEY_LAST_STATION = stringPreferencesKey("last_played_station")
        private val KEY_SLEEP_TIMER = intPreferencesKey("sleep_timer_minutes")
    }
}
