package com.radio.chinese

import com.radio.chinese.domain.sidecar.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test fun parsesSimpleLines() {
        val r = LrcParser.parse("[00:01.00]第一句\n[00:05.50]第二句")
        assertEquals(2, r.size)
        assertEquals(1_000L, r[0].timeMs)
        assertEquals(5_500L, r[1].timeMs)
        assertEquals("第一句", r[0].text)
    }
    @Test fun multipleTimestampsShareOneLine() {
        val r = LrcParser.parse("[00:10.00][01:30.00]副歌")
        assertEquals(2, r.size)
        assertEquals(90_000L, r[1].timeMs)
        assertEquals("副歌", r[1].text)
    }
    @Test fun fractionDigitPrecision() {
        // 1 位=十分之一秒、2 位=百分之一、3 位=毫秒；[mm:ss] 无小数按 0
        assertEquals(1_200L, LrcParser.parse("[00:01.2]x")[0].timeMs)
        assertEquals(1_250L, LrcParser.parse("[00:01.25]x")[0].timeMs)
        assertEquals(1_256L, LrcParser.parse("[00:01.256]x")[0].timeMs)
        assertEquals(61_000L, LrcParser.parse("[01:01]x")[0].timeMs)
    }
    @Test fun dotAndColonSeparatorAccepted() {
        assertEquals(65_000L, LrcParser.parse("[01:05.00]x")[0].timeMs)
        assertEquals(65_000L, LrcParser.parse("[01:05:00]x")[0].timeMs)
    }
    @Test fun metadataAndGarbageSkippedWithoutAbort() {
        val r = LrcParser.parse(
            "[ti:豫剧 花木兰]\n[ar:河南豫剧院]\n纯文本行没有时间戳\n[00:03.00]有效句\n[abc]\n[99:99.99]越界也算合法时间"
        )
        assertEquals(2, r.size)
        assertEquals(listOf("有效句", "越界也算合法时间"), r.map { it.text })
    }
    @Test fun emptyTextTimestampsDropped() {
        assertEquals(0, LrcParser.parse("[00:01.00]   \n[00:02.00]").size)
    }
    @Test fun sortedByTimeAcrossLines() {
        val r = LrcParser.parse("[00:30.00]后\n[00:10.00]先")
        assertEquals(listOf("先", "后"), r.map { it.text })
    }
    @Test fun indexAtFollowsPosition() {
        val lines = LrcParser.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c")
        assertEquals(-1, LrcParser.indexAt(lines, 500))
        assertEquals(0, LrcParser.indexAt(lines, 1_000))
        assertEquals(1, LrcParser.indexAt(lines, 6_000))
        assertEquals(2, LrcParser.indexAt(lines, 60_000))
        assertEquals(-1, LrcParser.indexAt(emptyList(), 1_000))
    }
}
