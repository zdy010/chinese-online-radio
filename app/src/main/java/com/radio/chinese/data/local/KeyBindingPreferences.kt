package com.radio.chinese.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 车机按键映射偏好（功能→键码 JSON）。
 */
internal class KeyBindingPreferences(private val store: DataStore<Preferences>) {

    companion object {
        val KEY_KEY_BINDINGS = stringPreferencesKey("car_key_bindings_json")
    }

    val keyBindingsJson: Flow<String?> = store.data.map { it[KEY_KEY_BINDINGS] }

    suspend fun setKeyBindingsJson(json: String?) {
        store.edit { prefs ->
            if (json != null) prefs[KEY_KEY_BINDINGS] = json else prefs.remove(KEY_KEY_BINDINGS)
        }
    }
}
