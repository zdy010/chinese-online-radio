package com.radio.chinese.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class GazetteerRaw(val provinces: List<ProvinceRaw> = emptyList())

@Serializable
private data class ProvinceRaw(val name: String, val cities: List<String> = emptyList())

/**
 * 省市词表：把「城市 -> 所属省」摊平成一张命中表，供 RegionResolver 从台名/简介反推地区。
 * 省份自身也作为一个键映射到它自己，这样「河南交通广播」这类只含省名的台也能命中（city 留空）。
 * 解析逻辑独立成纯函数，JVM 单测才能直接校验真词表的合法性（对齐 StationJson 的做法）。
 */
object RegionGazetteer {
    data class Data(
        val citiesToProvince: Map<String, String>,
        val provinceNames: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Data {
        val raw = json.decodeFromString<GazetteerRaw>(text)
        val map = LinkedHashMap<String, String>()
        for (p in raw.provinces) {
            map.putIfAbsent(p.name, p.name)
            for (c in p.cities) map.putIfAbsent(c, p.name)
        }
        return Data(map, raw.provinces.map { it.name })
    }
}
