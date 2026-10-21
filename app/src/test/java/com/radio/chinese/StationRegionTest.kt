package com.radio.chinese

import com.radio.chinese.data.local.RegionGazetteer
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 地区页的分组规则。钉住五类：央广/国际台归中央、境外优先于省市推断、
 * 本地组只是快捷入口（不把该省从省份列表里摘掉）、无地区线索进其他、空分组不产出。
 *
 * 用真词表跑，和 RegionResolverTest 同一套做法：避免「测试里的小词表能过、
 * 真词表里 34 个省 341 个市跑到线上就是另一回事」。
 */
class StationRegionTest {
    private val g = RegionGazetteer.parse(
        listOf(
            File("src/main/assets/region_gazetteer.json"),
            File("app/src/main/assets/region_gazetteer.json"),
        ).first { it.exists() }.readText(Charsets.UTF_8)
    )

    private fun st(id: String, name: String, country: String = "中国", desc: String = "") =
        RadioStation(id = id, name = name, category = "news", description = desc, country = country)

    @Test
    fun `央广与国际台归中央台，不落到其他`() {
        val buckets = StationRegion.bucketize(
            listOf(
                st("cnr1", "中国之声"),
                st("st_环球资讯广播", "环球资讯广播"),
                st("st_南海之声", "南海之声"),
            ),
            g,
        )
        assertEquals(listOf(StationRegion.CENTRAL), buckets.map { it.key })
        assertEquals(3, buckets.single().stations.size)
    }

    @Test
    fun `境外台按 id 与 country 判定，优先于省市推断`() {
        val buckets = StationRegion.bucketize(
            listOf(
                st("gl_us_abc", "BBC World Service", "美国"),
                st("ad_de_x", "RauteMusik", "德国"),
                // 名字里带「上海」但国家不是中国，仍应算境外
                st("gl_gb_sh", "Shanghai Radio", "英国"),
            ),
            g,
        )
        assertEquals(listOf(StationRegion.OVERSEAS), buckets.map { it.key })
        assertEquals(3, buckets.single().stations.size)
    }

    @Test
    fun `港澳台不算境外，按省市推断归到对应地区`() {
        // gl_ 是「全球源」的来源标记，里面混了 37 个台湾台：先看前缀会把这批台
        // 和一百多个欧美台一起塞进「境外台」，台湾用户就找不到自己的台了
        val buckets = StationRegion.bucketize(
            listOf(
                st("gl_tw_fm931", "警察廣播電臺 - 高雄分臺 FM 93.1", "台湾"),
                st("st_台北广播电台FM931", "台北广播电台FM93.1", "台湾"),
            ),
            g,
        )
        assertEquals(listOf(StationRegion.provinceKey("台湾")), buckets.map { it.key })
        assertEquals("台湾", buckets.single().label)
        assertEquals(2, buckets.single().stations.size)
    }

    @Test
    fun `country 为空的 gl 台仍算境外`() {
        val buckets = StationRegion.bucketize(listOf(st("gl_xx_abc", "Some Radio", "")), g)
        assertEquals(listOf(StationRegion.OVERSEAS), buckets.map { it.key })
    }

    @Test
    fun `本地组引用该省电台，且该省仍留在省份列表里`() {
        val buckets = StationRegion.bucketize(
            listOf(st("zj_traffic", "浙江交通之声"), st("hn_music", "湖南音乐之声")),
            g,
            userProvince = "浙江",
        )
        val local = buckets.first { it.key == StationRegion.LOCAL }
        assertEquals("本地 · 浙江", local.label)
        assertEquals(listOf("浙江交通之声"), local.stations.map { it.name })
        // 浙江不能因为被本地引用就从省份列表里消失：用户既要快捷入口，也要能按省浏览
        assertTrue(buckets.any { it.key == StationRegion.provinceKey("浙江") })
    }

    @Test
    fun `未设置地区时不产出本地组`() {
        val buckets = StationRegion.bucketize(listOf(st("zj_traffic", "浙江交通之声")), g)
        assertTrue(buckets.none { it.key == StationRegion.LOCAL })
    }

    @Test
    fun `无地区线索且非央广的台进其他`() {
        val buckets = StationRegion.bucketize(listOf(st("st_大象资讯台", "大象资讯台")), g)
        assertEquals(listOf(StationRegion.OTHER), buckets.map { it.key })
    }

    @Test
    fun `空列表不产出任何分组`() {
        assertTrue(StationRegion.bucketize(emptyList(), g).isEmpty())
    }

    @Test
    fun `省份按台数降序，同台数按省名`() {
        val buckets = StationRegion.bucketize(
            listOf(
                st("a", "湖南音乐之声"),
                st("b", "浙江交通之声"),
                st("c", "浙江之声"),
            ),
            g,
        )
        assertEquals(listOf("浙江", "湖南"), buckets.map { it.label })
    }
}
