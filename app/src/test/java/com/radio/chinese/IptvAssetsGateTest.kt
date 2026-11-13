package com.radio.chinese

import com.radio.chinese.data.local.StationJson
import com.radio.chinese.domain.model.IptvAdult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 内置 iptv 清单的闸门。这条不测代码，测**数据**：
 * 清单是 tools/iptv_sync.ps1 从网络生成的，一旦半截失败就会留下一份少一大截的 assets，
 * 而 App 照样能跑、只是"频道少得离谱"—— 用户会以为功能坏了。
 * 三重断言与工具里的三道校验一一对应，两边必须一致。
 *
 * 解析走 StationJson.parse：与内置电台清单同一个解析器、同一种 {"stations":[...]} 形状。
 * 这里绝不能用 kotlinx decodeFromString 另起一条路径 —— 手写解析器不认的字段会被静默丢掉，
 * 而这条测试就再也看不见它了。
 */
class IptvAssetsGateTest {

    private fun assetsFile(name: String): File {
        // 单测工作目录通常是模块目录（app/），不同 Gradle 版本可能差一层
        val candidates = listOf(
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("找不到 $name，尝试过: $candidates。先跑 pwsh -NoProfile -File tools/iptv_sync.ps1")
    }

    private val list by lazy { StationJson.parse(assetsFile("iptv_channels.json").readText(Charsets.UTF_8)) }

    @Test
    fun `清单条数不低于一萬`() {
        assertTrue("实际 ${list.size} 条", list.size >= 10_000)
    }

    @Test
    fun `每条都有 id、名字、至少一个源`() {
        assertTrue("有空 id", list.all { it.id.isNotBlank() })
        assertTrue("有空白名字", list.all { it.name.isNotBlank() })
        assertTrue("有无源条目", list.all { it.allSources().isNotEmpty() })
    }

    @Test
    fun `id 全部带 iptv 前缀且全库唯一`() {
        assertTrue("有不含前缀的 id", list.all { it.id.startsWith("iptv:") })
        assertTrue("id 有重复", list.map { it.id }.toSet().size == list.size)
    }

    @Test
    fun `显示名全库唯一`() {
        val dup = list.groupingBy { it.name }.eachCount().filterValues { it > 1 }
        assertTrue("重名 ${dup.size} 组，例：${dup.keys.take(3)}", dup.isEmpty())
    }

    /**
     * 分辨率必须落在 quality 上、label 必须留空。
     * label 会被写进「正在尝试节目源 X…」状态文案与通知栏 artist 字段，
     * 一旦工具把 1080p 塞进 label，用户会在通知栏看到分辨率而不是台名。
     */
    @Test
    fun `label 一律留空，分辨率在 quality 上`() {
        val srcs = list.flatMap { it.sources }
        assertTrue("有 source 把内容写进了 label", srcs.all { it.label.isEmpty() })
        assertTrue("没有任何 quality（工具没读对字段）", srcs.any { it.quality.isNotBlank() })
    }

    @Test
    fun `category 只用 general，不出现内部 key`() {
        // StationCategory.labelOf 对未知 key 会原样回显，填 "tv" 会让收藏页显示内部值
        assertTrue(list.all { it.category == "general" })
    }

    @Test
    fun `direct 记录的 id 含 host 与哈希`() {
        val d = list.filter { it.id.startsWith("iptv:direct:") }
        assertTrue("没有 direct 记录（应有约两千条）", d.isNotEmpty())
        assertTrue("direct id 段数不对", d.all { it.id.split(":").size == 4 })
    }

    /** 别名必须真的入库：Task 5 的搜索靠它，工具丢掉 [string] 强转就会变空。 */
    @Test
    fun `至少有一部分条目带别名`() {
        val withAlias = list.count { it.altNames.isNotEmpty() }
        assertTrue("带别名的条目只有 $withAlias 个，工具可能没读 alt_names", withAlias > 100)
    }

    /**
     * header 必须真的入库：不入库的话那批流会大面积 403，而没有任何测试会红。
     *
     * 阈值取实测基线（2026-11-06：带 header 的流远多于 50）而不是 `> 0`：
     * `> 0` 只能防"整字段不写"，防不了"只丢一半"。丢一半同样会有一大批台点不响。
     */
    @Test
    fun `带 header 的流不少于基线`() {
        val withHeader = list.flatMap { it.sources }.count { it.userAgent.isNotBlank() || it.referrer.isNotBlank() }
        assertTrue("带 header 的流只有 $withHeader 条，基线至少 50；工具可能只读了一部分", withHeader >= 50)
    }

    /**
     * 上游误标核对：Retroclub / Tele Posadas 曾被 iptv-org 标成 is_nsfw=true，
     * 用户实机核实是普通频道（音乐台 / 阿根廷地方综合台），已在清单和
     * tools/iptv_sync.ps1 的白名单里修正。这里钉住"重跑同步不能再写回来"。
     *
     * 修正后当前清单 0 条成人台，所以原先「成人条目 >= 1」的断言撤销。
     * IptvAdult / IptvViewLogic 的过滤机制仍保留：上游将来真出现成人台，默认照样隐藏。
     * 判定规则本身由 IptvModelFieldsTest 用内联 JSON 覆盖，不依赖清单里有没有样本。
     */
    @Test
    fun `上游误标的两条普通频道不得再被标成成人`() {
        val ids = setOf("iptv:Retroclub.ar", "iptv:TelePosadas.ar")
        val hit = list.filter { it.id in ids }
        assertEquals(2, hit.size)
        assertTrue(hit.none { IptvAdult.isAdult(it) })
    }

    /** 国家名必须是中文或英文原名，不能是裸码 —— 裸码说明 ISO2_CN 与 countries.json 都没命中。 */
    @Test
    fun `有国家的条目不能显示成裸国家码`() {
        val bare = list.filter { it.country.isNotBlank() && it.country.matches(Regex("[A-Z]{2}")) }
        assertTrue("有 ${bare.size} 条国家显示成裸码，例：${bare.take(3).map { it.id }}", bare.isEmpty())
    }

    /**
     * 中国频道必须都能归到一个已知省份（或「全国」「其他」兜底）。
     *
     * 同步工具的 RegionOf 是关键词表 + 拼音匹配，表漏一条就会静默落进「其他」；
     * 这条断言把「每个中国频道都有归属」钉住，表改坏了会立刻红。
     */
    @Test
    fun `中国频道的省份必须落在已知集合内`() {
        val allowed = setOf(
            "全国", "其他",
            "北京", "天津", "上海", "重庆",
            "河北", "山西", "辽宁", "吉林", "黑龙江", "江苏", "浙江", "安徽", "福建", "江西",
            "山东", "河南", "湖北", "湖南", "广东", "海南", "四川", "贵州", "云南", "陕西",
            "甘肃", "青海", "台湾", "内蒙古", "广西", "西藏", "宁夏", "新疆", "香港", "澳门",
        )
        val cn = list.filter { it.country == "中国" }
        val bad = cn.filter { it.region.isBlank() || it.region !in allowed }
        assertTrue(
            "有 ${bad.size} 条中国频道省份为空或越界，例：${bad.take(3).map { "${it.id}=${it.region}" }}",
            bad.isEmpty(),
        )
    }

    /**
     * vpnOnly 不是上游字段，是两轮实测后由 tools/iptv_vpn_only.py 标注的。
     * 手改 assets 或重跑同步漏掉回填，标记会静默消失，用户就再也分不清哪些台需代理。
     * 这条把「清单里的标记」与「入仓的标注名单」钉成同一份真相。
     */
    @Test
    fun `需 VPN 标记与入仓名单完全一致`() {
        val candidates = listOf(
            File("tools/iptv_vpn_only.json"),
            File("../tools/iptv_vpn_only.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("找不到 tools/iptv_vpn_only.json，尝试过: $candidates")
        val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        val ids = root["stationIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue("需 VPN 名单为空", ids.isNotEmpty())
        val marked = list.filter { it.vpnOnly }.map { it.id }.toSet()
        assertEquals("assets 的 vpnOnly 标记与名单不一致", ids, marked)
    }
}
