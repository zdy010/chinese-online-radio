package com.radio.chinese

import com.radio.chinese.domain.model.VideoMedia
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这个文件像不像视频」的正向判定，只用于列表徽标。
 *
 * 刻意把 m3u8 和裸名文件都当反例钉住：判成视频会在列表上误导用户，
 * 而云盘里的 m3u8 常常是歌单或电台清单。能不能播不由这里管，
 * 由 PlayableTrack 的黑名单与播放器运行时真相决定。
 */
class VideoMediaTest {

    @Test
    fun `常见视频容器都命中，大小写都算`() {
        for (ext in listOf("mp4", "mkv", "mov", "webm", "flv", "avi", "mpg",
                           "mpeg", "wmv", "m4v", "rm", "rmvb", "3gp", "vob", "ts", "m2ts")) {
            assertTrue(".$ext 应判为视频", VideoMedia.looksLikeVideo("穆桂英挂帅.$ext"))
            assertTrue(".$ext 大写应判为视频", VideoMedia.looksLikeVideo("穆桂英挂帅.${ext.uppercase()}"))
        }
    }

    @Test
    fun `音频与歌词不是视频`() {
        for (ext in listOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "lrc", "txt", "srt", "jpg")) {
            assertFalse(".$ext 不该判为视频", VideoMedia.looksLikeVideo("唱段.$ext"))
        }
    }

    @Test
    fun `m3u8 与裸名文件一律不标`() {
        // 歧义太大：云盘里的 m3u8 可能是歌单/电台清单；无扩展名宁少不多
        assertFalse(VideoMedia.looksLikeVideo("直播.m3u8"))
        assertFalse(VideoMedia.looksLikeVideo("花木兰"))
        assertFalse(VideoMedia.looksLikeVideo("花木兰."))
    }

    @Test
    fun `带路径的 href 只认最后一段`() {
        assertTrue(VideoMedia.looksLikeVideo("/webdav/%E6%88%8F%E6%9B%B2/穆桂英.mp4"))
        assertFalse(VideoMedia.looksLikeVideo("/webdav/常香玉/花木兰.lrc"))
        // 目录名里带 .mp4 也不能骗过判定
        assertFalse(VideoMedia.looksLikeVideo("/mp4/2021/说明.txt"))
    }

    @Test
    fun `空与空白不 panic`() {
        assertFalse(VideoMedia.looksLikeVideo(""))
        assertFalse(VideoMedia.looksLikeVideo("   "))
    }
}
