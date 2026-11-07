package com.radio.chinese

import androidx.media3.common.Player
import com.radio.chinese.data.repository.EpgRepository
import com.radio.chinese.data.repository.FavoriteRepository
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationSource
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.timer.SleepTimer
import com.radio.chinese.ui.player.PlayerViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test

/**
 * spec §10.1「loadStation 不静默失败」。
 *
 * 这是按 id 起播的唯一入口：通知栏点回、媒体键恢复、进程死亡后重建都走它。
 * 解析到 null 时早先是 `if (station != null)` 什么都不做，用户看到的是"点了没反应"，
 * 而编译与别的单测全绿。这里直接构造真实 ViewModel，钉住失败分支必须上报错误。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerViewModelLoadStationTest {

    private val stationFlow = MutableStateFlow<RadioStation?>(null)
    private lateinit var pm: PlayerManager
    private lateinit var stationRepo: StationRepository

    @After fun teardown() { Dispatchers.resetMain() }

    private fun TestScope.fixture(): PlayerViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        pm = mockk(relaxed = true)
        every { pm.currentStation } returns stationFlow
        every { pm.isPlaying } returns MutableStateFlow(false)
        every { pm.playbackState } returns MutableStateFlow(Player.STATE_IDLE)
        every { pm.error } returns MutableStateFlow<String?>(null)
        every { pm.status } returns MutableStateFlow<String?>(null)
        every { pm.currentSource } returns MutableStateFlow<StationSource?>(null)
        every { pm.sourceScores } returns MutableStateFlow<List<Pair<StationSource, Float>>>(emptyList())
        every { pm.hasVideoTrack } returns MutableStateFlow(false)

        stationRepo = mockk()
        coEvery { stationRepo.getActiveStations() } returns emptyList()
        coEvery { stationRepo.getStationById(any()) } returns null
        return PlayerViewModel(
            stationRepo,
            mockk<FavoriteRepository>(relaxed = true),
            mockk<EpgRepository>(relaxed = true),
            pm,
            mockk<SleepTimer>(relaxed = true),
        )
    }

    @Test
    fun `iptv id 解析失败时必须上报可见错误，且不静默起播`() = runTest {
        val vm = fixture()
        vm.loadStation("iptv:GONE.xx")
        runCurrent()
        verify { pm.reportError(match { it.contains("频道") && it.contains("iptv") }) }
        verify(exactly = 0) { pm.playStation(any()) }
    }

    @Test
    fun `iptv id 经回落解析成功时照常起播，不报错`() = runTest {
        val vm = fixture()
        val st = RadioStation(id = "iptv:CCTV1.cn", name = "CCTV 1", category = "general")
        coEvery { stationRepo.getStationById("iptv:CCTV1.cn") } returns st
        vm.loadStation("iptv:CCTV1.cn")
        runCurrent()
        verify { pm.playStation(st) }
        verify(exactly = 0) { pm.reportError(any()) }
    }

    @Test
    fun `同一个台直接返回，不重复查表也不重复起播`() = runTest {
        val st = RadioStation(id = "iptv:a", name = "A", category = "general")
        stationFlow.value = st
        val vm = fixture()
        vm.loadStation("iptv:a")
        runCurrent()
        coVerify(exactly = 0) { stationRepo.getStationById(any()) }
        verify(exactly = 0) { pm.playStation(any()) }
    }
}
