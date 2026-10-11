package com.radio.chinese

import com.radio.chinese.ui.manage.sanitizeTags
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自定义电台的标签清洗。UI 只给词表内的 chip，但这里仍要防住脏输入：
 * 例如从别的版本同步回来的 DataStore，或将来某个入口直接传 key。
 */
class CustomStationTagsTest {
    @Test
    fun `丢掉词表外的key并去重`() {
        assertEquals(listOf("yuju", "jazz"), sanitizeTags(listOf("yuju", "京剧", "yuju", "jazz")))
    }

    @Test
    fun `超过六个只留词表优先级靠前的`() {
        val raw = listOf("rock", "pop", "jazz", "folk", "blues", "oldies", "yuju", "crosstalk")
        val got = sanitizeTags(raw)
        // 期望值由词表顺序推导，而不是人工排一遍：人排会排错，也就测不出“顺序错”这个 bug
        val expected = com.radio.chinese.domain.model.StationTag.keyOrder
            .filter { it in raw.toSet() }
            .take(6)
        assertEquals(expected, got)
        assertEquals(6, got.size)
    }

    @Test
    fun `空白与空串被忽略`() {
        assertEquals(emptyList<String>(), sanitizeTags(listOf("", "   ", "nope")))
    }

    @Test
    fun `输入顺序不影响结果顺序`() {
        assertEquals(sanitizeTags(listOf("jingju", "drive")), sanitizeTags(listOf("drive", "jingju")))
    }
}
