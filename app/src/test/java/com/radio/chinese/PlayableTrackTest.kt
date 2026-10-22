package com.radio.chinese

import com.radio.chinese.domain.PlayableTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这个文件是不是能播的曲目」的判定。
 *
 * 起因：云盘目录里除了音频还散着歌词与封面，而列目录与索引的判断是
 * 「不是文件夹就算曲目」，于是 .lrc 也进了播放列表，点一下必然失败。
 *
 * 这里刻意把各种音频扩展名都列进来当反例：一旦有人把规则改成
 * 「只放行已知音频扩展名」的白名单，这些用例会立刻红——白名单会误杀
 * wav/flac/m3u8 以及用户自有的非常规格式，等于悄悄删掉能用的功能。
 */
class PlayableTrackTest {

    @Test
    fun `歌词文件不是曲目，大小写都算`() {
        assertFalse(PlayableTrack.isPlayable("花木兰.lrc"))
        assertFalse(PlayableTrack.isPlayable("花木兰.LRC"))
        assertFalse(PlayableTrack.isPlayable("005 《花木兰》(常香玉代表作之一）1.lrc"))
    }

    @Test
    fun `封面与图片不是曲目`() {
        for (ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")) {
            assertFalse(".$ext 应判为非曲目", PlayableTrack.isPlayable("cover.$ext"))
            assertFalse(".$ext 大写也应判为非曲目", PlayableTrack.isPlayable("cover.${ext.uppercase()}"))
        }
    }

    @Test
    fun `字幕说明与元数据文件不是曲目`() {
        for (name in listOf("说明.txt", "readme.md", "花木兰.srt", "花木兰.ass",
                            "花木兰.ssa", "subtitles.vtt", ".nfo", "meta.json", "data.xml")) {
            assertFalse("$name 应判为非曲目", PlayableTrack.isPlayable(name))
        }
    }

    /** 白名单做法会把这些全杀掉，所以必须逐个钉住。 */
    @Test
    fun `常见与不常见的音频格式都放行`() {
        for (ext in listOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus",
                           "wma", "ape", "aiff", "m3u8", "m3u", "pls", "mp2", "mka")) {
            assertTrue(".$ext 应判为可播放", PlayableTrack.isPlayable("唱段.$ext"))
        }
    }

    @Test
    fun `多点文件名取最后一段扩展名`() {
        assertTrue(PlayableTrack.isPlayable("0178--015--拷红 豫剧.mp3"))
        assertFalse(PlayableTrack.isPlayable("拷红.v2.lrc"))
    }

    @Test
    fun `无扩展名不猜测，保留`() {
        // 云盘里存在没有扩展名的音频；判成非曲目等于让用户少一批能听的
        assertTrue(PlayableTrack.isPlayable("花木兰"))
        assertTrue(PlayableTrack.isPlayable("现场录音"))
        // 结尾一个点不构成扩展名
        assertTrue(PlayableTrack.isPlayable("花木兰."))
    }

    @Test
    fun `传入带路径的 href 也能判对`() {
        // 索引行与浏览条目有时给的是完整 href，不能因为带斜杠就判错
        assertFalse(PlayableTrack.isPlayable("/webdav/%E5%B8%B8%E9%A6%99%E7%8E%89/花木兰.lrc"))
        assertTrue(PlayableTrack.isPlayable("/webdav/常香玉/花木兰.mp3"))
    }

    @Test
    fun `空名字不 panic 且不判为曲目`() {
        assertFalse(PlayableTrack.isPlayable(""))
        assertFalse(PlayableTrack.isPlayable("   "))
    }
}
