package com.radio.chinese

import androidx.media3.common.util.UnstableApi
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.repository.FavoriteRepository
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.model.FavoriteStation
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.favorites.FavoritesViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
 * 用户实测问题 4：屏蔽（标记无效）只能在主页生效，收藏页仍显示。
 *
 * 收藏页把收藏流与 invalidStationIds 流 combine，屏蔽的台要从收藏列表一并隐去。
 * 这里用真 Flow + MockK 钉住这条规则（本项目无 Robolectric，DAO 侧另用真 SQLite 验）。
 */
@OptIn(UnstableApi::class)
class FavoritesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @After fun teardown() = Dispatchers.resetMain()

    private fun station(id: String) = RadioStation(
        id = id, name = "台$id", category = "news",
        streamUrl = "https://x/$id.m3u8",
    )

    @Test fun `屏蔽后收藏列表隐去该台`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val repo = mockk<StationRepository>(relaxed = true)
        val favRepo = mockk<FavoriteRepository>(relaxed = true)
        val prefs = mockk<RadioPreferences>(relaxed = true)
        val invalid = MutableStateFlow<Set<String>>(emptySet())
        val favs = MutableStateFlow(listOf(FavoriteStation("a", 0L), FavoriteStation("b", 0L)))

        every { prefs.invalidStationIds } returns invalid
        every { prefs.customStationsJson } returns MutableStateFlow<String?>(null)
        every { favRepo.getAllFavorites() } returns favs
        coEvery { repo.getCustomStations() } returns emptyList()
        coEvery { repo.getAllStations() } returns listOf(station("a"), station("b"))

        val vm = FavoritesViewModel(favRepo, repo, prefs, mockk<PlayerManager>(relaxed = true))
        advanceUntilIdle()
        assertEquals(setOf("a", "b"), vm.uiState.value.favoriteStations.map { it.id }.toSet())

        // 屏蔽 a：invalid 流变化后收藏列表应重算并去掉 a
        invalid.value = setOf("a")
        advanceUntilIdle()
        assertEquals(listOf("b"), vm.uiState.value.favoriteStations.map { it.id })
    }
}
