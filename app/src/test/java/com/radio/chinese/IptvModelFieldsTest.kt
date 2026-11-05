package com.radio.chinese

import com.radio.chinese.data.local.StationJson
import com.radio.chinese.domain.model.IptvAdult
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationSource
import com.radio.chinese.domain.model.StreamHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iptv 需要的模型字段，以及"老数据零迁移"这条守卫。
 *
 * 最要紧的是那条读真实 radio_stations.json 的用例：新字段全部带默认值，
 * 一旦有人把默认值去掉或给它们设成必填，用户自建电台（存在 DataStore 里的 JSON）
 * 会在下次启动时解析失败 —— 那是"升级后收藏与自建台全空"的量级，
 * 而单测里只要不拿真实文件跑一遍就看不出来。
 */
class IptvModelFieldsTest {

    @Test
    fun `新字段都有默认值，老 JSON 照解`() {
        // 形状跟生产一致：radio_stations.json 顶层是 {"stations":[...]} 包装对象，不是裸数组
        val legacy = """{"stations":[{"id":"cnr","name":"中国之声","category":"news","streamUrl":"http://a/b"}]}"""
        val list = StationJson.parse(legacy)
        assertEquals(1, list.size)
        assertEquals("", list[0].altNames.joinToString())
        assertFalse(list[0].nsfw)
    }

    /**
     * iptv 清单要靠同一个解析器，否则就是“同一件事两个真相”：
     * 第二个解析器不会有人同步补字段，新字段会**静默丢**——
     * 表现为别名搜不到、成人内容藏不住、header 不生效，而三处都不报错。
     */
    @Test
    fun `StationJson 要能把新字段读进来`() {
        val text = """
            {"stations":[{"id":"iptv:CCTV1.cn","name":"CCTV 1","category":"general",
              "altNames":["中央电视台综合频道","CCTV-1"],"nsfw":false,
              "sources":[{"url":"http://a/1.m3u8","label":"","quality":"1080p",
                           "userAgent":"UA-X","referrer":"http://ref"}]}]}
        """.trimIndent()
        val st = StationJson.parse(text).single()
        assertEquals(listOf("中央电视台综合频道", "CCTV-1"), st.altNames)
        assertFalse(st.nsfw)
        val src = st.sources.single()
        assertEquals("1080p", src.quality)
        assertEquals("UA-X", src.userAgent)
        assertEquals("http://ref", src.referrer)
    }

    @Test
    fun `nsfw=true 要能被读出来`() {
        val text = """{"stations":[{"id":"iptv:x","name":"X","category":"general","nsfw":true}]}"""
        assertTrue(StationJson.parse(text).single().nsfw)
    }

    @Test
    fun `老清单里没有的新字段不能报错也不能造出假值`() {
        val text = """{"stations":[{"id":"cnr","name":"中国之声","category":"news",
              "sources":[{"url":"http://a/b","label":"主源","bitrate":128}]}]}"""
        val src = StationJson.parse(text).single().sources.single()
        assertEquals("主源", src.label)
        assertEquals(128, src.bitrate)
        assertEquals("", src.quality)
        assertEquals("", src.userAgent)
        assertEquals("", src.referrer)
    }

    /**
     * 资源路径按项目已有写法试两个候选：单元测试的工作目录通常是模块目录（app/），
     * 不同 Gradle 版本可能差一层 —— 写死一个路径会得到“找不到文件”的假失败。
     * （StationJsonTest / EpgStationsTest / RegionGazetteerTest 都是这个模式）
     */
    private fun assetsFile(name: String): java.io.File {
        val candidates = listOf(
            java.io.File("src/main/assets/$name"),
            java.io.File("app/src/main/assets/$name"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("找不到 $name，尝试过: $candidates")
    }

    @Test
    fun `仓库里的真实电台清单能被新模型解析`() {
        // 与生产同一份资源：这条用例不测代码，测“新字段不会打断老数据”
        val raw = assetsFile("radio_stations.json").readText(Charsets.UTF_8)
        val parsed = StationJson.parse(raw)
        assertTrue("真实清单解析出来是空", parsed.isNotEmpty())
        // 老数据没有这些字段 → 必须是默认值，不能是 null/异常
        assertTrue(parsed.none { it.nsfw })
        assertTrue(parsed.all { it.altNames.isEmpty() })
        assertTrue(parsed.flatMap { it.sources }.all { it.quality == "" && it.userAgent == "" })
    }

    @Test
    fun `quality 走新字段不借用 label`() {
        val s = StationSource(url = "http://a/b.m3u8", quality = "1080p")
        assertEquals("", s.label)          // 留空 → UI 回落「源 N」
        assertEquals("1080p", s.quality)
    }

    @Test
    fun `成人判定是标记与分类的并集`() {
        assertTrue(IptvAdult.isAdult(RadioStation(id = "a", name = "A", category = "general", nsfw = true)))
        assertTrue(IptvAdult.isAdult(RadioStation(id = "b", name = "B", category = "general", tags = listOf("xxx"))))
        assertFalse(IptvAdult.isAdult(RadioStation(id = "c", name = "C", category = "general", tags = listOf("news"))))
        assertEquals("xxx", IptvAdult.adultCategoryKey)
    }

    @Test
    fun `StreamHeaders 原样携带两个值`() {
        val h = StreamHeaders(userAgent = "UA/1.0", referrer = "https://x.y")
        assertEquals("UA/1.0", h.userAgent)
        assertEquals("https://x.y", h.referrer)
    }
}
