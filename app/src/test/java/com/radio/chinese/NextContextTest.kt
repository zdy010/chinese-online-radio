package com.radio.chinese

import com.radio.chinese.domain.model.OperaAudioFile
import com.radio.chinese.service.OperaPlayContext
import com.radio.chinese.service.nextOperaContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextContextTest {
    private fun file(path: String?, url: String?) = OperaAudioFile(
        fileId = 1L, name = "n", size = 0, categoryName = "c", operaName = "",
        downloadUrl = url, sourcePath = path
    )

    @Test fun explicitContextWins() {
        val old = OperaPlayContext(7L, "/a/old.mp3")
        val ctx = OperaPlayContext(9L, "/a/new.mp3")
        assertEquals(ctx, nextOperaContext(old, file("/x.mp3", null), ctx))
    }
    @Test fun nextTrackKeepsSourceIdAndSwapsPath() {
        val old = OperaPlayContext(7L, "/dav/a1.mp3")
        val next = file("/dav/a2.mp3", "https://wd/dav/a2.mp3")
        assertEquals(OperaPlayContext(7L, "/dav/a2.mp3"), nextOperaContext(old, next, null))
    }
    @Test fun fallsBackToDownloadUrlWhenNoSourcePath() {
        val old = OperaPlayContext(7L, "/dav/a1.mp3")
        val next = file(null, "https://wd/dav/a2.mp3?t=1")
        assertEquals(OperaPlayContext(7L, "https://wd/dav/a2.mp3?t=1"), nextOperaContext(old, next, null))
    }
    @Test fun nullWhenNoOldAndNoExplicit() {
        assertNull(nextOperaContext(null, file("/a.mp3", "u"), null))
    }
    @Test fun keepsOldWhenFileCarriesNoPathAtAll() {
        val old = OperaPlayContext(7L, "/dav/a1.mp3")
        assertEquals(old, nextOperaContext(old, file(null, null), null))
    }
}
