package com.radio.chinese

import com.radio.chinese.data.local.RegionGazetteer
import com.radio.chinese.domain.model.Region
import com.radio.chinese.domain.model.RegionResolver
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 从台名/简介反推省/市。钉住四类：城市命中带省、只有省名城市留空、无地区词返回 NONE、
 * 直辖市同时填省市（让上海台在上海用户下进最高档）。
 */
class RegionResolverTest {
    private val g = RegionGazetteer.parse(
        listOf(
            File("src/main/assets/region_gazetteer.json"),
            File("app/src/main/assets/region_gazetteer.json"),
        ).first { it.exists() }.readText(Charsets.UTF_8)
    )

    @Test fun `城市名命中带出所属省`() {
        assertEquals(Region("浙江", "绍兴"), RegionResolver.infer("绍兴之声", "中国", g))
        assertEquals(Region("辽宁", "大连"), RegionResolver.infer("DLR-3 大连体育广播", "中国", g))
    }

    @Test fun `只有省名时城市为空`() {
        assertEquals(Region("河南", ""), RegionResolver.infer("河南交通广播", "中国", g))
    }

    @Test fun `无地区词返回 NONE`() {
        assertEquals(Region.NONE, RegionResolver.infer("中国之声", "中国", g))
        assertEquals(Region.NONE, RegionResolver.infer("CCTV-1综合伴音", "中国", g))
    }

    @Test fun `直辖市同时填省市`() {
        assertEquals(Region("上海", "上海"), RegionResolver.infer("上海 东方卫视伴音", "中国", g))
    }

    @Test fun `歧义词取文本中最靠前的城市命中`() {
        // “绍兴”在 pos0，“杭州”在其后；取最靠前的绍兴
        assertEquals(Region("浙江", "绍兴"), RegionResolver.infer("绍兴杭州联合广播", "中国", g))
    }

    @Test fun `省名与市名同现时优先取市而非最靠前的省`() {
        // 全国电台最常见命名：省名在前、市名在后。不能因为省名 pos 更小就只判到省
        assertEquals(Region("河南", "郑州"), RegionResolver.infer("河南郑州交通广播", "中国", g))
        assertEquals(Region("四川", "成都"), RegionResolver.infer("四川成都音乐广播", "中国", g))
    }
}
