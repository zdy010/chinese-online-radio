package com.radio.chinese.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.radio.chinese.domain.model.SourceAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private val Context.availStore by preferencesDataStore(name = "source_availability")

/**
 * 节目源可用性持久化存储
 * 每个节目源存储: score(0-1), responseTimeMs, successCount, failCount
 */
@Singleton
class SourceAvailabilityStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val KEY = stringPreferencesKey("avail_data")

    /** 读取所有可用性数据 */
    val allAvailabilities: Flow<List<SourceAvailability>> = context.availStore.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map emptyList()
        parseJson(raw)
    }

    /** 获取指定电台的节目源可用性 */
    suspend fun getAvailabilities(stationId: String): List<SourceAvailability> {
        return allAvailabilities.first().filter { it.stationId == stationId }
    }

    /** 获取指定电台+URL 的可用性（无则返回默认） */
    suspend fun getAvailability(stationId: String, sourceUrl: String): SourceAvailability {
        return allAvailabilities.first().find {
            it.stationId == stationId && it.sourceUrl == sourceUrl
        } ?: SourceAvailability(stationId, sourceUrl)
    }

    /** 更新播放成功（提升可用性） */
    suspend fun recordSuccess(stationId: String, sourceUrl: String, responseTimeMs: Long) {
        val current = getAvailability(stationId, sourceUrl)
        // 成功：提升分数，增加成功计数
        val newScore = minOf(1.0f, current.score + 0.15f + (responseTimeMs.coerceAtMost(3000) / 3000f) * 0.1f)
        val updated = current.copy(
            score = newScore.coerceIn(0.1f, 1.0f),
            responseTimeMs = responseTimeMs,
            successCount = current.successCount + 1
        )
        saveAvailability(stationId, sourceUrl, updated)
    }

    /** 更新播放失败（降低可用性） */
    suspend fun recordFailure(stationId: String, sourceUrl: String) {
        val current = getAvailability(stationId, sourceUrl)
        val newScore = current.score - 0.25f
        val updated = current.copy(
            score = newScore.coerceIn(0.0f, 1.0f),
            failCount = current.failCount + 1
        )
        saveAvailability(stationId, sourceUrl, updated)
    }

    /** 手动设置分数（用户切换时设为最高） */
    suspend fun setScore(stationId: String, sourceUrl: String, score: Float) {
        val current = getAvailability(stationId, sourceUrl)
        saveAvailability(stationId, sourceUrl, current.copy(score = score.coerceIn(0.0f, 1.0f)))
    }

    /** 获取排序后的源URL（从高可用到低可用） */
    suspend fun getSortedSources(stationId: String, urls: List<String>): List<String> {
        val availMap = getAvailabilities(stationId).associateBy { it.sourceUrl }
        return urls.sortedByDescending { url ->
            availMap[url]?.score ?: 0.5f
        }
    }

    /** 获取带可用性的排序源列表 */
    suspend fun getSourcesWithScore(stationId: String, urls: List<String>): List<Pair<String, Float>> {
        val availMap = getAvailabilities(stationId).associateBy { it.sourceUrl }
        return urls.map { url ->
            url to (availMap[url]?.score ?: 0.5f)
        }.sortedByDescending { it.second }
    }

    private suspend fun saveAvailability(stationId: String, sourceUrl: String, data: SourceAvailability) {
        context.availStore.edit { prefs ->
            val raw = prefs[KEY] ?: "[]"
            val list = parseJson(raw).toMutableList()
            val idx = list.indexOfFirst { it.stationId == stationId && it.sourceUrl == sourceUrl }
            if (idx >= 0) list[idx] = data else list.add(data)
            prefs[KEY] = toJson(list)
        }
    }

    private fun parseJson(raw: String): MutableList<SourceAvailability> {
        return try {
            val arr = json.parseToJsonElement(raw).jsonArray
            arr.map { element ->
                val obj = element.jsonObject
                SourceAvailability(
                    stationId = obj["stationId"]?.jsonPrimitive?.content ?: "",
                    sourceUrl = obj["sourceUrl"]?.jsonPrimitive?.content ?: "",
                    score = obj["score"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.5f,
                    responseTimeMs = obj["responseTimeMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    successCount = obj["successCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    failCount = obj["failCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                )
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun toJson(list: List<SourceAvailability>): String {
        val arr = buildJsonArray {
            list.forEach { item ->
                add(buildJsonObject {
                    put("stationId", item.stationId)
                    put("sourceUrl", item.sourceUrl)
                    put("score", item.score.toString())
                    put("responseTimeMs", item.responseTimeMs.toString())
                    put("successCount", item.successCount.toString())
                    put("failCount", item.failCount.toString())
                })
            }
        }
        return arr.toString()
    }
}

/** 根据可用性分数计算颜色 (#RRGGBB 格式)：1.0=绿 0.5=黄 0.0=红 */
fun availabilityToColor(score: Float): Long {
    val clamped = score.coerceIn(0.0f, 1.0f)
    return when {
        clamped >= 0.5f -> {
            // 绿到黄 (0.5→黄, 1.0→绿)
            val ratio = (clamped - 0.5f) * 2f // 0~1
            val r = (0xFF * (1f - ratio * 0.7f)).toLong() shl 16
            val g = (0xFF * (0.7f + ratio * 0.3f)).toLong() shl 8
            0xFF000000L or r or g
        }
        else -> {
            // 红到黄 (0.0→红, 0.5→黄)
            val ratio = clamped * 2f // 0~1
            val r = 0xFFL shl 16
            val g = (0xFF * ratio * 0.7f).toLong() shl 8
            0xFF000000L or r or g
        }
    }
}
