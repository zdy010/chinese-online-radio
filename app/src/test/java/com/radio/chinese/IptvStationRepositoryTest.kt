package com.radio.chinese

import android.content.Context
import com.radio.chinese.data.repository.IptvStationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库的三件事：懒加载只解一次、byId 只认 iptv 前缀、header 查表只返回非空。
 *
 * header 那条最重要：`headersFor` 对没有 header 的流必须返回 null，
 * 因为 PlayerManager 只在拿到非 null 时才写 StreamHeaderStore，
 * 一旦这里返回了空的 StreamHeaders，RadioService 就会给纯音频台
 * 塞一组空请求头 —— 那是静默的行为改变，没有任何测试会红。
 */
class IptvStationRepositoryTest {

    // 包装对象形状：仓库走 StationJson.parse，与内置电台清单同一个解析器。
    // 用裸数组会让 parse 直接抛，被 runCatching 吞成空表 —— 那正是"iptv 页一条都没有"的成因。
    private val sample = """
        {"stations":[
          {"id":"iptv:CCTV1.cn","name":"CCTV 1","category":"general","streamUrl":"http://a/1.m3u8",
           "sources":[{"url":"http://a/1.m3u8","label":"","quality":"1080p",
                        "userAgent":"UA-X","referrer":"http://ref.example"}]},
          {"id":"iptv:plain.cn","name":"Plain","category":"general","streamUrl":"http://b/2.m3u8",
           "sources":[{"url":"http://b/2.m3u8","label":"","quality":"","userAgent":"","referrer":""}]}
        ]}
    """.trimIndent()

    private fun repoWith(json: String): IptvStationRepository {
        val ctx = mockk<Context>()
        val stream = java.io.ByteArrayInputStream(json.toByteArray())
        every { ctx.assets.open("iptv_channels.json") } returns stream
        return IptvStationRepository(ctx)
    }

    @Test
    fun `byId 命中 iptv 台`() = runTest {
        val r = repoWith(sample)
        assertEquals("CCTV 1", r.byId("iptv:CCTV1.cn")?.name)
    }

    /**
     * 这是一条**代价**断言，不是结果断言。
     *
     * 上一版只断言了 `byId("cnr")` 返回 null —— 变异检验：把仓库里的前缀守卫整行删掉，
     * 这 9 条测试仍然全绿（因为样例里没有非前缀 id，走完 12k 全表扫还是返回 null）。
     * 而 spec §10.1 要钉的是“非前缀 id 不去查 iptv 仓库”：Task 5 接上回落之后，
     * 守卫掉了就表现为电台页/通知栏点回/上下台每次查台都要过 12,419 条 —— 不报错、不红测。
     * 所以必须验“根本没打开过 assets”。
     */
    @Test
    fun `byId 对非前缀 id 不查表`() = runTest {
        val ctx = mockk<Context>()
        every { ctx.assets.open("iptv_channels.json") } returns
            java.io.ByteArrayInputStream(sample.toByteArray())
        val r = IptvStationRepository(ctx)
        assertNull(r.byId("cnr"))
        assertNull(r.byId(""))
        // 关键：不能为了“不可能命中”的 id 付 6.33MB 的加载代价
        verify(exactly = 0) { ctx.assets.open(any()) }
    }

    @Test
    fun `headersFor 只给真正带 header 的流`() = runTest {
        val r = repoWith(sample)
        val h = r.headersFor("http://a/1.m3u8")
        assertTrue("带 UA 的流应返回非空", h != null)
        assertEquals("UA-X", h!!.userAgent)
        assertEquals("http://ref.example", h.referrer)
        // 关键：两个字段都空的流必须返回 null，不是返回空 StreamHeaders
        assertNull(r.headersFor("http://b/2.m3u8"))
        assertNull(r.headersFor("http://不存在的.url"))
    }

    /**
     * 只有一个字段有值时也要返回非 null —— 否则那批"只要 Referer 不要 UA"的流
     * 会被整个跳过，表现是 403 且没人报警。
     */
    @Test
    fun `只有 referrer 也算带 header`() = runTest {
        val json = """
            {"stations":[{"id":"iptv:only.cn","name":"Only","category":"general",
              "streamUrl":"http://c/3.m3u8",
              "sources":[{"url":"http://c/3.m3u8","label":"","quality":"",
                          "userAgent":"","referrer":"http://only-ref"}]}]}
        """.trimIndent()
        val h = repoWith(json).headersFor("http://c/3.m3u8")
        assertTrue("只有 Referer 的流被漏掉了", h != null)
        assertEquals("http://only-ref", h!!.referrer)
    }

    @Test
    fun `all 只解析一次`() = runTest {
        var opens = 0
        val ctx = mockk<Context>()
        every { ctx.assets.open("iptv_channels.json") } answers {
            opens++
            java.io.ByteArrayInputStream(sample.toByteArray())
        }
        val r = IptvStationRepository(ctx)
        r.all(); r.all(); r.all()
        assertEquals("assets 被重复解析", 1, opens)
    }

    /** 并发进入（列表页与播放页同时起）不能解两遍。 */
    @Test
    fun `并发只解析一次`() = runTest {
        var opens = 0
        val ctx = mockk<Context>()
        every { ctx.assets.open("iptv_channels.json") } answers {
            opens++
            java.io.ByteArrayInputStream(sample.toByteArray())
        }
        val r = IptvStationRepository(ctx)
        coroutineScope {
            repeat(8) { launch { r.all() } }
        }
        assertEquals("并发下解析了 $opens 次", 1, opens)
    }

    @Test
    fun `isIptvId 只认前缀`() {
        val ctx = mockk<Context>()
        val r = IptvStationRepository(ctx)
        assertTrue(r.isIptvId("iptv:CCTV1.cn"))
        assertTrue(r.isIptvId("iptv:direct:a.tv:deadbeef"))
        assertTrue(!r.isIptvId("cnr"))
    }

    /**
     * 资源缺失/损坏时退化成空表而不是崩。
     * 但"退化"不等于"静默"：这条同时钉住 byId 返回 null，
     * 让上层（PlayerViewModel.loadStation）有机会走失败分支而不是拿到假对象。
     */
    @Test
    fun `资源缺失时返回空表而不是崩`() = runTest {
        val ctx = mockk<Context>()
        every { ctx.assets.open("iptv_channels.json") } throws java.io.FileNotFoundException("no such file")
        val r = IptvStationRepository(ctx)
        assertTrue(r.all().isEmpty())
        assertNull(r.byId("iptv:CCTV1.cn"))
    }

    @Test
    fun `形状不对时退化成空表而不是崩`() = runTest {
        val ctx = mockk<Context>()
        every { ctx.assets.open("iptv_channels.json") } returns
            java.io.ByteArrayInputStream("not json at all".toByteArray())
        val r = IptvStationRepository(ctx)
        assertTrue(r.all().isEmpty())
    }
}
