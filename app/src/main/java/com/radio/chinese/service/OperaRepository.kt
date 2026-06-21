package com.radio.chinese.service

import android.util.Log
import com.radio.chinese.data.local.DownloadedOperaDao
import com.radio.chinese.data.local.OperaDatabase
import com.radio.chinese.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OperaRepository @Inject constructor(
    private val webDavClient: WebDavClient,
    private val operaDatabase: OperaDatabase
) {
    private val dao = operaDatabase.downloadedOperaDao()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 设置 WebDAV 连接凭证 */
    fun setCredentials(serverUrl: String, username: String, password: String) {
        webDavClient.serverUrl = serverUrl
        webDavClient.username = username
        webDavClient.password = password
    }

    /** 验证 WebDAV 连接 */
    suspend fun verifyConnection(): Result<Unit> {
        return webDavClient.verifyConnection()
    }

    /** 是否已配置凭证 */
    fun hasCredentials(): Boolean {
        return webDavClient.serverUrl.isNotBlank() && webDavClient.username.isNotBlank()
    }

    // ========== 目录浏览 ==========

    /** 列出戏曲分类（第一级） */
    suspend fun getCategories(): List<OperaCategory> = withContext(Dispatchers.IO) {
        Log.d("OperaRepo", "getCategories called")
        val files = webDavClient.listFiles("/")
        Log.d("OperaRepo", "listFiles returned ${files.size} entries")
        files.forEach { Log.d("OperaRepo", "  -> ${it.displayName} folder=${it.isFolder} href=${it.href}") }
        // 过滤掉根目录自身（href与服务器路径一致的是根目录）
        val serverPath = try { java.net.URI(webDavClient.serverUrl).path.trimEnd('/') } catch (_: Exception) { "" }
        Log.d("OperaRepo", "serverPath=$serverPath")
        val categories = files.filter { it.isFolder && it.href.trimEnd('/') != serverPath }
            .map { OperaCategory(name = it.displayName, folderId = it.href.hashCode().toLong(), path = it.href) }
        Log.d("OperaRepo", "filtered ${categories.size} categories")
        categories
    }

    /** 获取某个分类下的剧目 */
    suspend fun getOperas(category: OperaCategory): List<OperaItem> = withContext(Dispatchers.IO) {
        val files = webDavClient.listFiles(category.path)
        files.filter { it.isFolder }
            .map { OperaItem(name = it.displayName, folderId = it.href.hashCode().toLong(), path = it.href) }
    }

    /** 获取某个剧目下的音频文件 */
    suspend fun getAudioFiles(
        categoryName: String,
        opera: OperaItem,
    ): List<OperaAudioFile> = withContext(Dispatchers.IO) {
        val files = webDavClient.listFiles(opera.path)
        files.filter { !it.isFolder }
            .map {
                OperaAudioFile(
                    fileId = it.href.hashCode().toLong(),
                    name = it.displayName,
                    size = it.size,
                    categoryName = categoryName,
                    operaName = opera.name,
                    downloadUrl = webDavClient.getFileUrl(it.href)
                )
            }
    }

    /** 搜索文件 */
    suspend fun searchFiles(query: String): List<OperaAudioFile> = withContext(Dispatchers.IO) {
        val results = webDavClient.searchFiles(query)
        // 服务器 base path，用于从 href 中去掉前缀
        val serverUrl = webDavClient.serverUrl
        val basePath = try { java.net.URI(serverUrl).path.trimEnd('/') } catch (_: Exception) { "" }
        results.map {
            // 从 href 中解析分类和剧目名
            // href 可能是完整URL或路径，如 https://example.com/webdav/豫剧/三夹弦/xxx.mp3
            val hrefPath = try {
                val uri = java.net.URI(it.href)
                if (uri.scheme != null) uri.path else it.href
            } catch (_: Exception) { it.href }
            // 去掉 base path 前缀
            val relativePath = hrefPath.trimEnd('/').removePrefix(basePath).trimStart('/')
            val pathParts = relativePath.split("/").filter { it.isNotBlank() }
            // pathParts = [分类, 剧目, 文件名] 或 [分类, 文件名]
            val categoryName = if (pathParts.size >= 2) pathParts[pathParts.size - 3] else ""
            val operaName = if (pathParts.size >= 2) pathParts[pathParts.size - 2] else ""
            OperaAudioFile(
                fileId = it.href.hashCode().toLong(),
                name = it.displayName,
                size = it.size,
                categoryName = categoryName,
                operaName = operaName,
                downloadUrl = webDavClient.getFileUrl(it.href)
            )
        }
    }

    // ========== 本地下载 ==========

    val allDownloaded: Flow<List<DownloadedOpera>> = dao.getAllDownloaded()

    suspend fun isDownloaded(fileId: Long): Boolean {
        return dao.getByFileId(fileId) != null
    }

    suspend fun getAllDownloadedIds(): Set<Long> = withContext(Dispatchers.IO) {
        dao.getAllDownloadedIds().toSet()
    }

    suspend fun downloadFile(
        audioFile: OperaAudioFile,
        saveDir: File,
        onProgress: (Int) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val downloadUrl = audioFile.downloadUrl
                ?: return@withContext Result.failure(Exception("获取下载地址失败"))

            val safeName = audioFile.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val targetFile = File(saveDir, safeName)

            val request = Request.Builder().url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .header("Authorization", webDavClient.authHeader())
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }

                val body = response.body ?: return@withContext Result.failure(Exception("响应为空"))
                val totalBytes = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(targetFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                var lastProgress = -1

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalBytes > 0) {
                        val pct = ((totalRead * 100) / totalBytes).toInt()
                        if (pct != lastProgress) {
                            lastProgress = pct
                            onProgress(pct)
                        }
                    }
                }
                outputStream.flush()
                outputStream.close()
                inputStream.close()
            }

            dao.insert(
                DownloadedOpera(
                    fileId = audioFile.fileId,
                    categoryName = audioFile.categoryName,
                    operaName = audioFile.operaName,
                    fileName = audioFile.name,
                    localPath = targetFile.absolutePath,
                    fileSize = audioFile.size,
                    downloadTime = System.currentTimeMillis()
                )
            )

            Result.success(targetFile.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteDownloaded(fileId: Long) {
        dao.delete(fileId)
    }
}
