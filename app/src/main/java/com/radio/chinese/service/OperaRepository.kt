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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OperaRepository @Inject constructor(
    private val webDavClient: WebDavClient,
    private val operaDatabase: OperaDatabase,
    httpClient: OkHttpClient
) {
    private val dao = operaDatabase.downloadedOperaDao()
    private val client = httpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // 本地文件缓存：key = 分类名，value = 该分类下所有文件（含剧目子目录）
    private val fileCache = java.util.concurrent.ConcurrentHashMap<String, List<OperaAudioFile>>()
    private var cacheInitialized = false

    /** 设置 WebDAV 连接凭证 */
    fun setCredentials(serverUrl: String, username: String, password: String) {
        webDavClient.serverUrl = serverUrl
        webDavClient.username = username
        webDavClient.password = password
        // 凭证变化时清缓存
        fileCache.clear()
        cacheInitialized = false
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
        val serverPath = try { java.net.URI(webDavClient.serverUrl).path.trimEnd('/') } catch (_: Exception) { "" }
        Log.d("OperaRepo", "serverPath=$serverPath")
        val categories = files.filter { it.isFolder && it.href.trimEnd('/') != serverPath }
            .map { OperaCategory(name = it.displayName, folderId = it.href.hashCode().toLong(), path = it.href) }
        Log.d("OperaRepo", "filtered ${categories.size} categories")
        categories
    }

    /** 递归加载某个分类下所有音频文件（支持混合结构：既有子目录也有直接文件） */
    private suspend fun loadAllFilesInCategory(category: OperaCategory): List<OperaAudioFile> {
        val result = mutableListOf<OperaAudioFile>()
        val entries = webDavClient.listFiles(category.path)
        for (item in entries) {
            if (item.isFolder) {
                // 剧目目录，递归加载其中的音频文件
                val files = webDavClient.listFiles(item.href)
                for (f in files) {
                    if (!f.isFolder && isAudioFile(f.displayName)) {
                        result.add(OperaAudioFile(
                            fileId = f.href.hashCode().toLong(),
                            name = f.displayName,
                            size = f.size,
                            categoryName = category.name,
                            operaName = item.displayName,
                            downloadUrl = webDavClient.getFileUrl(f.href)
                        ))
                    }
                }
            } else {
                // 文件直接在分类目录下（如"待确定"这类没有子目录的分类）
                if (isAudioFile(item.displayName)) {
                    result.add(OperaAudioFile(
                        fileId = item.href.hashCode().toLong(),
                        name = item.displayName,
                        size = item.size,
                        categoryName = category.name,
                        operaName = "",
                        downloadUrl = webDavClient.getFileUrl(item.href)
                    ))
                }
            }
        }
        return result
    }

    /** 判断是否为音频文件 */
    private fun isAudioFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") ||
                lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".wma")
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
        // 先查缓存
        val cached = fileCache[categoryName]
        if (cached != null) {
            return@withContext cached.filter { it.operaName == opera.name }
        }
        // 缓存未命中，实时加载
        val files = webDavClient.listFiles(opera.path)
        files.filter { !it.isFolder && isAudioFile(it.displayName) }
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

    /** 搜索文件（从本地缓存搜索，极快） */
    suspend fun searchFiles(query: String): List<OperaAudioFile> = searchLocalCache(query)

    /** 搜索文件（从本地缓存搜索，极快） */
    suspend fun searchLocalCache(query: String): List<OperaAudioFile> = withContext(Dispatchers.IO) {
        val q = query.lowercase().trim()
        if (q.length < 2) return@withContext emptyList()

        // 如果缓存未初始化，先实时搜索
        if (!cacheInitialized) {
            Log.d("OperaRepo", "Cache not initialized, doing realtime search")
            return@withContext searchFilesRealtime(q)
        }

        // 从缓存搜索
        val results = mutableListOf<OperaAudioFile>()
        for ((_, files) in fileCache) {
            for (file in files) {
                if (file.name.lowercase().contains(q) ||
                    file.categoryName.lowercase().contains(q) ||
                    file.operaName.lowercase().contains(q)) {
                    results.add(file)
                }
            }
        }
        Log.d("OperaRepo", "Local cache search for '$q' returned ${results.size} results")
        results
    }

    /** 实时搜索（遍历分类目录分层加载，避免 Depth:infinity） */
    private suspend fun searchFilesRealtime(query: String): List<OperaAudioFile> {
        val results = mutableListOf<OperaAudioFile>()
        try {
            val categories = getCategories()
            Log.d("OperaRepo", "Realtime search '$query': found ${categories.size} categories")
            for (cat in categories) {
                try {
                    val files = loadAllFilesInCategory(cat)
                    files.forEach { f ->
                        if (f.name.lowercase().contains(query) ||
                            f.operaName.lowercase().contains(query)) {
                            results.add(f)
                        }
                    }
                    // 顺便填缓存
                    if (!fileCache.containsKey(cat.name)) {
                        fileCache[cat.name] = files
                    }
                } catch (e: Exception) {
                    Log.w("OperaRepo", "Realtime search skip ${cat.name}: ${e.message}")
                }
            }
            cacheInitialized = true
        } catch (e: Exception) {
            Log.e("OperaRepo", "Realtime search failed: ${e.message}")
        }
        Log.d("OperaRepo", "Realtime search '$query' returned ${results.size} results")
        return results
    }

    /** 刷新缓存（后台调用） */
    suspend fun refreshCache(categories: List<OperaCategory>) = withContext(Dispatchers.IO) {
        for (cat in categories) {
            try {
                val files = loadAllFilesInCategory(cat)
                fileCache[cat.name] = files
                Log.d("OperaRepo", "Refreshed cache for ${cat.name}: ${files.size} files")
            } catch (e: Exception) {
                Log.w("OperaRepo", "Failed to refresh cache for ${cat.name}: ${e.message}")
            }
        }
        cacheInitialized = true
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

    // ========== 收藏文件查找 ==========

    /** 根据收藏的文件ID集合，从缓存中查找完整的 OperaAudioFile 对象 */
    suspend fun getFavoriteFiles(favoriteFileIds: Set<Long>): List<OperaAudioFile> = withContext(Dispatchers.IO) {
        if (favoriteFileIds.isEmpty()) return@withContext emptyList()
        val result = mutableListOf<OperaAudioFile>()
        for ((_, files) in fileCache) {
            for (file in files) {
                if (favoriteFileIds.contains(file.fileId)) {
                    result.add(file)
                }
            }
        }
        // 按收藏顺序排序（如果文件ID在favoriteFileIds中的顺序有意义的话）
        // 这里简单按文件名排序
        result.distinctBy { it.fileId }.sortedBy { it.name }
    }

    /** 根据收藏的分类名集合，从分类列表中过滤 */
    fun getFavoriteCategories(allCategories: List<OperaCategory>, favoriteCategoryNames: Set<String>): List<OperaCategory> {
        if (favoriteCategoryNames.isEmpty()) return emptyList()
        return allCategories.filter { favoriteCategoryNames.contains(it.name) }
    }
}
