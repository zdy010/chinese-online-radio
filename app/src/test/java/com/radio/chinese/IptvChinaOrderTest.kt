package com.radio.chinese

import com.radio.chinese.domain.model.IptvChinaOrder
import com.radio.chinese.domain.model.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Test

class IptvChinaOrderTest {

    private fun st(id: String, name: String, region: String = "") = RadioStation(
        id = id, name = name, category = "general", region = region,
    )

    @Test
    fun `CCTV 打头且按台号自然序`() {
        val data = listOf(
            st("iptv:AnhuiTV.cn", "Anhui TV", "安徽"),
            st("iptv:CCTV10.cn", "CCTV-10", "全国"),
            st("iptv:CCTV1.cn", "CCTV-1", "全国"),
            st("iptv:CCTV2.cn", "CCTV-2", "全国"),
            st("iptv:BreadTV.cn", "Bread TV", "其他"),
        )
        assertEquals(
            listOf("CCTV-1", "CCTV-2", "CCTV-10", "Anhui TV", "Bread TV"),
            IptvChinaOrder.sorted(data).map { it.name },
        )
    }

    @Test
    fun `名称里没有 CCTV 的不会抢在中央台前面`() {
        val data = listOf(
            st("iptv:x.cn", "央视综合", "全国"),
            st("iptv:CCTV5.cn", "CCTV-5", "全国"),
        )
        // 名称里没有 CCTV 的「央视综合」不抢在 CCTV-5 前面
        assertEquals(listOf("CCTV-5", "央视综合"), IptvChinaOrder.sorted(data).map { it.name })
    }

    @Test
    fun `非中央台保持清单原顺序，不按中文码点重排`() {
        // 中文 String 排序是 Unicode 码点（北京 < 安徽），不是拼音序；不如原样不动
        val data = listOf(
            st("iptv:z", "浙江卫视", "浙江"),
            st("iptv:a", "安徽卫视", "安徽"),
            st("iptv:b", "北京卫视", "北京"),
        )
        assertEquals(
            listOf("浙江卫视", "安徽卫视", "北京卫视"),
            IptvChinaOrder.sorted(data).map { it.name },
        )
    }
}
