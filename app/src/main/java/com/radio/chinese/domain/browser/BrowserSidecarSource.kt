package com.radio.chinese.domain.browser

import android.content.Context
import com.radio.chinese.data.repository.AudioLibraryRepository
import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.SourceType
import com.radio.chinese.domain.sidecar.SidecarMediaSource
import com.radio.chinese.domain.sidecar.decodeTextBytes
import com.radio.chinese.domain.sidecar.sidecarPath
import com.radio.chinese.service.OperaPlayContext
import com.radio.chinese.service.WebDavClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 走各源 AudioBrowser.readSidecar 取附属文件：按候选扩展名逐个试，
 * 命中即返回（先 .lrc 后 .LRC；图片 jpg/png 含大写）。字节按派生路径做小容量 LRU 缓存，
 * 换段回来不重复下载。sourceId=0 是本地直连约定：先经 MediaStore 把
 * content:// 解析成文件系统路径，再在文件系统上找同名附属文件。
 */
@Singleton
class BrowserSidecarSource @Inject constructor(
    private val repository: AudioLibraryRepository,
    private val webDavClient: WebDavClient,
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val appContext: Context
) : SidecarMediaSource {

    private val byteCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Result<ByteArray>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result<ByteArray>>?) = size > 64
        }
    )

    override suspend fun readLyrics(ctx: OperaPlayContext): Result<String> =
        readWithExtensions(ctx, listOf("lrc", "LRC")) { Result.success(decodeTextBytes(it)) }

    override suspend fun readImage(ctx: OperaPlayContext): Result<ByteArray> =
        readWithExtensions(ctx, listOf("jpg", "JPG", "png", "PNG")) { Result.success(it) }

    private suspend fun <T> readWithExtensions(
        ctx: OperaPlayContext,
        exts: List<String>,
        map: (ByteArray) -> Result<T>
    ): Result<T> {
        val browser = browserFor(ctx)
        val base = if (ctx.sourceId == 0L) {
            LocalBrowser(appContext).resolveDataPath(ctx.trackPath) ?: ctx.trackPath
        } else ctx.trackPath
        for (ext in exts) {
            val p = sidecarPath(base, ext)
            // 只缓存成功：弱网一次超时/竞态一次 403 不得把失败钉死到进程结束（终审 I3）
            val r = byteCache[p] ?: browser.readSidecar(p).also { if (it.isSuccess) byteCache[p] = it }
            r.getOrNull()?.let { return map(it) }
        }
        return Result.failure(Exception("NOT_FOUND"))
    }

    private suspend fun browserFor(ctx: OperaPlayContext): AudioBrowser {
        val source = if (ctx.sourceId == 0L) null else repository.getSource(ctx.sourceId)
        return when (source?.type) {
            SourceType.WEBDAV -> WebDavBrowser(webDavClient, source.url, source.username, source.password)
            SourceType.M3U -> M3uBrowser(okHttpClient)
            SourceType.HTTP -> HttpBrowser(okHttpClient)
            else -> LocalBrowser(appContext)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SidecarBinder {
    @Binds
    abstract fun bindSidecarMediaSource(impl: BrowserSidecarSource): SidecarMediaSource
}
