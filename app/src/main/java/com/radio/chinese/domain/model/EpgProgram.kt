package com.radio.chinese.domain.model

/**
 * 一档节目。时间不存时间戳，只存「当天 00:00 起算的分钟数」，且这个 00:00 固定是北京时间：
 * 时区换算全部在解析层做一次，UI 与查询只做整数比较，不会各处重复处理夏令时与跨天。
 */
data class EpgProgram(
    val stationId: String,
    /** yyyy-MM-dd，按北京时间切的一天 */
    val date: String,
    /** 0..1439 */
    val startMin: Int,
    /** 1..1440；跨天时截断到 1440 */
    val endMin: Int,
    val title: String,
)
