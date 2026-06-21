package com.radio.chinese.service

import android.util.Log
import com.radio.chinese.domain.model.OperaAudioFile
import com.radio.chinese.domain.model.OperaCategory
import com.radio.chinese.domain.model.OperaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
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

    /** 当前搜索请求的 Call，用于取消 */
    private var currentSearchCall: Call? = null

    /** 取消正在进行的搜索 */
    fun cancelSearch() { currentSearchCall?.cancel() }

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
        val responseBlockCount = body.split("<D:response>").size - 1
        Log.d(TAG, "listFiles: url=$url, bodyLength=${body.length}, responseBlocks=$responseBlockCount")
        // 如果responseBlockCount和预期不符，打印前1000字符帮助排查
        if (responseBlockCount < 6) {
            Log.w(TAG, "listFiles: 解析到的response块数较少($responseBlockCount)，原始响应前2000字符: ${body.take(2000)}")
        }
        parsePropfindResponse(body, url)
    }

    /** 搜索文件（列出所有文件后过滤），支持取消 */
    suspend fun searchFiles(query: String): List<WebDavFile> = withContext(Dispatchers.IO) {
        // 取消上一次搜索
        currentSearchCall?.cancel()
        val url = normalizeUrl("/")
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", propfindBody)
            .header("Authorization", authHeader())
            .header("Depth", "infinity")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .build()
        val call = client.newCall(request)
        currentSearchCall = call

        // 在 IO 调度器上执行阻塞调用，支持协程取消时中断
        val response = try {
            call.execute()
        } catch (e: Exception) {
            // 如果是取消导致的异常，转换为 CancellationException
            if (call.isCanceled()) throw kotlinx.coroutines.CancellationException("搜索已取消")
            throw e
        }
        if (!response.isSuccessful) {
            throw Exception("搜索失败: HTTP ${response.code}")
        }
        val body = response.body?.string() ?: throw Exception("响应为空")
        val allFiles = parsePropfindResponse(body, url)
        allFiles.filter { !it.isFolder && it.displayName.contains(query, ignoreCase = true) }
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
        var responseCount = 0

        try {
            // 用正则找所有 <D:response ...> 块（支持带属性的标签，如 <D:response xmlns:D="DAV:">）
            val startTagPattern = "<D:response[^>]*>".toRegex()
            val endTag = "</D:response>"

            var searchStart = 0
            while (true) {
                val match = startTagPattern.find(xml, searchStart) ?: break
                val tagEnd = xml.indexOf(">", match.range.start)
                if (tagEnd < 0) break

                val respEnd = xml.indexOf(endTag, tagEnd)
                if (respEnd < 0) break

                // block = 从 <D:response> 结束到 </D:response> 开始之间的内容
                val block = xml.substring(tagEnd + 1, respEnd)
                searchStart = respEnd + endTag.length
                responseCount++

                // 提取 href
                val hrefBegin = block.indexOf("<D:href>")
                if (hrefBegin < 0) continue
                val hrefClose = block.indexOf("</D:href>", hrefBegin)
                if (hrefClose < 0) continue
                val href = block.substring(hrefBegin + 8, hrefClose)

                // 提取 displayname
                val name = extractTagContent(block, "D:displayname")
                    ?: try { URLDecoder.decode(href, "UTF-8") }
                    catch (_: Exception) { href }
                        .trimEnd('/').substringAfterLast('/')

                // 检查是否是目录
                val isFolder = block.contains("<D:collection")

                // 提取文件大小
                val size = if (isFolder) 0L else {
                    extractTagContent(block, "D:getcontentlength")
                        ?.toLongOrNull() ?: 0L
                }

                // 忽略根目录自身
                val baseRef = try { java.net.URI(basePath).path.trimEnd('/') }
                catch (_: Exception) { basePath.trimEnd('/') }
                val hrefPath = try { java.net.URI(href).path.trimEnd('/') }
                catch (_: Exception) { href.trimEnd('/') }

                if (hrefPath != baseRef) {
                    files.add(WebDavFile(href = href, displayName = name, isFolder = isFolder, contentLength = size))
                }
            }
        } catch (e: Exception) {
            throw Exception("解析WebDAV响应失败: ${e.message}")
        }
        Log.d("WebDavClient", "解析完成: 找到 $responseCount 个response块, 解析出 ${files.size} 个条目")
        return files
    }

    /** 从block中提取标签内容，如 <D:displayname>xxx</D:displayname> */
    private fun extractTagContent(block: String, tag: String): String? {
        val startTag = "<$tag>"
        val endTag = "</$tag>"
        val start = block.indexOf(startTag)
        if (start < 0) return null
        val end = block.indexOf(endTag, start)
        if (end < 0) return null
        val content = block.substring(start + startTag.length, end)
        return content.ifEmpty { null }
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
