package com.radio.chinese.data.remote

import com.radio.chinese.domain.epg.EpgQuery
import com.radio.chinese.domain.model.EpgProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 拉取结果。失败与「成功但当天没有节目」必须分开：前者要保住旧缓存，
 * 后者要清空缓存并记下时间戳，否则没节目单的台会每次进页面都重拉。
 */
sealed interface EpgFetchResult {
    data class Success(val programs: List<EpgProgram>) : EpgFetchResult
    data object Failure : EpgFetchResult
}

/**
 * 节目单的远端来源。抽成接口是为了让仓库层的降级分支能用假件测：
 * 超时、坏 JSON、空数组三条路都不该把播放页拖垮。
 */
interface EpgRemoteSource {
    /** 拉取某台某天的全天节目单。失败返回 [EpgFetchResult.Failure]，不抛异常。 */
    suspend fun fetch(stationId: String, broadcastId: String, date: String): EpgFetchResult
}

/**
 * 云听（央广官方）网页端公开接口。签名照抄 `pc-portal/js/api.js`，不自行发明：
 * 参数按 key 字典序拼成 `k=v&k=v`，挂上 timestamp 与 key 后取 MD5 大写。
 *
 * 接口没有文档，随时可能改签名或加鉴权。所有失败都收敛成空列表：
 * 功能消失可以接受，把播放页拖崩不行。
 */
@Singleton
class YuntingEpgClient @Inject constructor(
    private val httpClient: OkHttpClient,
) : EpgRemoteSource {

    override suspend fun fetch(
        stationId: String,
        broadcastId: String,
        date: String,
    ): EpgFetchResult = withContext(Dispatchers.IO) {
        runCatching {
            val timestamp = System.currentTimeMillis()
            val params = mapOf("broadcastId" to broadcastId, "date" to date)
            val url = BASE.toHttpUrl().newBuilder()
                .addPathSegment("web")
                .addPathSegment("appProgram")
                .addPathSegment("listByDate")
                .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("equipmentId", "0000")
                .header("platformCode", "WEB")
                .header("timestamp", timestamp.toString())
                .header("sign", sign(params, timestamp))
                .build()
            val raw = httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            } ?: return@runCatching EpgFetchResult.Failure

            // parse 返回 null 才是失败；空列表是「成功但当天没节目」，两件事不能混
            parse(raw, stationId, date)
                ?.let { EpgFetchResult.Success(it) }
                ?: EpgFetchResult.Failure
        }.getOrDefault(EpgFetchResult.Failure)
    }

    companion object {
        private const val BASE = "https://ytmsout.radio.cn"

        /**
         * 公开在前端 JS 里的常量，不是密钥——它一字不差地出现在云听网页源码里。
         * 放在这里只是为了与 JS 的算法对齐。
         */
        private const val API_KEY = "f0fc4c668392f9f9a447e48584c214ee"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120 Safari/537.36"

        private val JSON = Json { ignoreUnknownKeys = true }

        /** 签名。纯函数，用固定 timestamp 就能钉住与 JS 的黄金样例。 */
        fun sign(params: Map<String, String>, timestampMs: Long, key: String = API_KEY): String {
            val sorted = params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
            val text = (if (sorted.isEmpty()) "" else "$sorted&") + "timestamp=$timestampMs&key=$key"
            return md5Upper(text)
        }

        /**
         * 解析响应。时间换算只在这里做一次：把毫秒时间戳换成相对查询日北京时间 00:00
         * 的分钟数，跨天的终点自然截断到 1440，前一天开始的节目自然从 0 起算。
         *
         * 默认锁定播出时区而不是设备时区：否则非东八区的设备会把北京零点前后的档算成负分钟，
         * 再被 coerceIn 截成一团。参数留着是为了单测能显式传入一个别的时区。
         *
         * 返回 null 表示这次拉取失败（坏 JSON、错误码、坏日期），空列表表示成功但没有节目。
         * 这个区别决定了仓库层是保住旧缓存还是清空它，不能简化。
         */
        fun parse(
            body: String,
            stationId: String,
            date: String,
            zone: ZoneId = EpgQuery.BROADCAST_ZONE,
        ): List<EpgProgram>? {
            val dayStart = runCatching { LocalDate.parse(date).atStartOfDay(zone) }.getOrNull()
                ?: return null
            val response = runCatching { JSON.decodeFromString<Response>(body) }.getOrNull()
                ?: return null
            // 签名过期时云听回的是错误码 + 空数组，不能把错误文案当成节目
            if (response.code != 0) return null
            return response.data.orEmpty().mapNotNull { it.toProgram(stationId, date, dayStart) }
        }

        private fun Row.toProgram(
            stationId: String,
            date: String,
            dayStart: ZonedDateTime,
        ): EpgProgram? {
            val start = startTime ?: return null
            val end = endTime ?: return null
            if (start <= 0L || end <= 0L) return null
            // 真实响应里有「新闻进行时 」这种尾随空格，原样进 UI 会让行尾多出一截空白
            val name = programName.trim()
            if (name.isEmpty()) return null
            val startMin = minutesSince(dayStart, start)
            val endMin = minutesSince(dayStart, end)
            if (endMin <= startMin) return null
            return EpgProgram(
                stationId = stationId,
                date = date,
                startMin = startMin,
                endMin = endMin,
                title = name,
            )
        }

        private fun minutesSince(dayStart: ZonedDateTime, epochMs: Long): Int =
            Duration.between(dayStart.toInstant(), Instant.ofEpochMilli(epochMs))
                .toMinutes()
                .toInt()
                .coerceIn(0, MINUTES_PER_DAY)

        private const val MINUTES_PER_DAY = 1440

        private fun md5Upper(text: String): String =
            MessageDigest.getInstance("MD5")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02X".format(it.toInt() and 0xFF) }

        @Serializable
        private data class Response(val code: Int = 0, val data: List<Row>? = null)

        @Serializable
        private data class Row(
            val programName: String = "",
            val startTime: Long? = null,
            val endTime: Long? = null,
        )
    }
}
