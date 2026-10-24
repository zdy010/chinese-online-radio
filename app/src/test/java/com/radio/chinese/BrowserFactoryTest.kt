package com.radio.chinese

import android.content.Context
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.SourceType
import com.radio.chinese.domain.browser.BrowserFactory
import com.radio.chinese.service.WebDavClient
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
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

    /**
     * 根路径必须能自己落到源地址。
     *
     * 用户实测坑：`browseSource` 进入一个源时传的是 `listChildren("")`，而此前
     * M3U/HTTP 两个浏览器只被注了 OkHttpClient，根不知道 `source.url`：
     * M3U 会 `Request.Builder().url("")` 直接抛异常（点进去永远“加载失败”），
     * HTTP 会返回一条 `path=""` 的空曲目（点开不可能播）。
     */
    @Test fun `M3U 与 HTTP 浏览器拿到源地址`() {
        val f = factory(mockk(relaxed = true))
        val m3u = f.create(AudioSource(5, "歌单", SourceType.M3U, "https://host/show.m3u"))
            as com.radio.chinese.domain.browser.M3uBrowser
        assertEquals("https://host/show.m3u", m3u.sourceUrl)
        val http = f.create(AudioSource(6, "流", SourceType.HTTP, "https://host/live.m3u8"))
            as com.radio.chinese.domain.browser.HttpBrowser
        assertEquals("https://host/live.m3u8", http.sourceUrl)
    }

    @Test fun `M3U 根路径解析成清单地址而不是空 URL`() {
        val b = com.radio.chinese.domain.browser.M3uBrowser(
            mockk(relaxed = true), "https://host/show.m3u"
        )
        assertEquals("https://host/show.m3u", b.playlistUrlFor(""))
        assertEquals("https://host/other.m3u", b.playlistUrlFor("https://host/other.m3u"))
    }

    @Test fun `HTTP 源根路径返回真实直链条目`() = runBlocking {
        val track = com.radio.chinese.domain.browser.HttpBrowser(
            mockk(relaxed = true), "https://host/live.m3u8"
        ).listChildren("").getOrThrow().single()
        assertEquals("https://host/live.m3u8", track.path)
        assertEquals(true, track.isHlsStream)
        assertEquals("live.m3u8", track.name)
    }

    @Test fun `两个地址都为空时不得伪造曲目`() = runBlocking {
        val tracks = com.radio.chinese.domain.browser.HttpBrowser(
            mockk(relaxed = true), ""
        ).listChildren("").getOrThrow()
        assertEquals("空地址写进列表就是一个点不响的假行", 0, tracks.size)
    }
}
