package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.nextStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 线控切台的“下一个/上一个”目标解析。命中当前取相邻；末位前进回绕；
 * 当前不在列表（含 null）时前进取首位、后退取末位；空列表 null。
 */
class NextStationTest {
    private fun st(id: String) = RadioStation(id = id, name = id, category = "general")
    private val list = listOf(st("a"), st("b"), st("c"))

    @Test fun `中间前进取下一个`() {
        assertEquals("b", nextStation(list, "a", true)?.id)
        assertEquals("c", nextStation(list, "b", true)?.id)
    }

    @Test fun `末位前进回绕首位`() {
        assertEquals("a", nextStation(list, "c", true)?.id)
    }

    @Test fun `首位后退回绕末位`() {
        assertEquals("c", nextStation(list, "a", false)?.id)
    }

    @Test fun `当前不在列表时前进取首位`() {
        assertEquals("a", nextStation(list, "x", true)?.id)
    }

    @Test fun `空列表返回 null`() {
        assertNull(nextStation(emptyList(), "a", true))
    }

    @Test fun `currentId 为空前进取首位`() {
        assertEquals("a", nextStation(list, null, true)?.id)
    }
}
