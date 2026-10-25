package com.radio.chinese.domain.browser

/**
 * WebDAV 文件条目（原在 service/WebDavClient.kt 定义，下移到 domain 消除违例）。
 */
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

/**
 * WebDAV 传输抽象：domain 只认接口，service/WebDavClient 实现它。
 * 消除 domain/browser → service/WebDavClient 的 3 处违例。
 */
interface WebDavTransport {
    var serverUrl: String
    var username: String
    var password: String

    fun authHeader(): String
    fun clearCache()
    suspend fun listFiles(path: String): List<WebDavFile>
    fun getFileUrl(path: String): String
    suspend fun verifyConnection(): Result<Unit>
    suspend fun getBytes(path: String, serverUrl: String, username: String, password: String): Result<ByteArray>
}
