package com.radio.chinese

import com.radio.chinese.domain.model.VideoControlsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全屏按钮该不该出现、控制层多久收起。
 *
 * 最要紧的是最后那条「车机档但已在全屏 → true」：大屏手机竖屏时不是车机档、有按钮，
 * 点下去强制横屏后 sw≥600 且横屏 会让形态判定跳成车机档。如果那时还按
 * 「车机档不给按钮」走，退出全屏的按钮会自己消失，用户只能靠系统返回键出来。
 */
class VideoControlsPolicyTest {

    @Test
    fun `车机档非全屏不给进入入口`() {
        assertFalse(
            VideoControlsPolicy.showsFullScreenButton(isCarSurface = true, hasVideoTrack = true, isFullScreen = false)
        )
    }

    @Test
    fun `没有视频轨时任何形态都不给按钮`() {
        assertFalse(
            VideoControlsPolicy.showsFullScreenButton(isCarSurface = false, hasVideoTrack = false, isFullScreen = false)
        )
    }

    @Test
    fun `手机档有画面才给按钮`() {
        assertTrue(
            VideoControlsPolicy.showsFullScreenButton(isCarSurface = false, hasVideoTrack = true, isFullScreen = false)
        )
    }

    @Test
    fun `已在全屏就必须给退出入口，即使形态跳成了车机档`() {
        assertTrue(
            VideoControlsPolicy.showsFullScreenButton(isCarSurface = true, hasVideoTrack = true, isFullScreen = true)
        )
        assertTrue(
            VideoControlsPolicy.showsFullScreenButton(isCarSurface = false, hasVideoTrack = true, isFullScreen = true)
        )
    }

    @Test
    fun `控制层收起延时是 3 秒`() {
        assertEquals(3000L, VideoControlsPolicy.CONTROLS_AUTO_HIDE_MS)
    }
}
