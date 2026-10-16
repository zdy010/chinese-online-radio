package com.radio.chinese.domain.model

import com.radio.chinese.data.local.RegionGazetteer

/**
 * 从台名/简介文本反推省/市。全国电台最常见命名是“省名在前 + 市名在后”，
 * 故不能只取位置最靠前的键（那样会总被判成省）。规则：
 * - 城市级键（地级市，以及省市同名的直辖市）优先；同级中取文本中位置最靠前者。
 * - 若没有任何城市级命中，退而用省级自身键（city 留空）。
 * - 无命中 => NONE。纯函数、不碰 Android，单测直接拿真词表跑。
 */
object RegionResolver {
    fun infer(text: String, country: String, gazetteer: RegionGazetteer.Data): Region {
        if (text.isBlank()) return Region.NONE
        var bestCity: String? = null
        var bestCityPos = Int.MAX_VALUE
        var bestProvince: String? = null
        var bestProvincePos = Int.MAX_VALUE
        for ((key, province) in gazetteer.citiesToProvince) {
            val pos = text.indexOf(key)
            if (pos < 0) continue
            val isCityGrade = key != province || key in gazetteer.municipalities
            if (isCityGrade) {
                if (pos < bestCityPos) { bestCityPos = pos; bestCity = key }
            } else {
                if (pos < bestProvincePos) { bestProvincePos = pos; bestProvince = key }
            }
        }
        val hit = bestCity ?: bestProvince ?: return Region.NONE
        val province = gazetteer.citiesToProvince.getValue(hit)
        return when {
            hit in gazetteer.municipalities -> Region(province = province, city = hit)
            hit == province -> Region(province = province, city = "")
            else -> Region(province = province, city = hit)
        }
    }
}
