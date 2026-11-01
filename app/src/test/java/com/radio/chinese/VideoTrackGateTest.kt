package com.radio.chinese

import com.radio.chinese.domain.model.VideoTrackGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频轨闸门的红线。
 *
 * 「没挂画面就绝不解码视频」是整个视频设计唯一挡住现状回退的东西：
 * 一旦有人把它改成默认开，所有只听声的电台都要多解码一路视频，
 * 耗电、发热、起播耗时全变差，而单测与 UI 回归都看不出来。所以这条要单独钉死。
 */
class VideoTrackGateTest {

    @Test
    fun `没有画面挂载时一律不解码视频`() {
        assertFalse(VideoTrackGate.shouldDecodeVideo(stageMounted = false))
    }

    @Test
    fun `画面挂载后才解码视频`() {
        assertTrue(VideoTrackGate.shouldDecodeVideo(stageMounted = true))
    }

    @Test
    fun `出厂默认是禁用视频轨`() {
        assertTrue(VideoTrackGate.DEFAULT_VIDEO_TRACK_DISABLED)
    }
}
