package com.radio.chinese

import com.radio.chinese.data.local.RegionGazetteer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 省市词表是「地区推断」与「数据」之间的闸：任何人手改漏了某个直辖市、
 * 或把城市映射到空省份，都必须在构建期变红，而不是运行时安静地排错序。
 */
class RegionGazetteerTest {
    private fun assetsFile(): File {
        val c = listOf(
            File("src/main/assets/region_gazetteer.json"),
            File("app/src/main/assets/region_gazetteer.json"),
        )
        return c.firstOrNull { it.exists() } ?: throw AssertionError("找不到 region_gazetteer.json: $c")
    }

    @Test
    fun `至少覆盖全部直辖市与绝大多数省份`() {
        val d = RegionGazetteer.parse(assetsFile().readText(Charsets.UTF_8))
        assertTrue("省份数应 >= 31，实际 ${d.provinceNames.size}", d.provinceNames.size >= 31)
        for (must in listOf("北京", "上海", "天津", "重庆")) {
            assertTrue("缺直辖市 $must", d.citiesToProvince.containsKey(must))
        }
    }

    @Test
    fun `每个城市映射到非空省份`() {
        val d = RegionGazetteer.parse(assetsFile().readText(Charsets.UTF_8))
        val bad = d.citiesToProvince.filterValues { it.isBlank() }
        assertTrue("有城市无省份: $bad", bad.isEmpty())
    }

    /**
     * 别名层：台标缩写（BTV）、别称（羊城）、盟与区县（锡林郭勒 / 开州）这几类
     * 光靠省市全名永远推不出来，451 个台里有 53 个因此掉进「其他」。
     * 它们进同一张命中表，RegionResolver 的匹配算法就不用改。
     */
    @Test
    fun `别名一并进命中表，且指向真实省份`() {
        val d = RegionGazetteer.parse(assetsFile().readText(Charsets.UTF_8))
        for ((alias, province) in mapOf(
            "BTV" to "北京",
            "羊城" to "广东",
            "锡林郭勒" to "内蒙古",
            "开州" to "重庆",
            "i-CABLE" to "香港",
            "楚天" to "湖北",
            "大象" to "河南",
        )) {
            assertEquals("别名 $alias 未正确进命中表", province, d.citiesToProvince[alias])
        }
        // 别名不能指向不存在的省，否则会在地区页凭空多出一个无法下钻的分组
        val unknown = d.citiesToProvince.filterValues { it !in d.provinceNames }
        assertTrue("别名指向了不存在的省份: $unknown", unknown.isEmpty())
    }

    /**
     * 别名不能与已有的省市键撞名：撞了就会被 putIfAbsent 静默丢弃，
     * 写的人以为生效了，实际没有。
     *
     * 这里走结构化解析而不是正则匹配 JSON 文本：词表是手工维护的紧凑单行格式，
     * 一旦有人用编辑器排一下版（本次改动就动过这个文件的行内格式），
     * 正则就会一行都匹不上、断言集体空转通过，防护网比没有更坑人。
     */
    @Test
    fun `别名不与已有省市键重名`() {
        val text = assetsFile().readText(Charsets.UTF_8)
        val provinces = Json.parseToJsonElement(text).jsonObject.getValue("provinces").jsonArray
        val cityKeys = HashSet<String>()
        val aliases = mutableListOf<Pair<String, String>>()
        for (el in provinces) {
            val p = el.jsonObject
            val name = p.getValue("name").jsonPrimitive.content
            cityKeys += name
            (p["cities"] ?: JsonArray(emptyList())).jsonArray.forEach {
                cityKeys += it.jsonPrimitive.content
            }
            (p["aliases"] ?: JsonArray(emptyList())).jsonArray.forEach {
                aliases += it.jsonPrimitive.content to name
            }
        }
        // 先兜住「解析管道整段失效」：不然下面两条断言会对着空集空转通过
        assertTrue("一个别名都没解析到，多半是词表结构变了", aliases.isNotEmpty())
        val clash = aliases.filter { (alias, _) -> alias in cityKeys }
        assertTrue("别名与省市键重名，会被静默忽略: $clash", clash.isEmpty())
        val dup = aliases.map { it.first }.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("同一别名指向多个省: $dup", dup.isEmpty())
    }

    /** 14 个别名全部要进命中表，不能只抽查一部分。 */
    @Test
    fun `词表里的每个别名都能被解析到`() {
        val d = RegionGazetteer.parse(assetsFile().readText(Charsets.UTF_8))
        val text = assetsFile().readText(Charsets.UTF_8)
        val provinces = Json.parseToJsonElement(text).jsonObject.getValue("provinces").jsonArray
        val declared = provinces.flatMap { el ->
            val p = el.jsonObject
            val name = p.getValue("name").jsonPrimitive.content
            (p["aliases"] ?: JsonArray(emptyList())).jsonArray.map {
                it.jsonPrimitive.content to name
            }
        }
        for ((alias, province) in declared) {
            assertEquals("别名 $alias 未映射到 $province", province, d.citiesToProvince[alias])
        }
    }
}
