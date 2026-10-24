package com.radio.chinese

import androidx.media3.common.util.UnstableApi
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.repository.AudioLibraryRepository
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.AudioTrack
import com.radio.chinese.domain.IndexState
import com.radio.chinese.domain.IndexedProgress
import com.radio.chinese.domain.TrackHit
import com.radio.chinese.domain.SearchPhase
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** 供用例在 build 之后改动源列表（重现“加完源回到搜索页”路径）。 */
    private lateinit var sourcesFlow: MutableStateFlow<List<AudioSource>>

    /**
     * 公共脚手架。indexEnabled / states 必须能参数化：这里无条件 stub 会把
     * 用例自己先设的 stub 盖掉，造成“开关关闭却仍触发索引”的假失败。
     */
    private fun build(
        env: Env,
        indexEnabled: Boolean = true,
        states: MutableStateFlow<Map<Long, IndexState>> = MutableStateFlow(emptyMap()),
        sources: List<AudioSource> = listOf(webdavSource),
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
        sourcesFlow = MutableStateFlow(sources)
        every { env.repo.allSources } returns sourcesFlow
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

    /** 2026-10-24 对齐：车机档与手机档同走跨源搜索，不再只当前目录过滤（用户实测问题 1）。 */
    @Test fun `车机档也走全源查询（对齐手机端）`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.indexedTrackCount() } returns 3469
        coEvery { env.repo.searchTracks(null, "花木兰", 200) } returns listOf(
            TrackHit(1, "花木兰", "/豫剧/花木兰.mp3", "/豫剧", 8_000_000),
            TrackHit(2, "花木兰", "/常香玉/花木兰.mp3", "/常香玉", 8_000_000),
        )
        val vm = build(env)
        vm.updateSearchQuery("花木兰", carSurface = true)
        advanceUntilIdle()
        assertEquals("花木兰", vm.uiState.value.searchQuery)
        assertEquals(1, vm.uiState.value.searchResults.size)
    }

    @Test fun `手机档查询走全源并按 name+size 归并`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.indexedTrackCount() } returns 3469
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

    // ── 不主动建索引：弹确认 → 确认才起爬（2026-10-18 用户决定）──

    @Test fun `从未建立索引时搜索不自动起爬而是请求确认`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.indexedTrackCount() } returns 0
        val vm = build(env)
        vm.updateSearchQuery("花木兰", carSurface = false)
        advanceUntilIdle()
        verify(exactly = 0) { env.indexer.start(any()) }
        coVerify(exactly = 0) { env.repo.searchTracks(any(), any(), any()) }
        assertEquals(true, vm.uiState.value.askIndexConfirm)
        assertEquals(SearchPhase.NotIndexed, vm.uiState.value.searchPhase)
    }

    @Test fun `用户确认后才开始建立索引`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.indexedTrackCount() } returns 0
        coEvery { env.repo.webdavSources() } returns listOf(webdavSource)
        val vm = build(env)
        vm.updateSearchQuery("花木兰", carSurface = false)
        advanceUntilIdle()
        vm.confirmIndexing()
        advanceUntilIdle()
        verify(exactly = 1) { env.indexer.start(webdavSource) }
        assertEquals(false, vm.uiState.value.askIndexConfirm)
    }

    @Test fun `以后再说后本次会话不再询问但保留手动入口`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.indexedTrackCount() } returns 0
        val vm = build(env)
        vm.updateSearchQuery("花木兰", carSurface = false)
        advanceUntilIdle()
        vm.declineIndexing()
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.askIndexConfirm)
        assertEquals(SearchPhase.Deferred, vm.uiState.value.searchPhase)
        verify(exactly = 0) { env.indexer.start(any()) }
    }

    /**
     * 用户实测问题 1：音频库「本地」页页签的搜索框应当只过滤本地曲目，
     * 不能被跨源搜索顶掉。跨源仅在「网络」页页签生效。
     */
    @Test fun `非网络页签搜索不做全源查询也不弹确认`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.indexedTrackCount() } returns 0
        val vm = build(env)
        vm.updateSearchQuery("花木兰", carSurface = false, networkTab = false)
        advanceUntilIdle()
        coVerify(exactly = 0) { env.repo.searchTracks(any(), any(), any()) }
        assertEquals(false, vm.uiState.value.askIndexConfirm)
        assertEquals(emptyList<Any>(), vm.uiState.value.searchResults)
    }

    /** 用户实测问题 3：没有网络源时不能问“现在建立索引？”。 */
    @Test fun `无网络源时不弹建立索引确认`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.indexedTrackCount() } returns 0
        val vm = build(env, sources = emptyList())
        vm.updateSearchQuery("花木兰", carSurface = false, networkTab = true)
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.askIndexConfirm)
        assertEquals(SearchPhase.NoWebdavSource, vm.uiState.value.searchPhase)
        verify(exactly = 0) { env.indexer.start(any()) }
    }

    /**
     * 用户实测回归：加完网络源回到搜索页，搜索框里的字没变，阶段却永远停在
     * “还没有可索引的网络来源”——看着就是搜索坏了。源列表变化必须重算阶段。
     */
    @Test fun `新增网络源后无需重输关键字即重算阶段`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.indexedTrackCount() } returns 0
        val vm = build(env, sources = emptyList())
        vm.updateSearchQuery("a", carSurface = false, networkTab = true)
        advanceUntilIdle()
        assertEquals(SearchPhase.NoWebdavSource, vm.uiState.value.searchPhase)

        sourcesFlow.value = listOf(webdavSource)          // 用户刚保存完一个源
        advanceUntilIdle()

        assertEquals("加完源应立刻进入待确认态，而不是等用户重输", SearchPhase.NotIndexed, vm.uiState.value.searchPhase)
        assertEquals(true, vm.uiState.value.askIndexConfirm)
    }

    @Test fun `索引完成后自动重跑搜索，不需用户重输关键字`() = runTest(dispatcher) {
        val states = MutableStateFlow<Map<Long, IndexState>>(
            mapOf(1L to IndexState.Running(IndexedProgress(900, 12, 0)))
        )
        val env = Env()
        coEvery { env.repo.indexedTrackCount() } returns 0 andThen 3469
        coEvery { env.repo.searchTracks(null, "花木兰", 200) } returns listOf(
            TrackHit(1, "花木兰", "/a.mp3", "/", 1)
        )
        val vm = build(env, states = states)
        vm.updateSearchQuery("花木兰", carSurface = false)
        advanceUntilIdle()
        assertEquals(SearchPhase.Indexing, vm.uiState.value.searchPhase)
        assertEquals(0, vm.uiState.value.searchResults.size)
        coVerify(exactly = 0) { env.repo.searchTracks(any(), any(), any()) }

        states.value = mapOf(1L to IndexState.Done(3469))
        advanceUntilIdle()
        assertEquals(SearchPhase.Ready, vm.uiState.value.searchPhase)
        assertEquals(1, vm.uiState.value.searchResults.size)
    }

    @Test fun `开关关闭时不询问也不起爬`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.indexedTrackCount() } returns 0
        // 必须用 build 的参数：在 build 之前手工 stub 会被 build 里的默认 stub 盖掉
        val vm = build(env, indexEnabled = false)
        vm.updateSearchQuery("花木兰", carSurface = false)
        advanceUntilIdle()
        assertEquals(SearchPhase.Disabled, vm.uiState.value.searchPhase)
        assertEquals(false, vm.uiState.value.askIndexConfirm)
        verify(exactly = 0) { env.indexer.start(any()) }
    }

    /**
     * 2026-10-24 对齐：车机档确认后同样起爬（用户选了“对齐手机端全功能”），
     * 索引仍只能由用户在确认入口主动起，绝不自动起爬。
     */
    @Test fun `车机档确认后同样起爬（对齐手机端）`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.indexedTrackCount() } returns 0
        coEvery { env.repo.webdavSources() } returns listOf(webdavSource)
        val vm = build(env)
        vm.updateSearchQuery("花木兰", carSurface = true)
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.askIndexConfirm)
        vm.confirmIndexing()
        advanceUntilIdle()
        verify(exactly = 1) { env.indexer.start(webdavSource) }
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

    // ── 搜索结果长按「转到所在目录」：跳源找回、返回栈重置 ──

    private val otherSource = AudioSource(2, "曲剧", SourceType.WEBDAV, "https://webdav.123pan.cn/webdav")

    /**
     * browseSource() 除了 listChildren 还会走 refreshDir 重取根目录；
     * 不 stub 它的话 relaxed mock 会返回一个转不成 List 的裸对象。
     */
    private fun Env.stubBrowse(
        source1: AudioSource = webdavSource,
        source2: AudioSource = otherSource,
    ) {
        coEvery { repo.listChildren(any(), any(), any()) } returns Result.success(emptyList<AudioTrack>())
        coEvery { repo.refreshDir(any(), any(), any()) } returns Result.success(emptyList<AudioTrack>())
        coEvery { repo.getSource(1L) } returns source1
        coEvery { repo.getSource(2L) } returns source2
    }

    private fun folderTrack(path: String) = AudioTrack(
        id = path, name = path.trimEnd('/').substringAfterLast('/'),
        path = path, isFolder = true, parentPath = "/豫剧",
    )

    /**
     * 早期索引把「不是文件夹」的都当曲目，歌词与封面可能已经在表里。
     * 用户重建索引之前，搜索结果必须挡掉这些行，否则装了新版还是能搜到点不响的条目。
     */
    @Test fun `搜索结果过滤掉已入库的歌词与封面行`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.indexedTrackCount() } returns 3469
        coEvery { env.repo.searchTracks(null, "花木兰", 200) } returns listOf(
            TrackHit(1, "花木兰.mp3", "/豫剧/花木兰.mp3", "/豫剧", 8_000_000),
            TrackHit(1, "花木兰.lrc", "/豫剧/花木兰.lrc", "/豫剧", 1_024),
            TrackHit(1, "封面.jpg", "/豫剧/cover.jpg", "/豫剧", 20_480),
        )
        val vm = build(env)
        vm.updateSearchQuery("花木兰", carSurface = false)
        advanceUntilIdle()
        assertEquals(listOf("花木兰.mp3"), vm.uiState.value.searchResults.map { it.name })
    }

    /**
     * 结果跳源：browseFolder() 读的是当前 browsingSource，而搜索结果跨多个云盘。
     * 拿错源去列目录，会把 A 盘的目录名当成 B 盘的路径去请求，列出来是空的或错的。
     */
    @Test fun `跳目录按结果自带的 sourceId 找回来源，不用当前正在浏览的那个`() = runTest(dispatcher) {
        val env = Env()
        env.stubBrowse()
        val vm = build(env, sources = listOf(webdavSource, otherSource))
        vm.browseSource(webdavSource)
        advanceUntilIdle()
        vm.browseFolder("/豫剧")
        advanceUntilIdle()
        vm.openResultFolder(TrackHit(2, "花木兰", "/曲剧/花木兰.mp3", "/曲剧", 8_000_000))
        advanceUntilIdle()
        coVerify { env.repo.listChildren(otherSource, "/曲剧", any()) }
        assertEquals(otherSource, vm.uiState.value.browsingSource)
    }

    @Test fun `跳过去后路径与内容区状态正确`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.getSource(1L) } returns webdavSource
        val kids = listOf(folderTrack("/豫剧/花木兰上.mp3"))
        coEvery { env.repo.listChildren(any(), any(), any()) } returns Result.success(kids)
        val vm = build(env)
        vm.openResultFolder(TrackHit(1, "花木兰", "/豫剧/花木兰.mp3", "/豫剧", 8_000_000))
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals("/豫剧", s.browsePath)
        assertEquals(kids, s.browseItems)
        assertEquals(true, s.showBrowseContent)
        assertEquals(false, s.isLoading)
    }

    /**
     * 从搜索跳进来的，上一级应该是来源列表；
     * 不重置 history 的话返回键会把用户带回八竿子打不着的旧目录。
     */
    @Test fun `跳目录重置返回栈，不把新路径接在旧浏览路径后面`() = runTest(dispatcher) {
        val env = Env()
        env.stubBrowse()
        val vm = build(env)
        vm.browseSource(webdavSource)
        advanceUntilIdle()
        vm.browseFolder("/豫剧")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.browseHistory.isNotEmpty())
        vm.openResultFolder(TrackHit(1, "花木兰", "/常香玉/花木兰.mp3", "/常香玉", 8_000_000))
        advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.uiState.value.browseHistory)
    }

    @Test fun `来源已被删除时给错误提示而不是静默失败`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.getSource(99L) } returns null
        val vm = build(env)
        vm.openResultFolder(TrackHit(99, "花木兰", "/豫剧/花木兰.mp3", "/豫剧", 8_000_000))
        advanceUntilIdle()
        assertNull(vm.uiState.value.browsingSource)
        assertNotNull(vm.uiState.value.error)
    }

    @Test fun `列目录失败时走错误通道并收起加载态`() = runTest(dispatcher) {
        val env = Env()
        coEvery { env.repo.getSource(1L) } returns webdavSource
        coEvery { env.repo.listChildren(any(), any(), any()) } returns Result.failure(Exception("403"))
        val vm = build(env)
        vm.openResultFolder(TrackHit(1, "花木兰", "/豫剧/花木兰.mp3", "/豫剧", 8_000_000))
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.isLoading)
        assertNotNull(vm.uiState.value.error)
    }
}
