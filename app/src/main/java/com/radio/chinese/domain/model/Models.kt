package com.radio.chinese.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class StationSource(
    val url: String,
    val label: String = "",
    val bitrate: Int = 0,
    /**
     * 分辨率（如 1080p）。刻意不复用 [label]：label 会被写进
     * 「正在尝试节目源 X…」状态文案与通知栏的 artist 字段，
     * 把分辨率放这里那些位置才能继续回落到「源 1 / 源 2」。
     */
    val quality: String = "",
    /** 流要求的 User-Agent；空串表示不覆盖。来自 iptv-org 清单 */
    val userAgent: String = "",
    /** 流要求的 Referer；空串表示不覆盖 */
    val referrer: String = "",
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
    /**
     * 标签 key 列表。只存 key，避免中文文案与数据两头飘。
     *
     * **词表按来源分两套**（不是当初那句“必须能在 StationTag 里查到”）：
     * 电台台（含自建台）的取值在 [StationTag] 内；iptv 台（id 以 `iptv:` 开头）的取值在
     * [IptvCategory] 内。`kids` 与 `weather` 两个 key 在两套词表里重名，**靠 id 前缀区分**。
     *
     * 不能混用的理由不是洁癖：StationFilter、TagScreen、HomeViewModel、
     * ManageViewModel.sanitizeTags 四处都按 StationTag 把关并丢弃词表外的 key，
     * iptv 的 tags 流进那些路径会被静默清空。
     */
    val tags: List<String> = emptyList(),
    /** 中文国家/地区名。此前被塞在 [frequency] 里当副标题用，现还原为独立字段 */
    val country: String = "",
    /** 省份/区域。仅 iptv 中国频道有值（如「安徽」），其余为空；用于按省筛选 */
    val region: String = "",
    /** 中文语言名，同上 */
    val language: String = "",
    /**
     * 频道别名，参与搜索匹配。iptv 台常有多种写法（如 CCTV 1 / 中央电视台综合频道），
     * 不存下来用户只能猜台名。
     */
    val altNames: List<String> = emptyList(),
    /**
     * 成人标记。iptv-org 的 is_nsfw 落在这里。
     *
     * 默认 false 是为了老数据零迁移：内置清单与用户自建台（存 DataStore 的 JSON）
     * 都没有这个字段，去掉默认值会让升级后解析失败 —— 那是“收藏与自建台全空”的量级。
     */
    val nsfw: Boolean = false,
    /**
     * 只有 VPN/代理才能访问：所有源在直连严格探测都没通过、至少一个源在代理探测通过。
     * 由 tools/iptv_vpn_only.py 从两轮实测生成，tools/iptv_sync.ps1 按 id 回填。
     *
     * 同样必须带默认值：老清单与用户自建台（DataStore JSON）没有这个字段，
     * 去掉默认值会让升级后解析失败，收藏与自建台会整片变空。
     */
    val vpnOnly: Boolean = false,
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
