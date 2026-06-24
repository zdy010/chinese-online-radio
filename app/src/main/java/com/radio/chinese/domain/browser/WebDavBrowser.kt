package com.radio.chinese.domain.browser

import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioTrack
import com.radio.chinese.service.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebDavBrowser(
    private val webDavClient: WebDavClient,
    private val serverUrl: String,
    private val username: String,
    private val password: String
) : AudioBrowser {

    override suspend fun listChildren(path: String): Result<List<AudioTrack>> = withContext(Dispatchers.IO) {
        applyCredentials()
        webDavClient.listFiles(path)
            .map { file ->
                AudioTrack(
                    id = file.href,
                    name = file.displayName,
                    path = file.href,
                    size = file.contentLength,
                    isFolder = file.isFolder,
                    parentPath = path
                )
            }
            .let { Result.success(it) }
    }

    override fun resolveUri(track: AudioTrack): String {
        applyCredentials()
        return webDavClient.getFileUrl(track.path)
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        applyCredentials()
        webDavClient.verifyConnection()
    }

    private fun applyCredentials() {
        webDavClient.serverUrl = serverUrl
        webDavClient.username = username
        webDavClient.password = password
    }
}
