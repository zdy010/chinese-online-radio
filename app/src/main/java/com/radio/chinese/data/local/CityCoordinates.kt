package com.radio.chinese.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CityPoint(val name: String, val province: String, val lat: Double, val lng: Double)

@Serializable
private data class CityListRaw(val cities: List<CityPoint> = emptyList())

/** 解析离线城市坐标表（供 NearestCityFinder 把设备经纬度映射到最近城市）。 */
object CityCoordinates {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): List<CityPoint> = json.decodeFromString<CityListRaw>(text).cities
}
