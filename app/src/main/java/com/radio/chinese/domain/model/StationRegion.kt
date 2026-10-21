package com.radio.chinese.domain.model

import com.radio.chinese.data.local.RegionGazetteer

/**
 * 地区页的一个入口：中央台 / 本地 / 某个省 / 境外 / 未归类。
 * 只承载分组结果，界面文案由 [label] 提供。
 */
data class RegionBucket(
    /** 稳定 key（central / local / overseas / other / province:<省名>），Compose 列表 key 用 */
    val key: String,
    /** 界面文案：中央台 / 本地 · 浙江 / 浙江 / 境外台 / 其他 */
    val label: String,
    val stations: List<RadioStation>,
)

/**
 * 按地域切分电台，供地区页使用。
 *
 * 为什么不给 radio_stations.json 加 province 字段：仓库里已有一套从台名/简介反推省市的
 * [RegionResolver]（「本地优先」排序在用），451 个台里 229 个能直接推出省份。
 * 再加一份手工维护的省份字段，等于给同一件事留两个真相来源，改数据要同步两处。
 *
 * 判定顺序：境外 > 中央 > 省市推断 > 未归类。
 * 境外必须排在最前：197 个 gl_ 台名字是英文，不会命中省市词表，但 country 有值。
 */
object StationRegion {
    const val CENTRAL = "central"
    const val LOCAL = "local"
    const val OVERSEAS = "overseas"
    const val OTHER = "other"

    fun provinceKey(province: String): String = "province:$province"

    /**
     * 央广与国际台的标志。这些台推不出省市，不单独归类就会掉进「其他」，
     * 和两百个境外台、电视伴音混在一起，等于没有地区页。
     * cnr/cri 走 id 前缀，其余国际台靠台名里的特征词。
     */
    private val CENTRAL_MARKERS = listOf("环球", "南海之声", "世界华声", "粤语国际")

    fun isCentral(station: RadioStation): Boolean =
        station.id.startsWith("cnr") || station.id.startsWith("cri") ||
            CENTRAL_MARKERS.any { station.name.contains(it) }

    /** 视作「国内」的国家/地区名。港澳台的台要归到各自的地区分组，不是「境外台」。 */
    private val CN_REGIONS = setOf("中国", "台湾", "香港", "澳门")

    /**
     * 有国家字段就以国家为准；字段为空才回落到 gl_/ad_ 前缀。
     * 不能反过来先看前缀：gl_ 只是「全球源」的来源标记，里面混了 37 个台湾台，
     * 先看前缀就会把它们和一百多个欧美台一起塞进「境外台」。
     */
    fun isOverseas(station: RadioStation): Boolean {
        if (station.id.startsWith("ad_")) return true
        val country = station.country.trim()
        if (country.isNotEmpty()) return country !in CN_REGIONS
        return station.id.startsWith("gl_")
    }

    /**
     * 切分。只返回非空分组；省份按台数降序（同台数按省名）。
     *
     * [userProvince] 非空且该省有台时额外产出一个「本地」组，它引用的是同一个省分组里的台，
     * 不会把该省从省份列表摘掉——用户既要点得快的快捷入口，也要能按省逐个浏览。
     */
    fun bucketize(
        stations: List<RadioStation>,
        gazetteer: RegionGazetteer.Data,
        userProvince: String = "",
    ): List<RegionBucket> {
        val central = mutableListOf<RadioStation>()
        val overseas = mutableListOf<RadioStation>()
        val other = mutableListOf<RadioStation>()
        val byProvince = LinkedHashMap<String, MutableList<RadioStation>>()

        for (station in stations) {
            when {
                isOverseas(station) -> overseas += station
                isCentral(station) -> central += station
                else -> {
                    val region = RegionResolver.infer(
                        "${station.name} ${station.description}", station.country, gazetteer
                    )
                    if (region.province.isBlank()) other += station
                    else byProvince.getOrPut(region.province) { mutableListOf() } += station
                }
            }
        }

        val buckets = mutableListOf<RegionBucket>()
        if (central.isNotEmpty()) {
            buckets += RegionBucket(CENTRAL, "中央台", central.sortedBy { it.name })
        }
        byProvince[userProvince]?.let { local ->
            if (local.isNotEmpty()) {
                buckets += RegionBucket(LOCAL, "本地 · $userProvince", local.sortedBy { it.name })
            }
        }
        byProvince.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, MutableList<RadioStation>>> { it.value.size }
                    .thenBy { it.key }
            )
            .forEach { (province, list) ->
                buckets += RegionBucket(provinceKey(province), province, list.sortedBy { it.name })
            }
        if (overseas.isNotEmpty()) {
            buckets += RegionBucket(OVERSEAS, "境外台", overseas.sortedBy { it.name })
        }
        if (other.isNotEmpty()) {
            buckets += RegionBucket(OTHER, "其他", other.sortedBy { it.name })
        }
        return buckets
    }
}
