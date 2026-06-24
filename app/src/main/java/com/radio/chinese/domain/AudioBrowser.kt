package com.radio.chinese.domain

import com.radio.chinese.domain.AudioTrack

interface AudioBrowser {
    suspend fun listChildren(path: String): Result<List<AudioTrack>>
    fun resolveUri(track: AudioTrack): String
    suspend fun testConnection(): Result<Unit>
    fun isStreamSource(): Boolean = false
}
