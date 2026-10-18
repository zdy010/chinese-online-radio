package com.radio.chinese

import androidx.media3.common.util.UnstableApi
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.repository.AudioLibraryRepository
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.IndexState
import com.radio.chinese.domain.IndexedProgress
import com.radio.chinese.domain.TrackHit
import com.radio.chinese.domain.SourceType
import com.radio.chinese.domain.browser.BrowserFactory
import com.radio.chinese.service.OperaRepeatMode
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.service.SourceIndexer
import com.radio.chinese.ui.library.AudioLibraryViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索与索引的形态分叉：车机档保持"当前目录内过滤"，全源搜索与后台索引只在手机档。
 *
 * 形态由 UI 作为布尔传进来，而不是 VM 去读 LocalUiSurface：CompositionLocal 属 UI 层，
 * 往下一层依赖就会让判定出现两套结论。
 */
@OptIn(UnstableApi::class)
class AudioLibrarySearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @After fun teardown() = Dispatchers.resetMain()

    private val webdavSource = AudioSource(1, "豫剧", SourceType.WEBDAV, "https://webdav.123pan.cn/webdav")

    private class Env(
        val repo: AudioLibraryRepository = mockk(relaxed = true),
        val indexer: SourceIndexer = mockk(relaxed = true),
        val prefs: RadioPreferences = mockk(relaxed = true),
    )

    /**
     * 公共脚手架。indexEnabled / states 必须能参数化：这里无条件 stub 会把
     * 用例自己先设的 stub 盖掉，造成“开关关闭却仍触发索引”的假失败。
     */
    private fun build(
        env: Env,
        indexEnabled: Boolean = true,
        states: MutableStateFlow<Map<Long, IndexState>> = MutableStateFlow(emptyMap()),
    ): AudioLibraryViewModel {
        Dispatchers.setMain(dispatcher)
        val pm = mockk<PlayerManager>(relaxed = true)
        // VM 的 init 会 collect 这一整批状态流，逐个 stub 清楚；混进一个 listOf 会把类型推成 Any?
        every { pm.isOperaMode } returns MutableStateFlow(false)
        every { pm.isPlaying } returns MutableStateFlow(false)
        every { pm.operaPosition } returns MutableStateFlow(0L)
        every { pm.operaDuration } returns MutableStateFlow(0L)
        every { pm.operaBitrate } returns MutableStateFlow(0)
        every { pm.operaFile } returns MutableStateFlow<com.radio.chinese.domain.model.OperaAudioFile?>(null)
        every { pm.error } returns MutableStateFlow<String?>(null)
        every { pm.operaRepeatMode } returns MutableStateFlow(OperaRepeatMode.ALL)
        every { env.repo.allSources } returns MutableStateFlow(listOf(webdavSource))
        every { env.repo.favorites } returns MutableStateFlow(emptyList())
        every { env.repo.recentPlays } returns MutableStateFlow(emptyList())
        every { env.prefs.webdavIndexEnabled } returns MutableStateFlow(indexEnabled)
        every { env.indexer.states } returns states
        return AudioLibraryViewModel(
            context = mockk(relaxed = true), repository = env.repo,
            playerManager = pm, browserFactory = mockk<BrowserFactory>(relaxed = true),
            indexer = env.indexer, preferences = env.prefs,
        )
    }

    @Test fun `车机档搜索不做全源查询`() = runTest(dispatcher) {
        val env = Env()
        val vm = build(env)
        vm.updateSearchQuery("花木兰", carSurface = true)
        advanceUntilIdle()
        coVerify(exactly = 0) { env.repo.searchTracks(any(), any(), any()) }
        assertEquals("花木兰", vm.uiState.value.searchQuery)
        assertEquals(emptyList<Any>(), vm.uiState.value.searchResults)
    }

    @Test fun `手机档查询走全源并按 name+size 归并`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.searchTracks(null, "花木兰", 200) } returns listOf(
            TrackHit(1, "花木兰", "/豫剧/花木兰.mp3", "/豫剧", 8_000_000),
            TrackHit(2, "花木兰", "/常香玉/花木兰.mp3", "/常香玉", 8_000_000),
        )
        val vm = build(env)
        vm.updateSearchQuery("花木兰", carSurface = false)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.searchResults.size)
        assertEquals(listOf(1L, 2L), vm.uiState.value.searchResults[0].sources.map { it.sourceId })
    }

    @Test fun `车机档永不触发索引`() = runTest(dispatcher) {
        val env = Env()
        val vm = build(env)
        vm.startIndexingIfNeeded(carSurface = true)
        advanceUntilIdle()
        verify(exactly = 0) { env.indexer.start(any()) }
    }

    @Test fun `手机档且开关打开才对 WebDAV 源触发索引`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.getSources() } returns listOf(webdavSource)
        val vm = build(env)
        vm.startIndexingIfNeeded(carSurface = false)
        advanceUntilIdle()
        verify(exactly = 1) { env.indexer.start(webdavSource) }
    }

    @Test fun `索引开关关闭时不触发`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.getSources() } returns listOf(webdavSource)
        val vm = build(env, indexEnabled = false)
        vm.startIndexingIfNeeded(carSurface = false)
        advanceUntilIdle()
        verify(exactly = 0) { env.indexer.start(any()) }
    }

    @Test fun `索引状态跨源聚合后进 uiState`() = runTest(dispatcher) {
        val states = MutableStateFlow(emptyMap<Long, IndexState>())
        val env = Env()
        val vm = build(env, states = states)
        states.value = mapOf(
            1L to IndexState.Running(IndexedProgress(indexedFiles = 300, pendingDirs = 4, skippedDirs = 0)),
            2L to IndexState.Running(IndexedProgress(indexedFiles = 100, pendingDirs = 1, skippedDirs = 2)),
        )
        advanceUntilIdle()
        val s = vm.uiState.value.indexState as IndexState.Running
        assertEquals(400, s.p.indexedFiles)
        assertEquals(5, s.p.pendingDirs)
        assertEquals(2, s.p.skippedDirs)
    }
}
