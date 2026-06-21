package com.radio.chinese.service

import android.util.Log
import com.radio.chinese.domain.model.OperaAudioFile
import com.radio.chinese.domain.model.OperaCategory
import com.radio.chinese.domain.model.OperaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class WebDavFile(
    val href: String,
    val displayName: String,
    val isFolder: Boolean,
    val contentLength: Long = 0,
    val contentType: String = ""
) {
    val fileName: String get() = displayName
    val fileId: String get() = href
    val size: Long get() = contentLength
}

@Singleton
class WebDavClient @Inject constructor() {

    companion object {
        private const val TAG = "WebDavClient"
    }

    /** WebDAV 连接凭证 */
    var serverUrl: String = ""
    var username: String = ""
    var password: String = ""

    private val client by lazy {
        // 创建信任所有证书的TrustManager（用于开发测试，生产环境应该使用正式的证书验证）
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        val sslSocketFactory = sslContext.socketFactory

        OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true } // 信任所有主机名
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                Log.d(TAG, "--> ${req.method} ${req.url}")
                val resp = chain.proceed(req)
                Log.d(TAG, "<-- ${resp.code} ${resp.message}")
                resp
            }
            .build()
    }

    private val xmlMediaType = "application/xml; charset=utf-8".toMediaType()

    private val propfindBody = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:propfind xmlns:D="DAV:">
            <D:prop>
                <D:displayname/>
                <D:resourcetype/>
                <D:getcontentlength/>
                <D:getcontenttype/>
            </D:prop>
        </D:propfind>
    """.trimIndent().toRequestBody(xmlMediaType)

    fun authHeader(): String = Credentials.basic(username, password)

    /** 验证连接是否有效 */
    suspend fun verifyConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(serverUrl)
                .method("PROPFIND", propfindBody)
                .header("Authorization", authHeader())
                .header("Depth", "0")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()
            Log.d(TAG, "verifyConnection: ${serverUrl}")
            val response = client.newCall(request).execute()
            Log.d(TAG, "verifyConnection result: ${response.code}")
            Log.d(TAG, "verifyConnection body: ${response.body?.string()?.take(500)}")
            if (response.isSuccessful) {
                Log.d(TAG, "verifyConnection OK")
                Result.success(Unit)
            } else {
                val err = "连接失败: HTTP ${response.code}"
                Log.e(TAG, err)
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyConnection exception: ${e.message}")
            Result.failure(e)
        }
    }

    /** 列出目录下的文件和文件夹 */
    suspend fun listFiles(path: String = "/"): List<WebDavFile> = withContext(Dispatchers.IO) {
        val url = normalizeUrl(path)
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", propfindBody)
            .header("Authorization", authHeader())
            .header("Depth", "1")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("列出目录失败: HTTP ${response.code}")
        }
        val body = response.body?.string() ?: throw Exception("响应为空")
        Log.d(TAG, "listFiles body length: ${body.length}, first 500: ${body.take(500)}")
        parsePropfindResponse(body, url)
    }

    /** 搜索文件（递归列出所有文件后过滤） */
    suspend fun searchFiles(query: String): List<WebDavFile> = withContext(Dispatchers.IO) {
        val url = normalizeUrl("/")
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", propfindBody)
            .header("Authorization", authHeader())
            .header("Depth", "infinity")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("搜索失败: HTTP ${response.code}")
        }
        val body = response.body?.string() ?: throw Exception("响应为空")
        val allFiles = parsePropfindResponse(body, url)
        // 只返回非文件夹且文件名匹配的
        allFiles.filter { f ->
            !f.isFolder && f.displayName.contains(query, ignoreCase = true)
        }
    }

    /** 获取文件的下载/流媒体URL */
    fun getFileUrl(path: String): String {
        val url = normalizeUrl(path)
        Log.d(TAG, "getFileUrl: path=$path, url=$url")
        return url
    }

    // ========== 工具方法 ==========

    private fun normalizeUrl(path: String): String {
        val base = serverUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        // 使用URI来正确处理URL编码
        return try {
            val uri = java.net.URI(base)
            val resolved = uri.resolve(p)
            resolved.toString()
        } catch (_: Exception) {
            // 如果URI解析失败，使用简单的字符串拼接
            "$base$p"
        }
    }

    private fun parsePropfindResponse(xml: String, basePath: String): List<WebDavFile> {
        val files = mutableListOf<WebDavFile>()
        // 预编译正则，避免每一条都重新编译
        val hrefRegex = Regex("<D:href>([^<]+)</D:href>")
        val nameRegex = Regex("<D:displayname>([^<]*)</D:displayname>")
        val sizeRegex = Regex("<D:getcontentlength>([^<]*)</D:getcontentlength>")
        
        // 统计找到的 response 块数
        var responseCount = 0
        
        try {
            // 用字符串搜索提取每个 <D:response> 块
            var searchStart = 0
            while (true) {
                val respStart = xml.indexOf("<D:response>", searchStart)
                if (respStart < 0) break
                val respEnd = xml.indexOf("</D:response>", respStart)
                if (respEnd < 0) break
                val block = xml.substring(respStart, respEnd + 14)
                searchStart = respEnd + 14
                
                responseCount++

                // 用 indexOf 提取 href（比正则快）
                val hrefTagStart = "<D:href>"
                val hrefTagEnd = "</D:href>"
                val hrefBegin = block.indexOf(hrefTagStart)
                val hrefClose = block.indexOf(hrefTagEnd, hrefBegin)
                if (hrefBegin < 0 || hrefClose < 0) continue
                val href = block.substring(hrefBegin + hrefTagStart.length, hrefClose)

                // 提取 displayname
                val dnTagStart = "<D:displayname>"
                val dnTagEnd = "</D:displayname>"
                val dnBegin = block.indexOf(dnTagStart)
                val name = if (dnBegin >= 0) {
                    val dnClose = block.indexOf(dnTagEnd, dnBegin)
                    if (dnClose >= 0) block.substring(dnBegin + dnTagStart.length, dnClose) else ""
                } else {
                    href.trimEnd('/').substringAfterLast('/')
                }

                // 检查是否是目录
                val isFolder = block.contains("<D:collection")

                // 提取大小
                val size = if (isFolder) 0L else {
                    val szTagStart = "<D:getcontentlength>"
                    val szTagEnd = "</D:getcontentlength>"
                    val szBegin = block.indexOf(szTagStart)
                    if (szBegin >= 0) {
                        val szClose = block.indexOf(szTagEnd, szBegin)
                        if (szClose >= 0) block.substring(szBegin + szTagStart.length, szClose).toLongOrNull() ?: 0L else 0L
                    } else 0L
                }

                // 忽略根目录自身（basePath是完整URL，提取path部分比较）
                val baseHref = try { java.net.URI(basePath).path } catch (_: Exception) { basePath }
                val decodedHref = try {
                    URLDecoder.decode(href, "UTF-8")
                } catch (_: Exception) {
                    href
                }
                Log.d(TAG, "Parsing entry: href=$href, decodedHref=$decodedHref, baseHref=$baseHref, isFolder=$isFolder")
                if (decodedHref.trimEnd('/') != baseHref.trimEnd('/')) {
                    Log.d(TAG, "Adding entry: $decodedHref")
                    files.add(WebDavFile(
                        href = decodedHref,
                        displayName = name,
                        isFolder = isFolder,
                        contentLength = size
                    ))
                } else {
                    Log.d(TAG, "Filtering self-entry: $decodedHref")
                }
            }
        } catch (e: Exception) {
            throw Exception("解析WebDAV响应失败: ${e.message}")
        }
        Log.d("WebDavClient", "解析完成: 找到 ${responseCount} 个 response 块, 解析出 ${files.size} 个条目")
        return files
    }

    /** 将 WebDavFile 列表按目录结构转换为 OperaCategory / OperaItem / OperaAudioFile */
    fun toOperaCategories(files: List<WebDavFile>): List<OperaCategory> {
        return files.filter { it.isFolder }
            .map { OperaCategory(name = it.displayName, folderId = it.href.hashCode().toLong(), itemCount = 0) }
    }

    fun toOperaItems(files: List<WebDavFile>): List<OperaItem> {
        return files.filter { it.isFolder }
            .map { OperaItem(name = it.displayName, folderId = it.href.hashCode().toLong()) }
    }

    fun toAudioFiles(files: List<WebDavFile>, categoryName: String, operaName: String): List<OperaAudioFile> {
        return files.filter { !it.isFolder }
            .map {
                OperaAudioFile(
                    fileId = it.href.hashCode().toLong(),
                    name = it.displayName,
                    size = it.size,
                    categoryName = categoryName,
                    operaName = operaName,
                    downloadUrl = getFileUrl(it.href)
                )
            }
    }
}
