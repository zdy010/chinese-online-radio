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

    /**
     * 终审 C1：全屏时页面细节区块（台名/频率/简介/标签）必须不渲染。
     *
     * 不渲染不是因为它们不好看，而是因为车机档的全屏走的是 Row 左右分栏：
     * 留着 StationInfoBlock 就吃掉 55% 屏宽，画面只占左侧 45%，
     * 用户点了「全屏」结果看到半屏画面 + 半屏台名，像应用坏了。
     */
    @Test
    fun `全屏时页面细节区块一律不渲染`() {
        assertFalse(VideoControlsPolicy.showsStationDetails(isFullScreen = true))
        assertTrue(VideoControlsPolicy.showsStationDetails(isFullScreen = false))
    }

    /**
     * 终审 I1：全屏时即使流断了 / 切到了纯音频台（hasVideoTrack 变 false），
     * 画面层也必须还在 —— 退出入口长在这一层上，层掉了人就锁在横屏沉浸态里。
     */
    @Test
    fun `全屏时没有视频轨也要保留画面层与退出入口`() {
        assertTrue(VideoControlsPolicy.showsPictureSurface(hasVideoTrack = false, isFullScreen = true))
        assertTrue(VideoControlsPolicy.showsPictureSurface(hasVideoTrack = true, isFullScreen = true))
        assertTrue(VideoControlsPolicy.showsPictureSurface(hasVideoTrack = true, isFullScreen = false))
        assertFalse(VideoControlsPolicy.showsPictureSurface(hasVideoTrack = false, isFullScreen = false))
    }
}
