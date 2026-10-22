package com.radio.chinese

import com.radio.chinese.domain.PinyinIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 索引字符串的生成规则。
 *
 * 这些断言全部来自对 pinyin4j 的实测探针，不是按文档想象：
 * 多音字候选顺序不是「常用音在前」，所以任何「取第一个读音」的写法
 * 都会在这里挂掉。
 */
class PinyinIndexTest {

    // ---- 多音字 ----

    @Test
    fun `长 同时给出 zhang 与 chang 两种读法`() {
        val full = PinyinIndex.variants("长")
        assertTrue("应含 zhang，实际 $full", "zhang" in full)
        assertTrue("应含 chang，实际 $full", "chang" in full)
    }

    @Test
    fun `重庆 的首字母同时能拼出 zq 与 cq`() {
        val initials = PinyinIndex.initialsVariants("重庆")
        assertTrue("应含 zq，实际 $initials", "zq" in initials)
        assertTrue("应含 cq，实际 $initials", "cq" in initials)
    }

    @Test
    fun `重复候选被去掉`() {
        // 探针实测「王」的候选数组含两个 wang
        assertEquals(listOf("wang"), PinyinIndex.variants("王"))
    }

    // ---- 用户点名的例子 ----

    @Test
    fun `wf 能命中王菲与汪峰`() {
        val targets = listOf("王菲", "汪峰", "张韶涵")
        val hits = targets.filter { "wf" in PinyinIndex.initialsVariants(it) }
        assertEquals(listOf("王菲", "汪峰"), hits)
    }

    @Test
    fun `wangfei 能命中王菲与王飞`() {
        val targets = listOf("王菲", "王飞", "汪峰")
        val hits = targets.filter { "wangfei" in PinyinIndex.variants(it) }
        assertEquals(listOf("王菲", "王飞"), hits)
    }

    // ---- 归一与混排 ----

    @Test
    fun `标点不参与索引`() {
        assertEquals(
            PinyinIndex.column("黄梅戏天仙配"),
            PinyinIndex.column("黄梅戏-天仙配"),
        )
    }

    @Test
    fun `字母与汉字混排时不丢失字母`() {
        assertEquals("|xg|", PinyinIndex.initialsColumn("X光"))
    }

    @Test
    fun `纯英文数字原样参与索引`() {
        assertEquals(listOf("abc123"), PinyinIndex.variants("ABC123"))
    }

    // ---- 截断 ----

    @Test
    fun `变体数截断到 16`() {
        // 5 个双读音字 = 32 种组合
        val variants = PinyinIndex.variants("长重乐传行")
        assertEquals(16, variants.size)
        assertEquals(variants.sorted(), variants)
        assertTrue(variants.none { it.isEmpty() })
    }

    // ---- 查询分流 ----

    @Test
    fun `纯字母数字才算拼音查询`() {
        assertTrue(PinyinIndex.isAsciiQuery("wf"))
        assertTrue(PinyinIndex.isAsciiQuery("WF"))
        assertTrue(PinyinIndex.isAsciiQuery("bt360"))
        assertFalse(PinyinIndex.isAsciiQuery("王菲"))
        assertFalse(PinyinIndex.isAsciiQuery("王 wf"))
        assertFalse(PinyinIndex.isAsciiQuery(""))
        assertFalse(PinyinIndex.isAsciiQuery("   "))
    }

    // ---- 边界 ----

    @Test
    fun `空文本与全标点得到空串而不是空壳变体`() {
        assertEquals("", PinyinIndex.column(""))
        assertEquals("", PinyinIndex.column("--- · （）"))
    }

    @Test
    fun `列值首尾都带竖线`() {
        assertEquals("|wangfei|", PinyinIndex.column("王菲"))
        assertEquals("|wf|", PinyinIndex.initialsColumn("王菲"))
    }
}
