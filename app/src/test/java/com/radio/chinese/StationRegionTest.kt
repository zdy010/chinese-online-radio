package com.radio.chinese

import com.radio.chinese.data.local.RegionGazetteer
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.RegionSection
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
    fun `境外台按国家归类，优先于省市推断`() {
        val buckets = StationRegion.bucketize(
            listOf(
                st("gl_us_abc", "KBS Global", "美国"),
                st("ad_de_x", "RauteMusik", "德国"),
                // 名字里带「上海」但国家不是中国，仍应算境外
                st("gl_gb_sh", "Shanghai Radio", "英国"),
            ),
            g,
        )
        assertEquals(
            listOf(
                StationRegion.countryKey("美国"),
                StationRegion.countryKey("英国"),
                StationRegion.countryKey("德国"),
            ).sorted(),
            buckets.map { it.key }.sorted(),
        )
        assertTrue("全部应落在国外段", buckets.all { it.section == RegionSection.FOREIGN })
    }

    @Test
    fun `境外台按国家分桶，台数降序`() {
        val buckets = StationRegion.bucketize(
            listOf(
                st("gl_us_a", "KBS Global", "美国"),
                st("gl_us_b", "Voice of America", "美国"),
                st("gl_gb_a", "BBC World Service", "英国"),
            ),
            g,
        )
        assertEquals(
            listOf(StationRegion.countryKey("美国"), StationRegion.countryKey("英国")),
            buckets.map { it.key },
        )
        assertEquals(2, buckets.first().stations.size)
    }

    @Test
    fun `country 写成 ISO 两字母码时组名给中文`() {
        // 真数据里 country 混着 PK / NP / MU 这类码，不翻就把英文缩写糊到界面上
        val buckets = StationRegion.bucketize(
            listOf(st("gl_pk_a", "Radio Pakistan", "PK")),
            g,
        )
        assertEquals("巴基斯坦", buckets.single().label)
        assertEquals(RegionSection.FOREIGN, buckets.single().section)
    }

    @Test
    fun `中国段排在国外段之前，本地组紧跟中央台`() {
        val buckets = StationRegion.bucketize(
            listOf(
                st("cnr1", "中国之声"),
                st("zj", "浙江新闻广播"),
                st("gl_us", "VOA", "美国"),
            ),
            g,
            userProvince = "浙江",
        )
        assertEquals(
            listOf(
                StationRegion.CENTRAL,
                StationRegion.LOCAL,
                StationRegion.provinceKey("浙江"),
                StationRegion.countryKey("美国"),
            ),
            buckets.map { it.key },
        )
    }

    @Test
    fun `台标缩写与别称把品牌台归到对应省`() {
        // 这几个台的共同点：台名里根本没有省市全名，光靠词表原样推不出来
        val buckets = StationRegion.bucketize(
            listOf(
                st("st_BTV", "BTV生活伴音", "中国", "中国的华语电视伴音电台"),
                st("st_yangcheng", "羊城交通广播"),
                st("st_xilin", "锡林郭勒汉语广播"),
                st("st_kaizhou", "开州之声"),
                st("st_chutian", "楚天交通广播"),
            ),
            g,
        )
        assertEquals(
            listOf("内蒙古", "北京", "广东", "重庆", "湖北").sorted(),
            buckets.map { it.label }.sorted(),
        )
        assertTrue("别称台属于中国段", buckets.all { it.section == RegionSection.CN })
    }

    @Test
    fun `CCTV 与 CETV 电视伴音进中央台`() {
        val buckets = StationRegion.bucketize(
            listOf(
                st("st_cctv1", "CCTV-1综合伴音", "中国", "中国的华语电视伴音电台"),
                st("st_cetv1", "CETV-1伴音", "中国", "中国的华语电视伴音电台"),
            ),
            g,
        )
        assertEquals(listOf(StationRegion.CENTRAL), buckets.map { it.key })
        assertEquals(2, buckets.single().stations.size)
    }

    @Test
    fun `中国香港归中国段的香港，不掉进国外段`() {
        val buckets = StationRegion.bucketize(
            listOf(st("gl_hk_cfe485780", "香港普通话台", "中国香港")),
            g,
        )
        val only = buckets.single()
        assertEquals(RegionSection.CN, only.section)
        assertEquals(StationRegion.provinceKey("香港"), only.key)
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
    fun `country 写成中国香港这类全称时不掉进境外`() {
        // 真数据里香港台的 country 存的是「中国香港」，不是裸「香港」。
        // 拿精确集合比对港澳名会把它们判成境外台（2026-10-21 code review 发现），
        // 用户按地区浏览香港时就找不到这几个台。
        val buckets = StationRegion.bucketize(
            listOf(
                st("gl_hk_cfe485780", "香港普通话台", "中国香港"),
                st("gl_mo_x", "澳门台", "中国澳门"),
            ),
            g,
        )
        assertTrue("港澳台不能落进国外段", buckets.none { it.section == RegionSection.FOREIGN })
        assertEquals(
            listOf(StationRegion.provinceKey("澳门"), StationRegion.provinceKey("香港")),
            buckets.map { it.key },
        )
    }

    @Test
    fun `国家码形式的 country 仍算国外，并且汉化后各自成组`() {
        // 放宽成前缀/包含判定后，不能让 PK、NP 这类两字母国家码顺手被放行；
        // 真数据里 country=PK 有 2 个台，它们确实属于国外
        val buckets = StationRegion.bucketize(
            listOf(
                st("gl_pk_a", "Radio Pakistan", "PK"),
                st("gl_np_a", "Radio Nepal", "NP"),
            ),
            g,
        )
        assertTrue("PK/NP 不能被判成国内", buckets.all { it.section == RegionSection.FOREIGN })
        assertEquals(
            listOf(StationRegion.countryKey("巴基斯坦"), StationRegion.countryKey("尼泊尔")).sorted(),
            buckets.map { it.key }.sorted(),
        )
    }

    @Test
    fun `country 为空的 gl 台归未标注国家，不混进中国段`() {
        val buckets = StationRegion.bucketize(listOf(st("gl_xx_abc", "Some Radio", "")), g)
        assertEquals(listOf(StationRegion.UNKNOWN_COUNTRY), buckets.map { it.key })
        assertEquals(RegionSection.FOREIGN, buckets.single().section)
    }

    @Test
    fun `ad_ 台没有 country 时从 id 里取国家码`() {
        // ad_ 前缀的台把国家码写在 id 里，这是它们唯一的国家线索；
        // 原来唯一那个 ad_ 用例带着 country，这条反解分支一次也没被跑到
        val buckets = StationRegion.bucketize(listOf(st("ad_de_y", "RauteMusik", "")), g)
        assertEquals(StationRegion.countryKey("德国"), buckets.single().key)
        assertEquals(RegionSection.FOREIGN, buckets.single().section)
    }

    @Test
    fun `认不出的国家码原样显示，不把台弄丢`() {
        val buckets = StationRegion.bucketize(listOf(st("gl_kk_x", "Unknown Radio", "KK")), g)
        assertEquals("KK", buckets.single().label)
        assertEquals(RegionSection.FOREIGN, buckets.single().section)
    }

    @Test
    fun `country 写成 ISO 码 CN 时仍算国内`() {
        // isDomestic 与 ISO2_CN 的口径必须一致：否则 country="CN" 会被判国外，
        // 在外段里生成一个 label 为「中国」的分组，跟同一页的「中国」段标题自相矛盾
        val buckets = StationRegion.bucketize(listOf(st("st_cn_x", "某地人民广播", "CN")), g)
        assertTrue("CN 不能落进国外段", buckets.none { it.section == RegionSection.FOREIGN })
    }

    @Test
    fun `国外段同台数时按国名排序`() {
        // countThenName 是中国省份与国外国家共用的，并列时的 tie-break 两边都要钉住
        val buckets = StationRegion.bucketize(
            listOf(
                st("gl_a1", "A", "英国"), st("gl_a2", "B", "英国"),
                st("gl_b1", "C", "美国"), st("gl_b2", "D", "美国"),
            ),
            g,
        )
        assertEquals(
            listOf(StationRegion.countryKey("美国"), StationRegion.countryKey("英国")),
            buckets.map { it.key },
        )
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
        // 原来拿「大象资讯台」当例子，但「大象」现在是河南的别名，它该被归位。
        // 换成真数据里确实推不出地区的网络台
        val buckets = StationRegion.bucketize(listOf(st("st_心灵音乐馆", "心灵音乐馆")), g)
        assertEquals(listOf(StationRegion.OTHER), buckets.map { it.key })
        assertEquals(RegionSection.CN, buckets.single().section)
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

    // ---- 电视伴音独立成组 ----
    // 以前“湖南卫视伴音”会被词表推成湖南、和湖南的电台排在同一组；CCTV-1 伴音则靠台名
    // 关键词进中央台。用户要的是：地区页里电视伴音自己一组，且不再在省份里重复出现。
    // 判定只看 category 一个来源，不再拿台名关键词猜内容类型。

    private fun byCat(id: String, name: String, category: String, country: String = "中国") =
        RadioStation(id = id, name = name, category = category, description = "", country = country)

    @Test
    fun `电视伴音独立成组，不再散进省份与中央台`() {
        val buckets = StationRegion.bucketize(
            listOf(
                byCat("st_cctv1", "CCTV-1综合伴音", "tv_audio"),
                byCat("st_hnws", "湖南卫视伴音", "tv_audio"),
                byCat("cnr1", "中国之声", "news"),
                byCat("zjxw", "浙江新闻广播", "news"),
            ),
            g,
        )
        assertEquals(
            listOf(
                StationRegion.CENTRAL,
                StationRegion.provinceKey("浙江"),
                StationRegion.TV_AUDIO,
            ),
            buckets.map { it.key },
        )
        val tv = buckets.last()
        assertEquals("电视伴音", tv.label)
        assertEquals(RegionSection.CN, tv.section)
        assertEquals(listOf("CCTV-1综合伴音", "湖南卫视伴音"), tv.stations.map { it.name }.sorted())
        assertTrue(
            "伴音不许同时计入所属省份，否则同一个台在两个分组里都能点到",
            buckets.first { it.key == StationRegion.provinceKey("浙江") }.stations
                .none { it.category == "tv_audio" },
        )
    }

    @Test
    fun `没有伴音时不产出空分组`() {
        val buckets = StationRegion.bucketize(listOf(byCat("zj2", "浙江新闻广播", "news")), g)
        assertTrue(buckets.none { it.key == StationRegion.TV_AUDIO })
    }

    @Test
    fun `写港澳或缺国家的伴音仍进伴音组，不掉进地区或国外段`() {
        // 凤凰中文台 country=中国香港、另一台 country 空但 id 是 gl_：两者以前
        // 会被地区页分到「香港」与「国家未标注」；内容形态优先于地域
        val buckets = StationRegion.bucketize(
            listOf(
                byCat("gl_hk_phx", "凤凰中文台", "tv_audio", "中国香港"),
                byCat("gl_us_no_country", "某外语卫视", "tv_audio", ""),
            ),
            g,
        )
        assertEquals(listOf(StationRegion.TV_AUDIO), buckets.map { it.key })
        assertEquals(RegionSection.CN, buckets.single().section)
        assertEquals(2, buckets.single().stations.size)
    }

    @Test
    fun `体育与戏曲分类的 CCTV 伴音仍归中央台`() {
        // 清单里 CCTV-5体育伴音= sports、CCTV-11戏曲伴音= opera（按内容分类的既定规则），
        // 它们不是 tv_audio，必须继续走台名关键词进中央台，不能掉进「其他」
        val buckets = StationRegion.bucketize(
            listOf(
                byCat("st_cctv5", "CCTV-5体育伴音", "sports"),
                byCat("st_cctv11", "CCTV-11戏曲伴音", "opera"),
            ),
            g,
        )
        assertEquals(listOf(StationRegion.CENTRAL), buckets.map { it.key })
    }
}
