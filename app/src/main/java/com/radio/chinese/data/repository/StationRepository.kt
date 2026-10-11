package com.radio.chinese.data.repository

import android.content.Context
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.local.SourceAvailabilityStore
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationFilter
import com.radio.chinese.domain.model.StationTag
import com.radio.chinese.domain.model.StationSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: RadioPreferences,
    private val sourceAvailabilityStore: SourceAvailabilityStore
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var builtInStations: List<RadioStation>? = null

    /** 获取指定电台排序后的节目源列表（按可用性从高到低） */
    suspend fun getSortedSources(stationId: String): List<StationSource> {
        val station = getStationById(stationId) ?: return emptyList()
        val sources = station.allSources()
        val sorted = sourceAvailabilityStore.getSortedSources(stationId, sources.map { it.url })
        return sorted.mapNotNull { url -> sources.find { it.url == url } }
    }

    /** 获取节目源带可用性分数 */
    suspend fun getSourcesWithScore(stationId: String): List<Pair<StationSource, Float>> {
        val station = getStationById(stationId) ?: return emptyList()
        val sources = station.allSources()
        val scores = sourceAvailabilityStore.getSourcesWithScore(stationId, sources.map { it.url })
        return scores.mapNotNull { (url, score) ->
            sources.find { it.url == url }?.let { it to score }
        }
    }

    suspend fun recordSourceSuccess(stationId: String, url: String, responseTimeMs: Long) {
        sourceAvailabilityStore.recordSuccess(stationId, url, responseTimeMs)
    }

    suspend fun recordSourceFailure(stationId: String, url: String) {
        sourceAvailabilityStore.recordFailure(stationId, url)
    }

    suspend fun setSourceScore(stationId: String, url: String, score: Float) {
        sourceAvailabilityStore.setScore(stationId, url, score)
    }

    private suspend fun loadBuiltInStations(): List<RadioStation> {
        builtInStations?.let { return it }

        val jsonString = context.assets.open("radio_stations.json")
            .bufferedReader()
            .use { it.readText() }

        // 解析逻辑在 StationJson（纯函数），JVM 单测才能直接校验真 JSON 的合法性
        val stations = com.radio.chinese.data.local.StationJson.parse(jsonString)
        builtInStations = stations
        return stations
    }

    /** 全部电台（内置 + 自定义，含无效标记） */
    suspend fun getAllStations(): List<RadioStation> = withContext(Dispatchers.IO) {
        val builtIn = loadBuiltInStations()
        val custom = getCustomStations()
        builtIn + custom
    }

    /** 有效电台（排除用户标记为无效的） */
    suspend fun getActiveStations(): List<RadioStation> = withContext(Dispatchers.IO) {
        val all = getAllStations()
        val invalidIds = preferences.invalidStationIds.first()
        all.filter { it.id !in invalidIds }
    }

    suspend fun getStationById(id: String): RadioStation? {
        return getAllStations().find { it.id == id }
    }

    suspend fun getStationsByCategory(category: String): List<RadioStation> {
        return getActiveStations().filter { it.category == category }
    }

    suspend fun searchStations(query: String): List<RadioStation> {
        val lowerQuery = query.lowercase()
        return getActiveStations().filter { station ->
            station.name.lowercase().contains(lowerQuery) ||
            station.frequency.lowercase().contains(lowerQuery) ||
            station.description.lowercase().contains(lowerQuery)
        }
    }

    fun getCategories(): List<Pair<String, String>> =
        com.radio.chinese.domain.model.StationCategory.ordered()

    // ---- 自定义电台 ----

    suspend fun getCustomStations(): List<RadioStation> = withContext(Dispatchers.IO) {
        val jsonStr = preferences.customStationsJson.first() ?: return@withContext emptyList()
        try {
            json.decodeFromString<List<RadioStation>>(jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun addCustomStation(station: RadioStation) {
        val current = getCustomStations().toMutableList()
        if (current.any { it.id == station.id }) return
        current.add(station)
        preferences.setCustomStationsJson(json.encodeToString(current))
    }

    suspend fun removeCustomStation(stationId: String) {
        val current = getCustomStations().toMutableList()
        current.removeAll { it.id == stationId }
        preferences.setCustomStationsJson(json.encodeToString(current))
    }

    // ---- 标签 ----

    /** 某标签下的有效电台。 */
    suspend fun getStationsByTag(tagKey: String): List<RadioStation> =
        StationFilter.apply(getActiveStations(), null, "", tagKey)

    /** 有电台的标签及其台数；词表外的 key 不会出现在这里。 */
    suspend fun getUsedTagsWithCount(): List<Pair<StationTag, Int>> =
        StationFilter.tagsWithCount(getActiveStations())

    // ---- 无效标记 ----

    suspend fun getInvalidIds(): Set<String> {
        return preferences.invalidStationIds.first()
    }

    suspend fun markInvalid(stationId: String) {
        val current = getInvalidIds().toMutableSet()
        current.add(stationId)
        preferences.setInvalidStationIds(current)
    }

    suspend fun markValid(stationId: String) {
        val current = getInvalidIds().toMutableSet()
        current.remove(stationId)
        preferences.setInvalidStationIds(current)
    }

    // ---- 彩蛋：加载默认电台到自定义列表 ----

    /** 将内置电台加载到自定义电台列表中（彩蛋功能） */
    suspend fun loadDefaultStationsToCustom() {
        val builtIn = loadBuiltInStations()
        val current = getCustomStations().toMutableList()
        val existingIds = current.map { it.id }.toSet()

        builtIn.forEach { station ->
            val customId = "default_${station.id}"
            if (customId !in existingIds) {
                current.add(station.copy(id = customId))
            }
        }

        preferences.setCustomStationsJson(json.encodeToString(current))
    }
}
