package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义电台是以 List<RadioStation> 的 JSON 存在 DataStore 里的。
 * 老版本存下的记录没有 tags/country/language 三个键，升级后必须安静地当空处理——
 * 这里一旦失败，用户管理页里的自定义电台会整批消失。
 */
class RadioStationCompatTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `旧自定义电台 JSON 缺三个新字段仍能解析`() {
        val legacy = """[{"id":"custom_1","name":"我的台","category":"news",
            |"streamUrl":"https://a/b.mp3","logoUrl":"","frequency":"FM 90.0",
            |"description":"","website":"","sources":[]}]""".trimMargin()
        val list = json.decodeFromString<List<RadioStation>>(legacy)
        assertEquals(1, list.size)
        assertEquals("我的台", list[0].name)
        assertTrue(list[0].tags.isEmpty())
        assertEquals("", list[0].country)
        assertEquals("", list[0].language)
    }

    @Test
    fun `新字段写入再读出一致`() {
        val station = RadioStation(
            id = "custom_2",
            name = "豫剧台",
            category = "opera",
            streamUrl = "https://a/b.m3u8",
            tags = listOf("yuju", "history"),
            country = "中国",
            language = "华语",
        )
        val roundTrip = json.decodeFromString<List<RadioStation>>(json.encodeToString(listOf(station)))
        assertEquals(station, roundTrip.single())
    }

    @Test
    fun `旧版本遇到未来新增字段不崩`() {
        // ignoreUnknownKeys 的兜底：降级安装时 assets 与 DataStore 里可能多出老代码不认识的键
        val newer = """[{"id":"custom_3","name":"新格式台","category":"music",
            |"streamUrl":"https://a/c","sources":[],"tags":["jazz"],
            |"country":"英国","language":"英语","将来字段":"xxx"}]""".trimMargin()
        val list = json.decodeFromString<List<RadioStation>>(newer)
        assertEquals(listOf("jazz"), list.single().tags)
    }
}
