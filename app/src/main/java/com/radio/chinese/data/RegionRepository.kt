package com.radio.chinese.data

import android.content.Context
import com.radio.chinese.data.local.CityCoordinates
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.domain.model.NearestCityFinder
import com.radio.chinese.domain.model.Region
import com.radio.chinese.domain.model.resolveRegion
import com.radio.chinese.service.AndroidLocationSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * 用户当前地区：手动选择优先，其次自动定位缓存，都没有则 NONE（本地不加分）。
 * region 串格式 "省|市"，与 RadioPreferences 存储一致。
 * 列表排序走缓存值，不在每次取列表时实时定位；refreshFromLocation 由设置页/进首页触发。
 */
@Singleton
class RegionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: RadioPreferences,
    private val locationSource: AndroidLocationSource,
) {
    private val cities by lazy {
        CityCoordinates.parse(
            context.assets.open("city_coordinates.json").bufferedReader().use { it.readText() }
        )
    }

    fun parseRegion(text: String?): Region? {
        if (text.isNullOrBlank()) return null
        val parts = text.split("|")
        return Region(province = parts.getOrElse(0) { "" }, city = parts.getOrElse(1) { "" })
    }

    private fun format(r: Region): String = "${r.province}|${r.city}"

    /**
     * 当前地区的响应式流：手动/缓存任一变化都会重新发射。
     * 首页观察它，设置页定位或改选手动地区后返回列表即重排，不需重启 App。
     */
    val currentRegion: Flow<Region> =
        combine(preferences.manualRegion, preferences.cachedRegion) { manual, cached ->
            resolveRegion(manual, cached)
        }

    suspend fun currentUserRegion(): Region = currentRegion.first()

    /** 尝试实时定位并写入缓存；成功返回 region，失败返回 null（缓存不动）。 */
    suspend fun refreshFromLocation(): Region? {
        val coords = locationSource.currentCoords() ?: return null
        val region = NearestCityFinder.find(coords.lat, coords.lng, cities) ?: return null
        preferences.setCachedRegion(format(region))
        return region
    }
}
