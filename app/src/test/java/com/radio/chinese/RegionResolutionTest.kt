package com.radio.chinese

import com.radio.chinese.domain.model.Region
import com.radio.chinese.domain.model.manualRegionAfterProvinceChange
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

    /**
     * 地区页的省份弹窗只提供省。若一律写成「省|」，设置页选过的城市会被抹掉，
     * 首页「本地优先」从同市（tier3）退化成同省（tier2）。
     */
    @Test
    fun `改选省份时同省保留已选城市，换省则清空`() {
        assertEquals(
            "浙江|杭州",
            manualRegionAfterProvinceChange("浙江", Region("浙江", "杭州")),
        )
        assertEquals(
            "江苏|",
            manualRegionAfterProvinceChange("江苏", Region("浙江", "杭州")),
        )
        // 原本没选城市时，同省再选一次也不会凭空造出一个市
        assertEquals("浙江|", manualRegionAfterProvinceChange("浙江", Region("浙江", "")))
    }
}
