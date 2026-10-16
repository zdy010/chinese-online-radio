package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.Region
import com.radio.chinese.domain.model.StationSorter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本地优先排序：tier 分档 + 稳定（同档保持入参顺序）+ 空用户地区原样返回。
 */
class StationSorterTest {
    private fun st(id: String) = RadioStation(id = id, name = id, category = "general")

    private val regionMap = mapOf(
        "sameCity" to Region("浙江", "绍兴"),
        "sameProv" to Region("浙江", "杭州"),
        "sameCountry" to Region("河南", ""),
        "foreign" to Region("美国", ""),
        "none" to Region.NONE,
    )
    private val regionOf: (RadioStation) -> Region = { regionMap.getValue(it.id) }

    @Test fun `tier 分档`() {
        val user = Region("浙江", "绍兴")
        assertEquals(3, StationSorter.tierOf(Region("浙江", "绍兴"), user))
        assertEquals(2, StationSorter.tierOf(Region("浙江", "杭州"), user))
        assertEquals(1, StationSorter.tierOf(Region("河南", ""), user)) // 同国不同省
        assertEquals(0, StationSorter.tierOf(Region("美国", ""), user))
        assertEquals(0, StationSorter.tierOf(Region.NONE, user))
    }

    @Test fun `按 tier 降序且同 tier 保持入参顺序`() {
        val input = listOf(st("foreign"), st("sameProv"), st("none"), st("sameCity"), st("sameCountry"))
        val out = StationSorter.localPriority(input, Region("浙江", "绍兴"), regionOf).map { it.id }
        assertEquals(listOf("sameCity", "sameProv", "sameCountry", "foreign", "none"), out)
    }

    @Test fun `空用户地区时原样返回`() {
        val input = listOf(st("sameProv"), st("foreign"))
        val out = StationSorter.localPriority(input, Region.NONE, regionOf).map { it.id }
        assertEquals(listOf("sameProv", "foreign"), out)
    }
}
