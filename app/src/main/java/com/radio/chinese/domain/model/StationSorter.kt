package com.radio.chinese.domain.model

/**
 * 本地优先排序。tier：同市=3 / 同省=2 / 同国=1 / 其余=0。
 * “同国”用中国大陆省级集合判定——避免把非中国地区误判为中国同级。
 * sortedByDescending 是稳定排序，同 tier 保持入参相对顺序；用户地区为空时原样返回。
 */
object StationSorter {
    fun tierOf(region: Region, user: Region): Int {
        if (user.isEmpty || region.isEmpty) return 0
        if (region.city.isNotEmpty() && region.city == user.city) return 3
        if (region.province == user.province) return 2
        if (isChina(region) && isChina(user)) return 1
        return 0
    }

    private fun isChina(r: Region): Boolean = r.province in CHINA_PROVINCES

    private val CHINA_PROVINCES = setOf(
        "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江", "上海", "江苏", "浙江", "安徽",
        "福建", "江西", "山东", "河南", "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州", "云南",
        "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆"
    )

    fun localPriority(
        stations: List<RadioStation>,
        user: Region,
        regionOf: (RadioStation) -> Region,
    ): List<RadioStation> {
        if (user.isEmpty) return stations
        return stations.sortedByDescending { tierOf(regionOf(it), user) }
    }
}
