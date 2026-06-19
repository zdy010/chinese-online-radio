package com.radio.chinese.data.repository

import android.content.Context
import com.radio.chinese.domain.model.RadioStation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StationRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cachedStations: List<RadioStation>? = null

    suspend fun getAllStations(): List<RadioStation> = withContext(Dispatchers.IO) {
        cachedStations?.let { return@withContext it }

        val jsonString = context.assets.open("radio_stations.json")
            .bufferedReader()
            .use { it.readText() }

        val jsonObject = json.parseToJsonElement(jsonString).jsonObject
        val stationsArray = jsonObject["stations"]?.jsonArray ?: return@withContext emptyList()

        val stations = stationsArray.map { element ->
            val obj = element.jsonObject
            RadioStation(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                category = obj["category"]?.jsonPrimitive?.content ?: "general",
                streamUrl = obj["streamUrl"]?.jsonPrimitive?.content ?: "",
                logoUrl = obj["logoUrl"]?.jsonPrimitive?.content ?: "",
                frequency = obj["frequency"]?.jsonPrimitive?.content ?: "",
                description = obj["description"]?.jsonPrimitive?.content ?: "",
                website = obj["website"]?.jsonPrimitive?.content ?: ""
            )
        }

        cachedStations = stations
        stations
    }

    suspend fun getStationById(id: String): RadioStation? {
        return getAllStations().find { it.id == id }
    }

    suspend fun getStationsByCategory(category: String): List<RadioStation> {
        return getAllStations().filter { it.category == category }
    }

    suspend fun searchStations(query: String): List<RadioStation> {
        val lowerQuery = query.lowercase()
        return getAllStations().filter { station ->
            station.name.lowercase().contains(lowerQuery) ||
            station.frequency.lowercase().contains(lowerQuery) ||
            station.description.lowercase().contains(lowerQuery)
        }
    }

    fun getCategories(): List<Pair<String, String>> {
        return listOf(
            "news" to "新闻",
            "music" to "音乐",
            "traffic" to "交通",
            "arts" to "文艺",
            "sports" to "体育",
            "finance" to "财经",
            "general" to "综合"
        )
    }
}
