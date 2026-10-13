package com.radio.chinese.domain

import com.radio.chinese.domain.AudioTrack

interface AudioBrowser {
    suspend fun listChildren(path: String): Result<List<AudioTrack>>
    fun resolveUri(track: AudioTrack): String
    suspend fun testConnection(): Result<Unit>
    fun isStreamSource(): Boolean = false

    /**
     * 读取任意附属文件字节（同名 .lrc/.jpg 等），path 为源内路径或完整 URL。
     * 默认不支持：新增源类型不实现也编译得过，歌词/封面自动降级为“没有”。
     */
    suspend fun readSidecar(path: String): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("该来源不支持附属文件读取"))
}
