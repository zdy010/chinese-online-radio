package com.radio.chinese.domain.sidecar

import com.radio.chinese.domain.model.OperaPlayContext

/** 播放页附属内容（歌词/封面）的取料抽象，VM 只依赖它，单测用 Fake 顶替。 */
interface SidecarMediaSource {
    suspend fun readLyrics(ctx: OperaPlayContext): Result<String>
    suspend fun readImage(ctx: OperaPlayContext): Result<ByteArray>
}
