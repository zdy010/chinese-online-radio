package com.radio.chinese.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class StreamStatus {
    UNKNOWN,
    VALID,
    INVALID,
    CHECKING
}

data class StreamCheckResult(
    val stationId: String,
    val status: StreamStatus,
    val message: String = "",
    val responseTimeMs: Long = 0
)

@Singleton
class StreamChecker @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun checkStream(url: String, stationId: String): StreamCheckResult =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) {
                return@withContext StreamCheckResult(stationId, StreamStatus.INVALID, "地址为空")
            }
            val startTime = System.currentTimeMillis()
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .head()
                    .build()

                client.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - startTime
                    if (response.isSuccessful || response.code == 301 || response.code == 302) {
                        StreamCheckResult(stationId, StreamStatus.VALID, "正常", elapsed)
                    } else {
                        StreamCheckResult(stationId, StreamStatus.INVALID, "HTTP ${response.code}", elapsed)
                    }
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                StreamCheckResult(stationId, StreamStatus.INVALID, e.message?.take(60) ?: "连接失败", elapsed)
            }
        }
}
