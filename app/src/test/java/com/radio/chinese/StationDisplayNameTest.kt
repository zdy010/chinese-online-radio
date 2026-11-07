package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationDisplayName
import org.junit.Assert.assertEquals
import org.junit.Test

class StationDisplayNameTest {

    private fun station(name: String, altNames: List<String> = emptyList()) =
        RadioStation(id = "iptv:x", name = name, category = "general", altNames = altNames)

    @Test
    fun `英文名有中文别名时显示中文`() {
        assertEquals("安徽卫视", StationDisplayName.of(station("Anhui TV", listOf("安徽卫视"))))
        assertEquals("北京卫视", StationDisplayName.of(station("Beijing Satellite TV", listOf("北京卫视"))))
    }

    @Test
    fun `CCTV 系列保持原名`() {
        // 「CCTV-1」比「中国中央电视台综合频道」更好认，也更好在搜索里匹配
        assertEquals("CCTV-1", StationDisplayName.of(station("CCTV-1", listOf("中国中央电视台综合频道"))))
        assertEquals("CCTV-10", StationDisplayName.of(station("CCTV-10", listOf("央视科教"))))
    }

    @Test
    fun `原名已是中文不替换`() {
        // 电台几乎都是中文名，不能因为 altNames 里有个中文别名就把「中国之声」换掉
        assertEquals("中国之声", StationDisplayName.of(station("中国之声", listOf("CNR 1"))))
    }

    @Test
    fun `没有中文别名时回退原名`() {
        assertEquals("Ando TV", StationDisplayName.of(station("Ando TV", listOf("Ando TV"))))
        assertEquals("ACC Network", StationDisplayName.of(station("ACC Network")))
    }
}
