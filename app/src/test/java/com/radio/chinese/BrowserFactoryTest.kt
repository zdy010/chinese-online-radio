package com.radio.chinese

import android.content.Context
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.SourceType
import com.radio.chinese.domain.browser.BrowserFactory
import com.radio.chinese.domain.browser.WebDavBrowser
import com.radio.chinese.service.WebDavClient
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 索引并发的前提：每源一个 WebDavClient。
 *
 * WebDavClient 的 listFilesCache 的 key 只有 path，而四个快捷码源的 base URL
 * 完全相同；复用单例会跨源串数据，凭证字段也会互相改写（spec §4 / 终审 I2 同源缺陷）。
 */
class BrowserFactoryTest {
    private fun webdav(id: Long, user: String, pass: String) = AudioSource(
        id = id, name = "源$id", type = SourceType.WEBDAV,
        url = "https://webdav.123pan.cn/webdav", username = user, password = pass
    )

    private fun factory() = BrowserFactory(mockk<Context>(relaxed = true), mockk<OkHttpClient>(relaxed = true))

    @Test fun `两个 WebDAV 源得到互不相同的客户端实例`() {
        val made = mutableListOf<WebDavClient>()
        val f = factory()
        f.clientFactory = { WebDavClient().also { made += it } }

        val a = f.create(webdav(1, "u1", "p1"))
        val b = f.create(webdav(2, "u2", "p2"))

        assertTrue("a=${a.javaClass.simpleName}", a is WebDavBrowser)
        assertTrue("b=${b.javaClass.simpleName}", b is WebDavBrowser)
        assertEquals(2, made.size)
        assertNotSame(made[0], made[1])
    }

    @Test fun `工厂不缓存实例，持有期由调用方负责`() {
        var created = 0
        val f = factory()
        f.clientFactory = { WebDavClient().also { created++ } }
        f.createForIndexing(webdav(1, "u", "p"))
        f.createForIndexing(webdav(1, "u", "p"))
        // 缓存归调用方持有：索引器一轮任务建一个，跑完就丢，避免常驻内存吃下两万条目录行
        assertEquals(2, created)
    }

    @Test fun `非 WebDAV 源不参与索引`() {
        val f = factory()
        f.clientFactory = { WebDavClient() }
        assertNull(f.createForIndexing(AudioSource(1, "本地", SourceType.LOCAL, "")))
        assertNull(f.createForIndexing(AudioSource(2, "歌单", SourceType.M3U, "http://x")))
        assertNull(f.createForIndexing(AudioSource(3, "流", SourceType.HTTP, "http://x")))
    }
}
