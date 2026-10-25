package com.radio.chinese.domain.model

/**
 * 省市词表数据。原为 RegionGazetteer.Data (nested)，
 * 下移到 domain 消除 RegionResolver/StationRegion 的 domain→data 违例。
 */
data class GazetteerData(
    val citiesToProvince: Map<String, String>,
    val provinceNames: List<String>,
    /** 直辖市（省市同名）：北京/上海/天津/重庆。命中它们时同时填 city，便于按 tier3 匹配。 */
    val municipalities: Set<String>,
)
