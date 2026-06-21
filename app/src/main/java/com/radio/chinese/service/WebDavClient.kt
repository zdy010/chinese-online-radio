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

    /** 验证连接是否有效 - 返回详细错误信息 */
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
            val bodyString = response.body?.string() ?: ""
            Log.d(TAG, "verifyConnection response code: ${response.code}, body length: ${bodyString.length}")
            Log.d(TAG, "verifyConnection body preview: ${bodyString.take(500)}")
            if (response.isSuccessful) {
                Log.d(TAG, "verifyConnection OK")
                Result.success(Unit)
            } else {
                // 从响应body中提取有用信息（如WebDAV multi-status中的status）
                val detail = try {
                    // 尝试提取 HTTP 状态码（WebDAV响应中可能包含多个status）
                    val statusMatch = "HTTP/[0-9.]+ ([0-9]+)".toRegex().find(bodyString)
                    val statusCode = statusMatch?.groupValues?.get(1)
                    if (statusCode != null && statusCode != "${response.code}") {
                        "WebDAV status: $statusCode | body: ${bodyString.take(200)}"
                    } else {
                        bodyString.take(300)
                    }
                } catch (_: Exception) { bodyString.take(300) }
                val err = "连接失败: HTTP ${response.code} | URL: ${serverUrl} | detail: ${detail}"
                Log.e(TAG, err)
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            val err = "连接异常: ${e.javaClass.simpleName}: ${e.message} | URL: ${serverUrl}"
            Log.e(TAG, err)
            Result.failure(Exception(err))
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
            val bodyString = try { response.body?.string()?.take(300) } catch (_: Exception) { "" }
            throw Exception("列出目录失败: HTTP ${response.code} | URL: ${url} | detail: ${bodyString}")
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
        // 如果path是根路径"/"，直接返回base URL（避免URI.resolve替换掉已有的path部分）
        if (path == "/" || path.isEmpty()) return base
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
                
                // 重要：保持href的原始编码形式，不要解码！
                // 解码只在显示名称时使用，路径用于API请求时必须保持编码

                // 提取 displayname
                val dnTagStart = "<D:displayname>"
                val dnTagEnd = "</D:displayname>"
                val dnBegin = block.indexOf(dnTagStart)
                val name = if (dnBegin >= 0) {
                    val dnClose = block.indexOf(dnTagEnd, dnBegin)
                    if (dnClose >= 0) block.substring(dnBegin + dnTagStart.length, dnClose) else ""
                } else {
                    // 从href中提取名称（需要解码）
                    val decoded = try { URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href }
                    decoded.trimEnd('/').substringAfterLast('/')
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

                // 忽略根目录自身（比较href的path部分和basePath的path部分）
                val baseHref = try { 
                    val uri = java.net.URI(basePath)
                    uri.path.trimEnd('/') 
                } catch (_: Exception) { 
                    basePath.trimEnd('/') 
                }
                
                // 获取当前条目的path部分
                val hrefPath = try { 
                    val uri = java.net.URI(href)
                    uri.path.trimEnd('/') 
                } catch (_: Exception) { 
                    href.trimEnd('/') 
                }
                
                Log.d(TAG, "Parsing entry: href=$href, hrefPath=$hrefPath, baseHref=$baseHref, isFolder=$isFolder")
                if (hrefPath != baseHref) {
                    Log.d(TAG, "Adding entry: $href (name=$name)")
                    files.add(WebDavFile(
                        href = href,  // 保持原始编码
                        displayName = name,
                        isFolder = isFolder,
                        contentLength = size
                    ))
                } else {
                    Log.d(TAG, "Filtering self-entry: $href")
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
            .map { OperaCategory(name = it.displayName, folderId = it.href.hashCode().toLong(), path = it.href, itemCount = 0) }
    }

    fun toOperaItems(files: List<WebDavFile>): List<OperaItem> {
        return files.filter { it.isFolder }
            .map { OperaItem(name = it.displayName, folderId = it.href.hashCode().toLong(), path = it.href) }
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
