package com.radio.chinese

import com.radio.chinese.domain.sidecar.decodeTextBytes
import com.radio.chinese.domain.sidecar.sidecarPath
import org.junit.Assert.assertEquals
import org.junit.Test

class SidecarPathTest {
    @Test fun replacesAudioExtension() {
        assertEquals("/music/henan/a.lrc", sidecarPath("/music/henan/a.mp3", "lrc"))
    }
    @Test fun keepsPercentEncodingAndQuery() {
        assertEquals("https://wd/dir/%E8%B1%AB.lrc?t=1",
            sidecarPath("https://wd/dir/%E8%B1%AB.mp3?t=1", "lrc"))
    }
    @Test fun appendsWhenNoExtension() {
        assertEquals("/d/song.lrc", sidecarPath("/d/song", "lrc"))
    }
    @Test fun replacesCaseInsensitivePosition() {
        // 原扩展名多大写都整段替换为调用方给的 ext
        assertEquals("/d/a.lrc", sidecarPath("/d/a.MP3", "lrc"))
        assertEquals("/d/a.LRC", sidecarPath("/d/a.mp3", "LRC"))
    }
    @Test fun emptyInputsReturnBase() {
        assertEquals("/d/a.mp3", sidecarPath("/d/a.mp3", ""))
        assertEquals("", sidecarPath("", "lrc"))
    }

    @Test fun decodesUtf8Normally() {
        assertEquals("第一行", decodeTextBytes("第一行".toByteArray()))
    }
    @Test fun stripsUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "豫剧".toByteArray()
        assertEquals("豫剧", decodeTextBytes(bytes))
    }
    @Test fun fallsBackToGb18030WhenNotUtf8() {
        // 老工具产出的 GBK 歌词：按 UTF-8 解会得到替换符，必须回退 GB18030
        val gbk = "河南豫剧院".toByteArray(java.nio.charset.Charset.forName("GB18030"))
        assertEquals("河南豫剧院", decodeTextBytes(gbk))
    }
}
