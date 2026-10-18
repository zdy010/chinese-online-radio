package com.radio.chinese.domain.browser

import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioTrack
import com.radio.chinese.service.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV 浏览器。
 *
 * [client] 可读取是为了让测试能钉死一件播放侧的事：`RadioService` 建 ExoPlayer 时
 * 从注入的 `WebDavClient` 单例读 `authHeader()`，所以浏览/播放路径必须复用那个单例，
 * 只有并发爬取的索引器才另建独立实例（`listFilesCache` 的 key 不含源，共用会跨源串数据）。
 */
class WebDavBrowser(
    val client: WebDavClient,
    val serverUrl: String,
    val username: String,
    val password: String
) : AudioBrowser {

    override suspend fun listChildren(path: String): Result<List<AudioTrack>> = withContext(Dispatchers.IO) {
        applyCredentials()
        client.listFiles(path)
            .map { file ->
                AudioTrack(
                    id = file.href,
                    name = file.displayName,
                    path = file.href,
                    size = file.contentLength,
                    isFolder = file.isFolder,
                    parentPath = path
                )
            }
            .let { Result.success(it) }
    }

    override fun resolveUri(track: AudioTrack): String {
        applyCredentials()
        return client.getFileUrl(track.path)
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        applyCredentials()
        client.verifyConnection()
    }

    override suspend fun readSidecar(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        // 不走 applyCredentials：sidecar 取料与列表页并发，凭证随调用显式传入，
        // 不读写单例可变字段（终审 I2）
        client.getBytes(path, serverUrl, username, password)
    }

    private fun applyCredentials() {
        client.serverUrl = serverUrl
        client.username = username
        client.password = password
    }
}
