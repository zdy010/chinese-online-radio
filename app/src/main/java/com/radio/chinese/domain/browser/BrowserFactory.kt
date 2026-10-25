package com.radio.chinese.domain.browser

import android.content.Context
import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.BrowserProvider
import com.radio.chinese.domain.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 浏览器工厂。两条路径**故意**用不同的客户端：
 *
 * - 浏览 / 播放：用注入的 [sharedWebDavClient] 单例。`RadioService` 建 ExoPlayer 时
 *   读的就是它（`webDavClient.username.isNotEmpty()` 才加 Authorization 头），
 *   换成临时实例会让所有 WebDAV 播放失去鉴权——这个耦合很脏，但改掉它必须先重构播放侧，
 *   不能在一次索引功能里顺手换掉（用户实测问题 2 就是这么来的）。
 * - 索引：每轮任务一个独立实例。`WebDavClient.listFilesCache` 的 key 只有 path，
 *   四个快捷码源 base URL 又完全相同，并发共用单例会跨源串数据。
 */
@Singleton
class BrowserFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val sharedWebDavClient: WebDavTransport,
) : BrowserProvider {

    /** 索引客户端工厂；由 DI 提供，测试里替换成计数假件。 */
    internal var indexClientFactory: () -> WebDavTransport = { sharedWebDavClient }

    fun browseClient(source: AudioSource): WebDavTransport = sharedWebDavClient

    fun indexClient(source: AudioSource): WebDavTransport = indexClientFactory()

    fun create(source: AudioSource): AudioBrowser = when (source.type) {
        SourceType.WEBDAV -> WebDavBrowser(browseClient(source), source.url, source.username, source.password)
        SourceType.LOCAL -> LocalBrowser(context)
        SourceType.M3U -> M3uBrowser(okHttpClient, source.url)
        SourceType.HTTP -> HttpBrowser(okHttpClient, source.url)
    }

    /**
     * 只有具备目录树语义的来源能建索引。LOCAL 走 MediaStore 自有查询，
     * M3U / HTTP 是扁平播放列表，爬它们没有意义。
     */
    override fun createForIndexing(source: AudioSource): AudioBrowser? =
        if (source.type == SourceType.WEBDAV) {
            WebDavBrowser(indexClient(source), source.url, source.username, source.password)
        } else {
            null
        }
}
