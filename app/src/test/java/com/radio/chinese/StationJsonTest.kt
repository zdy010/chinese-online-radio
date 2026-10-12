package com.radio.chinese

import com.radio.chinese.data.local.StationJson
import com.radio.chinese.domain.model.StationCategory
import com.radio.chinese.domain.model.StationTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 直接读仓库里那份内置清单跑校验。
 *
 * 这条测试是「词表」与「数据」之间的闸：任何人手改出一条词表外的 tag
 * （比如把 key 写成中文「京剧」），或派生脚本漂出新词，都必须在构建期变红，
 * 而不是运行时安静地显示一个英文原词。A-1 的教训是：自证的脚本不可信，
 * 所以要有一条独立于写入路径的检查。
 */
class StationJsonTest {

    private val stations by lazy { StationJson.parse(assetsFile().readText(Charsets.UTF_8)) }

    private fun assetsFile(): File {
        // 单元测试工作目录通常是模块目录（app/），不同 Gradle 版本可能差一层
        val candidates = listOf(
            File("src/main/assets/radio_stations.json"),
            File("app/src/main/assets/radio_stations.json"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("找不到 radio_stations.json，尝试过: $candidates")
    }

    @Test
    fun `台数不少于 451`() {
        // 这条是防「意外截断」的下限（比如解析改坏只读到前一段），不是对死源的承诺。
        // 从 465 降到 451，两轮都有实测凭据：
        //   465→455：全量体检（tools/recheck_report.txt）判 10 台所有地址均死，
        //             且逐个找过替代源，能救的都救回了（福建新闻广播换 satellitepull）。
        //   455→451：剩下 6 台「未表态」的拿到真机当裁判，4 台确实放不出声
        //             （北京戏曲曲艺/山西音乐/Jil FM 直接 ERROR，苏州综合广播 60s 仍缓冲），
        //             另 2 台在手机上正常出声所以保留（PC 侧 timeout 只是本机网络假阳性）。
        assertTrue("实际 ${stations.size} 台", stations.size >= 451)
    }

    @Test
    fun `每个 tag 都在词表里`() {
        val bad = stations.flatMap { st -> st.tags.filterNot { StationTag.isValidKey(it) }.map { st.id to it } }
        assertTrue("词表外的 tag: $bad", bad.isEmpty())
    }

    @Test
    fun `每个 category 都在分类枚举里`() {
        val keys = StationCategory.entries.map { it.key }.toSet()
        val bad = stations.filter { it.category !in keys }.map { it.id }
        assertTrue("非法 category: $bad", bad.isEmpty())
    }

    @Test
    fun `country 非空时 language 也必须非空`() {
        val bad = stations.filter { it.country.isNotBlank() && it.language.isBlank() }.map { it.id }
        assertTrue("有国家没语言: $bad", bad.isEmpty())
    }

    @Test
    fun `单台标签不超过六个且无重复`() {
        val over = stations.filter { it.tags.size > 6 }.map { it.id }
        val dup = stations.filter { it.tags.size != it.tags.toSet().size }.map { it.id }
        assertTrue("超过 6 个标签: $over", over.isEmpty())
        assertTrue("标签重复: $dup", dup.isEmpty())
    }

    @Test
    fun `id 全局唯一`() {
        assertEquals(stations.size, stations.map { it.id }.toSet().size)
    }

    @Test
    fun `每条都有主 streamUrl 且带 sources 时首源一致`() {
        assertTrue(stations.all { it.streamUrl.isNotBlank() })
        val mismatch = stations.filter {
            it.sources.isNotEmpty() && it.sources.first().url != it.streamUrl
        }.map { it.id }
        // 既有约定是 sources[0] 与 streamUrl 同址；不一致说明切源起点与显示不一致
        assertTrue("首源与 streamUrl 不一致: $mismatch", mismatch.isEmpty())
    }
}
