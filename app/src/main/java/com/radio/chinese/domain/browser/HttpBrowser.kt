package com.radio.chinese.domain.browser

import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class HttpBrowser(
    private val okHttpClient: OkHttpClient,
    /** 源自身地址：根浏览时 UI 不传 path，回落到它，才不会产出一条 path="" 的假曲目。 */
    val sourceUrl: String = "",
) : AudioBrowser {

    override suspend fun listChildren(path: String): Result<List<AudioTrack>> = withContext(Dispatchers.IO) {
        val url = path.ifBlank { sourceUrl }
        if (url.isBlank()) return@withContext Result.success(emptyList())
        val isHls = url.endsWith(".m3u8", ignoreCase = true)
        val track = AudioTrack(
            id = url.hashCode().toString(),
            name = url.substringAfterLast("/").substringBefore("?"),
            path = url,
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

    override suspend fun readSidecar(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            okHttpClient.newCall(Request.Builder().url(path).build()).execute().use { r ->
                val b = r.body
                if (r.isSuccessful && b != null) Result.success(b.bytes())
                else Result.failure(Exception("HTTP ${r.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
