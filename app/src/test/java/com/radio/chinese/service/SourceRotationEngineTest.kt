package com.radio.chinese.service

import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.model.StationSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SourceRotationEngine 单测：验证多源轮转、失败推进、成功记录、手动切换逻辑。
 */
class SourceRotationEngineTest {

    // 引擎内部用 scope.launch 推进分数写入与起播，Unconfined 让这两步在调用点就地跑完，
    // 断言才能直接看 recorded 的顺序
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: StationRepository
    private lateinit var scope: CoroutineScope
    private lateinit var engine: SourceRotationEngine
    private lateinit var recorded: MutableList<String>
    private var allExhausted = false

    private val callbacks = object : SourceRotationEngine.Callbacks {
        override fun onRequestPlay(source: StationSource, index: Int, total: Int) {
            recorded.add("play:${source.url}@${index + 1}/$total")
        }
        override fun onAllSourcesExhausted() {
            allExhausted = true
            recorded.add("exhausted")
        }
        override fun onScoresUpdated(scores: List<Pair<StationSource, Float>>) {
            recorded.add("scores:${scores.size}")
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        scope = CoroutineScope(testDispatcher)
        engine = SourceRotationEngine(repository, scope)
        recorded = mutableListOf()
        allExhausted = false
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialize loads sorted sources and calls back scores`() = runTest {
        val sources = listOf(
            StationSource(url = "http://a.com/stream", label = "A"),
            StationSource(url = "http://b.com/stream", label = "B"),
        )
        coEvery { repository.getSortedSources("st1") } returns sources
        coEvery { repository.getSourcesWithScore("st1") } returns sources.map { it to 1.0f }

        engine.initialize("st1", callbacks)

        assertTrue(recorded.any { it == "scores:2" })
    }

    @Test
    fun `startRotation plays first source`() = runTest {
        val sources = listOf(
            StationSource(url = "http://a.com/stream", label = "A"),
            StationSource(url = "http://b.com/stream", label = "B"),
        )
        coEvery { repository.getSortedSources("st1") } returns sources
        coEvery { repository.getSourcesWithScore("st1") } returns sources.map { it to 1.0f }

        engine.initialize("st1", callbacks)
        engine.startRotation()

        assertEquals("play:http://a.com/stream@1/2", recorded.last())
    }

    @Test
    fun `onCurrentFailed advances to next`() = runTest {
        val sources = listOf(
            StationSource(url = "http://a.com/stream", label = "A"),
            StationSource(url = "http://b.com/stream", label = "B"),
        )
        coEvery { repository.getSortedSources("st1") } returns sources
        coEvery { repository.getSourcesWithScore("st1") } returns sources.map { it to 1.0f }

        engine.initialize("st1", callbacks)
        engine.startRotation()
        engine.onCurrentFailed("st1")

        assertEquals("play:http://b.com/stream@2/2", recorded.last())
    }

    @Test
    fun `all sources exhausted after last failure`() = runTest {
        val sources = listOf(
            StationSource(url = "http://a.com/stream", label = "A"),
        )
        coEvery { repository.getSortedSources("st1") } returns sources
        coEvery { repository.getSourcesWithScore("st1") } returns sources.map { it to 1.0f }

        engine.initialize("st1", callbacks)
        engine.startRotation()
        engine.onCurrentFailed("st1")

        assertTrue(allExhausted)
        assertEquals("exhausted", recorded.last())
    }

    @Test
    fun `switchTo jumps to specified url`() = runTest {
        val sources = listOf(
            StationSource(url = "http://a.com/stream", label = "A"),
            StationSource(url = "http://b.com/stream", label = "B"),
            StationSource(url = "http://c.com/stream", label = "C"),
        )
        coEvery { repository.getSortedSources("st1") } returns sources
        coEvery { repository.getSourcesWithScore("st1") } returns sources.map { it to 1.0f }

        engine.initialize("st1", callbacks)
        engine.switchTo("st1", "http://c.com/stream")

        assertEquals("play:http://c.com/stream@3/3", recorded.last())
    }

    @Test
    fun `switchTo writes the score before asking to play`() = runTest {
        val sources = listOf(
            StationSource(url = "http://a.com/stream", label = "A"),
            StationSource(url = "http://b.com/stream", label = "B"),
        )
        coEvery { repository.getSortedSources("st1") } returns sources
        coEvery { repository.getSourcesWithScore("st1") } returns sources.map { it to 1.0f }
        coEvery { repository.setSourceScore("st1", "http://b.com/stream", 1.0f) } answers {
            recorded.add("score")
            Unit
        }

        engine.initialize("st1", callbacks)
        engine.switchTo("st1", "http://b.com/stream")

        // 先落分数再开播：反序会让秒失败时的 recordFailure 被 setScore 盖掉，
        // 下次这个死源又被排到第一个
        assertEquals(listOf("score", "play:http://b.com/stream@2/2"), recorded.takeLast(2))
    }
}
