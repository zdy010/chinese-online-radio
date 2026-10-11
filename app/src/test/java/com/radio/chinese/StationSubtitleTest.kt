package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.ui.common.stationSubtitle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 副标题在首页列表、播放器、管理页三处共用同一个纯函数。
 *
 * 老 bug 的形状是：「国家 · 语言」被塞在 frequency 字符串里，三处各自拼接，
 * 于是同一台在两个页面显示不一样的副标题。这里锁的是优先级本身。
 */
class StationSubtitleTest {

    private fun st(freq: String = "", country: String = "", lang: String = "") =
        RadioStation(
            id = "x", name = "x", category = "news", streamUrl = "https://a/b",
            frequency = freq, country = country, language = lang,
        )

    @Test
    fun `真频率优先，大陆台的 FM 值不能被国家盖掉`() {
        assertEquals("FM 88.0", stationSubtitle(st(freq = "FM 88.0"), "新闻"))
    }

    @Test
    fun `无频率时用 国家 · 语言`() {
        assertEquals("德国 · 英语", stationSubtitle(st(country = "德国", lang = "英语"), "新闻"))
    }

    @Test
    fun `只有国家时不出现尾随分隔符`() {
        assertEquals("德国", stationSubtitle(st(country = "德国"), "新闻"))
    }

    @Test
    fun `只有语言时也不出现前导分隔符`() {
        assertEquals("英语", stationSubtitle(st(lang = "英语"), "新闻"))
    }

    @Test
    fun `全空时回落分类中文名`() {
        assertEquals("新闻", stationSubtitle(st(), "新闻"))
    }

    @Test
    fun `分类名也为空时返回空串让调用方不渲染该行`() {
        assertEquals("", stationSubtitle(st(), ""))
    }

    @Test
    fun `频率为空串与全空格等价`() {
        assertEquals("德国", stationSubtitle(st(freq = "   ", country = "德国"), "新闻"))
    }
}
