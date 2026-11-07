package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationSource
import com.radio.chinese.domain.model.VideoTrackGate
import com.radio.chinese.service.nextStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iptv 播放页的三条接线判据，都是"漏了不报错、只会表现怪"的那类。
 *
 * 断言的是生产真正调用的函数，不是测试里重写的替身：
 * 闸门 → VideoTrackGate.shouldDecodeVideo；上下台 → nextStation；
 * 源名 → StationSource.quality / label 的分工（spec §2.3）。
 */
class IptvPlayerWiringTest {

    private val ids = listOf(
        RadioStation(id = "iptv:a", name = "A", category = "general"),
        RadioStation(id = "iptv:b", name = "B", category = "general"),
        RadioStation(id = "iptv:c", name = "C", category = "general"),
    )

    @Test
    fun `闸门跟页面意图，有视频轨才开解码`() {
        assertTrue(VideoTrackGate.shouldDecodeVideo(pictureWanted = true))
        assertFalse(VideoTrackGate.shouldDecodeVideo(pictureWanted = false))
        // 出厂默认必须关着：只听声的电台不该被新播放页捎带开一路视频
        assertTrue(VideoTrackGate.DEFAULT_VIDEO_TRACK_DISABLED)
    }

    @Test
    fun `上下台在首尾回绕且不越界`() {
        assertEquals("iptv:b", nextStation(ids, "iptv:a", forward = true)?.id)
        assertEquals("iptv:c", nextStation(ids, "iptv:b", forward = true)?.id)
        assertEquals("iptv:a", nextStation(ids, "iptv:c", forward = true)?.id)
        assertEquals("iptv:c", nextStation(ids, "iptv:a", forward = false)?.id)
        assertEquals("iptv:a", nextStation(ids, "不在表里", forward = true)?.id)
        assertNull(nextStation(emptyList(), "iptv:a", forward = true))
    }

    @Test
    fun `源名走 quality 而不是 label`() {
        // label 会进「正在尝试节目源 X…」与通知栏 artist；iptv 源必须留空
        val src = StationSource(url = "http://a/1.m3u8", quality = "1080p")
        assertEquals("", src.label)
        assertEquals("1080p", src.quality)
    }
}
