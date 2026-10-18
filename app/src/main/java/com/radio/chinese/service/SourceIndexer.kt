package com.radio.chinese.service

import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.IndexCacheWriter
import com.radio.chinese.domain.IndexRow
import com.radio.chinese.domain.IndexSourceSync
import com.radio.chinese.domain.model.IndexQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 索引进度快照。`pendingDirs` 是队列里还剩的目录数，不是整棵树的总数。 */
data class IndexedProgress(
    val indexedFiles: Int,
    val pendingDirs: Int,
    val skippedDirs: Int,
)

/** 索引状态。UI 侧只读，据此决定提示文案与是否显示"结果可能不全"。 */
sealed interface IndexState {
    data object Idle : IndexState
    data class Running(val p: IndexedProgress) : IndexState
    data class PausedNoWifi(val p: IndexedProgress) : IndexState
    data class PartiallyFailed(val p: IndexedProgress) : IndexState
    data class Done(val trackCount: Int) : IndexState
}

/**
 * BFS + Depth:1 分层遍历一棵 WebDAV 目录树，把每个目录的行灌进缓存表。
 *
 * 为什么不能用 WebDavClient 单例：它的 serverUrl/username/password 是可写字段，
 * listFilesCache 的 key 只有 path，而四个快捷码源的 base URL 完全相同——并发爬
 * 多源会把 B 账号凭证发往 A 主机、让同名路径跨源串数据。浏览器由调用方按源新建。
 *
 * 为什么不用 Depth: infinity：123pan 在文件多时响应巨大或超时，很多服务端也禁用它。
 */
class IndexCoordinator(
    private val browser: AudioBrowser,
    private val cache: IndexCacheWriter,
    private val sourceSync: IndexSourceSync,
    private val gate: NetworkGate,
    private val concurrency: Int = 6,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val onState: (IndexState) -> Unit = {},
) {
    companion object {
        const val DEFAULT_TTL_MS = 86_400_000L
        const val RETRY_DELAY_MS = 200L
    }

    private val root = "/"

    /**
     * 断点续爬不靠持久化队列：重新触发时按目录最旧 cachedAt 跳过新鲜目录，
     * 效果等同续爬，进程被杀也不用保存任何遍历状态。
     */
    suspend fun crawl(sourceId: Long): IndexState = coroutineScope {
        val stamps = cache.oldestByDir(sourceId)
        val pending = ArrayDeque<String>()
        val seen = HashSet<String>()
        val sem = Semaphore(concurrency)
        var files = 0
        var skipped = 0

        // 根目录恒第一：它同时是"这棵树有多大"的探测器
        pending.add(root)
        seen.add(root)

        while (pending.isNotEmpty()) {
            if (!gate.isIndexingAllowed()) {
                onState(IndexState.PausedNoWifi(IndexedProgress(files, pending.size, skipped)))
                gate.awaitAllowed()
            }
            onState(IndexState.Running(IndexedProgress(files, pending.size, skipped)))

            val batch = mutableListOf<String>()
            while (batch.size < concurrency && pending.isNotEmpty()) batch += pending.removeFirst()

            // 批次内必须并发：串行等于把两百多次 RTT 累加，实测会把 75s 拖成四分钟
            val fetched = batch.map { dir -> async { sem.withPermit { fetchWithRetry(dir) } } }.awaitAll()

            val discovered = mutableListOf<String>()
            fetched.forEach { res ->
                if (res == null) {
                    skipped++
                } else {
                    files += res.rows.count { !it.isFolder }
                    cache.replaceDir(sourceId, res.path, res.rows)
                    discovered += res.rows.filter { it.isFolder }.map { it.path }
                }
            }
            discovered.forEach { sub ->
                if (IndexQuery.shouldCrawl(stamps[sub], ttlMs, now()) && seen.add(sub)) pending.add(sub)
            }
        }

        val p = IndexedProgress(files, 0, skipped)
        if (skipped > 0) {
            onState(IndexState.PartiallyFailed(p))
            IndexState.PartiallyFailed(p)
        } else {
            // 只有整棵树爬完才写源级时间戳：部分完成写了，会让浏览路径误信"缓存已新"
            sourceSync.markSynced(sourceId, now())
            val count = cache.countTracks(sourceId)
            onState(IndexState.Done(count))
            IndexState.Done(count)
        }
    }

    /** 单目录抓取结果。空目录 rows 为空但仍算成功，不该被当成失败重试。 */
    private data class DirFetch(val path: String, val rows: List<IndexRow>)

    private suspend fun fetchWithRetry(path: String): DirFetch? {
        repeat(2) { attempt ->
            val rows = runCatching { browser.listChildren(path) }.getOrNull()?.getOrNull()
            if (rows != null) {
                return DirFetch(path, rows.map {
                    IndexRow(
                        path = it.path, parentPath = path, name = it.name,
                        size = it.size, isFolder = it.isFolder
                    )
                })
            }
            // 重试一次；仍失败计入 skipped，绝不停掉整轮索引
            if (attempt == 0) delay(RETRY_DELAY_MS)
        }
        return null
    }
}
