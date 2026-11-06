package com.radio.chinese

import android.content.Context
import com.radio.chinese.data.RegionRepository
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.local.SourceAvailabilityStore
import com.radio.chinese.data.repository.IptvStationRepository
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.model.RadioStation
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
     * 用"iptv 仓库一旦被问就炸"来钉这条：结果断言看不出手腕（两个都是 null）。
     */
    @Test
    fun `非前缀 id 不去问 iptv 仓库`() = runTest {
        val iptv = mockk<IptvStationRepository>()
        every { iptv.isIptvId(any()) } answers { firstArg<String>().startsWith("iptv:") }
        // byId 是 suspend：用 every 调它编译不过，必须 coEvery
        coEvery { iptv.byId(any()) } throws AssertionError("非前缀 id 不该来问 iptv 仓库")
        val r = repo(iptv, radioJson())
        assertEquals("中国之声", r.getStationById("cnr")?.name)
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

    @Test
    fun `iptv 条目不存在时返回 null 而不是抛`() = runTest {
        val r = repo(iptvWith("iptv:Here.cn", "Here"), radioJson())
        assertNull(r.getStationById("iptv:Gone.cn"))
    }
}
