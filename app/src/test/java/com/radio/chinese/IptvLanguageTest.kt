package com.radio.chinese

import com.radio.chinese.domain.model.IptvLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语言准入规则（默认只留中文与英语）。
 *
 * 断言的是 IptvViewLogic 真正调用的那个函数：改坏"推测层要求全部中英"这一条，
 * 美国西语台、印度印地语台会立刻混进默认列表，而编译与其它单测全绿。
 */
class IptvLanguageTest {

    @Test
    fun `没有语言信息时不隐藏`() {
        // 上游既不标语言也没有国家（direct 记录）：无法判断，保留
        assertTrue(IptvLanguage.isPreferred(""))
        assertTrue(IptvLanguage.isPreferred("   "))
    }

    @Test
    fun `语言列表明确标注的中文英语保留`() {
        assertTrue(IptvLanguage.isPreferred("zho"))
        assertTrue(IptvLanguage.isPreferred("eng"))
        assertTrue(IptvLanguage.isPreferred("zho,eng"))
    }

    @Test
    fun `国家推测为单一中英的保留`() {
        assertTrue(IptvLanguage.isPreferred("country:zho"))
        assertTrue(IptvLanguage.isPreferred("country:eng"))
    }

    @Test
    fun `多官方语言国家默认隐藏`() {
        // 美国 eng,spa 与印度 eng,hin,tam：按"含 eng 就留"会把整批西语/印地语台放进来
        assertFalse(IptvLanguage.isPreferred("country:eng,spa"))
        assertFalse(IptvLanguage.isPreferred("country:eng,hin,tam"))
    }

    @Test
    fun `其他语言隐藏`() {
        assertFalse(IptvLanguage.isPreferred("country:jpn"))
        assertFalse(IptvLanguage.isPreferred("spa"))
        assertFalse(IptvLanguage.isPreferred("country:rus,ukr"))
    }

    @Test
    fun `副标题不显示内部推测标记`() {
        // 曾经把 country:jpn 原样显示成「日本 · country:jpn」
        assertEquals("", IptvLanguage.displayName("country:jpn"))
        assertEquals("", IptvLanguage.displayName(""))
        assertEquals("", IptvLanguage.displayName("spa"))
    }

    @Test
    fun `副标题把中英代码翻成中文名，中文标签原样保留`() {
        assertEquals("英语", IptvLanguage.displayName("eng"))
        assertEquals("中文", IptvLanguage.displayName("zho"))
        assertEquals("中文", IptvLanguage.displayName("yue,eng"))
        assertEquals("普通话", IptvLanguage.displayName("普通话"))
    }

    @Test
    fun `大小写与空格不影响判定`() {
        assertTrue(IptvLanguage.isPreferred(" ENG "))
        assertTrue(IptvLanguage.isPreferred("country: ZHO "))
        assertFalse(IptvLanguage.isPreferred("country: ENG , SPA "))
    }

    // ========== 分类页语言多选 ==========

    @Test
    fun `多选语言码把推测串归一到选项`() {
        // 西班牙多语言官方码：cat/eus/glg 不是选项，但串里有 spa 就归 spa，
        // 不能整台丢进「其他」
        assertEquals(setOf("spa"), IptvLanguage.codesOf("country:spa,cat,eus,glg"))
        assertEquals(setOf("zho"), IptvLanguage.codesOf("zho"))
        assertEquals(setOf("eng"), IptvLanguage.codesOf("EN"))
        assertEquals(setOf("spa", "eng"), IptvLanguage.codesOf("country:eng,spa"))
        assertEquals(setOf(IptvLanguage.OTHER), IptvLanguage.codesOf(""))
        assertEquals(setOf(IptvLanguage.OTHER), IptvLanguage.codesOf("country:grn"))
    }

    @Test
    fun `空选集不过滤，命中任意一个即显示`() {
        assertTrue(IptvLanguage.matches("country:spa", emptySet()))
        assertTrue(IptvLanguage.matches("country:spa", setOf("spa", "zho")))
        assertFalse(IptvLanguage.matches("country:spa", setOf("zho")))
        assertTrue(IptvLanguage.matches("", setOf(IptvLanguage.OTHER)))
    }

    @Test
    fun `语言选项 key 唯一且都有中文名`() {
        val keys = IptvLanguage.OPTIONS.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.containsAll(listOf("zho", "eng", IptvLanguage.OTHER)))
        assertEquals("中文", IptvLanguage.labelOf("zho"))
        assertEquals("西班牙语", IptvLanguage.labelOf("spa"))
    }
}
