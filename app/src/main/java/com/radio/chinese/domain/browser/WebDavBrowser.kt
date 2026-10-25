package com.radio.chinese.domain.browser

import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioTrack
import com.radio.chinese.domain.PlayableTrack
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
    val client: WebDavTransport,
    val serverUrl: String,
    val username: String,
    val password: String
) : AudioBrowser {

    override suspend fun listChildren(path: String): Result<List<AudioTrack>> = withContext(Dispatchers.IO) {
        applyCredentials()
        client.listFiles(path)
            // 目录里的歌词与封面不是曲目：以前只判「不是文件夹」，它们会作为点不响的
            // 一行混进列表。剔掉不影响取歌词——sidecar 是按曲目名派生路径去请求的，
            // 本来就不需要这些文件出现在列表里。这里一道闸同时管住目录浏览与索引写入。
            .filter { it.isFolder || PlayableTrack.isPlayable(it.displayName) }
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
