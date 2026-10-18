package com.radio.chinese.domain.browser

import android.content.Context
import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.BrowserProvider
import com.radio.chinese.domain.SourceType
import com.radio.chinese.service.WebDavClient
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 浏览器工厂。
 *
 * WebDAV 每次调用都新建一个 `WebDavClient`：它的凭证是可写字段、列表缓存的 key
 * 只有 path，多源并发共用一个实例必然串。工厂刻意不做缓存——索引器一轮任务持有
 * 一个实例、跑完就丢，免得两万条目录行常驻内存；浏览页同理，用完即弃。
 */
@Singleton
class BrowserFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) : BrowserProvider {

    internal var clientFactory: () -> WebDavClient = { WebDavClient() }

    fun create(source: AudioSource): AudioBrowser = when (source.type) {
        SourceType.WEBDAV -> WebDavBrowser(clientFactory(), source.url, source.username, source.password)
        SourceType.LOCAL -> LocalBrowser(context)
        SourceType.M3U -> M3uBrowser(okHttpClient)
        SourceType.HTTP -> HttpBrowser(okHttpClient)
    }

    /**
     * 只有具备目录树语义的来源能建索引。LOCAL 走 MediaStore 自有查询，
     * M3U / HTTP 是扁平播放列表，爬它们没有意义。
     */
    override fun createForIndexing(source: AudioSource): AudioBrowser? =
        if (source.type == SourceType.WEBDAV) create(source) else null
}
