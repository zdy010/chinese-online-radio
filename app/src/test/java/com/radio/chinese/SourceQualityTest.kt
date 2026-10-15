package com.radio.chinese

import androidx.compose.ui.graphics.Color
import com.radio.chinese.data.local.SourceQuality
import com.radio.chinese.data.local.availabilityToQuality
import com.radio.chinese.ui.theme.DarkQualityColors
import com.radio.chinese.ui.theme.LightQualityColors
import com.radio.chinese.ui.theme.contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceQualityTest {

    @Test
    fun scoreMapsToThreeBands() {
        // 边界必须与旧连续算法的语义一致：≥0.5 绝不能判成红
        assertEquals(SourceQuality.GOOD, availabilityToQuality(1.0f))
        assertEquals(SourceQuality.GOOD, availabilityToQuality(0.75f))
        assertEquals(SourceQuality.FAIR, availabilityToQuality(0.74f))
        assertEquals(SourceQuality.FAIR, availabilityToQuality(0.5f))
        assertEquals(SourceQuality.BAD, availabilityToQuality(0.49f))
        assertEquals(SourceQuality.BAD, availabilityToQuality(0.0f))
        // 脏输入不越界（旧函数有 coerceIn，收窄成档位后仍要保住）
        assertEquals(SourceQuality.GOOD, availabilityToQuality(9f))
        assertEquals(SourceQuality.BAD, availabilityToQuality(-3f))
        assertEquals(SourceQuality.BAD, availabilityToQuality(Float.NaN))
    }

    @Test
    fun qualityColorsReadableOnBothExtremes() {
        // 旧值 #4CFF00 对白底 1.4:1、#FF0000 对纯黑 1.5:1，等于看不见。
        // 两套新色必须在「最亮底」和「最暗底」上都够正文对比度。
        SourceQuality.entries.forEach { q ->
            val light = LightQualityColors.getValue(q)
            val dark = DarkQualityColors.getValue(q)
            val lc = contrast(light, Color.White)
            val dc = contrast(dark, Color.Black)
            assertTrue("浅底套 $q 对白底只有 ${"%.2f".format(lc)}:1（需 ≥4.5）", lc >= 4.5)
            assertTrue("深底套 $q 对纯黑只有 ${"%.2f".format(dc)}:1（需 ≥4.5）", dc >= 4.5)
        }
    }

    @Test
    fun theTwoBandsStaySeparable() {
        // 三档必须两两能分辨。这里用 RGB 通道距离而不是 WCAG 亮度比：
        // 深橙 #9A6800 与深红 #B3261E 几乎同亮度（只有 1.36:1），它们靠色相区分，
        // 拿亮度比量色相差异是量错了对象（计划缺陷，见 ledger 裁决）。
        // 60/255 ≈ 通道量程的 24%，是「一眼能分开」的粗略代理；实测最小的一对是 76.7。
        listOf(LightQualityColors, DarkQualityColors).forEach { table ->
            val colors = SourceQuality.entries.map { table.getValue(it) }
            for (i in colors.indices) for (j in i + 1 until colors.size) {
                val d = channelDistance(colors[i], colors[j])
                assertTrue("两档语义色太接近（距离 ${"%.1f".format(d)}，需 ≥60）", d >= 60.0)
            }
        }
    }

    /** 0..255 尺度上的 RGB 欧氏距离。 */
    private fun channelDistance(a: Color, b: Color): Double {
        fun ch(v: Float) = v * 255.0
        val dr = ch(a.red) - ch(b.red)
        val dg = ch(a.green) - ch(b.green)
        val db = ch(a.blue) - ch(b.blue)
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }
}
