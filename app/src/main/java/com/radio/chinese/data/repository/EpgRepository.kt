package com.radio.chinese.data.repository

import com.radio.chinese.data.remote.EpgFetchResult
import com.radio.chinese.data.remote.EpgRemoteSource
import com.radio.chinese.di.EpgStationMapping
import com.radio.chinese.domain.epg.EpgQuery
import com.radio.chinese.domain.epg.EpgStore
import com.radio.chinese.domain.model.EpgProgram
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一台某天的节目单结果。
 *
 * [fetchedAt] 为 null 表示从没成功抓到过；[refreshFailed] 表示这次想刷新但失败了，
 * 此时 [programs] 是旧缓存，UI 必须标注「数据截至」——否则用户看到的是昨天的节目
 * 却以为是今天的。
 */
data class EpgSnapshot(
    val programs: List<EpgProgram>,
    val fetchedAt: Long?,
    val refreshFailed: Boolean,
)

/**
 * 节目单的编排层：先看缓存，过期才按需拉取，失败保住旧数据。
 *
 * 合规边界落在这里：只在调用方要的时候拉，单台单日；不做后台批量、不做定时轮询。
 * 所有失败都静默降级——接口挂了等于这个功能不存在，不影响播放。
 */
@Singleton
class EpgRepository @Inject constructor(
    @EpgStationMapping private val broadcastIds: Map<String, String>,
    private val store: EpgStore,
    private val remote: EpgRemoteSource,
) {

    /**
     * 同台同日 30 秒内不重复请求。只放内存：进程重启后重新判断一次没有坏处，
     * 反而能避免把「一次失败」的记忆持久化到下次冷启动。
     */
    private val lastRequestAt = ConcurrentHashMap<String, Long>()

    /** 这个台有没有接入节目单。没有的台不该出现任何 EPG 痕迹。 */
    fun hasEpg(stationId: String): Boolean = stationId in broadcastIds

    val stationIds: Set<String> get() = broadcastIds.keys

    /**
     * 取某台某天的节目单。返回 null 表示这个台没有接入节目单——UI 必须整块不渲染。
     */
    suspend fun load(
        stationId: String,
        date: String,
        now: Long = System.currentTimeMillis(),
    ): EpgSnapshot? {
        val broadcastId = broadcastIds[stationId] ?: return null
        val stamp = store.fetchedAt(stationId, date)

        if (stamp != null && !EpgQuery.isStale(stamp, now)) {
            return EpgSnapshot(store.programs(stationId, date), stamp, refreshFailed = false)
        }

        val key = "$stationId@$date"
        val last = lastRequestAt[key]
        if (last != null && now - last < THROTTLE_MS) {
            // 刚失败过就别追打：网络抖动时连点页面不该变成连发请求
            return EpgSnapshot(store.programs(stationId, date), stamp, refreshFailed = true)
        }
        lastRequestAt[key] = now

        return when (val result = remote.fetch(stationId, broadcastId, date)) {
            is EpgFetchResult.Success -> {
                val sorted = result.programs.sortedBy { it.startMin }
                store.replaceDay(stationId, date, sorted, now)
                EpgSnapshot(sorted, now, refreshFailed = false)
            }
            EpgFetchResult.Failure ->
                // 失败时保住旧缓存：用户宁可看到标注了时间的旧节目单，也不想它凭空消失
                EpgSnapshot(store.programs(stationId, date), stamp, refreshFailed = true)
        }
    }

    /**
     * 批量取多台某天的节目单，并发上限 3。
     *
     * 上限是为了不把接口当自家后端打：节目单页首屏最多几十个台，全并发出去
     * 与「按需单台」的边界差得太远。
     */
    suspend fun loadAll(
        stationIds: Collection<String>,
        date: String,
        now: Long = System.currentTimeMillis(),
    ): Map<String, EpgSnapshot> = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT)
        stationIds.asSequence()
            .distinct()
            .filter { it in broadcastIds }
            .map { id ->
                async { id to semaphore.withPermit { load(id, date, now) } }
            }
            .toList()
            .awaitAll()
            .mapNotNull { (id, snapshot) -> snapshot?.let { id to it } }
            .toMap()
    }

    /** 节目搜索只搜本地已缓存的当天节目：实时搜 307 个台会瞬间打出 300+ 请求。 */
    suspend fun cachedPrograms(date: String): List<EpgProgram> = store.programsOn(date)

    /** 清掉今天与昨天之前的缓存。每次进节目单页顺手做一次，不必另开定时任务。 */
    suspend fun cleanup(today: String) {
        val before = runCatching { LocalDate.parse(today).minusDays(1).toString() }.getOrNull() ?: return
        store.deleteBefore(before)
    }

    private companion object {
        const val THROTTLE_MS = 30_000L
        const val MAX_CONCURRENT = 3
    }
}
