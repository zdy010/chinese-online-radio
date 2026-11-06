package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.SkipBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切台基准的选取规则（spec V11）。
 *
 * 这条不是"测一个局部 pick() 函数"：断言的是 PlayerManager 真正调用的那个纯函数，
 * 把 SkipBase.resolve 删掉或改回硬编码，这里会红。
 */
class SkipBaseSelectionTest {

    private val radio = listOf(RadioStation(id = "cnr", name = "中国之声", category = "news"))
    private val iptv = listOf(
        RadioStation(id = "iptv:a", name = "A", category = "general"),
        RadioStation(id = "iptv:b", name = "B", category = "general"),
    )

    @Test
    fun `注册过就用注册的表`() {
        assertEquals(listOf("iptv:a", "iptv:b"), SkipBase.resolve(iptv, radio).map { it.id })
    }

    @Test
    fun `未注册时回落电台活动列表`() {
        assertEquals("cnr", SkipBase.resolve(null, radio).single().id)
    }

    @Test
    fun `空表按未注册处理`() {
        // 清单还在加载时页面会先给一个空表；拿它当基准会让滚轮彻底切不动
        assertEquals("cnr", SkipBase.resolve(emptyList(), radio).single().id)
    }

    @Test
    fun `两边都空时返回空表而不是抛异常`() {
        // 无正在播放时上层保持既有 no-op，不能把异常抛到媒体键回调里
        assertTrue(SkipBase.resolve(null, emptyList()).isEmpty())
    }

    @Test
    fun `电台页与 iptv 页的基准不会互相污染`() {
        // 注册的 iptv 表里不含任何电台 id，反之亦然；这是双向隔离的结构性判据
        assertTrue(SkipBase.resolve(iptv, radio).none { !it.id.startsWith("iptv:") })
        assertTrue(SkipBase.resolve(null, radio).none { it.id.startsWith("iptv:") })
    }
}
