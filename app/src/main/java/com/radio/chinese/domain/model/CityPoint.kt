package com.radio.chinese.domain.model

import kotlinx.serialization.Serializable

/**
 * 城市坐标点：名称、省、经纬度。
 * 原定义在 data/local/CityCoordinates.kt，下移到 domain 消除 domain→data 违例。
 */
@Serializable
data class CityPoint(val name: String, val province: String, val lat: Double, val lng: Double)
