package com.radio.chinese.domain.model

/**
 * 列表/播放页显示用的台名。
 *
 * iptv 清单的 `name` 是上游英文名（Anhui TV、Beijing Satellite TV），
 * 而 `altNames` 里有 105/156 条中国频道带中文别名（安徽卫视、北京卫视）。
 * 优先显示中文名，用户不用在脑内翻译。
 *
 * CCTV 系列除外：`CCTV-1` 比「中国中央电视台综合频道」更短、更好认，也更好在搜索里匹配。
 */
object StationDisplayName {

    fun of(station: RadioStation): String {
        if (station.name.startsWith("CCTV", ignoreCase = true)) return station.name
        // 原名已是中文（电台几乎都如此）就不用换，避免把「中国之声」换成别的别名
        if (station.name.any { it.code > 127 }) return station.name
        val zh = station.altNames.firstOrNull { it.any { c -> c.code > 127 } }
        return zh?.trim()?.takeIf { it.isNotEmpty() } ?: station.name
    }
}
