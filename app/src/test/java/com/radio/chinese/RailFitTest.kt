package com.radio.chinese

import com.radio.chinese.ui.theme.railFits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页签栏用左侧 rail 的尺寸判据。
 *
 * 2026-10 在 1280x720@160dpi 的车机屏上实测：页签从 4 个加到 5 个后，
 * 第 5 个「最近」落在屏幕外点不到。根因是旧判据只写了 screenHeightDp >= 560，
 * 没有把页签数量、搜索栏、底部导航栏算进去；宽度也没管，手机竖屏强制车机档时
 * rail 会吃掉 120dp，内容区只剩两百多 dp。
 */
class RailFitTest {

    /** 4 个页签是音频库页的现状，不能因为改了公式就把它的车机档弄丢。 */
    @Test
    fun `四个页签在 560dp 高的车机屏上装得下`() {
        assertTrue(railFits(screenWidthDp = 1280, screenHeightDp = 560, tabCount = 4))
    }

    /** 1280x720@160dpi 是 8 寸车机最常见的一档。 */
    @Test
    fun `五个页签在 720dp 高的车机屏上装得下`() {
        assertTrue(railFits(screenWidthDp = 1280, screenHeightDp = 720, tabCount = 5))
    }

    /**
     * 钉住真机实测值：Pixel 8a 报 wm size 1280x720 / density 160 时，
     * dumpsys 里的 mCurrentConfig 是 `w1280dp h636dp`——720 被状态栏与桌面 taskbar
     * 吃掉 84。这一档必须能进 rail，否则车机档的真实效果根本测不到。
     */
    @Test
    fun `五个页签在实测的 636dp 高视口上装得下`() {
        assertTrue(railFits(screenWidthDp = 1280, screenHeightDp = 636, tabCount = 5))
    }

    /** 同样的屏高，多一个页签就得多留 92dp：600dp 只够 4 个。 */
    @Test
    fun `五个页签在 600dp 高的车机屏上装不下`() {
        assertFalse(railFits(screenWidthDp = 1280, screenHeightDp = 600, tabCount = 5))
        assertTrue(railFits(screenWidthDp = 1280, screenHeightDp = 600, tabCount = 4))
    }

    /** 1280x480 的超宽车机屏（报 240dpi 时只有 853x480dp）连 4 个都装不下。 */
    @Test
    fun `超宽矮屏五个页签装不下`() {
        assertFalse(railFits(screenWidthDp = 853, screenHeightDp = 480, tabCount = 5))
    }

    /**
     * 手机竖屏强制车机档：914dp 高听着宽裕，但只有 411dp 宽，
     * rail 吃掉 120dp 后内容区剩 227dp，卡片文字全部折行。
     */
    @Test
    fun `竖屏窄屏不用 rail`() {
        assertFalse(railFits(screenWidthDp = 411, screenHeightDp = 914, tabCount = 5))
    }

    /** 阈值随页签数线性上移：每多一个页签，就要多 92dp 屏高。 */
    @Test
    fun `页签越多需要的屏幕越高`() {
        val minimumForFour = (1..2000).first { railFits(1280, it, 4) }
        val minimumForFive = (1..2000).first { railFits(1280, it, 5) }
        val minimumForSix = (1..2000).first { railFits(1280, it, 6) }
        assertTrue(minimumForFive - minimumForFour == 92)
        assertTrue(minimumForSix - minimumForFive == 92)
    }
}
