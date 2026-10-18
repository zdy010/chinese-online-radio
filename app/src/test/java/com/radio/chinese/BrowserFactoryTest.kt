package com.radio.chinese

import android.content.Context
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.SourceType
import com.radio.chinese.domain.browser.BrowserFactory
import com.radio.chinese.service.WebDavClient
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 播放凭证回归（用户实测问题 2：所有 WebDAV 源都播不了了）。
 *
 * `RadioService` 建 ExoPlayer 时从**注入的 `WebDavClient` 单例**读 `authHeader()`，
 * 且 `username` 为空就根本不加 Authorization 头。因此浏览/播放路径必须继续复用这个
 * 单例，否则播放侧拿不到凭证；只有并发爬取的索引器才需要每源独立实例
 * （避开 `listFilesCache` 的 key 不含源而跨源串数据）。
 */
class BrowserFactoryTest {
    private fun webdav(id: Long, user: String, pass: String) = AudioSource(
        id = id, name = "源$id", type = SourceType.WEBDAV,
        url = "https://webdav.123pan.cn/webdav", username = user, password = pass
    )

    private fun factory(shared: WebDavClient) = BrowserFactory(
        mockk<Context>(relaxed = true), mockk<OkHttpClient>(relaxed = true), shared
    )

    @Test fun `浏览与播放复用 RadioService 读取的那个单例`() {
        val shared = mockk<WebDavClient>(relaxed = true)
        val f = factory(shared)
        assertSame("不同源也必须同一个客户端，否则 RadioService 读不到凭证", shared, f.browseClient(webdav(1, "u1", "p1")))
        assertSame(shared, f.browseClient(webdav(2, "u2", "p2")))
        assertSame(shared, (f.create(webdav(1, "u1", "p1")) as com.radio.chinese.domain.browser.WebDavBrowser).client)
    }

    @Test fun `索引器每个源用独立客户端`() {
        var created = 0
        val f = factory(mockk(relaxed = true))
        f.indexClientFactory = { WebDavClient().also { created++ } }

        val a = f.indexClient(webdav(1, "u1", "p1"))
        val b = f.indexClient(webdav(2, "u2", "p2"))

        assertNotSame(a, b)
        assertEquals(2, created)
    }

    @Test fun `索引客户端不等于播放单例`() {
        val shared = mockk<WebDavClient>(relaxed = true)
        val f = factory(shared)
        f.indexClientFactory = { WebDavClient() }
        assertNotSame(shared, f.indexClient(webdav(1, "u", "p")))
    }

    @Test fun `非 WebDAV 源不参与索引`() {
        val f = factory(mockk(relaxed = true))
        f.indexClientFactory = { WebDavClient() }
        assertNull(f.createForIndexing(AudioSource(1, "本地", SourceType.LOCAL, "")))
        assertNull(f.createForIndexing(AudioSource(2, "歌单", SourceType.M3U, "http://x")))
        assertNull(f.createForIndexing(AudioSource(3, "流", SourceType.HTTP, "http://x")))
        assertNotNull(f.createForIndexing(webdav(4, "u", "p")))
    }
}
