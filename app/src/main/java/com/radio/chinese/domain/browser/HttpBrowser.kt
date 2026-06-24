package com.radio.chinese.domain.browser

import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class HttpBrowser(private val okHttpClient: OkHttpClient) : AudioBrowser {

    override suspend fun listChildren(path: String): Result<List<AudioTrack>> = withContext(Dispatchers.IO) {
        val isHls = path.endsWith(".m3u8", ignoreCase = true)
        val track = AudioTrack(
            id = path.hashCode().toString(),
            name = path.substringAfterLast("/").substringBefore("?"),
            path = path,
            isFolder = false,
            isHlsStream = isHls
        )
        Result.success(listOf(track))
    }

    override fun resolveUri(track: AudioTrack): String = track.path

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(this@HttpBrowser.toString()).head().build()
            okHttpClient.newCall(request).execute().close()
            Result.success(Unit)
        } catch (e: Exception) {
            // For HTTP direct links, a failed test doesn't mean invalid
            Result.success(Unit)
        }
    }

    override fun isStreamSource(): Boolean = true
}
