package com.radio.chinese.domain.model

import com.radio.chinese.domain.model.OperaAudioFile

/**
 * 戏曲点播上下文：sourceId=0 是约定值，表示本地直连（MediaStore/文件路径，无库记录无鉴权）。
 * 附属文件（歌词/封面）靠它找回浏览器与凭证。
 *
 * 原定义在 service/PlayerManager.kt 底部，下移到 domain 以消除 domain→service 违例。
 */
data class OperaPlayContext(val sourceId: Long, val trackPath: String)

/** 纯函数便于单测：显式 context 优先；内部切段保留 sourceId 只换 trackPath；无路可推时保持旧值。 */
fun nextOperaContext(
    old: OperaPlayContext?,
    file: OperaAudioFile,
    explicit: OperaPlayContext?
): OperaPlayContext? = explicit ?: old?.let { o ->
    (file.sourcePath ?: file.downloadUrl)?.let { o.copy(trackPath = it) } ?: o
}
