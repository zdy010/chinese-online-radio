package com.radio.chinese.data.entity

import androidx.room.Entity

/**
 * 「这台这天拉过节目单」的凭证。与节目行分开存的原因见 [EpgProgramEntity]：
 * 空结果也必须留下时间戳，否则没节目单的台会每次进页面都重拉一次。
 */
@Entity(tableName = "epg_fetch", primaryKeys = ["stationId", "date"])
data class EpgFetchEntity(
    val stationId: String,
    val date: String,
    /** 本机抓取时刻，毫秒。缓存有效期按它算。 */
    val fetchedAt: Long,
)
