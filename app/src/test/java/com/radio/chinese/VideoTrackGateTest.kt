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

    /**
     * 服务同进程重建时的情形：`_videoStageMounted` 还留着 true，而新建的播放器默认禁着视频轨。
     * 如果只看布尔翻转，`setVideoStageMounted(true)` 会被幂等守卫吞掉，闸门永远不重落 ——
     * 用户看到的是「退出重进才好」的黑屏。所以换了播放器实例就必须重落。
     */
    @Test
    fun `播放器实例换了就必须重落闸门，即使挂载态没变`() {
        assertTrue(VideoTrackGate.shouldReapplyGate(previousMounted = true, nextMounted = true, playerReplaced = true))
        assertTrue(VideoTrackGate.shouldReapplyGate(previousMounted = false, nextMounted = false, playerReplaced = true))
    }

    @Test
    fun `挂载态翻转要重落，没翻且没换实例不重落`() {
        assertTrue(VideoTrackGate.shouldReapplyGate(previousMounted = false, nextMounted = true, playerReplaced = false))
        assertTrue(VideoTrackGate.shouldReapplyGate(previousMounted = true, nextMounted = false, playerReplaced = false))
        assertFalse(VideoTrackGate.shouldReapplyGate(previousMounted = true, nextMounted = true, playerReplaced = false))
        assertFalse(VideoTrackGate.shouldReapplyGate(previousMounted = false, nextMounted = false, playerReplaced = false))
    }
}
