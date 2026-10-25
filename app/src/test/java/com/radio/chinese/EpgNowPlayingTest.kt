package com.radio.chinese

import com.radio.chinese.domain.epg.nowPlayingTitles
import com.radio.chinese.domain.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电台列表页那一行「正在播出」的取数规则。
 *
 * 这一层把三个各自都会写错的判断收在一处：
 * 未接入节目单的台不能冒出节目名；接入但此刻落在空档的台给不出标题（标记由
 * hasEpg 单独判断，不在这里）；缓存里混进的其它日期节目不能参与当前档判定——
 * 缓存按 stationId+date 存，跨天残留会让用户在本机零点过后看到昨天的节目名。
 */
class EpgNowPlayingTest {

    private val day = "2026-10-23"

    private fun p(station: String, start: Int, end: Int, title: String, date: String = day) =
        EpgProgram(stationId = station, date = date, startMin = start, endMin = end, title = title)

    /** 接入集合由映射表给出，节目名只能给「接入 且 此刻真有节目」的台出现。 */
    @Test
    fun `接入节目单的台给出此刻在播的节目名`() {
        val programs = mapOf("cnr1" to listOf(p("cnr1", 540, 600, "全国新闻联播")))

        val titles = nowPlayingTitles(programs, date = day, minute = 570, epgStationIds = setOf("cnr1"))

        assertEquals(mapOf("cnr1" to "全国新闻联播"), titles)
    }

    /** 未接入的台即使缓存里有残留节目，也不许在列表里冒出节目名。 */
    @Test
    fun `没有接入节目单的台整行不出现`() {
        val programs = mapOf("local" to listOf(p("local", 540, 600, "缓存里的残留节目")))

        val titles = nowPlayingTitles(programs, date = day, minute = 570, epgStationIds = setOf("cnr1"))

        assertTrue(titles.isEmpty())
    }

    /** 空档时刻给不出标题：这个台此刻没有正在播的节目，列表行不渲染。 */
    @Test
    fun `接入但此刻落在空档时没有节目名`() {
        val programs = mapOf("cnr1" to listOf(p("cnr1", 540, 600, "全国新闻联播")))

        val titles = nowPlayingTitles(programs, date = day, minute = 700, epgStationIds = setOf("cnr1"))

        assertTrue(titles.isEmpty())
    }

    /** 边界沿用 currentAt 的左闭右开：结束分钟归下一档，否则两档同时声称在播。 */
    @Test
    fun `正好到结束分钟时改取下一档`() {
        val programs = mapOf(
            "cnr1" to listOf(p("cnr1", 540, 600, "全国新闻联播"), p("cnr1", 600, 660, "焦点访谈"))
        )

        val titles = nowPlayingTitles(programs, date = day, minute = 600, epgStationIds = setOf("cnr1"))

        assertEquals("焦点访谈", titles.getValue("cnr1"))
    }

    /** 缓存里留着昨天的排播，今天零点过后不能拿昨天的节目名糊弄用户。 */
    @Test
    fun `不是指定日期的缓存节目不参与判定`() {
        val programs = mapOf("cnr1" to listOf(p("cnr1", 0, 1440, "昨天的全天节目", date = "2026-10-22")))

        val titles = nowPlayingTitles(programs, date = day, minute = 300, epgStationIds = setOf("cnr1"))

        assertTrue(titles.isEmpty())
    }

    /**
     * 节目单页切到明天后，同一个台在缓存里会同时留着今天与明天两天的行。
     * 算「明天 09:30」时只能看明天那批：今天的 09:00–10:00 不许串过来顶替明天。
     */
    @Test
    fun `两天都在缓存里时只取指定日期的节目`() {
        val programs = mapOf(
            "cnr1" to listOf(
                p("cnr1", 540, 600, "今天此刻在播", date = day),
                p("cnr1", 540, 600, "明天此刻在播", date = "2026-10-24"),
            )
        )

        val titles = nowPlayingTitles(programs, date = "2026-10-24", minute = 570, epgStationIds = setOf("cnr1"))

        assertEquals(mapOf("cnr1" to "明天此刻在播"), titles)
    }

    /** 排序不能交给 map 的迭代顺序：同一份数据两次重组必须给出先后一致的映射。 */
    @Test
    fun `接入台按台 id 稳定排序`() {
        val programs = mapOf(
            "b" to listOf(p("b", 540, 600, "乙台节目")),
            "a" to listOf(p("a", 540, 600, "甲台节目")),
        )

        val titles = nowPlayingTitles(programs, date = day, minute = 570, epgStationIds = setOf("c", "b", "a"))

        assertEquals(listOf("a", "b"), titles.keys.toList())
    }

    /** 空接入集合是最常见的情形（台库还没加载完），此时一行都不该渲染。 */
    @Test
    fun `没有接入台时返回空`() {
        val programs = mapOf("cnr1" to listOf(p("cnr1", 540, 600, "全国新闻联播")))

        assertTrue(nowPlayingTitles(programs, date = day, minute = 570, epgStationIds = emptySet()).isEmpty())
    }
}
