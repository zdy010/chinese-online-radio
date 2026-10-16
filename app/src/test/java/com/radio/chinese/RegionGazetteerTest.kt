package com.radio.chinese

import com.radio.chinese.data.local.RegionGazetteer
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
}
