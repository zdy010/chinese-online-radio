package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationTag
import com.radio.chinese.domain.model.StationFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签、分类、搜索三个维度必须正交组合。
 *
 * 最容易出的两个错：加了标签筛选把分类丢掉（用户以为还能同时用），
 * 以及没有任何电台的标签也出现在入口里（点进去只看到空列表）。
 */
class StationFilterTest {
    private fun st(
        id: String,
        cat: String,
        vararg tags: String,
        name: String = id,
        freq: String = "",
        country: String = "",
        lang: String = "",
        region: String = "",
    ) = RadioStation(
        id = id, name = name, category = cat, streamUrl = "https://a/$id",
        frequency = freq, country = country, language = lang, region = region,
        tags = tags.toList(),
    )

    private val sample = listOf(
        st("1", "opera", "yuju", "history", name = "河南豫剧广播", country = "中国", lang = "华语"),
        st("2", "news", "localnews", name = "浙江新闻广播", freq = "FM 88.0"),
        st("3", "music", "jazz", name = "Linn Jazz", country = "英国", lang = "英语"),
        st("4", "opera", "unknown_tag_key", name = "脏数据台"),
    )

    @Test
    fun `搜索匹配省份`() {
        // 台名里没有省名的也要能被「江西」搜到：用户按省找台是自然行为
        val data = listOf(
            st("a", "general", name = "经济生活频道", region = "江西"),
            st("b", "general", name = "都市频道", region = "江西"),
            st("c", "general", name = "少儿频道", region = "安徽"),
        )
        assertEquals(listOf("a", "b"), StationFilter.apply(data, null, "江西").map { it.id })
    }

    @Test
    fun `空标签不会让搜索命中所有台`() {
        // 脏数据里有空 tag key 时，q.contains("") 恒为真会把整张表都搜出来
        val data = listOf(
            st("a", "general", "", name = "有脏标签台"),
            st("b", "general", "music", name = "音乐台"),
        )
        assertTrue(StationFilter.apply(data, null, "江西").isEmpty())
    }

    @Test
    fun `标签与分类同时生效`() {
        assertEquals(listOf("1"), StationFilter.apply(sample, "opera", "", "yuju").map { it.id })
        assertTrue(StationFilter.apply(sample, "news", "", "yuju").isEmpty())
    }

    @Test
    fun `只按标签筛选时跨分类生效`() {
        assertEquals(listOf("1"), StationFilter.apply(sample, null, "", "history").map { it.id })
    }

    @Test
    fun `搜索能命中标签中文名`() {
        // 用户输「豫剧」时并不关心它是标签还是台名，搜不到就是难用
        assertEquals(listOf("1"), StationFilter.apply(sample, null, "豫剧", null).map { it.id })
    }

    @Test
    fun `搜索能命中国家与语言`() {
        assertEquals(listOf("3"), StationFilter.apply(sample, null, "英国", null).map { it.id })
        assertEquals(listOf("3"), StationFilter.apply(sample, null, "英语", null).map { it.id })
    }

    @Test
    fun `搜索保留原有的频率命中能力`() {
        assertEquals(listOf("2"), StationFilter.apply(sample, null, "88.0", null).map { it.id })
    }

    @Test
    fun `搜索与标签同时生效`() {
        assertEquals(listOf("1"), StationFilter.apply(sample, null, "豫剧", "yuju").map { it.id })
        assertTrue(StationFilter.apply(sample, null, "豫剧", "jazz").isEmpty())
    }

    @Test
    fun `只统计真有电台的标签并丢弃词表外key`() {
        val counted = StationFilter.tagsWithCount(sample).associate { it.first.key to it.second }
        assertEquals(mapOf("yuju" to 1, "history" to 1, "localnews" to 1, "jazz" to 1), counted)
    }

    @Test
    fun `标签计数按词表顺序稳定`() {
        val keys = StationFilter.tagsWithCount(sample).map { it.first.key }
        assertEquals(StationTag.keyOrder.filter { it in keys.toSet() }, keys)
    }

    @Test
    fun `空查询与空分类不改变结果`() {
        assertEquals(sample.size, StationFilter.apply(sample, null, "", null).size)
        assertEquals(sample.size, StationFilter.apply(sample, null, "   ", null).size)
    }

    // ---- 拼音简称 ----

    private val pinyinSample = listOf(
        st("zj", "traffic", name = "浙江交通之声"),
        st("hn", "opera", name = "河南豫剧广播"),
        st("lin", "music", name = "Linn Jazz"),
    )

    @Test
    fun `首字母能搜到中文台名`() {
        assertEquals(listOf("zj"), StationFilter.apply(pinyinSample, null, "zjjt", null).map { it.id })
        assertEquals(listOf("zj"), StationFilter.apply(pinyinSample, null, "ZJJT", null).map { it.id })
    }

    @Test
    fun `全拼能搜到中文台名`() {
        assertEquals(
            listOf("zj"),
            StationFilter.apply(pinyinSample, null, "zhejiangjiaotong", null).map { it.id },
        )
    }

    @Test
    fun `拼音不介入中文查询`() {
        // 中文查询走原有路径；若拼音列也参与，`zg` 这种两句词根很容易把全表拉进来
        assertEquals(listOf("hn"), StationFilter.apply(pinyinSample, null, "豫剧", null).map { it.id })
        assertTrue(StationFilter.apply(pinyinSample, null, "zhejiang", null).isNotEmpty())
    }

    @Test
    fun `英文台名仍按原名命中`() {
        assertEquals(listOf("lin"), StationFilter.apply(pinyinSample, null, "jazz", null).map { it.id })
    }

    /**
     * iptv 台常有多种写法（CCTV 1 / 中央电视台综合频道 / CCTV-1），
     * 不匹配别名就只能猜台名。拼音仍只对台名生效，不把别名也拼进拼音列：
     * 别名多是英文全称，展开后会把假命中面铺大。
     */
    @Test
    fun `别名参与搜索匹配`() {
        val st = RadioStation(
            id = "iptv:CCTV1.cn", name = "CCTV 1", category = "general",
            altNames = listOf("中央电视台综合频道", "CCTV-1"),
        )
        assertTrue(StationFilter.matches(st, "中央电视台综合频道"))
        assertTrue(StationFilter.matches(st, "cctv-1"))
        assertTrue(StationFilter.matches(st, "CCTV 1"))
        assertFalse(StationFilter.matches(st, "不存在的写法"))
    }

    /**
     * 名字要配得上能力：上一版这条叫“两页对同一 query 走同一套判定”，
     * 但它不引用任何页面代码 —— Task 8 的 IptvViewModel 里写一段自己的
     * `filter { it.name.contains(q) }`，它照样绿。所以改回它真正测的东西，
     * 跳页一致则挂到 Task 8 的验收项（在那一页真断言）。
     *
     * 顺便记下审查查到的四处自造匹配（都是既有代码，不是本提交引入）：
     * FavoritesScreen（只比台名）、RadioRecentTab（只比台名）、EpgViewModel（台名+节目名）、
     * StationRepository.searchStations（无别名/国家/标签/拼音，且全仓无调用点）。
     * 收藏与最近一旦接入 iptv 条目，这两页搜不到别名而首页搜得到 —— Task 8 必须一并处理。
     */
    @Test
    fun `别名参与匹配且 apply 委托 matches`() {
        val list = listOf(
            RadioStation(id = "a", name = "音乐台", category = "music"),
            RadioStation(id = "iptv:b", name = "电影台", category = "general",
                altNames = listOf("影视频道")),
        )
        assertEquals(listOf("iptv:b"), StationFilter.apply(list, null, "影视", null).map { it.id })
        assertEquals(listOf("a"), StationFilter.apply(list, null, "音乐", null).map { it.id })
        assertEquals(2, StationFilter.apply(list, null, "", null).size)
    }
}
