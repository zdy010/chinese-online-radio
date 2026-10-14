package com.radio.chinese

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.radio.chinese.ui.theme.AppTheme
import com.radio.chinese.ui.theme.DarkColorScheme
import com.radio.chinese.ui.theme.LightColorScheme
import com.radio.chinese.ui.theme.contrast
import com.radio.chinese.ui.theme.resolveColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本功能唯一的安全网：漏改一个槽位导致的「白底白字」只有遍历全配对才抓得住，
 * 人肉核对 9 主题 × 30 槽位是抓不住的。
 */
class ThemeContrastTest {

    private data class Pairing(val name: String, val container: Color, val foreground: Color)

    private fun textPairs(s: ColorScheme) = listOf(
        Pairing("primary/onPrimary", s.primary, s.onPrimary),
        Pairing("primaryContainer/onPrimaryContainer", s.primaryContainer, s.onPrimaryContainer),
        Pairing("secondary/onSecondary", s.secondary, s.onSecondary),
        Pairing("secondaryContainer/onSecondaryContainer", s.secondaryContainer, s.onSecondaryContainer),
        Pairing("tertiary/onTertiary", s.tertiary, s.onTertiary),
        Pairing("tertiaryContainer/onTertiaryContainer", s.tertiaryContainer, s.onTertiaryContainer),
        Pairing("surface/onSurface", s.surface, s.onSurface),
        Pairing("background/onBackground", s.background, s.onBackground),
        Pairing("surfaceVariant/onSurfaceVariant", s.surfaceVariant, s.onSurfaceVariant),
        Pairing("inverseSurface/inverseOnSurface", s.inverseSurface, s.inverseOnSurface),
        Pairing("containerLowest/onSurface", s.surfaceContainerLowest, s.onSurface),
        Pairing("containerLow/onSurface", s.surfaceContainerLow, s.onSurface),
        Pairing("container/onSurface", s.surfaceContainer, s.onSurface),
        Pairing("containerHigh/onSurface", s.surfaceContainerHigh, s.onSurface),
        Pairing("containerHighest/onSurface", s.surfaceContainerHighest, s.onSurface),
    )

    @Test
    fun everyThemeMeetsWcagForTextPairs() {
        AppTheme.entries.forEach { theme ->
            listOf(false, true).forEach { dark ->
                val s = theme.resolveColorScheme(dark)
                textPairs(s).forEach { (name, container, foreground) ->
                    val c = contrast(container, foreground)
                    assertTrue(
                        "${theme.key}#$dark $name 只有 ${"%.2f".format(c)}:1（需 ≥4.5）",
                        c >= 4.5
                    )
                }
            }
        }
    }

    @Test
    fun outlineIsVisibleAgainstSurface() {
        AppTheme.entries.forEach { theme ->
            listOf(false, true).forEach { dark ->
                val s = theme.resolveColorScheme(dark)
                val c = contrast(s.outline, s.surface)
                assertTrue(
                    "${theme.key}#$dark outline/surface ${"%.2f".format(c)}:1（需 ≥3）",
                    c >= 3.0
                )
            }
        }
        // 明确不断言 outlineVariant（纯装饰分隔线）与 outline/surfaceContainerHighest：
        // 后者实测亮档只有 2.4:1，M3 自身默认值同样达不到，写进来只会逼人把描边改粗。
    }

    @Test
    fun classicThemesStillReturnBaselineInstances() {
        // 回归基线：三档经典主题必须返回同一个对象，不能是「值相等的重建对象」
        assertSame(LightColorScheme, AppTheme.CLASSIC_LIGHT.resolveColorScheme(dark = false))
        assertSame(DarkColorScheme, AppTheme.CLASSIC_DARK.resolveColorScheme(dark = true))
        assertSame(LightColorScheme, AppTheme.SYSTEM.resolveColorScheme(dark = false))
        assertSame(DarkColorScheme, AppTheme.SYSTEM.resolveColorScheme(dark = true))
    }

    @Test
    fun overridingThemesActuallyOverride() {
        // 只改 background 不算覆写：色调面板必须跟走，否则黑底上会浮出库默认灰块
        val black = AppTheme.PURE_BLACK.resolveColorScheme(dark = true)
        assertEquals(Color(0xFF000000), black.background)
        assertEquals(Color(0xFF000000), black.surface)
        val lift = contrast(black.surfaceContainerHigh, Color(0xFF000000))
        assertTrue("纯黑档的 containerHigh 应比底色亮一点点但仍很暗，实测 $lift:1", lift in 1.05..1.6)
        assertTrue(
            "色调面板不能一路亮到接近白字",
            contrast(black.surfaceContainerHighest, black.onSurface) > 4.5
        )

        val amber = AppTheme.AMBER.resolveColorScheme(dark = true)
        // amber 的验收点：secondaryContainer 必须已转暖，冷蓝灰留在暖底上是一块脏斑
        assertTrue(
            "amber 的 secondaryContainer 残留冷色（b 通道不该高于 r 通道）",
            amber.secondaryContainer.blue <= amber.secondaryContainer.red
        )
        assertTrue(
            "amber 的 surfaceContainer 系列必须偏暖",
            amber.surfaceContainer.blue <= amber.surfaceContainer.red
        )
    }

    @Test
    fun everyThemeResolvesForBothDepths() {
        // 穷尽性：新增主题忘了在 when 里给分支时，这个测试与编译一起拦
        AppTheme.entries.forEach { theme ->
            listOf(false, true).forEach { dark ->
                val s = theme.resolveColorScheme(dark)
                assertEquals("${theme.key}#$dark 的 primary 带了透明度", 1.0, s.primary.alpha.toDouble(), 0.0)
                assertEquals("${theme.key}#$dark 的 onSurface 没铺实", 1.0, s.onSurface.alpha.toDouble(), 0.0)
                assertEquals("${theme.key}#$dark 的底色没铺实", 1.0, s.background.alpha.toDouble(), 0.0)
            }
        }
    }
}
