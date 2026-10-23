package com.radio.chinese

import com.radio.chinese.domain.model.EpgProgram
import com.radio.chinese.domain.epg.EpgQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * 节目单的纯函数层：时间一律用「当天 00:00 起算的分钟数」，
 * 时区换算全部在解析层做完，这里只管区间比较。
 */
class EpgQueryTest {

    private fun p(start: Int, end: Int, title: String, date: String = "2026-10-23") =
        EpgProgram(stationId = "cnr1", date = date, startMin = start, endMin = end, title = title)

    private val day = listOf(
        p(0, 120, "千里共良宵"),
        p(120, 330, "夜间节目"),
        p(330, 360, "新闻和报纸摘要"),
        p(360, 1440, "全天节目"),
    )

    // ---- currentAt ----

    @Test fun `零点命中从零点开始的节目`() {
        assertEquals("千里共良宵", EpgQuery.currentAt(day, 0)?.title)
    }

    @Test fun `正好等于开始分钟算在播`() {
        assertEquals("新闻和报纸摘要", EpgQuery.currentAt(day, 330)?.title)
    }

    @Test fun `正好等于结束分钟不算在播`() {
        // 与下一档的 startMin 重合，归下一档；否则两档会同时声称自己在播
        assertEquals("全天节目", EpgQuery.currentAt(day, 360)?.title)
    }

    @Test fun `节目之间的空档返回 null`() {
        assertNull(EpgQuery.currentAt(listOf(p(0, 60, "A"), p(120, 180, "B")), 90))
    }

    @Test fun `跨午夜节目在截断点仍然在播`() {
        // 解析层把跨天的 endTime 截断成 1440，23:59 必须还属于这一档
        assertEquals("午夜档", EpgQuery.currentAt(listOf(p(1380, 1440, "午夜档")), 1439)?.title)
    }

    @Test fun `空列表没有在播节目`() {
        assertNull(EpgQuery.currentAt(emptyList(), 600))
    }

    // ---- nextAfter ----

    @Test fun `下一档是开始时间严格大于当前的最近一档`() {
        assertEquals("夜间节目", EpgQuery.nextAfter(day, 0)?.title)
    }

    @Test fun `当前在空档时下一档依然能给出`() {
        assertEquals("B", EpgQuery.nextAfter(listOf(p(0, 60, "A"), p(120, 180, "B")), 90)?.title)
    }

    @Test fun `最后一档之后没有下一档`() {
        assertNull(EpgQuery.nextAfter(listOf(p(0, 60, "A")), 60))
    }

    // ---- search ----

    @Test fun `节目名中文子串命中`() {
        assertEquals(listOf("新闻和报纸摘要"), EpgQuery.search(day, "报纸").map { it.title })
    }

    @Test fun `搜索忽略首尾空格`() {
        assertEquals(1, EpgQuery.search(day, "  新闻  ").size)
    }

    @Test fun `空白查询不返回整张表`() {
        // 空查询必须当成「没有节目搜索」，否则输入框一清空就把全天节目全列出来
        assertEquals(emptyList<EpgProgram>(), EpgQuery.search(day, "   "))
    }

    @Test fun `搜索无结果返回空列表`() {
        assertEquals(emptyList<EpgProgram>(), EpgQuery.search(day, "不存在的节目"))
    }

    @Test fun `搜索结果按开始时间排序`() {
        val shuffled = listOf(p(360, 400, "新闻联播"), p(60, 90, "新闻和报纸摘要"))
        assertEquals(
            listOf("新闻和报纸摘要", "新闻联播"),
            EpgQuery.search(shuffled, "新闻").map { it.title },
        )
    }

    // ---- scheduleFor ----

    @Test fun `取某天节目并按开始时间排序`() {
        val mixed = listOf(
            p(360, 400, "B", date = "2026-10-23"),
            p(0, 60, "A", date = "2026-10-23"),
            p(0, 60, "昨天", date = "2026-10-22"),
        )
        assertEquals(listOf("A", "B"), EpgQuery.scheduleFor(mixed, "2026-10-23").map { it.title })
    }

    // ---- 缓存新鲜度 ----

    @Test fun `六小时内的缓存不重拉`() {
        val now = 1_000_000_000_000L
        assertEquals(false, EpgQuery.isStale(fetchedAt = now - 5 * 3600_000L, now = now))
    }

    @Test fun `超过六小时的缓存要重拉`() {
        val now = 1_000_000_000_000L
        assertEquals(true, EpgQuery.isStale(fetchedAt = now - 7 * 3600_000L, now = now))
    }

    @Test fun `未来时间戳视为过期`() {
        // 用户把时钟改回去时不能让节目单永远停留在旧数据上
        val now = 1_000_000_000_000L
        assertEquals(true, EpgQuery.isStale(fetchedAt = now + 3600_000L, now = now))
    }

    // ---- 时段文案 ----

    @Test fun `时段的分钟数格式化补零`() {
        assertEquals("06:00–06:30", EpgQuery.formatRange(360, 390))
    }

    @Test fun `跨天截断的终点显示 24 点`() {
        assertEquals("23:00–24:00", EpgQuery.formatRange(1380, 1440))
    }

    // ---- 数据截至文案 ----

    @Test fun `缓存时间显示成月日时分`() {
        // 1_792_675_800_000 = 2026-10-22 21:30 +08:00；UI 拼成“数据截至 10-22 21:30”
        assertEquals(
            "10-22 21:30",
            EpgQuery.formatFetchedAt(1_792_675_800_000L, ZoneId.of("Asia/Shanghai")),
        )
    }

    @Test fun `缓存时间的月日时分都补零`() {
        val ms = LocalDateTime.of(2026, 1, 5, 8, 5).atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant().toEpochMilli()
        assertEquals("01-05 08:05", EpgQuery.formatFetchedAt(ms, ZoneId.of("Asia/Shanghai")))
    }

    // ---- 节目单页排序 ----

    private data class Row(val id: String, val name: String, val programs: List<EpgProgram>)

    private fun row(id: String, name: String, vararg programs: EpgProgram) =
        Row(id = id, name = name, programs = programs.toList())

    private fun ordered(rows: List<Row>, minute: Int) = EpgQuery.nowPlayingFirst(
        items = rows,
        minute = minute,
        programsOf = { it.programs },
        nameOf = { it.name },
    ).map { it.id }

    @Test fun `有当前节目的台排在前面`() {
        // 节目单页的第一眼价值是“现在各家在播什么”，空档的台不该占满首屏
        val rows = listOf(
            row("a", "广播A", p(0, 60, "早间档")),
            row("b", "广播B", p(600, 660, "午间档")),
        )
        assertEquals(listOf("b", "a"), ordered(rows, minute = 620))
    }

    @Test fun `同组内按台名排序`() {
        // 三台此刻都在播，只靠台名决定先后（按字符串字典序，不做拼音）
        val rows = listOf(
            row("c", "广播C", p(0, 60, "早间档")),
            row("a", "广播A", p(0, 60, "早间档")),
            row("b", "广播B", p(0, 60, "早间档")),
        )
        assertEquals(listOf("a", "b", "c"), ordered(rows, minute = 30))
    }

    @Test fun `空档的台不会被排序丢掉`() {
        // 排序只决定先后，不能把没节目的台过滤掉——用户还得能看到它的全天节目单
        val rows = listOf(row("a", "广播A", p(0, 60, "早间档")))
        assertEquals(listOf("a"), ordered(rows, minute = 600))
    }

    @Test fun `完全没有节目的台也保留在列表里`() {
        val rows = listOf(
            row("empty", "没有节目单"),
            row("a", "广播A", p(0, 60, "早间档")),
        )
        assertEquals(listOf("a", "empty"), ordered(rows, minute = 30))
    }

    // ---- 时间基准 ----

    @Test fun `播出时区固定为北京时间`() {
        // 节目单是北京排播的。拿设备时区去切一天的话，落在本地零点之前的早间档
        // 会被截断压扁：非东八区的设备一打开就会看到半夜那几档挤在 00:00
        assertEquals(ZoneId.of("Asia/Shanghai"), EpgQuery.BROADCAST_ZONE)
    }

    @Test fun `分钟数直接取北京时间时分`() {
        assertEquals(0, EpgQuery.minuteOf(LocalDateTime.of(2026, 10, 23, 0, 0)))
        assertEquals(930, EpgQuery.minuteOf(LocalDateTime.of(2026, 10, 23, 15, 30)))
        assertEquals(1439, EpgQuery.minuteOf(LocalDateTime.of(2026, 10, 23, 23, 59)))
    }

    @Test fun `设备时区不是北京时判定为需要标注`() {
        // 改的是 JVM 默认时区，用完必须还原：同一 JVM 里后面的用例会跟着遭殃
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertFalse(EpgQuery.isBroadcastZoneLocal())
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertTrue(EpgQuery.isBroadcastZoneLocal())
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
