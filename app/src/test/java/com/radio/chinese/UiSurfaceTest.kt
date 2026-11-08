package com.radio.chinese

import com.radio.chinese.ui.theme.UiSurface
import com.radio.chinese.ui.theme.resolveUiSurface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 界面档位判定的守卫。
 *
 * 电视 1080p@320dpi 的 smallestWidthDp 只有 540，按老的 600 阈值会被判成手机档，
 * 列表压成单列、右侧空一大片。这里把「TV 横屏即大屏档」钉住。
 */
class UiSurfaceTest {

    @Test
    fun `电视横屏走大屏档，即便 sw 只有 540`() {
        assertEquals(
            UiSurface.Car,
            resolveUiSurface(mode = 0, smallestWidthDp = 540, landscape = true, isTv = true),
        )
    }

    @Test
    fun `电视竖屏仍按手机档`() {
        assertEquals(
            UiSurface.Phone,
            resolveUiSurface(mode = 0, smallestWidthDp = 540, landscape = false, isTv = true),
        )
    }

    @Test
    fun `手机 sw540 横屏仍是手机档`() {
        assertEquals(
            UiSurface.Phone,
            resolveUiSurface(mode = 0, smallestWidthDp = 540, landscape = true, isTv = false),
        )
    }

    @Test
    fun `大屏横屏仍走车机档`() {
        assertEquals(
            UiSurface.Car,
            resolveUiSurface(mode = 0, smallestWidthDp = 720, landscape = true, isTv = false),
        )
    }

    @Test
    fun `手动档优先于电视判定`() {
        assertEquals(
            UiSurface.Phone,
            resolveUiSurface(mode = 1, smallestWidthDp = 540, landscape = true, isTv = true),
        )
        assertEquals(
            UiSurface.Car,
            resolveUiSurface(mode = 2, smallestWidthDp = 400, landscape = false, isTv = false),
        )
    }
}
