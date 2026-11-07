package com.radio.chinese.domain.model

/**
 * iptv 频道的"简介"。
 *
 * 上游没有简介字段——`description` 存的是 network（如 "Pluto TV"），英文且不是简介。
 * 所以按现有结构化字段拼一行：`国家 · 分类 · 语言`，例如「中国 · 新闻 · 中文」。
 * 未知项直接省略，不留空槽（"中国 ·  · 中文" 比没有更糟）。
 */
object IptvBrief {

    fun of(station: RadioStation): String {
        val parts = mutableListOf<String>()
        if (station.country.isNotBlank()) parts += station.country
        val category = station.tags.firstOrNull { it.isNotBlank() }
            ?.let { IptvCategory.labelOf(it) }
        if (!category.isNullOrBlank() && category != "其他") parts += category
        val language = IptvLanguage.displayName(station.language)
        if (language.isNotBlank()) parts += language
        return parts.joinToString(" · ")
    }
}
