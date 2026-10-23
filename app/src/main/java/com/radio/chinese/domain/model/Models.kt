package com.radio.chinese.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class StationSource(
    val url: String,
    val label: String = "",
    val bitrate: Int = 0
)

@Serializable
data class RadioStation(
    val id: String,
    val name: String,
    val category: String,
    val streamUrl: String = "",
    val logoUrl: String = "",
    val frequency: String = "",
    val description: String = "",
    val website: String = "",
    val sources: List<StationSource> = emptyList(),
    /** 标签 key 列表，取值必须能在 [StationTag] 里查到。只存 key，避免中文文案与数据两头飘 */
    val tags: List<String> = emptyList(),
    /** 中文国家/地区名。此前被塞在 [frequency] 里当副标题用，现还原为独立字段 */
    val country: String = "",
    /** 中文语言名，同上 */
    val language: String = "",
) {
    /** 获取所有可用节目源（若 sources 为空则用 streamUrl 构造单源） */
    fun allSources(): List<StationSource> {
        if (sources.isNotEmpty()) return sources
        if (streamUrl.isNotBlank()) return listOf(StationSource(url = streamUrl))
        return emptyList()
    }

    /** 默认主源 URL（first source 或 streamUrl） */
    val primaryUrl: String get() {
        if (sources.isNotEmpty()) return sources.first().url
        return streamUrl
    }
}

@Serializable
data class Category(
    val id: String,
    val name: String,
    val icon: String = ""
)

data class FavoriteStation(
    val stationId: String,
    val addedTime: Long
)

/** 用户自定义的节目源可用性分数 */
data class SourceAvailability(
    val stationId: String,
    val sourceUrl: String,
    val score: Float = 0.5f,
    val responseTimeMs: Long = 0,
    val successCount: Int = 0,
    val failCount: Int = 0
)
