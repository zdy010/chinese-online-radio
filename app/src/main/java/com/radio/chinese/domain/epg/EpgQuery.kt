package com.radio.chinese.domain.epg

import com.radio.chinese.domain.model.EpgProgram
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 节目单的纯函数层。入参一律是「北京时间当天 00:00 起算的分钟数」，
 * 这里不做任何时间戳换算，也不碰 IO——三处 UI（播放页卡片、节目单页、搜索）共用同一套判定。
 */
object EpgQuery {

    /**
     * 节目单的时间基准：北京时间，不看设备时区。
     *
     * 排播表是北京零点切一天的。拿设备时区去切，落在本地零点之前的那些档会被
     * 算成负分钟再截成 0：北京半夜那几档在非东八区的设备上会整段消失或挤到 00:00。
     * 固定用北京时间后，每一档都落在它自己的位置上；设备不在东八区时由 UI 标出「北京时间」。
     */
    val BROADCAST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 本地时钟→当天分钟数。不做时区换算，进来的就该是基准时区的墙上时间。 */
    fun minuteOf(local: LocalDateTime): Int = local.hour * 60 + local.minute

    /** 设备时区是否就是播出时区。不是的时候，UI 要把「北京时间」写出来。 */
    fun isBroadcastZoneLocal(): Boolean = ZoneId.systemDefault() == BROADCAST_ZONE

    /** 缓存有效期。节目单一天最多改几次，6 小时既跟得上排播调整，又不会反复打接口。 */
    const val TTL_MS: Long = 6 * 60 * 60 * 1000L

    /**
     * 此刻在播的节目。区间取左闭右开：`startMin <= minute < endMin`。
     * 相邻两档的边界分钟归后一档，否则两档会同时声称自己在播。
     */
    fun currentAt(programs: List<EpgProgram>, minute: Int): EpgProgram? =
        programs.firstOrNull { minute >= it.startMin && minute < it.endMin }

    /** 下一档：开始时间严格大于当前分钟的第一档。当前落在空档里也能给出。 */
    fun nextAfter(programs: List<EpgProgram>, minute: Int): EpgProgram? =
        programs.asSequence()
            .filter { it.startMin > minute }
            .minByOrNull { it.startMin }

    /**
     * 按节目名做子串搜索，结果按开始时间排序。
     *
     * 空白查询必须返回空而不是全表：搜索框被清空时若把全天节目都列出来，
     * 用户会看到一份与输入无关的清单。
     */
    fun search(programs: List<EpgProgram>, query: String): List<EpgProgram> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return programs
            .filter { it.title.contains(q, ignoreCase = true) }
            .sortedBy { it.startMin }
    }

    /** 某一天的节目，按开始时间排序。 */
    fun scheduleFor(programs: List<EpgProgram>, date: String): List<EpgProgram> =
        programs.filter { it.date == date }.sortedBy { it.startMin }

    /**
     * 缓存是否该重拉。未来时间戳一律算过期：用户或车机把时钟改回去时，
     * 不能因为 `now - fetchedAt` 变成负数就让节目单永远停在旧数据上。
     */
    fun isStale(fetchedAt: Long, now: Long, ttlMs: Long = TTL_MS): Boolean =
        fetchedAt > now || now - fetchedAt >= ttlMs

    /** 时段文案：`06:00–06:30`。终点 1440 显示成 24:00，跨天截断看起来才自然。 */
    fun formatRange(startMin: Int, endMin: Int): String =
        "${formatMinute(startMin)}–${formatMinute(endMin)}"

    fun formatMinute(minute: Int): String =
        "%02d:%02d".format(minute / 60, minute % 60)

    /**
     * 缓存时间文案：`10-22 21:30`。UI 拼成「数据截至 …」。
     *
     * 只给月日与时分，不给年份：节目单只留今天与昨天，跨年那一夜的“01-01”不会歧义；
     * 多写一个年份反而让这行副标题长到要折行。
     *
     * 用设备时区而不是播出时区：这一行回答的是「我什么时候拿到这份数据」，与排播无关。
     */
    fun formatFetchedAt(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        FETCHED_AT_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    private val FETCHED_AT_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /**
     * 节目单页的台排序：有当前在播节目的排前面，同组内按台名。
     *
     * 只决定先后，**不丢行**：此刻在空档、甚至完全没拉到节目单的台也要留在列表里，
     * 用户点进去还能看它的全天节目（或知道接口没数据），而不是从列表里凭空消失。
     *
     * 写成泛型是为了让排序规则能单独测，不用在单测里造 RadioStation。
     */
    fun <T> nowPlayingFirst(
        items: List<T>,
        minute: Int,
        programsOf: (T) -> List<EpgProgram>,
        nameOf: (T) -> String,
    ): List<T> = items.sortedWith(
        compareByDescending<T> { currentAt(programsOf(it), minute) != null }
            .thenBy { nameOf(it) }
    )
}
