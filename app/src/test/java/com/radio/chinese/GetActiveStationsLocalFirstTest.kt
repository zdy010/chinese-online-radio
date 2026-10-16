package com.radio.chinese

import android.content.Context
import com.radio.chinese.data.RegionRepository
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.local.SourceAvailabilityStore
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.Region
import com.radio.chinese.domain.model.StationSorter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 只验证 getActiveStations 的接线决策：什么时候套用本地优先、什么时候原样返回。
 * 排序本体由 StationSorterTest（Task 4）负责，这里不重复；把排序抽成内部 applyLocalPriority
 * 便于在不起真电台清单解析的前提下钉住“开关关 / 地区空 => 不排序”。
 */
class GetActiveStationsLocalFirstTest {
    private fun st(id: String) = RadioStation(id = id, name = id, category = "general")

    private fun repo(prefs: RadioPreferences, regionRepo: RegionRepository): StationRepository =
        StationRepository(mockk<Context>(relaxed = true), prefs, mockk<SourceAvailabilityStore>(relaxed = true), regionRepo)

    @Test fun `开关开且有用户地区时套用排序`() = runBlocking {
        val input = listOf(st("a"), st("b"))
        val prefs = mockk<RadioPreferences>()
        every { prefs.localFirstEnabled } returns flowOf(true)
        val regionRepo = mockk<RegionRepository>()
        coEvery { regionRepo.currentUserRegion() } returns Region("浙江", "绍兴")
        mockkObject(StationSorter)
        try {
            every { StationSorter.localPriority(any(), any(), any()) } returns input
            val out = repo(prefs, regionRepo).applyLocalPriority(input)
            verify { StationSorter.localPriority(eq(input), eq(Region("浙江", "绍兴")), any()) }
            assertEquals(input, out)
        } finally {
            unmockkObject(StationSorter)
        }
    }

    @Test fun `开关关闭时不排序、原样返回`() = runBlocking {
        val input = listOf(st("a"), st("b"))
        val prefs = mockk<RadioPreferences>()
        every { prefs.localFirstEnabled } returns flowOf(false)
        val regionRepo = mockk<RegionRepository>()
        mockkObject(StationSorter)
        try {
            val out = repo(prefs, regionRepo).applyLocalPriority(input)
            verify(exactly = 0) { StationSorter.localPriority(any(), any(), any()) }
            assertEquals(input, out)
        } finally {
            unmockkObject(StationSorter)
        }
    }

    @Test fun `用户地区为空时不排序、原样返回`() = runBlocking {
        val input = listOf(st("a"), st("b"))
        val prefs = mockk<RadioPreferences>()
        every { prefs.localFirstEnabled } returns flowOf(true)
        val regionRepo = mockk<RegionRepository>()
        coEvery { regionRepo.currentUserRegion() } returns Region.NONE
        mockkObject(StationSorter)
        try {
            val out = repo(prefs, regionRepo).applyLocalPriority(input)
            verify(exactly = 0) { StationSorter.localPriority(any(), any(), any()) }
            assertEquals(input, out)
        } finally {
            unmockkObject(StationSorter)
        }
    }
}
