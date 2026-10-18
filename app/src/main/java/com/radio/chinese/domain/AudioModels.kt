package com.radio.chinese.domain

enum class SourceType { LOCAL, WEBDAV, M3U, HTTP }

data class AudioSource(
    val id: Long = 0,
    val name: String,
    val type: SourceType,
    val url: String,
    val username: String = "",
    val password: String = ""             // 调用 CredentialManager 填充
)

data class AudioTrack(
    val id: String,
    val name: String,
    val path: String,
    val size: Long = 0,
    val sourceId: Long = 0,
    val isFolder: Boolean = false,
    val parentPath: String = "",
    val isHlsStream: Boolean = false
)

/**
 * 全源搜索的一条命中。
 *
 * 放在 domain 而不是 repository：索引/搜索的纯判定（IndexQuery）要吃它，
 * 定义在 data 层会让 domain 反向依赖 data。
 */
data class TrackHit(
    val sourceId: Long,
    val name: String,
    val path: String,
    val parentPath: String,
    val size: Long,
)
