package com.radio.chinese

import com.radio.chinese.domain.model.VideoControlsPolicy
import com.radio.chinese.domain.model.VideoOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全屏/旋转入口该不该出现。
 *
 * 最要紧的是「车机形态也必须给全屏入口」那条：上一版把 `!isCarSurface` 写进条件，
 * 而「界面模式=车机」「sw≥600 且横着拿」「车机恒横屏」三种情况都会判成 Car，
 * 于是用户在手机上完全看不到这个功能 —— 就是本轮报的 bug。
 */
class VideoControlsPolicyTest {

    @Test
    fun `车机形态也必须给全屏入口`() {
        assertTrue(VideoControlsPolicy.showsFullScreenButton(hasVideoTrack = true, isFullScreen = false))
        assertTrue(VideoControlsPolicy.showsFullScreenButton(hasVideoTrack = true, isFullScreen = true))
    }

    @Test
    fun `没有画面且不在全屏时不给入口`() {
        assertFalse(VideoControlsPolicy.showsFullScreenButton(hasVideoTrack = false, isFullScreen = false))
    }

    @Test
    fun `iptv 页强制显示全屏入口，即便还没出画面`() {
        assertTrue(
            VideoControlsPolicy.showsFullScreenButton(
                hasVideoTrack = false,
                isFullScreen = false,
                alwaysShow = true,
            )
        )
    }

    @Test
    fun `已在全屏时恒给退出入口`() {
        assertTrue(VideoControlsPolicy.showsFullScreenButton(hasVideoTrack = false, isFullScreen = true))
    }

    @Test
    fun `旋转按钮只在可转设备的全屏态给`() {
        // 车机恒横屏：给了就是"按了没反应"
        assertFalse(VideoControlsPolicy.showsRotateButton(canRotateAtEntry = false, isFullScreen = true))
        assertFalse(VideoControlsPolicy.showsRotateButton(canRotateAtEntry = false, isFullScreen = false))
        // 手机非全屏：方向归系统与用户，应用不插队
        assertFalse(VideoControlsPolicy.showsRotateButton(canRotateAtEntry = true, isFullScreen = false))
        // 手机全屏：横看竖看由用户挑
        assertTrue(VideoControlsPolicy.showsRotateButton(canRotateAtEntry = true, isFullScreen = true))
    }

    /**
     * 为什么是“进入全屏那一刻能不能转”，而不是“现在是不是车机形态”：
     * sw≥600 的手机点全屏后强制横屏，形态判定会当场翻成 Car。
     * 若按当前形态判，旋转按钮会在用户眼前消失，只剩“退出全屏”一条路 ——
     * 而设备明明还能转。
     */
    @Test
    fun `大屏手机全屏后形态翻转，旋转按钮不得跟着消失`() {
        assertTrue(VideoControlsPolicy.showsRotateButton(canRotateAtEntry = true, isFullScreen = true))
    }

    @Test
    fun `旋转按钮在当前横屏时切竖屏当前竖屏时切横屏`() {
        assertEquals(VideoOrientation.Portrait, VideoControlsPolicy.nextOrientation(currentLandscape = true))
        assertEquals(VideoOrientation.Landscape, VideoControlsPolicy.nextOrientation(currentLandscape = false))
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
