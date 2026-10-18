package com.radio.chinese

import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.AudioTrack
import com.radio.chinese.domain.BrowserProvider
import com.radio.chinese.domain.IndexCacheWriter
import com.radio.chinese.domain.IndexRow
import com.radio.chinese.domain.IndexSourceSync
import com.radio.chinese.domain.SourceType
import com.radio.chinese.service.IndexCoordinator
import com.radio.chinese.service.IndexState
import com.radio.chinese.service.NetworkGate
import com.radio.chinese.service.SourceIndexer
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遍历内核的行为钉死。浏览器与缓存都用假件，虚拟时间推进，
 * 不依赖任何 Android 框架类（本项目 JVM 下它们是 stub，mockk 也演不了）。
 */
class SourceIndexerTest {

    private class FakeCache : IndexCacheWriter {
        val dirs = mutableMapOf<Long, MutableList<IndexRow>>()
        val stamps = mutableMapOf<Pair<Long, String>, Long>()

        override suspend fun oldestByDir(sourceId: Long): Map<String, Long> =
            dirs[sourceId].orEmpty().groupBy { it.parentPath }
                .mapValues { (path, _) -> stamps[sourceId to path] ?: 0L }

        /** 手工预置一个"已爬且新鲜"的目录，供续爬用例使用；缺失 stamp 时按 0 处理。 */
        fun seedFresh(sourceId: Long, path: String, at: Long) {
            dirs.getOrPut(sourceId) { mutableListOf() }
                .add(IndexRow("$path/x.mp3", path, "x.mp3", 1, false))
            stamps[sourceId to path] = at
        }

        override suspend fun replaceDir(sourceId: Long, path: String, rows: List<IndexRow>) {
            dirs.getOrPut(sourceId) { mutableListOf() }.removeAll { it.parentPath == path }
            dirs[sourceId]!!.addAll(rows)
            stamps[sourceId to path] = System.currentTimeMillis()
        }

        override suspend fun countTracks(sourceId: Long): Int =
            dirs[sourceId].orEmpty().count { !it.isFolder }
    }

    private class FakeBrowser(
        val tree: Map<String, List<AudioTrack>>,
        val failOnce: MutableSet<String> = mutableSetOf(),
        val alwaysFail: Set<String> = emptySet(),
        val hangOn: String? = null,
    ) : AudioBrowser {
        val requested = mutableListOf<String>()
        var inFlight = 0
        var maxInFlight = 0

        override suspend fun listChildren(path: String): Result<List<AudioTrack>> {
            requested += path
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            kotlinx.coroutines.delay(10)   // 真挂起点：让同批次协程真的重叠，否则并发测不出来
            inFlight--
            if (path == hangOn) kotlinx.coroutines.CompletableDeferred<Unit>().await()
            if (path in alwaysFail || (path in failOnce && failOnce.remove(path)))
                return Result.failure(RuntimeException("PROPFIND $path 失败"))
            return Result.success(tree[path] ?: emptyList())
        }

        override fun resolveUri(track: AudioTrack) = track.path
        override suspend fun testConnection() = Result.success(Unit)
    }

    /** 只返回固定浏览器的提供口假件，避免真去建 WebDavClient。 */
    private class FixedProvider(val browser: AudioBrowser) : BrowserProvider {
        override fun createForIndexing(source: AudioSource): AudioBrowser? =
            if (source.type == SourceType.WEBDAV) browser else null
    }

    private class RecordingSync : IndexSourceSync {
        var syncWrites = 0
        override suspend fun markSynced(sourceId: Long, at: Long) { syncWrites++ }
    }

    private object AllowAllGate : NetworkGate {
        override fun isIndexingAllowed() = true
        override suspend fun awaitAllowed() {}
    }

    private fun dir(name: String) = AudioTrack(id = name, name = name, path = name, isFolder = true)
    private fun file(name: String, parent: String) = AudioTrack(id = name, name = name, path = name, parentPath = parent)

    private suspend fun crawl(
        browser: AudioBrowser,
        cache: FakeCache,
        sync: RecordingSync = RecordingSync(),
        gate: NetworkGate = AllowAllGate,
    ): IndexState = IndexCoordinator(browser, cache, sync, gate, concurrency = 6).crawl(1L)

    @Test fun `两层树全部目录都被请求`() = runTest {
        val cache = FakeCache()
        val browser = FakeBrowser(mapOf(
            "/" to listOf(dir("/常香玉"), file("花木兰.mp3", "/")),
            "/常香玉" to listOf(file("七仙女.mp3", "/常香玉")),
        ))
        val state = crawl(browser, cache)
        assertEquals(IndexState.Done(2), state)
        assertEquals(listOf("/", "/常香玉"), browser.requested)
        assertEquals(2, cache.countTracks(1L))
    }

    @Test fun `并发上限为 6 不越界且真的在并发`() = runTest {
        val tree = LinkedHashMap<String, List<AudioTrack>>()
        tree["/"] = (1..20).map { dir("/d$it") } + file("root.mp3", "/")
        (1..20).forEach { tree["/d$it"] = listOf(file("f$it.mp3", "/d$it")) }
        val browser = FakeBrowser(tree)
        crawl(browser, FakeCache())
        // 下界同样要断：只写上界的话，串行实现 maxInFlight=1 也能过，是假绿
        assertTrue("并发度应在 2..6，实测 maxInFlight=${browser.maxInFlight}", browser.maxInFlight in 2..6)
    }

    @Test fun `瞬时失败重试一次后成功`() = runTest {
        val cache = FakeCache()
        val browser = FakeBrowser(
            mapOf("/" to listOf(dir("/b")), "/b" to listOf(file("c.mp3", "/b"))),
            failOnce = mutableSetOf("/b"),
        )
        val state = crawl(browser, cache)
        assertEquals(2, browser.requested.count { it == "/b" })
        assertEquals(IndexState.Done(1), state)
    }

    @Test fun `永久失败的目录计入 skipped 且不中断`() = runTest {
        val cache = FakeCache()
        val browser = FakeBrowser(
            mapOf("/" to listOf(dir("/坏目录"), file("ok.mp3", "/"))),
            alwaysFail = setOf("/坏目录"),
        )
        val state = crawl(browser, cache)
        assertTrue("state=$state", state is IndexState.PartiallyFailed)
        assertEquals(1, (state as IndexState.PartiallyFailed).p.skippedDirs)
        assertEquals(1, cache.countTracks(1L))
    }

    @Test fun `全部爬完才写源级同步时间戳`() = runTest {
        val sync = RecordingSync()
        val browser = FakeBrowser(mapOf("/" to listOf(file("a.mp3", "/"))))
        crawl(browser, FakeCache(), sync)
        assertEquals(1, sync.syncWrites)
    }

    @Test fun `有跳过目录时不写源级同步时间戳`() = runTest {
        val sync = RecordingSync()
        val browser = FakeBrowser(
            mapOf("/" to listOf(dir("/坏"), file("a.mp3", "/"))), alwaysFail = setOf("/坏")
        )
        crawl(browser, FakeCache(), sync)
        assertEquals(0, sync.syncWrites)
    }

    @Test fun `已新鲜目录不重复请求`() = runTest {
        val cache = FakeCache()
        cache.seedFresh(1L, "/常香玉", System.currentTimeMillis())
        val browser = FakeBrowser(mapOf("/" to listOf(dir("/常香玉"), file("根.mp3", "/"))))
        crawl(browser, cache)
        assertEquals("根目录必爬，新鲜子目录应跳过", listOf("/"), browser.requested)
    }

    // ── 门面：生命周期与并发触发 ──

    private val webdavSource = AudioSource(1, "豫剧", SourceType.WEBDAV, "http://x", "u", "p")

    @Test fun `同一源重复触发只跑一个任务`() = runTest {
        val browser = FakeBrowser(mapOf("/" to listOf(file("a.mp3", "/"))))
        val indexer = SourceIndexer(FixedProvider(browser), FakeCache(), RecordingSync(), AllowAllGate, this)
        indexer.start(webdavSource)
        indexer.start(webdavSource)
        advanceUntilIdle()
        assertEquals("重复 start 必须并入在跑的任务，不能叠两份并发", 1, browser.requested.count { it == "/" })
    }

    @Test fun `非 WebDAV 源不建任务也不上报状态`() = runTest {
        val indexer = SourceIndexer(
            FixedProvider(FakeBrowser(emptyMap())), FakeCache(), RecordingSync(), AllowAllGate, this
        )
        indexer.start(AudioSource(2, "本地", SourceType.LOCAL, ""))
        advanceUntilIdle()
        assertEquals(0, indexer.runningCount())
        assertEquals(emptyMap<Long, IndexState>(), indexer.states.value)
    }

    @Test fun `取消只停任务，不清已写入的索引`() = runTest {
        val cache = FakeCache()
        val browser = FakeBrowser(
            mapOf("/" to listOf(dir("/常香玉")), "/常香玉" to listOf(file("七仙女.mp3", "/常香玉"))),
            hangOn = "/常香玉",
        )
        val indexer = SourceIndexer(FixedProvider(browser), cache, RecordingSync(), AllowAllGate, this)
        indexer.start(webdavSource)
        advanceUntilIdle()                       // 根目录已落库，子目录挂在网上
        assertTrue("根目录应先已写入", cache.dirs[1L].orEmpty().any { it.parentPath == "/" })

        indexer.cancel(1L)

        assertEquals(IndexState.Idle, indexer.states.value[1L])
        assertEquals(0, indexer.runningCount())
        assertTrue("取消不得清空已索引内容", cache.dirs[1L].orEmpty().any { it.parentPath == "/" })
    }
}
