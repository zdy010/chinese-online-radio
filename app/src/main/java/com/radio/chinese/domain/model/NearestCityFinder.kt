package com.radio.chinese.domain.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 经纬度 -> 最近城市（带最大距离阈值，超出视为“无本地城市”，人在境外/表外）。 */
object NearestCityFinder {
    private const val EARTH_R_KM = 6371.0

    fun find(lat: Double, lng: Double, cities: List<CityPoint>, maxKm: Double = 120.0): Region? {
        var best: CityPoint? = null
        var bestKm = Double.MAX_VALUE
        for (c in cities) {
            val km = haversineKm(lat, lng, c.lat, c.lng)
            if (km < bestKm) { bestKm = km; best = c }
        }
        if (best == null || bestKm > maxKm) return null
        return Region(province = best.province, city = best.name)
    }

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return EARTH_R_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
