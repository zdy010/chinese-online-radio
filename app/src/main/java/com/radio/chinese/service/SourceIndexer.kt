package com.radio.chinese.service

import com.radio.chinese.di.ApplicationScope
import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.BrowserProvider
import com.radio.chinese.domain.IndexCacheWriter
import com.radio.chinese.domain.IndexRow
import com.radio.chinese.domain.IndexSourceSync
import com.radio.chinese.domain.IndexState
import com.radio.chinese.domain.IndexedProgress
import com.radio.chinese.domain.model.IndexQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

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

/**
 * 索引生命周期门面：每源最多一个在跑任务、状态可订阅、取消不清数据。
 *
 * 不读 UiSurface（CompositionLocal 属 UI 层）：是否调用 start 由手机档 UI 决定，
 * 车机档永不调用，因此车机档一份 PROPFIND 都不会发。
 *
 * 调用时机由用户确认驱动（2026-10-18 决策）：不自动起索引，只在用户
 * 在“是否建立索引”确认框里点了建立之后才 start。网络类型不再是前提条件。
 */
@Singleton
class SourceIndexer @Inject constructor(
    private val browserProvider: BrowserProvider,
    private val cacheWriter: IndexCacheWriter,
    private val sourceSync: IndexSourceSync,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<Long, IndexState>>(emptyMap())
    val states: StateFlow<Map<Long, IndexState>> = _states.asStateFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()

    fun runningCount(): Int = jobs.values.count { it.isActive }

    fun start(source: AudioSource) {
        // 非 WebDAV 静默跳过：建索引对它没有意义，也不该弹错
        val browser = browserProvider.createForIndexing(source) ?: return
        if (jobs[source.id]?.isActive == true) return   // 重复触发合并，不叠并发
        jobs[source.id] = scope.launch {
            _states.update { it + (source.id to IndexState.Running(IndexedProgress(0, 1, 0))) }
            val st = IndexCoordinator(browser, cacheWriter, sourceSync).crawl(source.id)
            _states.update { it + (source.id to st) }
        }
    }

    /** 取消只停任务：已写入的行留着，下次 start 按新鲜度续爬。 */
    fun cancel(sourceId: Long) {
        jobs.remove(sourceId)?.cancel()
        _states.update { it + (sourceId to IndexState.Idle) }
    }
}
