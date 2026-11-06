package com.radio.chinese

import com.radio.chinese.domain.model.StreamHeaders
import com.radio.chinese.service.StreamHeaderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * header 单例的三条规矩：默认 null、能被写入、必须能被清空。
 *
 * "默认 null" 是纯音频台的零回退守卫 —— RadioService 只在非 null 时才动
 * setDefaultRequestProperties，默认非 null 会让所有老流都被改头。
 *
 * "能清空" 防的是串台：播完带 header 的 iptv 台再播电台，残留会把上一个台的
 * UA/Referer 带给下一个台（项目已在 WebDAV 单例凭证上踩过同一类跨源串写）。
 */
class StreamHeaderStoreTest {

    @Test
    fun `默认没有 header`() {
        assertNull(StreamHeaderStore().current)
    }

    @Test
    fun `写入与清空`() {
        val s = StreamHeaderStore()
        val h = StreamHeaders(userAgent = "UA/1", referrer = "http://r")
        s.current = h
        assertSame(h, s.current)
        s.current = null
        assertNull(s.current)
    }

    /**
     * 只有一个字段有值也要原样带着 —— 上游有一批流只要 Referer 不要 UA。
     * 如果这里被"规范化"成空串或整条丢弃，那批流会 403 且没人报警。
     */
    @Test
    fun `只带 referer 的条目不被改写`() {
        val h = StreamHeaders(userAgent = "", referrer = "http://only-ref")
        val s = StreamHeaderStore()
        s.current = h
        assertEquals("", s.current!!.userAgent)
        assertEquals("http://only-ref", s.current!!.referrer)
    }

    /**
     * 测我对 media3 API 的记忆，不是测我们的代码。
     *
     * 只断言生产真正调的那个 setter。上一版还顺手断了 setUserAgent 也存在，
     * 结果它红了 —— 因为 setUserAgent 不在 HttpDataSource.Factory 上，
     * 而且**我们已故意不再用它**：UA 本身就是个 header，与
     * setDefaultRequestProperties 同时用谁赢未经实测，而 WebDAV 的 Authorization
     * 已经在调后者（它是覆盖语义，不是累加）。把 UA 并进同一张 map 一次写入，
     * 才能同时避开“互相覆盖”与“谁后写谁赢”两个不确定。
     */
    @Test
    fun `media3 里存在我们用的那个 setter`() {
        val cls = Class.forName("androidx.media3.datasource.HttpDataSource\$Factory")
        val names = cls.methods.map { it.name }
        org.junit.Assert.assertTrue(
            "HttpDataSource.Factory 上没有 setDefaultRequestProperties，实际有：$names",
            "setDefaultRequestProperties" in names,
        )
    }

    /**
     * spec §6 要求实测的覆盖关系，落到具体依赖版本上的静态守卫。
     *
     * media3 1.5.1 的 DefaultHttpDataSource.open() 在写完 defaultRequestProperties 之后，
     * 只要 Factory.userAgent != null 就再 setRequestProperty("User-Agent", userAgent) 覆盖一次。
     * 我们只调 setDefaultRequestProperties 一次写全（含 User-Agent），前提是 Factory 默认 UA 为 null。
     * media3 升级后若默认值变了，这条会红，提示重新验 T6，别把旧结论带过去。
     */
    @Test
    fun `DefaultHttpDataSource Factory 默认不带 userAgent`() {
        val cls = Class.forName("androidx.media3.datasource.DefaultHttpDataSource\$Factory")
        val factory = cls.getDeclaredConstructor().newInstance()
        val field = cls.getDeclaredField("userAgent").apply { isAccessible = true }
        assertNull(
            "media3 升级后 Factory 默认 UA 不再是 null：map 里的 User-Agent 会被覆盖，需重验 T6",
            field.get(factory),
        )
    }
}
