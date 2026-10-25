package com.radio.chinese

import androidx.media3.common.MediaMetadata
import com.radio.chinese.domain.model.OperaAudioFile
import com.radio.chinese.domain.sidecar.SidecarMediaSource
import com.radio.chinese.service.OperaRepeatMode
import com.radio.chinese.service.OperaPlayContext
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.player.CoverSource
import com.radio.chinese.ui.player.LyricsStatus
import com.radio.chinese.ui.player.OperaPlayerViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private class FakeSidecar(
    var lyrics: suspend () -> Result<String> = { Result.failure(Exception("NOT_FOUND")) },
    var image: suspend () -> Result<ByteArray> = { Result.failure(Exception("NOT_FOUND")) }
) : SidecarMediaSource {
    var imageCalls = 0
    override suspend fun readLyrics(ctx: OperaPlayContext): Result<String> = lyrics()
    override suspend fun readImage(ctx: OperaPlayContext): Result<ByteArray> {
        imageCalls++
        return image()
    }
}

class OperaPlayerViewModelTest {
    private val fileFlow = MutableStateFlow<OperaAudioFile?>(null)
    private val ctxFlow = MutableStateFlow<OperaPlayContext?>(null)
    private val posFlow = MutableStateFlow(0L)
    private val metaFlow = MutableStateFlow<MediaMetadata?>(null)
    private val playingFlow = MutableStateFlow(false)
    private val durFlow = MutableStateFlow(0L)
    private val brFlow = MutableStateFlow(0)
    private val repeatFlow = MutableStateFlow(OperaRepeatMode.ALL)
    private val speedFlow = MutableStateFlow(1.0f)
    private lateinit var pm: PlayerManager

    @Before fun setup() {
        pm = mockk()
        every { pm.operaFile } returns fileFlow
        every { pm.operaContext } returns ctxFlow
        every { pm.operaPosition } returns posFlow
        every { pm.operaMediaMetadata } returns metaFlow
        every { pm.isPlaying } returns playingFlow
        every { pm.operaDuration } returns durFlow
        every { pm.operaBitrate } returns brFlow
        every { pm.operaRepeatMode } returns repeatFlow
        every { pm.operaSpeed } returns speedFlow
        // 严格 mockk：seekToLine 会真调到 seekOperaTo，不 stub 会抛
        every { pm.seekOperaTo(any()) } returns Unit
    }

    @After fun teardown() { Dispatchers.resetMain() }

    private fun opera(id: Long) = OperaAudioFile(
        fileId = id, name = "唱段$id", size = 0, categoryName = "河南豫剧", operaName = ""
    )
    private val ctx1 = OperaPlayContext(7L, "/dav/a1.mp3")
    private val twoLines = "[00:01.00]第一句\n[00:05.00]第二句"

    @Test fun lyricsReadyAndIndexFollowsPosition() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = OperaPlayerViewModel(pm, FakeSidecar(lyrics = { Result.success(twoLines) }))
        fileFlow.value = opera(1L); ctxFlow.value = ctx1
        runCurrent()
        assertEquals(LyricsStatus.READY, vm.uiState.value.lyricsStatus)
        assertEquals(2, vm.uiState.value.lines.size)
        assertEquals(-1, vm.uiState.value.currentIndex)
        posFlow.value = 6_000L
        runCurrent()
        assertEquals(1, vm.uiState.value.currentIndex)
        vm.seekToLine(0)
    }

    @Test fun missingLyricsDegradeToMissing() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = OperaPlayerViewModel(pm, FakeSidecar())
        fileFlow.value = opera(1L); ctxFlow.value = ctx1
        runCurrent()
        assertEquals(LyricsStatus.MISSING, vm.uiState.value.lyricsStatus)
        assertEquals(0, vm.uiState.value.lines.size)
    }

    @Test fun timeoutTreatedAsMissing() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val vm = OperaPlayerViewModel(pm, FakeSidecar(lyrics = { delay(60_000); Result.success(twoLines) }))
        fileFlow.value = opera(1L); ctxFlow.value = ctx1
        main.scheduler.advanceUntilIdle() // 触发内部 8s withTimeout
        assertEquals(LyricsStatus.MISSING, vm.uiState.value.lyricsStatus)
    }

    @Test fun switchingTrackDropsLateResponseOfPrevious() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        var second = false
        val sidecar = FakeSidecar(lyrics = {
            if (!second) { delay(9_000); Result.success("[00:01.00]旧段歌词") }
            else { Result.success(twoLines) }
        })
        val vm = OperaPlayerViewModel(pm, sidecar)
        fileFlow.value = opera(1L); ctxFlow.value = ctx1
        main.scheduler.advanceTimeBy(1_000); runCurrent()
        // 换段：ctx1 的慢响应必须被 flatMapLatest 丢弃，不得挂到新一段上
        second = true
        val ctx2 = OperaPlayContext(7L, "/dav/a2.mp3")
        fileFlow.value = opera(2L); ctxFlow.value = ctx2
        main.scheduler.advanceUntilIdle()
        assertEquals(LyricsStatus.READY, vm.uiState.value.lyricsStatus)
        assertEquals("第一句", vm.uiState.value.lines[0].text)
    }

    @Test fun embeddedArtworkWinsAndSidecarImageNotFetched() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        // MediaMetadata 在纯 JVM 单测下是 android.jar stub，不能 mockk 其行为；
        // Builder 是纯 Java 实现，造真对象合法（artworkData 存取在 stub 里也不会走 native）
        val md = MediaMetadata.Builder().setArtworkData(byteArrayOf(1, 2, 3), null).build()
        metaFlow.value = md
        val sidecar = FakeSidecar(
            lyrics = { Result.failure(Exception("NOT_FOUND")) },
            image = { Result.success(byteArrayOf(9)) }
        )
        val vm = OperaPlayerViewModel(pm, sidecar)
        fileFlow.value = opera(1L); ctxFlow.value = ctx1
        runCurrent()
        assertEquals(CoverSource.EMBEDDED, vm.uiState.value.coverSource)
        assertEquals(0, sidecar.imageCalls)
    }

    @Test fun embeddedArtworkClearedOnTrackSwitch() = runTest {
        // 终审 C1：A 段带内嵌封面，换到无封面的 B 段——旧 artwork 不得挂在 B 上。
        // 真实链路里段落边界由 PlayerManager 重置 operaMediaMetadata=null，
        // 本测试验证 VM 对“新段+元数据已清空”不会残留旧封面
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val mdA = MediaMetadata.Builder().setArtworkData(byteArrayOf(1, 2, 3), null).build()
        metaFlow.value = mdA
        val vm = OperaPlayerViewModel(pm, FakeSidecar(lyrics = { Result.failure(Exception("NOT_FOUND")) }))
        fileFlow.value = opera(1L); ctxFlow.value = ctx1
        runCurrent()
        assertEquals(CoverSource.EMBEDDED, vm.uiState.value.coverSource)
        // 换段瞬间：PlayerManager 尚未重置元数据（md 仍是 A 的），
        // 内嵌收集器会把 A 的 artwork 写进 B 的键上——这就是 C1 的污染窗口
        fileFlow.value = opera(2L)
        ctxFlow.value = OperaPlayContext(7L, "/dav/a2.mp3")
        runCurrent()
        // B 段真正的元数据到达：无内嵌封面（artworkData=null）
        metaFlow.value = null
        runCurrent()
        assertEquals(CoverSource.NONE, vm.uiState.value.coverSource)
        assertEquals(null, vm.uiState.value.coverBytes)
    }

    @Test fun noContextClearsEverything() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = OperaPlayerViewModel(pm, FakeSidecar(lyrics = { Result.success(twoLines) }))
        fileFlow.value = opera(1L); ctxFlow.value = ctx1
        runCurrent()
        fileFlow.value = null; ctxFlow.value = null
        runCurrent()
        assertEquals(LyricsStatus.NONE, vm.uiState.value.lyricsStatus)
        assertEquals(0, vm.uiState.value.lines.size)
        assertEquals(CoverSource.NONE, vm.uiState.value.coverSource)
    }
}
