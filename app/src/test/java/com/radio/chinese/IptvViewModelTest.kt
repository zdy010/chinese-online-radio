package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.ui.iptv.IptvViewLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iptv 页可见集合的守卫（spec §8 / review C1）。
 *
 * 断言的是 ViewModel 真正调用的 [IptvViewLogic.build]，不是在测试里重写一份过滤：
 * 上一版把规则写成测试里的局部函数，写完就绿，删掉生产逻辑也照样 PASS。
 */
class IptvViewModelTest {

    private val all = listOf(
        RadioStation(
            id = "iptv:CCTV1.cn", name = "CCTV 1", category = "general",
            altNames = listOf("中央电视台综合频道"), tags = listOf("general"), country = "中国",
        ),
        RadioStation(
            id = "iptv:XZ.cn", name = "西藏卫视", category = "general",
            tags = listOf("news"), country = "中国",
        ),
        RadioStation(
            id = "iptv:porn.tv", name = "Adult One", category = "general", nsfw = true,
            tags = listOf("general"),
        ),
        RadioStation(
            id = "iptv:xxx.tag", name = "Tagged Adult", category = "general",
            tags = listOf("xxx"),
        ),
        RadioStation(
            id = "iptv:unknown.cat", name = "Unknown Cat", category = "general",
            tags = listOf("brand-new-a", "brand-new-b"),
        ),
        // 电台 id 混进同一份输入：证明 iptv 页按前缀解析，不吃电台的账
        RadioStation(id = "cnr", name = "中国之声", category = "news", country = "中国"),
    )

    private fun build(
        adultEnabled: Boolean = false,
        allLanguages: Boolean = false,
        query: String = "",
        favIds: List<String> = emptyList(),
        recentIds: List<String> = emptyList(),
    ) = IptvViewLogic.build(all, query, adultEnabled, allLanguages, favIds, recentIds)

    @Test
    fun `默认只留中文与英语频道，开关打开后全显示`() {
        val data = listOf(
            RadioStation(id = "iptv:cn.cn", name = "CN", category = "general", language = "country:zho"),
            RadioStation(id = "iptv:eng.us", name = "EN", category = "general", language = "eng"),
            RadioStation(id = "iptv:jpn.jp", name = "JP", category = "general", language = "country:jpn"),
            RadioStation(id = "iptv:us.us", name = "US", category = "general", language = "country:eng,spa"),
            RadioStation(id = "iptv:direct:x", name = "D", category = "general", language = ""),
        )
        val off = IptvViewLogic.build(data, "", false, false, emptyList(), emptyList())
            .visible.map { it.id }
        assertEquals(setOf("iptv:cn.cn", "iptv:eng.us", "iptv:direct:x"), off.toSet())
        val on = IptvViewLogic.build(data, "", false, true, emptyList(), emptyList())
            .visible.map { it.id }
        assertEquals(5, on.size)
    }

    @Test
    fun `默认隐藏成人，标记与 xxx 分类都算`() {
        val off = build().visible.map { it.id }
        assertTrue("nsfw 标记漏出", "iptv:porn.tv" !in off)
        assertTrue("xxx 分类漏出", "iptv:xxx.tag" !in off)
        assertEquals(setOf("iptv:CCTV1.cn", "iptv:XZ.cn", "iptv:unknown.cat"), off.toSet())
        val on = build(adultEnabled = true).visible.map { it.id }
        assertTrue(on.contains("iptv:porn.tv"))
        assertTrue(on.contains("iptv:xxx.tag"))
    }

    @Test
    fun `收藏只解析出 iptv 条目，电台 id 不会混进来`() {
        val lists = build(favIds = listOf("iptv:CCTV1.cn", "cnr", "iptv:GONE.xx"))
        assertEquals(listOf("iptv:CCTV1.cn"), lists.favorites.map { it.id })
    }

    @Test
    fun `最近同理：认不出的 id 被丢弃而不是显示空白行`() {
        val lists = build(recentIds = listOf("iptv:XZ.cn", "gl_uk", "iptv:GONE.xx", "cnr"))
        assertEquals(listOf("iptv:XZ.cn"), lists.recents.map { it.id })
    }

    @Test
    fun `搜索走 StationFilter，别名也能命中`() {
        assertEquals(listOf("iptv:XZ.cn"), build(query = "西藏").visible.map { it.id })
        assertEquals(listOf("iptv:CCTV1.cn"), build(query = "中央电视台综合频道").visible.map { it.id })
        assertTrue(build(query = "不存在的东西").visible.isEmpty())
    }

    @Test
    fun `分类计数先翻译再聚合，未知 key 只产生一个其他`() {
        val entries = build().categories
        val counts = entries.toMap()
        assertTrue(counts["xxx"] == null)
        assertEquals(1, counts["综合"])
        assertEquals(1, counts["新闻"])
        assertEquals(2, counts["其他"])
        val labels = entries.map { it.first }
        assertEquals("列表 key 撞车会抛 Key was already used", labels.size, labels.toSet().size)
    }

    @Test
    fun `成人开关关闭时收藏与最近也不含成人台`() {
        val off = build(
            favIds = listOf("iptv:porn.tv"),
            recentIds = listOf("iptv:xxx.tag", "iptv:CCTV1.cn"),
        )
        assertTrue(off.favorites.isEmpty())
        assertEquals(listOf("iptv:CCTV1.cn"), off.recents.map { it.id })

        val on = build(
            adultEnabled = true,
            favIds = listOf("iptv:porn.tv"),
            recentIds = listOf("iptv:xxx.tag"),
        )
        assertEquals(listOf("iptv:porn.tv"), on.favorites.map { it.id })
        assertEquals(listOf("iptv:xxx.tag"), on.recents.map { it.id })
    }

    @Test
    fun `国家名走中文且不产生空格子`() {
        val countries = build().countries
        assertEquals(2, countries.toMap()["中国"])
        assertTrue(countries.all { it.first.isNotBlank() })
    }
}
