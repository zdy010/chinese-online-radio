package com.radio.chinese

import com.radio.chinese.domain.model.IptvBrief
import com.radio.chinese.domain.model.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Test

class IptvBriefTest {

    private fun station(
        country: String = "",
        tags: List<String> = emptyList(),
        language: String = "",
    ) = RadioStation(
        id = "iptv:x", name = "X", category = "general",
        country = country, tags = tags, language = language,
    )

    @Test
    fun `按国家分类语言拼一行简介`() {
        assertEquals(
            "中国 · 新闻 · 中文",
            IptvBrief.of(station(country = "中国", tags = listOf("news"), language = "zho")),
        )
    }

    @Test
    fun `未知项省略而不是留空槽`() {
        assertEquals("美国", IptvBrief.of(station(country = "美国")))
        assertEquals("电影", IptvBrief.of(station(tags = listOf("movies"))))
        assertEquals("", IptvBrief.of(station()))
    }

    @Test
    fun `未收录的标签落成其他时不写进简介`() {
        assertEquals(
            "日本 · 英语",
            IptvBrief.of(station(country = "日本", tags = listOf("unknown_tag"), language = "eng")),
        )
    }
}
