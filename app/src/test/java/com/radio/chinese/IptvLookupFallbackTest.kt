package com.radio.chinese

import android.content.Context
import com.radio.chinese.data.RegionRepository
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.local.SourceAvailabilityStore
import com.radio.chinese.data.repository.IptvStationRepository
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.model.RadioStation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * getStationById 的回落。
 *
 * 需要它的不是播放主路径 —— `PlayerManager.playStation(station: RadioStation)` 收的是对象，
 * 根本不查 id。真正按 id 解析、且**失败静默**的是 PlayerViewModel.loadStation：
 * 那里 `if (station != null)` 拿到 null 就什么都不做。
 * 所以不加回落的症状是"点通知栏没反应 / 进程重建后播放页停在旧台"，
 * 而不是编译错误或崩溃 —— 这类问题只能被测试拦住。
 */
class IptvLookupFallbackTest {

    private fun repo(iptv: IptvStationRepository, radioJson: String): StationRepository {
        // 不能用 relaxed mock 的 Context：那样 assets.open("radio_stations.json") 会返回一个
        // relaxed 假流，读出来 0 字节，StationRepository 解出空表 ——
        // 于是“电台 id 仍走电台表”这类断言测的根本不是路由，而是假件喂空（本轮真实踩过）。
        val ctx = mockk<Context>()
        every { ctx.assets.open("radio_stations.json") } answers { ByteArrayInputStream(radioJson.toByteArray()) }
        // 同理：customStationsJson / invalidStationIds 在 relaxed mock 上不是 Flow，
        // 调 .first() 会抛 NoSuchElementException。必须给真 Flow。
        val prefs = mockk<RadioPreferences> {
            every { customStationsJson } returns flowOf(null)
            every { invalidStationIds } returns flowOf(emptySet())
            every { localFirstEnabled } returns flowOf(false)
        }
        return StationRepository(
            ctx,
            prefs,
            mockk<SourceAvailabilityStore>(relaxed = true),
            mockk<RegionRepository>(relaxed = true),
            iptv,
        )
    }

    private fun radioJson() = """{"stations":[{"id":"cnr","name":"中国之声","category":"news"}]}"""

    private fun iptvWith(id: String, name: String): IptvStationRepository {
        val json = """{"stations":[{"id":"$id","name":"$name","category":"general"}]}"""
        val ctx = mockk<Context>()
        every { ctx.assets.open("iptv_channels.json") } answers { ByteArrayInputStream(json.toByteArray()) }
        return IptvStationRepository(ctx)
    }

    @Test
    fun `iptv 前缀的 id 能从 iptv 仓库解析`() = runTest {
        val r = repo(iptvWith("iptv:CCTV1.cn", "CCTV 1"), radioJson())
        assertEquals("CCTV 1", r.getStationById("iptv:CCTV1.cn")?.name)
    }

    @Test
    fun `电台 id 仍走电台表，且不会被 iptv 表污染`() = runTest {
        val r = repo(iptvWith("iptv:CCTV1.cn", "CCTV 1"), radioJson())
        assertEquals("中国之声", r.getStationById("cnr")?.name)
        assertNull(r.getStationById("nope"))
    }

    /**
     * 电台查找不该每次都过一遍 12k 条。
     *
     * 上一版这条是完全空转的：它查的是 `cnr`，而 `cnr` **命中电台表**；
     * Kotlin 的 `?:` 惰性求值，左侧非 null 时右侧根本不执行，那个“被问就炸”的桩
     * 从未被碰过 —— 审查员把守卫整行删掉，5 条全绿。现在查一个电台表里**没有**的
     * 非前缀 id，才会走到守卫那一步。
     *
     * 另外用 spyk 而不是纯 mockk：`isIptvId` 必须跑生产实现，否则就是在测试里
     * 重写一遍 `startsWith`，改了生产语义也不会红。
     */
    @Test
    fun `非前缀 id 不去问 iptv 仓库`() = runTest {
        val iptv = spyk(iptvWith("iptv:CCTV1.cn", "CCTV 1"))
        coEvery { iptv.byId(any()) } throws AssertionError("非前缀 id 不该来问 iptv 仓库")
        val r = repo(iptv, radioJson())
        // 必须是电台表里也没有的 id：否则短路，永远测不到守卫
        assertNull(r.getStationById("nope"))
        coVerify(exactly = 0) { iptv.byId(any()) }
    }

    /**
     * 回落不许把 iptv 条目掺进电台列表 —— 首页/分类/地区/EPG/检测全部吃这个入口，
     * 掺进来就是 451 条变 12,870 条（V5/V9 定的恰恰是"iptv 自成一页"）。
     */
    @Test
    fun `电台列表里不出现 iptv 条目`() = runTest {
        val r = repo(iptvWith("iptv:CCTV1.cn", "CCTV 1"), radioJson())
        assertTrue("getAllStations 被掺进 iptv", r.getAllStations().none { it.id.startsWith("iptv:") })
        assertTrue("getActiveStations 被掺进 iptv", r.getActiveStations().none { it.id.startsWith("iptv:") })
    }

    /**
     * 前缀未命中时**必须问过** iptv 仓库。
     *
     * 上一版只断言“返回 null”：两种变异（删回落 / 删守卫）都不红，因为它对
     * “根本没接线”和“接线正确但查不到”两种情况给出同一个结论。现在配对 I-1：
     * 非前缀未命中不许问（exactly 0），前缀未命中必须问（exactly 1），
     * 守卫两侧都有牙齿。
     */
    @Test
    fun `前缀未命中时必须问过 iptv 仓库`() = runTest {
        val iptv = spyk(iptvWith("iptv:Here.cn", "Here"))
        val r = repo(iptv, radioJson())
        assertNull(r.getStationById("iptv:Gone.cn"))
        coVerify(exactly = 1) { iptv.byId("iptv:Gone.cn") }
    }
}
