package com.radio.chinese.data.local

import kotlinx.serialization.json.Json

// CityPoint 已下移到 domain/model，此处重导出保持兼容。
typealias CityPoint = com.radio.chinese.domain.model.CityPoint

@kotlinx.serialization.Serializable
private data class CityListRaw(val cities: List<CityPoint> = emptyList())

/** 解析离线城市坐标表（供 NearestCityFinder 把设备经纬度映射到最近城市）。 */
object CityCoordinates {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): List<CityPoint> = json.decodeFromString<CityListRaw>(text).cities
}
