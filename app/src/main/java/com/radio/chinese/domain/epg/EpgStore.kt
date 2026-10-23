package com.radio.chinese.domain.epg

import com.radio.chinese.domain.model.EpgProgram

/**
 * 节目单缓存的端口。抽成接口有两个原因：
 *
 * 1. 仓库层要能用假件测「失败保旧缓存 / 成功为空清缓存」这两条最容易写错的分支，
 *    而真实实现依赖 Room 与 Android。
 * 2. 落库必须是原子的（删旧、插新、打时间戳三步），这条约束只属于实现，
 *    不该漏到调用方。
 */
interface EpgStore {

    suspend fun programs(stationId: String, date: String): List<EpgProgram>

    /** 某天全部已缓存节目，节目搜索用。 */
    suspend fun programsOn(date: String): List<EpgProgram>

    /** 这台这天成功抓取的时刻；null 表示从没成功过（含从没抓过）。 */
    suspend fun fetchedAt(stationId: String, date: String): Long?

    /** 原子替换某台某天。空列表也要写时间戳：那是「拉过且确认没有节目」。 */
    suspend fun replaceDay(
        stationId: String,
        date: String,
        programs: List<EpgProgram>,
        fetchedAt: Long,
    )

    /** 删除早于 [date] 的所有缓存。 */
    suspend fun deleteBefore(date: String)
}
