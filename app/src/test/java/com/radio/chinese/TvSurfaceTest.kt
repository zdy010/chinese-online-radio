package com.radio.chinese

import com.radio.chinese.ui.theme.TvSurface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电视判定的守卫。
 *
 * 本机没有 Android TV 系统镜像（SDK 缺 cmdline-tools，下不了），
 * 所以「装上 TV 会不会被当电视」这件事只能靠这层单测保证，
 * 真机/AVD 的观感验证另行说明。
 */
class TvSurfaceTest {

    @Test
    fun `UiMode 报 TELEVISION 时直接判定为电视`() {
        // 标准 Android TV：uiMode=4，即便声明了触屏也按电视处理
        assertTrue(TvSurface.isTv(uiModeType = 4, hasTouchscreen = true, hasLeanback = true))
        assertTrue(TvSurface.isTv(uiModeType = 4, hasTouchscreen = true, hasLeanback = false))
    }

    @Test
    fun `没有触屏且有 leanback 的盒子也算电视`() {
        // 部分国产盒子不设 TELEVISION 位，靠这条兜底
        assertTrue(TvSurface.isTv(uiModeType = 0, hasTouchscreen = false, hasLeanback = true))
    }

    @Test
    fun `手机与车机不算电视`() {
        assertFalse(TvSurface.isTv(uiModeType = 0, hasTouchscreen = true, hasLeanback = false))
        // 车机：无触屏但没有 leanback
        assertFalse(TvSurface.isTv(uiModeType = 3, hasTouchscreen = false, hasLeanback = false))
    }
}
