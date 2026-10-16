package com.radio.chinese.domain.model

import com.radio.chinese.data.local.RegionGazetteer

/**
 * 从台名/简介文本反推省/市。规则：扫描所有出现的地区键，取在文本中位置最靠前的一个。
 * - 命中直辖市（省市同名）=> 同时填省与市（便于本地匹配进最高档）。
 * - 命中普通省份自身 => 只填省、市留空（省台）。
 * - 命中地级市 => 填该省与该市。
 * 无命中 => NONE。纯函数、不碰 Android，单测直接拿真词表跑。
 */
object RegionResolver {
    fun infer(text: String, country: String, gazetteer: RegionGazetteer.Data): Region {
        if (text.isBlank()) return Region.NONE
        var bestKey: String? = null
        var bestPos = Int.MAX_VALUE
        for (key in gazetteer.citiesToProvince.keys) {
            val pos = text.indexOf(key)
            if (pos in 0 until bestPos) { bestPos = pos; bestKey = key }
        }
        val hit = bestKey ?: return Region.NONE
        val province = gazetteer.citiesToProvince.getValue(hit)
        return when {
            hit in gazetteer.municipalities -> Region(province = province, city = hit)
            hit == province -> Region(province = province, city = "")
            else -> Region(province = province, city = hit)
        }
    }
}
