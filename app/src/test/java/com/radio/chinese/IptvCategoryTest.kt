package com.radio.chinese

import com.radio.chinese.domain.model.IptvAdult
import com.radio.chinese.domain.model.IptvCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iptv 的分类集。不动 StationCategory —— 那份是管理页与添加电台下拉的取值来源，
 * 往里加 movies/series/kids 会让那些页面长出与"电台"语义不符的格子（spec V8）。
 */
class IptvCategoryTest {

    /**
     * 这份 id 清单是 2026-11-06 从 categories.json 实测抄下来的 30 个。
     * 抄录本身不可靠，所以另有一条直接读 categories.json 的用例兜底。
     */
    @Test
    fun `覆盖 iptv-org 全部 30 个分类 id`() {
        val upstream = listOf(
            "auto", "animation", "business", "classic", "comedy", "cooking", "culture",
            "documentary", "education", "entertainment", "family", "general", "interactive",
            "kids", "legislative", "lifestyle", "movies", "music", "news", "outdoor",
            "public", "relax", "religious", "science", "series", "shop", "sports",
            "travel", "weather", "xxx",
        )
        val mine = IptvCategory.entries.map { it.key }.toSet()
        val missing = upstream.filterNot { it in mine }
        assertTrue("枚举漏了 $missing", missing.isEmpty())
        assertEquals("枚举条目数与上游不一致", 30, mine.size)
    }

    /**
     * 直接读仓库里那份真实清单，核对**实际用到的分类**都能翻出中文。
     * 上面那条靠我抄的 id 列表；这条靠数据本身，两者互为对照 ——
     * 抄错时第一条红，上游新增分类时第二条红。
     *
     * 覆盖边界（实测，不是推测）：清单里实际只出现 29 个分类，`xxx` 一条都没有
     * （上游 354 个 xxx 频道全都没有流）。所以**这条盖不住 `xxx`**：
     * 删掉 ADULT 条目只会让上面那条红，不会让这条红。
     */
    @Test
    fun `真实清单里出现的每个分类都能翻出中文标签`() {
        val f = listOf(
            File("src/main/assets/iptv_channels.json"),
            File("app/src/main/assets/iptv_channels.json"),
        ).firstOrNull { it.exists() } ?: return   // 清单尚未生成时跳过，不制造假红
        val text = f.readText(Charsets.UTF_8)
        val used = Regex("\"tags\":\\[([^\\]]*)\\]").findAll(text)
            .flatMap { m -> Regex("\"([a-z_]+)\"").findAll(m.groupValues[1]) }
            .map { it.groupValues[1] }.toSet()
        assertTrue("清单里没有分类数据", used.isNotEmpty())
        val unmapped = used.filter { IptvCategory.labelOf(it) == IptvCategory.otherLabel }
        assertTrue("有分类翻不出中文：$unmapped", unmapped.isEmpty())
    }

    @Test
    fun `每个分类都有非空中文标签`() {
        val blank = IptvCategory.entries.filter { it.label.isBlank() }
        assertTrue("空标签 $blank", blank.isEmpty())
    }

    @Test
    fun `未知 key 回落其他而不是抛异常`() {
        // 上游随时可能加新分类；抛异常的后果是整个 iptv 页打不开
        assertEquals(IptvCategory.otherLabel, IptvCategory.labelOf("not-a-real-category"))
        assertEquals(IptvCategory.otherLabel, IptvCategory.labelOf(""))
    }

    /**
     * 断言的是**关系**，不是字面量。
     * 写成 assertEquals("xxx", IptvCategory.ADULT.key) 的话全文没引用 IptvAdult，
     * 谁改了 adultCategoryKey 这条照样绿 —— 而失效后果是 xxx 分类频道在
     * 成人开关关着（默认）时直接出现在列表与搜索里（V2 唯一的默认隐藏闸门）。
     */
    @Test
    fun `成人分类的 key 与 IptvAdult 用的是同一个字`() {
        assertEquals(IptvAdult.adultCategoryKey, IptvCategory.ADULT.key)
    }

    @Test
    fun `ordered 的顺序就是展示顺序且不含空 key`() {
        val keys = IptvCategory.ordered().map { it.first }
        assertTrue(keys.none { it.isBlank() })
        assertEquals(IptvCategory.entries.map { it.key }.toSet(), keys.toSet())
    }
}
