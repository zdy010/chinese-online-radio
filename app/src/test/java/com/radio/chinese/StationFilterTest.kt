package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationTag
import com.radio.chinese.domain.model.StationFilter
import org.junit.Assert.assertEquals
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
    ) = RadioStation(
        id = id, name = name, category = cat, streamUrl = "https://a/$id",
        frequency = freq, country = country, language = lang, tags = tags.toList(),
    )

    private val sample = listOf(
        st("1", "opera", "yuju", "history", name = "河南豫剧广播", country = "中国", lang = "华语"),
        st("2", "news", "localnews", name = "浙江新闻广播", freq = "FM 88.0"),
        st("3", "music", "jazz", name = "Linn Jazz", country = "英国", lang = "英语"),
        st("4", "opera", "unknown_tag_key", name = "脏数据台"),
    )

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
}
