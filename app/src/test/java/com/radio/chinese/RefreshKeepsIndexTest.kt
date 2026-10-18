package com.radio.chinese

import com.radio.chinese.data.CredentialManager
import com.radio.chinese.data.dao.AudioCacheDao
import com.radio.chinese.data.dao.AudioFavoriteDao
import com.radio.chinese.data.dao.AudioRecentDao
import com.radio.chinese.data.dao.AudioSourceDao
import com.radio.chinese.data.repository.AudioLibraryRepository
import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.AudioTrack
import com.radio.chinese.domain.SourceType
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 现状缺陷：browseSource() 与 refreshBrowse() 都走 refreshSource，
 * 而它先做 deleteBySource 整源清空——用户每进一次目录，后台索引建立的
 * 两万行就被抹掉，接着退化成整轮重爬。这里钉死"只清当前目录"。
 */
class RefreshKeepsIndexTest {
    private fun repo(cacheDao: AudioCacheDao, sourceDao: AudioSourceDao) = AudioLibraryRepository(
        sourceDao, cacheDao, mockk<AudioFavoriteDao>(relaxed = true),
        mockk<AudioRecentDao>(relaxed = true), mockk<CredentialManager>(relaxed = true)
    )

    private class StubBrowser : AudioBrowser {
        var calls = 0
        override suspend fun listChildren(path: String): Result<List<AudioTrack>> {
            calls++; return Result.success(emptyList())
        }

        override fun resolveUri(track: AudioTrack) = ""
        override suspend fun testConnection() = Result.success(Unit)
    }

    @Test fun `目录级刷新不删除其他目录的缓存行`() = runTest {
        val sourceDao = mockk<AudioSourceDao>(relaxed = true)
        val cacheDao = mockk<AudioCacheDao>(relaxed = true)
        val browser = StubBrowser()

        repo(cacheDao, sourceDao).refreshDir(
            source = AudioSource(1, "豫剧", SourceType.WEBDAV, "https://webdav.123pan.cn/webdav"),
            path = "/常香玉", browser = browser
        )

        coVerify(exactly = 0) { cacheDao.deleteBySource(any()) }
        coVerify(exactly = 1) { cacheDao.deleteByParentPath(1L, "/常香玉") }
        assertEquals(1, browser.calls)
    }
}
