package com.radio.chinese

import com.radio.chinese.data.local.CityCoordinates
import com.radio.chinese.domain.model.NearestCityFinder
import com.radio.chinese.domain.model.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 离线最近城市匹配。钉住：表覆盖度、已知坐标命中、境外超阈值返回 null（人在国外绝不硬塞错误城市）。
 */
class NearestCityFinderTest {
    private val cities = CityCoordinates.parse(
        listOf(
            File("src/main/assets/city_coordinates.json"),
            File("app/src/main/assets/city_coordinates.json"),
        ).first { it.exists() }.readText(Charsets.UTF_8)
    )

    @Test fun `表覆盖 100 个以上城市`() {
        assertTrue("实际 ${cities.size}", cities.size >= 100)
    }

    @Test fun `北京坐标命中北京`() {
        val r = NearestCityFinder.find(39.90, 116.40, cities)
        assertEquals("北京", r?.city)
    }

    @Test fun `上海坐标命中上海`() {
        assertEquals(Region("上海", "上海"), NearestCityFinder.find(31.23, 121.47, cities))
    }

    @Test fun `境外坐标超阈值返回 null`() {
        assertNull(NearestCityFinder.find(51.50, -0.12, cities)) // 伦敦
    }
}
