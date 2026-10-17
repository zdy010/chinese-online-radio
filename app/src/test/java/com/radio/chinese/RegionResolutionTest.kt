package com.radio.chinese

import com.radio.chinese.domain.model.Region
import com.radio.chinese.domain.model.resolveRegion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 地区优先级：手动 > 自动缓存 > 无。此前设置页改了地区，
 * 首页要重启才生效——因为消费端只在建仓时读一次；纯解析先钉死优先级本身。
 */
class RegionResolutionTest {
    @Test fun `手动优先于缓存`() {
        assertEquals(
            Region("广东", "深圳"),
            resolveRegion(manual = "广东|深圳", cached = "浙江|绍兴")
        )
    }

    @Test fun `无手动时用缓存`() {
        assertEquals(Region("浙江", "绍兴"), resolveRegion(manual = null, cached = "浙江|绍兴"))
    }

    @Test fun `手动为空串或无效时不遮蔽缓存`() {
        assertEquals(Region("浙江", "绍兴"), resolveRegion(manual = "|", cached = "浙江|绍兴"))
        assertEquals(Region("浙江", "绍兴"), resolveRegion(manual = "", cached = "浙江|绍兴"))
    }

    @Test fun `两者皆无得 NONE`() {
        assertEquals(Region.NONE, resolveRegion(null, null))
        assertEquals(Region.NONE, resolveRegion("", "  "))
    }

    @Test fun `只给省名时 city 为空`() {
        assertEquals(Region("浙江", ""), resolveRegion("浙江|", null))
    }
}
