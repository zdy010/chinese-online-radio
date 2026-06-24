package com.radio.chinese.domain.browser

import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

class M3uBrowser @Inject constructor(
    private val okHttpClient: OkHttpClient
) : AudioBrowser {

    override suspend fun listChildren(path: String): Result<List<AudioTrack>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(path).build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            val tracks = parseM3u(body, path)
            Result.success(tracks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun resolveUri(track: AudioTrack): String = track.path

    override suspend fun testConnection(): Result<Unit> {
        return listChildren("").map { Unit }
    }

    override fun isStreamSource(): Boolean = false

    private fun parseM3u(content: String, baseUrl: String): List<AudioTrack> {
        val tracks = mutableListOf<AudioTrack>()
        var currentTitle: String? = null
        var index = 0

        for (line in content.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXTINF:") -> {
                    // #EXTINF:240,豫剧 - 花木兰
                    val commaIdx = trimmed.indexOf(",")
                    currentTitle = if (commaIdx > 0) trimmed.substring(commaIdx + 1).trim() else null
                }
                trimmed.isEmpty() || trimmed.startsWith("#") -> continue
                else -> {
                    // URL line
                    val title = currentTitle ?: trimmed.substringAfterLast("/").substringBefore("?")
                    tracks.add(AudioTrack(
                        id = (baseUrl + index++).hashCode().toString(),
                        name = title,
                        path = trimmed,
                        isFolder = false
                    ))
                    currentTitle = null
                }
            }
        }
        return tracks
    }
}
