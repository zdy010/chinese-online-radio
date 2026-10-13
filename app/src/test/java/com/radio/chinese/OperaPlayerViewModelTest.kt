package com.radio.chinese

import androidx.media3.common.MediaMetadata
import com.radio.chinese.domain.model.OperaAudioFile
import com.radio.chinese.domain.sidecar.SidecarMediaSource
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
    private lateinit var pm: PlayerManager

    @Before fun setup() {
        pm = mockk()
        every { pm.operaFile } returns fileFlow
        every { pm.operaContext } returns ctxFlow
        every { pm.operaPosition } returns posFlow
        every { pm.operaMediaMetadata } returns metaFlow
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
