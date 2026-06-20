package com.radio.chinese.service

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
    private val yunPanApi: YunPanApi,
    private val operaDatabase: OperaDatabase
) {
    private val dao = operaDatabase.downloadedOperaDao()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 当前使用的分享提取码，由 ViewModel 设置 */
    var sharePassword: String = ""

    // ========== 云盘浏览 ==========

    /** 获取一级分类列表（戏曲种类） */
    suspend fun getCategories(): List<OperaCategory> = withContext(Dispatchers.IO) {
        val resp = yunPanApi.listFiles(
            shareKey = YunPanConfig.SHARE_KEY,
            sharePassword = sharePassword,
            parentId = YunPanConfig.ROOT_PARENT_ID
        )
        if (resp.code != 0) throw Exception(resp.message ?: "API错误:${resp.code}")
        (resp.data?.list ?: emptyList())
            .filter { it.isFolder }
            .map { OperaCategory(name = it.fileName, folderId = it.fileId) }
    }

    /** 获取某个分类下的剧目列表 */
    suspend fun getOperas(categoryFolderId: Long): List<OperaItem> = withContext(Dispatchers.IO) {
        val resp = yunPanApi.listFiles(
            shareKey = YunPanConfig.SHARE_KEY,
            sharePassword = sharePassword,
            parentId = categoryFolderId
        )
        if (resp.code != 0) throw Exception(resp.message ?: "API错误:${resp.code}")
        (resp.data?.list ?: emptyList())
            .filter { it.isFolder }
            .map { OperaItem(name = it.fileName, folderId = it.fileId) }
    }

    /** 获取某个剧目下的音频文件列表 */
    suspend fun getAudioFiles(
        categoryName: String,
        operaName: String,
        operaFolderId: Long
    ): List<OperaAudioFile> = withContext(Dispatchers.IO) {
        val resp = yunPanApi.listFiles(
            shareKey = YunPanConfig.SHARE_KEY,
            sharePassword = sharePassword,
            parentId = operaFolderId
        )
        if (resp.code != 0) throw Exception(resp.message ?: "API错误:${resp.code}")
        (resp.data?.list ?: emptyList())
            .filter { !it.isFolder }
            .map {
                OperaAudioFile(
                    fileId = it.fileId,
                    name = it.fileName,
                    size = it.fileSize,
                    categoryName = categoryName,
                    operaName = operaName,
                    etag = it.etag,
                    s3KeyFlag = it.s3KeyFlag
                )
            }
    }

    /** 获取文件的下载直链 */
    suspend fun getDownloadUrl(
        fileId: Long,
        etag: String? = null,
        s3KeyFlag: String? = null,
        size: Long = 0
    ): String? = withContext(Dispatchers.IO) {
        val body = mutableMapOf<String, Any>(
            "shareKey" to YunPanConfig.SHARE_KEY,
            "SharePwd" to sharePassword,
            "fileId" to fileId
        )
        if (etag != null) body["etag"] = etag
        if (s3KeyFlag != null) body["s3keyFlag"] = s3KeyFlag
        if (size > 0) body["size"] = size

        val resp = yunPanApi.getDownloadInfo(body)
        if (resp.code != 0) throw Exception(resp.message ?: "下载API错误:${resp.code}")
        resp.data?.downloadUrl
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
            val downloadUrl = getDownloadUrl(
                audioFile.fileId,
                etag = audioFile.etag,
                s3KeyFlag = audioFile.s3KeyFlag,
                size = audioFile.size
            )
                ?: return@withContext Result.failure(Exception("获取下载地址失败"))

            val safeName = audioFile.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val targetFile = File(saveDir, safeName)

            val request = Request.Builder().url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
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
