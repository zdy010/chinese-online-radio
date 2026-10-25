package com.radio.chinese.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * WebDAV 凭证、云盘 Token、戏曲授权与收藏偏好。
 */
internal class WebDavPreferences(private val store: DataStore<Preferences>) {

    companion object {
        val KEY_WEBDAV_SERVER_URL = stringPreferencesKey("webdav_server_url")
        val KEY_WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
        val KEY_WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
        val KEY_OPERA_SHARE_PASSWORD = stringPreferencesKey("opera_share_password")
        val KEY_PAN123_AUTH_TOKEN = stringPreferencesKey("pan123_auth_token")
        val KEY_OPERA_AUTH_VERIFIED = booleanPreferencesKey("opera_auth_verified")
        val KEY_OPERA_FAV_CATEGORIES = stringSetPreferencesKey("opera_fav_categories")
        val KEY_OPERA_FAV_FILES = stringSetPreferencesKey("opera_fav_files")
    }

    val webDavServerUrl: Flow<String> = store.data.map { it[KEY_WEBDAV_SERVER_URL] ?: "" }
    val webDavUsername: Flow<String> = store.data.map { it[KEY_WEBDAV_USERNAME] ?: "" }
    val webDavPassword: Flow<String> = store.data.map { it[KEY_WEBDAV_PASSWORD] ?: "" }
    val operaSharePassword: Flow<String> = store.data.map { it[KEY_OPERA_SHARE_PASSWORD] ?: "" }
    val pan123AuthToken: Flow<String> = store.data.map { it[KEY_PAN123_AUTH_TOKEN] ?: "" }
    val operaAuthVerified: Flow<Boolean> = store.data.map { it[KEY_OPERA_AUTH_VERIFIED] ?: false }

    val operaFavoriteCategories: Flow<Set<String>> = store.data.map { it[KEY_OPERA_FAV_CATEGORIES] ?: emptySet() }
    val operaFavoriteFiles: Flow<Set<Long>> = store.data.map { prefs ->
        prefs[KEY_OPERA_FAV_FILES]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    suspend fun saveWebDavCredentials(serverUrl: String, username: String, password: String) {
        store.edit { prefs ->
            prefs[KEY_WEBDAV_SERVER_URL] = serverUrl
            prefs[KEY_WEBDAV_USERNAME] = username
            prefs[KEY_WEBDAV_PASSWORD] = password
        }
    }

    suspend fun clearWebDavCredentials() {
        store.edit { prefs ->
            prefs.remove(KEY_WEBDAV_SERVER_URL)
            prefs.remove(KEY_WEBDAV_USERNAME)
            prefs.remove(KEY_WEBDAV_PASSWORD)
        }
    }

    suspend fun saveOperaSharePassword(password: String) { store.edit { it[KEY_OPERA_SHARE_PASSWORD] = password } }
    suspend fun savePan123AuthToken(token: String) { store.edit { it[KEY_PAN123_AUTH_TOKEN] = token } }
    suspend fun setOperaAuthVerified(verified: Boolean) { store.edit { it[KEY_OPERA_AUTH_VERIFIED] = verified } }
    suspend fun setOperaFavoriteCategories(categories: Set<String>) { store.edit { it[KEY_OPERA_FAV_CATEGORIES] = categories } }
    suspend fun setOperaFavoriteFiles(fileIds: Set<Long>) {
        store.edit { it[KEY_OPERA_FAV_FILES] = fileIds.map { it.toString() }.toSet() }
    }
}
