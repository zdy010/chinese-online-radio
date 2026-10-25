package com.radio.chinese

import com.radio.chinese.domain.model.StationCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分类展示顺序。分类表此前在四个界面各写一份且取值不一致，统一进 StationCategory 之后，
 * “顺序”这件事也必须有唯一一个来源，否则首页 chips 与分类网格会各排各的。
 */
class StationCategoryTest {

    @Test
    fun `浏览顺序把电视伴音置顶，其余相对顺序不变`() {
        val browse = StationCategory.orderedForBrowse().map { it.first }
        assertEquals(StationCategory.TV_AUDIO.key, browse.first())

        val rest = StationCategory.ordered().map { it.first }.filter { it != StationCategory.TV_AUDIO.key }
        assertEquals(rest, browse.drop(1))
    }

    @Test
    fun `下拉与筛选条用的 ordered 不被浏览顺序改写`() {
        // ordered() 是管理页/添加电台下拉的取值来源，置顶只属于浏览视图
        assertEquals(
            StationCategory.entries.map { it.key },
            StationCategory.ordered().map { it.first },
        )
        assertEquals("新闻", StationCategory.ordered().first().second)
    }

    @Test
    fun `电视伴音的中文名只有枚举一处定义`() {
        assertEquals("电视伴音", StationCategory.labelOf(StationCategory.TV_AUDIO.key))
    }
}
