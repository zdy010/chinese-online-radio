package com.radio.chinese.domain.model

import com.radio.chinese.domain.IndexState
import com.radio.chinese.domain.IndexedProgress
import com.radio.chinese.domain.TrackHit

/** 归并后的搜索结果；`sources.first()` 为主选来源。 */
data class MergedTrack(val name: String, val size: Long, val sources: List<TrackHit>)

/**
 * 索引与搜索的纯判定集合。
 *
 * SQL 侧只负责取行，语义（转义、归并、面包屑、新鲜度）全部留在这里：
 * 本项目没有 Robolectric，DAO 行为无法在 JVM 单测里验证，能把判定搬出 SQL
 * 的就搬出来，剩下真正无法单测的只有一条 LIKE 语句本身。
 */
object IndexQuery {

    /**
     * 转成 Room 的 LIKE 参数。转义顺序固定：先反斜杠，再 `%` 与 `_`，
     * 否则补出来的 `\%` 会被反斜杠规则再吃掉一层。
     * 配套 SQL 必须写 `ESCAPE '\'`。
     *
     * @return 空白输入返回空串，由调用方短路，不能把它送进 SQL
     */
    fun likePattern(raw: String): String {
        val q = raw.trim()
        if (q.isEmpty()) return ""
        val escaped = q.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }

    /**
     * 跨源重复曲目归并。实测两个豫剧源同为 3468 首，是同一批内容的两种
     * 分类视图，不归并则一次搜索出双倍结果。
     *
     * 归并键 name+size 有极低概率误合不同内容，UI 侧保留“几个来源”作逃生门。
     * 排序用码位序，与 DAO 里 `ORDER BY name ASC`（SQLite BINARY 排序）一致，
     * 免得库内序与屏上序两套规则。
     */
    fun mergeHits(hits: List<TrackHit>): List<MergedTrack> {
        val grouped = LinkedHashMap<Pair<String, Long>, MutableList<TrackHit>>()
        for (h in hits) grouped.getOrPut(h.name.trim() to h.size) { mutableListOf() }.add(h)
        return grouped.values.map { group ->
            MergedTrack(
                name = group.first().name.trim(),
                size = group.first().size,
                sources = group.sortedBy { it.sourceId }
            )
        }.sortedBy { it.name }
    }

    /** `/豫剧/常香玉` → `豫剧 / 常香玉`；空路径回退源名。 */
    fun breadcrumb(parentPath: String, sourceName: String, maxSegments: Int = 3): String {
        val segs = parentPath.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return sourceName
        val shown = if (segs.size > maxSegments) listOf("…") + segs.takeLast(maxSegments) else segs
        return shown.joinToString(" / ")
    }

    /**
     * 目录行最旧 cachedAt 决定它还算不算新鲜。
     *
     * 两种情况一律判为过期：超出 TTL，以及时间戳在未来（用户或车机把时钟
     * 改回去时，不能让整棵树永远不再刷新）；TTL 非正数视为不做缓存。
     */
    fun isFresh(oldestCachedAt: Long, ttlMs: Long, now: Long): Boolean {
        if (ttlMs <= 0L) return false
        return (now - oldestCachedAt) in 0 until ttlMs
    }

    /** 没爬过的目录（无时间戳）恒要爬。 */
    fun shouldCrawl(stamp: Long?, ttlMs: Long, now: Long): Boolean =
        stamp == null || !isFresh(stamp, ttlMs, now)

    /**
     * 多个源的索引状态聚合为一个给 UI 的口径。
     *
     * 搜索是跨源的，提示也必须是跨源口径：否则 A 源在跑、B 源已完，
     * UI 会拿 B 的 Done 去说“结果已完整”。优先级 Running > Paused > Failed > Done。
     */
    fun aggregate(states: Collection<IndexState>): IndexState {
        running(states)?.let { return IndexState.Running(it) }
        paused(states)?.let { return IndexState.PausedNoWifi(it) }
        failed(states)?.let { return IndexState.PartiallyFailed(it) }
        val done = states.filterIsInstance<IndexState.Done>()
        if (done.isNotEmpty()) return IndexState.Done(done.sumOf { it.trackCount })
        return IndexState.Idle
    }

    private fun running(s: Collection<IndexState>) = progress<IndexState.Running>(s)
    private fun paused(s: Collection<IndexState>) = progress<IndexState.PausedNoWifi>(s)
    private fun failed(s: Collection<IndexState>) = progress<IndexState.PartiallyFailed>(s)

    /** 只取带进度字段的分支，类型参数限定可用状态，避免手拼四个 sumOf。 */
    private inline fun <reified T : IndexState> progress(states: Collection<IndexState>): IndexedProgress? {
        val list = states.filterIsInstance<T>().mapNotNull { p ->
            when (p) {
                is IndexState.Running -> p.p
                is IndexState.PausedNoWifi -> p.p
                is IndexState.PartiallyFailed -> p.p
                else -> null
            }
        }
        if (list.isEmpty()) return null
        return IndexedProgress(
            indexedFiles = list.sumOf { it.indexedFiles },
            pendingDirs = list.sumOf { it.pendingDirs },
            skippedDirs = list.sumOf { it.skippedDirs },
        )
    }
}
