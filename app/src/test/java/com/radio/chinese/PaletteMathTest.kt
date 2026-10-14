package com.radio.chinese

import androidx.compose.ui.graphics.Color
import com.radio.chinese.ui.theme.contrast
import com.radio.chinese.ui.theme.luminance
import com.radio.chinese.ui.theme.tonal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tonal 的 tone 表把对比度成败压在几个具体值上，所以先把算法本身钉死：
 * 算法错一位，Task 3 的全量不变式会一片红，人就会去改色值而不是改算法。
 */
class PaletteMathTest {

    private fun assertClose(expected: Double, actual: Double, message: String) {
        assertEquals(message, expected, actual, 0.05)
    }

    @Test
    fun tonalInterpolatesPerChannel() {
        val black = Color(0xFF000000)
        val white = Color(0xFFFFFFFF)
        // tone=0 得底色，tone=1 得墨色，不掺中间值
        assertEquals(black, tonal(black, white, 0f))
        assertEquals(white, tonal(black, white, 1f))
        // 0.5 落在中点。注意 Compose 的 Color 把通道量化成 8 位（RGBA_8888），
        // 所以中点存的是 128/255 而不是 0.5——断言按量化后的真实取值写，
        // Task 3 的对比度不变式也是在这个量化域上算的，两边口径必须一致。
        val mid = tonal(black, white, 0.5f)
        fun byte(v: Float) = (v * 255 + 0.5).toInt()
        assertEquals(128, byte(mid.red))
        assertEquals(128, byte(mid.green))
        assertEquals(128, byte(mid.blue))
        assertEquals(255, byte(mid.alpha))
        // 非灰底也要按通道各自插，不能先转灰度：#1A1206→#F0C070 的 0.42 处红通道
        // 26 + (240-26)*0.42 = 115.88 → 量化到 116
        val brown = Color(0xFF1A1206)
        val amber = Color(0xFFF0C070)
        assertEquals(116, byte(tonal(brown, amber, 0.42f).red))
        // 18 + (192-18)*0.42 = 91.08 → 91（三通道分别插值的直接证据：绿通道得自己的值）
        assertEquals(91, byte(tonal(brown, amber, 0.42f).green))
    }

    @Test
    fun alphaComesFromBaseNotFromInk() {
        // 底色半透明时结果必须保持底色的 alpha：面板色不该继承墨色的 alpha
        // （alpha 同样量化到 8 位：0.4 → 102/255）
        val translucent = Color(red = 0f, green = 0f, blue = 0f, alpha = 0.4f)
        assertEquals(102, ((tonal(translucent, Color.White, 0.5f).alpha) * 255 + 0.5).toInt())
    }

    @Test
    fun luminanceMatchesWcagReference() {
        assertEquals(0.0, luminance(Color(0xFF000000)), 1e-9)
        assertEquals(1.0, luminance(Color(0xFFFFFFFF)), 1e-9)
        // #808080：WCAG 参考值约 0.2159（靠它就能抓住幂次或临界值写错）
        assertClose(0.2159, luminance(Color(0xFF808080)), "#808080 亮度")
    }

    @Test
    fun contrastMatchesWcagReference() {
        assertEquals(21.0, contrast(Color(0xFF000000), Color(0xFFFFFFFF)), 1e-6)
        assertEquals(1.0, contrast(Color(0xFF777777), Color(0xFF777777)), 1e-6)
        // 顺序无关：反着传得到同一个值
        assertEquals(
            contrast(Color(0xFF000000), Color(0xFF767676)),
            contrast(Color(0xFF767676), Color(0xFF000000)),
            1e-9
        )
        // #767676 是 4.5:1 的公认临界参考色
        assertClose(4.54, contrast(Color(0xFF767676), Color(0xFFFFFFFF)), "#767676/白")
        // 3:1 的临界参考：outline 的达标判断就靠这条口径
        assertClose(3.0, contrast(Color(0xFF949494), Color(0xFFFFFFFF)), "#949494/白")
    }

    @Test
    fun specCriticalToneValuesHold() {
        // 逐条复现 spec §5.2/§5.3 的验算，防止有人事后把 tone 调回去却以为在优化观感
        val black = Color(0xFF000000)
        val white = Color(0xFFFFFFFF)
        assertTrue("outline 0.32 在纯黑上必须不达标（这是提到 0.50 的理由）",
            contrast(tonal(black, white, 0.32f), black) < 3.0)
        assertTrue("outline 0.50 在纯黑上须 ≥3:1",
            contrast(tonal(black, white, 0.50f), black) >= 3.0)
        assertTrue("亮档 outline 0.48 对白底须 ≥3:1",
            contrast(tonal(white, black, 0.48f), white) >= 3.0)

        val softBase = Color(0xFF232326)
        val softInk = Color(0xFFD6D3D9)
        val softPrimary = Color(0xFF86AEDC)
        assertTrue("onPrimary 0.25 必须不达标（这是改用 0.18 的理由）",
            contrast(softPrimary, tonal(softBase, softPrimary, 0.25f)) < 4.5)
        assertTrue("onPrimary 0.18 须 ≥4.5:1",
            contrast(softPrimary, tonal(softBase, softPrimary, 0.18f)) >= 4.5)
        // 暗档墨色取 ink 而不是白：soft_dark 的底色不是纯黑
        assertTrue("soft_dark 的 surface/onSurface 须 ≥4.5:1",
            contrast(softBase, softInk) >= 4.5)
    }
}
