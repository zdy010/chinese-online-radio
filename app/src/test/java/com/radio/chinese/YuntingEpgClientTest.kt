package com.radio.chinese

import com.radio.chinese.data.remote.YuntingEpgClient
import com.radio.chinese.domain.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * 云听接口的两件纯活：签名与解析。两者都单独可测，不碰网络。
 *
 * 签名黄金样例是用同一套算法在 Python 里独立算出来的，不是抄 Kotlin 自己的输出——
 * 抄自己的输出等于把 bug 固化进断言。
 */
class YuntingEpgClientTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 23)

    private fun ms(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun body(vararg rows: String) = """{"code":0,"data":[${rows.joinToString(",")}]}"""

    private fun row(name: String, start: Long, end: Long) =
        """{"broadcastId":"639","programName":"$name","startTime":$start,"endTime":$end}"""

    /** 解析必须成功。失败时直接抛错，比在断言里散落 !! 更能指出是哪条用例挂了。 */
    private fun parseOk(
        raw: String,
        date: String = "2026-10-23",
        stationId: String = "cnr1",
    ): List<EpgProgram> = requireNotNull(YuntingEpgClient.parse(raw, stationId, date, zone)) {
        "解析应当成功，实际被判为失败"
    }

    /** 不传 zone，走 parse 的生产默认值。测试时区默认值只能这么测。 */
    private fun parseDefault(raw: String): List<EpgProgram> =
        requireNotNull(YuntingEpgClient.parse(raw, "cnr1", "2026-10-23")) {
            "解析应当成功，实际被判为失败"
        }

    // ---- 签名 ----

    @Test fun `签名与 JS 算法的手算值一致`() {
        // 参数按 key 字典序拼接，再挂 timestamp 与 key，取 MD5 大写
        assertEquals(
            "F49D4C56B9A273DFCCC3BE77638B13CB",
            YuntingEpgClient.sign(
                params = mapOf("date" to "2026-10-23", "broadcastId" to "639"),
                timestampMs = 1_730_000_000_000L,
            ),
        )
    }

    @Test fun `无参数时只拼 timestamp 与 key`() {
        assertEquals(
            "E1C93DA6441F5B647075D84677FFCC24",
            YuntingEpgClient.sign(params = emptyMap(), timestampMs = 1_730_000_000_000L),
        )
    }

    @Test fun `另一个参数组合的签名`() {
        // 换一组台与日期再钉一次：单一样例无法排除「排序方向恰好对」这种巧合
        assertEquals(
            "F1A29FC59E25572C11A9701B394434AD",
            YuntingEpgClient.sign(
                params = mapOf("broadcastId" to "641", "date" to "2026-10-24"),
                timestampMs = 1_700_000_000_000L,
            ),
        )
    }

    // ---- 解析 ----

    @Test fun `毫秒时间戳换算成当天分钟数`() {
        // 样本取自 2026-10-23 中国之声真实响应的首档 00:00–00:30
        val programs = parseOk(body(row("档案揭秘", 1_792_684_800_000L, 1_792_686_600_000L)))
        assertEquals(1, programs.size)
        assertEquals(0, programs[0].startMin)
        assertEquals(30, programs[0].endMin)
        assertEquals("档案揭秘", programs[0].title)
        assertEquals("2026-10-23", programs[0].date)
        assertEquals("cnr1", programs[0].stationId)
    }

    @Test fun `节目名两端空格被去掉`() {
        // 真实响应里确实有「新闻进行时 」这种尾随空格，原样进 UI 会让行尾多出一截空白
        val programs = parseOk(
            body(row(" 新闻进行时 ", ms(day, LocalTime.of(8, 30)), ms(day, LocalTime.of(10, 0)))),
        )
        assertEquals("新闻进行时", programs.single().title)
    }

    @Test fun `跨天节目截断到 24 点`() {
        val programs = parseOk(
            body(row("午夜档", ms(day, LocalTime.of(23, 0)), ms(day.plusDays(1), LocalTime.of(1, 0)))),
        )
        assertEquals(1380, programs.single().startMin)
        assertEquals(1440, programs.single().endMin)
    }

    @Test fun `前一天开始的节目从零点算起`() {
        // 节目单按日切片时，前一天的跨天档要落成当天的第一段，而不是被算成 23:00
        val programs = parseOk(
            body(row("跨年档", ms(day.minusDays(1), LocalTime.of(23, 30)), ms(day, LocalTime.of(0, 30)))),
        )
        assertEquals(0, programs.single().startMin)
        assertEquals(30, programs.single().endMin)
    }

    @Test fun `结束不晚于开始的行被丢弃`() {
        val same = ms(day, LocalTime.of(10, 0))
        val programs = parseOk(
            body(
                row("零长", same, same),
                row("倒序", ms(day, LocalTime.of(12, 0)), ms(day, LocalTime.of(11, 0))),
                row("正常", same, ms(day, LocalTime.of(11, 0))),
            ),
        )
        assertEquals(listOf("正常"), programs.map { it.title })
    }

    @Test fun `空标题被丢弃`() {
        val programs = parseOk(
            body(
                row("   ", ms(day, LocalTime.of(9, 0)), ms(day, LocalTime.of(10, 0))),
                row("正常", ms(day, LocalTime.of(10, 0)), ms(day, LocalTime.of(11, 0))),
            ),
        )
        assertEquals(listOf("正常"), programs.map { it.title })
    }

    @Test fun `缺失时间戳的行被丢弃`() {
        val programs = parseOk(
            body(
                """{"broadcastId":"639","programName":"无时间"}""",
                row("正常", ms(day, LocalTime.of(10, 0)), ms(day, LocalTime.of(11, 0))),
            ),
        )
        assertEquals(listOf("正常"), programs.map { it.title })
    }

    @Test fun `data 为 null 是成功但当天没有节目`() {
        // 与失败要分开：成功为空会让仓库把缓存清空并记时间戳，失败则要保住旧缓存
        assertEquals(
            emptyList<Any>(),
            YuntingEpgClient.parse(
                body = """{"code":0,"data":null}""",
                stationId = "cnr1",
                date = "2026-10-23",
                zone = zone,
            ),
        )
    }

    @Test fun `接口返回非零 code 视为失败`() {
        // 签名过期时云听会回一个错误码而不是 HTTP 500。这里必须返回 null 而不是空列表：
        // 空列表会被仓库当成「拉取成功但当天没节目」，从而把已有缓存清掉
        assertNull(
            YuntingEpgClient.parse(
                body = """{"code":1001,"message":"签名错误","data":[${row("不该出现", 0, 1)}]}""",
                stationId = "cnr1",
                date = "2026-10-23",
                zone = zone,
            ),
        )
    }

    @Test fun `坏 JSON 不抛异常且判为失败`() {
        // 接口随时可能返回一页 HTML：功能消失可以，崩掉播放页不行
        assertNull(YuntingEpgClient.parse("<html>502</html>", "cnr1", "2026-10-23", zone))
    }

    @Test fun `日期参数非法时判为失败`() {
        assertNull(
            YuntingEpgClient.parse(
                body = body(row("正常", ms(day, LocalTime.of(10, 0)), ms(day, LocalTime.of(11, 0)))),
                stationId = "cnr1",
                date = "not-a-date",
                zone = zone,
            ),
        )
    }

    @Test fun `主机时区不是北京时仍按北京时间换算`() {
        // 把 JVM 默认时区改到纽约：若 parse 的默认值退回 systemDefault，
        // 北京 07:00–09:00 会被算成负分钟再截成 0，整档直接从节目单上消失
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val programs = parseDefault(
                body(row("新闻纵横", ms(day, LocalTime.of(7, 0)), ms(day, LocalTime.of(9, 0))))
            )
            assertEquals(420, programs.single().startMin)
            assertEquals(540, programs.single().endMin)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
