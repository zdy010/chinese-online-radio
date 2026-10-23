package com.radio.chinese

import com.radio.chinese.data.remote.EpgFetchResult
import com.radio.chinese.data.remote.EpgRemoteSource
import com.radio.chinese.data.repository.EpgRepository
import com.radio.chinese.domain.epg.EpgStore
import com.radio.chinese.domain.model.EpgProgram
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库层的降级分支。这是整块功能里最容易写错的地方：
 * 「拉取失败」与「拉取成功但当天没节目」必须给出两种完全不同的结果，
 * 混成一种就会要么把缓存清空、要么把空结果的台变成每次进页面都重拉。
 */
class EpgRepositoryTest {

    private val day = "2026-10-23"
    private val now = 1_790_000_000_000L
    private val hour = 3_600_000L

    private fun p(start: Int, title: String, date: String = day) =
        EpgProgram(stationId = "cnr1", date = date, startMin = start, endMin = start + 30, title = title)

    private fun repo(
        mapping: Map<String, String> = mapOf("cnr1" to "639"),
        store: FakeStore = FakeStore(),
        remote: FakeRemote = FakeRemote(),
    ) = Triple(
        EpgRepository(broadcastIds = mapping, store = store, remote = remote),
        store,
        remote,
    )

    // ---- 台是否接入 ----

    @Test fun `映射表外的台直接返回 null`() = runBlocking {
        val (repository, store, remote) = repo(mapping = emptyMap())
        assertNull(repository.load("unknown", day, now))
        assertTrue(store.stamps.isEmpty())
        assertTrue(remote.calls.isEmpty())
    }

    @Test fun `映射表内的台 hasEpg 为真`() {
        val (repository, _, _) = repo()
        assertTrue(repository.hasEpg("cnr1"))
        assertFalse(repository.hasEpg("unknown"))
    }

    // ---- 缓存 ----

    @Test fun `六小时内的缓存直接命中不发请求`() = runBlocking {
        val store = FakeStore().apply {
            replaceDay("cnr1", day, listOf(p(0, "早间节目")), now - 5 * hour)
        }
        val (repository, _, remote) = repo(store = store)

        val snapshot = repository.load("cnr1", day, now)

        assertEquals(listOf("早间节目"), snapshot!!.programs.map { it.title })
        assertFalse(snapshot.refreshFailed)
        assertTrue("不该打接口", remote.calls.isEmpty())
    }

    @Test fun `超过六小时的缓存会重新拉取`() = runBlocking {
        val store = FakeStore().apply {
            replaceDay("cnr1", day, listOf(p(0, "旧节目")), now - 7 * hour)
        }
        val remote = FakeRemote(EpgFetchResult.Success(listOf(p(0, "新节目"))))
        val (repository, _, _) = repo(store = store, remote = remote)

        val snapshot = repository.load("cnr1", day, now)

        assertEquals(listOf("新节目"), snapshot!!.programs.map { it.title })
        assertEquals(1, remote.calls.size)
        assertEquals("639", remote.calls.single().broadcastId)
    }

    @Test fun `昨天的缓存不会当成今天的`() = runBlocking {
        val store = FakeStore().apply {
            replaceDay("cnr1", "2026-10-22", listOf(p(0, "昨天节目", date = "2026-10-22")), now - hour)
        }
        val remote = FakeRemote(EpgFetchResult.Success(listOf(p(60, "今天节目"))))
        val (repository, _, _) = repo(store = store, remote = remote)

        val snapshot = repository.load("cnr1", day, now)

        assertEquals(listOf("今天节目"), snapshot!!.programs.map { it.title })
        assertEquals(1, remote.calls.size)
    }

    @Test fun `成功但当天没有节目时清空缓存并记时间戳`() = runBlocking {
        val store = FakeStore().apply {
            replaceDay("cnr1", day, listOf(p(0, "已经撤掉的节目")), now - 7 * hour)
        }
        val remote = FakeRemote(EpgFetchResult.Success(emptyList()))
        val (repository, _, _) = repo(store = store, remote = remote)

        val first = repository.load("cnr1", day, now)
        assertEquals(emptyList<Any>(), first!!.programs)
        assertTrue(store.programs("cnr1", day).isEmpty())
        assertEquals(now, store.fetchedAt("cnr1", day))

        // 第二次不该再拉：空结果也要算「拉过了」
        repository.load("cnr1", day, now + hour)
        assertEquals(1, remote.calls.size)
    }

    // ---- 失败降级 ----

    @Test fun `请求失败时保留旧缓存并标记为降级`() = runBlocking {
        val store = FakeStore().apply {
            replaceDay("cnr1", day, listOf(p(0, "旧节目")), now - 7 * hour)
        }
        val (repository, _, _) = repo(store = store, remote = FakeRemote(EpgFetchResult.Failure))

        val snapshot = repository.load("cnr1", day, now)

        assertEquals(listOf("旧节目"), snapshot!!.programs.map { it.title })
        assertTrue("要标出来，UI 才好显示「数据截至」", snapshot.refreshFailed)
        assertEquals(now - 7 * hour, store.fetchedAt("cnr1", day))
    }

    @Test fun `请求失败且没有缓存时返回空`() = runBlocking {
        val (repository, _, _) = repo(remote = FakeRemote(EpgFetchResult.Failure))

        val snapshot = repository.load("cnr1", day, now)

        assertEquals(emptyList<Any>(), snapshot!!.programs)
        assertTrue(snapshot.refreshFailed)
    }

    @Test fun `失败后三十秒内不重复请求`() = runBlocking {
        val remote = FakeRemote(EpgFetchResult.Failure)
        val (repository, _, _) = repo(remote = remote)

        repository.load("cnr1", day, now)
        repository.load("cnr1", day, now + 10_000L)

        assertEquals("接口刚失败就别追打", 1, remote.calls.size)

        // 过了节流窗口要允许重试，否则一次网络抖动会让这台一整天都不再刷新
        repository.load("cnr1", day, now + 40_000L)
        assertEquals(2, remote.calls.size)
    }

    // ---- 批量 ----

    @Test fun `批量加载时单个台失败不拖累其他台`() = runBlocking {
        val mapping = mapOf("cnr1" to "639", "cnr2" to "640")
        val store = FakeStore().apply {
            replaceDay("cnr1", day, listOf(p(0, "旧一")), now - 7 * hour)
        }
        val remote = FakeRemote(
            default = EpgFetchResult.Failure,
            byStation = mapOf("cnr2" to EpgFetchResult.Success(listOf(p(0, "二套").copy(stationId = "cnr2")))),
        )
        val (repository, _, _) = repo(mapping = mapping, store = store, remote = remote)

        val result = repository.loadAll(listOf("cnr1", "cnr2", "unknown"), day, now)

        assertEquals(2, result.size)
        assertEquals(listOf("旧一"), result.getValue("cnr1").programs.map { it.title })
        assertEquals(listOf("二套"), result.getValue("cnr2").programs.map { it.title })
    }

    @Test fun `批量加载返回的节目单按开始时间排序`() = runBlocking {
        val remote = FakeRemote(
            EpgFetchResult.Success(listOf(p(360, "下午"), p(0, "凌晨"), p(120, "清晨"))),
        )
        val (repository, _, _) = repo(remote = remote)

        val result = repository.loadAll(listOf("cnr1"), day, now)

        assertEquals(listOf("凌晨", "清晨", "下午"), result.getValue("cnr1").programs.map { it.title })
    }

    // ---- 搜索 ----

    @Test fun `搜索只读当天已缓存的节目`() = runBlocking {
        val store = FakeStore().apply {
            replaceDay("cnr1", day, listOf(p(0, "新闻和报纸摘要")), now)
            replaceDay("cnr2", day, listOf(p(0, "新闻纵横").copy(stationId = "cnr2")), now)
            replaceDay("cnr1", "2026-10-22", listOf(p(0, "新闻联播", date = "2026-10-22")), now)
        }
        val (repository, _, _) = repo(store = store)

        val hits = repository.cachedPrograms(day)

        assertEquals(setOf("新闻和报纸摘要", "新闻纵横"), hits.map { it.title }.toSet())
    }

    // ---- 清理 ----

    @Test fun `清理只保留今天和昨天`() = runBlocking {
        val store = FakeStore().apply {
            replaceDay("cnr1", "2026-10-21", listOf(p(0, "前天", date = "2026-10-21")), now)
            replaceDay("cnr1", "2026-10-22", listOf(p(0, "昨天", date = "2026-10-22")), now)
            replaceDay("cnr1", day, listOf(p(0, "今天")), now)
        }
        val (repository, _, _) = repo(store = store)

        repository.cleanup(day)

        assertTrue(store.programsOn("2026-10-21").isEmpty())
        assertEquals(1, store.programsOn("2026-10-22").size)
        assertEquals(1, store.programsOn(day).size)
    }

    // ---- 假件 ----

    private class FakeStore : EpgStore {
        private val programRows = mutableMapOf<Pair<String, String>, MutableList<EpgProgram>>()
        val stamps = mutableMapOf<Pair<String, String>, Long>()

        override suspend fun programs(stationId: String, date: String): List<EpgProgram> =
            programRows[stationId to date].orEmpty().sortedBy { it.startMin }

        override suspend fun programsOn(date: String): List<EpgProgram> =
            programRows.filterKeys { it.second == date }.values.flatten()

        override suspend fun fetchedAt(stationId: String, date: String): Long? =
            stamps[stationId to date]

        override suspend fun replaceDay(
            stationId: String,
            date: String,
            programs: List<EpgProgram>,
            fetchedAt: Long,
        ) {
            programRows[stationId to date] = programs.sortedBy { it.startMin }.toMutableList()
            stamps[stationId to date] = fetchedAt
        }

        override suspend fun deleteBefore(date: String) {
            programRows.keys.removeAll { it.second < date }
            stamps.keys.removeAll { it.second < date }
        }
    }

    private class FakeRemote(
        private val default: EpgFetchResult = EpgFetchResult.Success(emptyList()),
        private val byStation: Map<String, EpgFetchResult> = emptyMap(),
    ) : EpgRemoteSource {
        data class Call(val stationId: String, val broadcastId: String, val date: String)

        val calls = mutableListOf<Call>()

        override suspend fun fetch(stationId: String, broadcastId: String, date: String): EpgFetchResult {
            calls += Call(stationId, broadcastId, date)
            return byStation[stationId] ?: default
        }
    }
}
