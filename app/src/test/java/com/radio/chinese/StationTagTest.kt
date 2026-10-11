package com.radio.chinese

import com.radio.chinese.domain.model.StationTag
import com.radio.chinese.domain.model.TagGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签词表的约束。改词表的人多半是想"加一个标签"，这几条测试拦的是
 * 加进来之后才暴露的坑：key 撞名、与分类语义重复、未知 key 把界面显示成空白。
 */
class StationTagTest {
    @Test
    fun `key 全局唯一`() {
        val keys = StationTag.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `四个分组都非空`() {
        for (g in TagGroup.entries) {
            assertTrue("${g.name} 组为空", StationTag.entries.any { it.group == g })
        }
    }

    @Test
    fun `越剧与粤剧 key 不冲突`() {
        // 两者拼音同为 yueju，是真实撞名，必须用不同 key
        assertEquals("越剧", StationTag.labelOf("yueju"))
        assertEquals("粤剧", StationTag.labelOf("yueopera"))
    }

    @Test
    fun `乡村音乐不占用 country 这个 key`() {
        // 与 RadioStation.country 字段同名会让人分不清数据里哪个是哪个
        assertFalse(StationTag.isValidKey("country"))
        assertTrue(StationTag.isValidKey("countrymusic"))
        assertEquals("乡村", StationTag.labelOf("countrymusic"))
    }

    @Test
    fun `未知 key 回落显示原值而不是空或崩`() {
        assertEquals("不存在的标签", StationTag.labelOf("不存在的标签"))
        assertNull(StationTag.fromKey("nope"))
        assertFalse(StationTag.isValidKey("nope"))
    }

    @Test
    fun `标签不与分类语义重复`() {
        // 纪律 1：这些词是 StationCategory 的活，打成标签等于零信息量筛选
        for (banned in listOf("news", "sports", "traffic", "finance", "opera", "music")) {
            assertFalse("词表里不该有与分类同名的 key: $banned", StationTag.isValidKey(banned))
        }
    }

    @Test
    fun `byKeyOrder 丢弃未知 key 并按分组顺序取前 N`() {
        val picked = StationTag.byKeyOrder(listOf("rock", "没这个", "jingju"), limit = 4)
        assertEquals(listOf("jingju", "rock"), picked.map { it.key })
    }

    @Test
    fun `分组顺序为戏曲 内容 风格 场景`() {
        assertEquals(
            listOf(TagGroup.Opera, TagGroup.Content, TagGroup.Music, TagGroup.Scene),
            StationTag.orderedByGroup().map { it.first }
        )
    }

    @Test
    fun `词表规模不低于 spec 初版的 48 个`() {
        assertTrue("实际 ${StationTag.entries.size}", StationTag.entries.size >= 48)
    }
}
