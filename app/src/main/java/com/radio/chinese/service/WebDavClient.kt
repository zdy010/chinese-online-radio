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
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
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
        set(value) { if (field != value) { field = value; clearCache() } }
    var username: String = ""
        set(value) { if (field != value) { field = value; clearCache() } }
    var password: String = ""
        set(value) { if (field != value) { field = value; clearCache() } }

    /** 目录列表缓存 */
    private val listFilesCache = java.util.concurrent.ConcurrentHashMap<String, List<WebDavFile>>()

    /** 清除缓存（凭证变化时调用） */
    fun clearCache() { listFilesCache.clear() }

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
        // 先查缓存
        listFilesCache[path]?.let { return@withContext it }

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
        val files = parsePropfindResponse(body, url)
        // 存入缓存
        listFilesCache[path] = files
        files
    }

    /** 获取文件的下载/流媒体URL（含 Basic Auth 凭证，ExoPlayer 自动提取） */
    fun getFileUrl(path: String): String {
        val url = normalizeUrl(path)
        return if (username.isNotBlank()) {
            try {
                val uri = java.net.URI(url)
                java.net.URI(uri.scheme, "${username}:${password}", uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
            } catch (_: Exception) { url }
        } else url
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
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType

        var currentHref: String? = null
        var currentName: String? = null
        var isFolder = false
        var contentLength = 0L

        val baseRef = try {
            java.net.URI(basePath).path.trimEnd('/')
        } catch (_: Exception) {
            basePath.trimEnd('/')
        }

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name?.lowercase()
                    val ns = parser.namespace?.lowercase().orEmpty()
                    val isDav = ns == "dav:" || ns.isBlank()
                    when {
                        name == "response" && isDav -> {
                            currentHref = null; currentName = null; isFolder = false; contentLength = 0L
                        }
                        name == "href" && isDav && currentHref == null -> {
                            currentHref = parser.nextText()
                        }
                        name == "displayname" && isDav -> {
                            currentName = parser.nextText()
                        }
                        name == "collection" && isDav -> {
                            isFolder = true
                        }
                        name == "getcontentlength" && isDav -> {
                            contentLength = parser.nextText().trim().toLongOrNull() ?: 0L
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name?.lowercase() == "response") {
                        val href = currentHref
                        if (href != null) {
                            val hrefPath = try {
                                java.net.URI(href).path.trimEnd('/')
                            } catch (_: Exception) {
                                href.trimEnd('/')
                            }
                            if (hrefPath != baseRef) {
                                val name = currentName?.takeIf { it.isNotBlank() }
                                    ?: try {
                                        URLDecoder.decode(href, "UTF-8")
                                    } catch (_: Exception) { href }
                                        .trimEnd('/').substringAfterLast('/')
                                files.add(WebDavFile(href, name, isFolder, contentLength))
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        Log.d("WebDavClient", "XmlPullParser: 解析出 ${files.size} 个条目")
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
