package com.radio.chinese.domain.model

import com.radio.chinese.domain.IndexState
import com.radio.chinese.domain.IndexedProgress
import com.radio.chinese.domain.SearchPhase
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

    /**
     * `/豫剧/常香玉` → `豫剧 / 常香玉`；空路径回退源名。
     *
     * 设备实测：索引落库的 parentPath 是 href 形态（`/webdav/%E7%8E%8B%E9%9C%9E/`），
     * 所以这里还要剔掉 DAV 挂载前缀段并百分号解码，否则结果行直接显示乱码。
     * URLDecoder 会把 `+` 解成空格，而曲目文件名里的 `+` 是字面意义，先转义回 %2B。
     */
    fun breadcrumb(parentPath: String, sourceName: String, maxSegments: Int = 3): String {
        val segs = parentPath.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .dropWhile { it.equals("webdav", ignoreCase = true) || it.equals("dav", ignoreCase = true) }
            .map { decodeSegment(it) }
        if (segs.isEmpty()) return sourceName
        val shown = if (segs.size > maxSegments) listOf("…") + segs.takeLast(maxSegments) else segs
        return shown.joinToString(" / ")
    }

    /** 解不了的片段（如 `%ZZ`）原样保留：面包屑是展示层，不该因一个坏片段报错。 */
    private fun decodeSegment(raw: String): String =
        runCatching {
            java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(raw)

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
     * UI 会拿 B 的 Done 去说“结果已完整”。优先级 Running > Failed > Done。
     */
    fun aggregate(states: Collection<IndexState>): IndexState {
        running(states)?.let { return IndexState.Running(it) }
        failed(states)?.let { return IndexState.PartiallyFailed(it) }
        val done = states.filterIsInstance<IndexState.Done>()
        if (done.isNotEmpty()) return IndexState.Done(done.sumOf { it.trackCount })
        return IndexState.Idle
    }

    private fun running(s: Collection<IndexState>) = progress<IndexState.Running>(s)
    private fun failed(s: Collection<IndexState>) = progress<IndexState.PartiallyFailed>(s)

    /** 只取带进度字段的分支，类型参数限定可用状态，避免手拼三个 sumOf。 */
    private inline fun <reified T : IndexState> progress(states: Collection<IndexState>): IndexedProgress? {
        val list = states.filterIsInstance<T>().mapNotNull { p ->
            when (p) {
                is IndexState.Running -> p.p
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

    /**
     * 搜索阶段判定（2026-10-18 用户决定：继不主动建索引，进行中不出结果）。
     *
     * 顺序有意：先看“在不在跑 / 有没有索引”，再看开关与询问状态——
     * 关掉“允许建立索引”不能让已建好的搜索失效；正在跑时也不该改口成“以后再说”。
     */
    fun searchPhase(
        indexState: IndexState,
        indexedCount: Int,
        askedThisSession: Boolean,
        allowIndexing: Boolean,
        stale: Boolean,
        webdavSourceCount: Int = 1,
    ): SearchPhase {
        if (indexState is IndexState.Running) return SearchPhase.Indexing
        if (indexedCount > 0) return if (stale) SearchPhase.ReadyStale else SearchPhase.Ready
        // 没源就谈不上索引：向用户问“要不要建立索引”是错的，得先让他添加来源
        if (webdavSourceCount <= 0) return SearchPhase.NoWebdavSource
        if (!allowIndexing) return SearchPhase.Disabled
        return if (askedThisSession) SearchPhase.Deferred else SearchPhase.NotIndexed
    }

    /**
     * 提示行文案；null 表示不占行。
     *
     * 进行中带“已收录 N 个曲目”：首轮要一分多钟，光一句“请稍后”会让人以为卡死。
     */
    fun searchHint(phase: SearchPhase, indexedCount: Int): String? = when (phase) {
        SearchPhase.Indexing ->
            if (indexedCount > 0) "正在建立索引，请稍后搜索（已收录 $indexedCount 个曲目）"
            else "正在建立索引，请稍后搜索"
        SearchPhase.Deferred -> "尚未建立索引，无法搜索全部曲目"
        SearchPhase.NoWebdavSource -> "还没有可索引的网络来源"
        SearchPhase.Disabled -> "索引功能已关闭，可在设置中开启"
        SearchPhase.ReadyStale -> "索引可能已过期，点这里更新"
        else -> null
    }
}
