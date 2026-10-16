# 线控播报 + 定位本地优先 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 线控/方控切台时先用中文 TTS 播报"即将播放：<名称>"再起新流；并按用户当前省/市把本地电台在列表与切台顺序里排前。

**Architecture:** 可判定逻辑全部做成 domain 纯函数（地区推断、最近城市、本地排序、下一台、音色评分），JVM 单测钉死；TTS/Location/DataStore 是薄 Android 适配层，出声与 GPS 上车机真机裁决。排序接入 `StationRepository.getActiveStations()`，播报接入 `PlayerManager.handleMediaSkip()` 这一唯一线控收敛点。

**Tech Stack:** Kotlin、Jetpack Compose、Hilt、Media3、DataStore、android.speech.tts.TextToSpeech、android.location.LocationManager、kotlinx.serialization、JUnit4。

**Spec:** `doc/specs/2026-10-16-voice-announce-local-sort-design.md`（执行时两份一起读，本计划从 spec 论证）

## Global Constraints

- 最低支持安卓 10（车机主力）；TTS 引擎可见性与定位权限须在安卓 10 与 14+ 都不崩。
- 单测命令：`./gradlew testDebugUnitTest --tests "<类名>"`；全量：`./gradlew testDebugUnitTest`。构建：`./gradlew assembleDebug`。
- 纯函数放 `domain`（不依赖 Android），Android 依赖只出现在 `service`/`data` 适配类。
- 资产 JSON 放 `app/src/main/assets/`，单测按现有 `StationJsonTest` 的候选路径方式直接读磁盘文件校验。
- 不改 `RadioStation` 领域模型字段、不改 `radio_stations.json`（地区一律运行时推断）。
- 播报失败/无引擎必须静默降级为"照常切台"，不得吞按键、不得无限等待。
- 遵循车机 UI 几何与字号规范：设置页新条目复用既有开关/行组件，不新增自定义 `Dimens`。
- versionCode = git 提交数；每个 Task 结尾单独提交，提交信息用 `feat:`/`test:` 前缀。

## Review Focus

以下是 spec 隐含但无哪个 Task 的单测专门覆盖、最可能在真机上咬人的五类输入，交付前须逐条人工确认：

1. **方易通车机无优质中文 TTS voice**：期望自动选优失败后播报静默跳过、切台照常出声；不得出现"按下无反应"或长时间静音。
2. **TTS 引擎初始化成功但 speak 永不回调 onDone**：期望 3.5s 超时兜底放行开播，不卡死。
3. **GPS 冷启动无 fix / 权限被拒**：期望 5s 超时后回退手动地区，列表仍出（只是不加分），不弹到崩溃。
4. **用户在境外**：期望最近城市超阈值 → region 空 → 不加分、顺序等同现状，绝不硬塞一个错误城市。
5. **本地加分把用户当前正在听的台挤出相邻位**：期望切台仍按"列表相邻"语义前进，`nextStation` 基于已排序列表计算，与首页所见顺序一致。

---

## Task 1: Region 模型 + 省市词表资产

**Files:**
- Create: `app/src/main/java/com/radio/chinese/domain/model/Region.kt`
- Create: `app/src/main/java/com/radio/chinese/data/local/RegionGazetteer.kt`
- Create: `app/src/main/assets/region_gazetteer.json`
- Test: `app/src/test/java/com/radio/chinese/RegionGazetteerTest.kt`

**Interfaces:**
- Produces: `data class Region(val province: String, val city: String)`，`Region.NONE`；`object RegionGazetteer { data class Data(val citiesToProvince: Map<String,String>, val provinceNames: List<String>); fun parse(json: String): Data }`

- [ ] **Step 1: 写失败测试（词表合法性）**

```kotlin
package com.radio.chinese

import com.radio.chinese.data.local.RegionGazetteer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RegionGazetteerTest {
    private fun assetsFile(): File {
        val c = listOf(File("src/main/assets/region_gazetteer.json"), File("app/src/main/assets/region_gazetteer.json"))
        return c.firstOrNull { it.exists() } ?: throw AssertionError("找不到 region_gazetteer.json: $c")
    }

    @Test fun `至少覆盖全部直辖市与绝大多数省份`() {
        val d = RegionGazetteer.parse(assetsFile().readText(Charsets.UTF_8))
        assertTrue("省份数应 >= 31，实际 ${d.provinceNames.size}", d.provinceNames.size >= 31)
        for (must in listOf("北京", "上海", "天津", "重庆")) {
            assertTrue("缺直辖市 $must", d.citiesToProvince.containsKey(must))
        }
    }

    @Test fun `每个城市映射到非空省份`() {
        val d = RegionGazetteer.parse(assetsFile().readText(Charsets.UTF_8))
        val bad = d.citiesToProvince.filterValues { it.isBlank() }
        assertTrue("有城市无省份: $bad", bad.isEmpty())
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.RegionGazetteerTest"`
Expected: 编译失败（`RegionGazetteer`/`Region` 未定义）

- [ ] **Step 3: 写 Region 模型**

```kotlin
package com.radio.chinese.domain.model

/** 电台或用户所处的省/市级地区。空省市用 NONE 表示“未知/不参与本地加分”。 */
data class Region(val province: String = "", val city: String = "") {
    val isEmpty: Boolean get() = province.isBlank() && city.isBlank()
    companion object { val NONE = Region() }
```

（补右括号 `}`）

- [ ] **Step 4: 写词表解析（kotlinx.serialization）**

```kotlin
package com.radio.chinese.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class GazetteerRaw(val provinces: List<ProvinceRaw> = emptyList())

@Serializable
private data class ProvinceRaw(val name: String, val cities: List<String> = emptyList())

object RegionGazetteer {
    data class Data(
        val citiesToProvince: Map<String, String>,
        val provinceNames: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Data {
        val raw = json.decodeFromString<GazetteerRaw>(text)
        val map = LinkedHashMap<String, String>()
        for (p in raw.provinces) {
            // 省份本身也作为一个“城市”键（省台命中省名）
            map.putIfAbsent(p.name, p.name)
            for (c in p.cities) map.putIfAbsent(c, p.name)
        }
        return Data(map, raw.provinces.map { it.name })
    }
}
```

- [ ] **Step 5: 写资产 `region_gazetteer.json`**

内容（逐字写入，覆盖 34 省级行政区 + 各地级市；executor 直接抄本块）：

```json
{
  "provinces": [
    {"name":"北京","cities":["北京"]},
    {"name":"天津","cities":["天津"]},
    {"name":"河北","cities":["石家庄","唐山","秦皇岛","邯郸","邢台","保定","张家口","承德","沧州","廊坊","衡水"]},
    {"name":"山西","cities":["太原","大同","阳泉","长治","晋城","朔州","晋中","运城","忻州","临汾","吕梁"]},
    {"name":"内蒙古","cities":["呼和浩特","包头","乌海","赤峰","通辽","鄂尔多斯","呼伦贝尔","巴彦淖尔","乌兰察布"]},
    {"name":"辽宁","cities":["沈阳","大连","鞍山","抚顺","本溪","丹东","锦州","营口","阜新","辽阳","盘锦","铁岭","朝阳","葫芦岛"]},
    {"name":"吉林","cities":["长春","吉林","四平","辽源","通化","白山","松原","白城","延边"]},
    {"name":"黑龙江","cities":["哈尔滨","齐齐哈尔","鸡西","鹤岗","双鸭山","大庆","伊春","佳木斯","七台河","牡丹江","黑河","绥化","大兴安岭"]},
    {"name":"上海","cities":["上海"]},
    {"name":"江苏","cities":["南京","无锡","徐州","常州","苏州","南通","连云港","淮安","盐城","扬州","镇江","泰州","宿迁"]},
    {"name":"浙江","cities":["杭州","宁波","温州","嘉兴","湖州","绍兴","金华","衢州","舟山","台州","丽水"]},
    {"name":"安徽","cities":["合肥","芜湖","蚌埠","淮南","马鞍山","淮北","铜陵","安庆","黄山","滁州","阜阳","宿州","六安","亳州","池州","宣城"]},
    {"name":"福建","cities":["福州","厦门","莆田","三明","泉州","漳州","南平","龙岩","宁德"]},
    {"name":"江西","cities":["南昌","景德镇","萍乡","九江","新余","鹰潭","赣州","吉安","宜春","抚州","上饶"]},
    {"name":"山东","cities":["济南","青岛","淄博","枣庄","东营","烟台","潍坊","济宁","泰安","威海","日照","临沂","德州","聊城","滨州","菏泽"]},
    {"name":"河南","cities":["郑州","开封","洛阳","平顶山","安阳","鹤壁","新乡","焦作","濮阳","许昌","漯河","三门峡","南阳","商丘","信阳","周口","驻马店"]},
    {"name":"湖北","cities":["武汉","黄石","十堰","宜昌","襄阳","鄂州","荆门","孝感","荆州","黄冈","咸宁","随州","恩施"]},
    {"name":"湖南","cities":["长沙","株洲","湘潭","衡阳","邵阳","岳阳","常德","张家界","益阳","郴州","永州","怀化","娄底","湘西"]},
    {"name":"广东","cities":["广州","韶关","深圳","珠海","汕头","佛山","江门","湛江","茂名","肇庆","惠州","梅州","汕尾","河源","阳江","清远","东莞","中山","潮州","揭阳","云浮"]},
    {"name":"广西","cities":["南宁","柳州","桂林","梧州","北海","防城港","钦州","贵港","玉林","百色","贺州","河池","来宾","崇左"]},
    {"name":"海南","cities":["海口","三亚","三沙","儋州"]},
    {"name":"重庆","cities":["重庆"]},
    {"name":"四川","cities":["成都","自贡","攀枝花","泸州","德阳","绵阳","广元","遂宁","内江","乐山","南充","眉山","宜宾","广安","达州","雅安","巴中","资阳","阿坝","甘孜","凉山"]},
    {"name":"贵州","cities":["贵阳","六盘水","遵义","安顺","毕节","铜仁","黔西南","黔东南","黔南"]},
    {"name":"云南","cities":["昆明","曲靖","玉溪","保山","昭通","丽江","普洱","临沧","楚雄","红河","文山","西双版纳","大理","德宏","怒江","迪庆"]},
    {"name":"西藏","cities":["拉萨","日喀则","昌都","林芝","山南","那曲","阿里"]},
    {"name":"陕西","cities":["西安","铜川","宝鸡","咸阳","渭南","延安","汉中","榆林","安康","商洛"]},
    {"name":"甘肃","cities":["兰州","嘉峪关","金昌","白银","天水","武威","张掖","平凉","酒泉","庆阳","定西","陇南","临夏","甘南"]},
    {"name":"青海","cities":["西宁","海东","海北","黄南","海南","果洛","玉树","海西"]},
    {"name":"宁夏","cities":["银川","石嘴山","吴忠","固原","中卫"]},
    {"name":"新疆","cities":["乌鲁木齐","克拉玛依","吐鲁番","哈密","昌吉","博尔塔拉","巴音郭楞","阿克苏","喀什","和田","伊犁","塔城","阿勒泰"]},
    {"name":"台湾","cities":["台北","高雄","台中","台南","新北","桃园"]},
    {"name":"香港","cities":["香港"]},
    {"name":"澳门","cities":["澳门"]}
  ]
}
```

- [ ] **Step 6: 运行，确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.RegionGazetteerTest"`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/Region.kt app/src/main/java/com/radio/chinese/data/local/RegionGazetteer.kt app/src/main/assets/region_gazetteer.json app/src/test/java/com/radio/chinese/RegionGazetteerTest.kt
git commit -m "feat: add Region model and province-city gazetteer asset"
```

## Task 2: RegionResolver 地区推断（纯函数）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/domain/model/RegionResolver.kt`
- Test: `app/src/test/java/com/radio/chinese/RegionResolverTest.kt`

**Interfaces:**
- Consumes: `RegionGazetteer.Data`（Task 1）
- Produces: `object RegionResolver { fun infer(text: String, country: String, gazetteer: RegionGazetteer.Data): Region }`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.radio.chinese

import com.radio.chinese.data.local.RegionGazetteer
import com.radio.chinese.domain.model.Region
import com.radio.chinese.domain.model.RegionResolver
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class RegionResolverTest {
    private val g = RegionGazetteer.parse(
        listOf(File("src/main/assets/region_gazetteer.json"), File("app/src/main/assets/region_gazetteer.json"))
            .first { it.exists() }.readText(Charsets.UTF_8)
    )

    @Test fun `城市名命中带出所属省`() {
        assertEquals(Region("浙江", "绍兴"), RegionResolver.infer("绍兴之声", "中国", g))
        assertEquals(Region("辽宁", "大连"), RegionResolver.infer("DLR-3 大连体育广播", "中国", g))
    }

    @Test fun `只有省名时城市为空`() {
        assertEquals(Region("河南", ""), RegionResolver.infer("河南交通广播", "中国", g))
    }

    @Test fun `无地区词返回 NONE`() {
        assertEquals(Region.NONE, RegionResolver.infer("中国之声", "中国", g))
        assertEquals(Region.NONE, RegionResolver.infer("CCTV-1综合伴音", "中国", g))
    }

    @Test fun `歧义词取文本中最靠前的城市命中`() {
        // “东方”不是城市，“上海”在“东方卫视伴音”里没有——但简介含“上海”则命中
        assertEquals(Region("上海", "上海"), RegionResolver.infer("上海 东方卫视伴音", "中国", g))
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.RegionResolverTest"`
Expected: 编译失败（`RegionResolver` 未定义）

- [ ] **Step 3: 实现（取首个命中，城市优先于省）**

```kotlin
package com.radio.chinese.domain.model

import com.radio.chinese.data.local.RegionGazetteer

/**
 * 从台名/简介文本反推省/市。规则：扫描所有出现的城市键，取在文本中位置最靠前的一个；
 * 命中的是省份键则只填省、市留空。无命中 => NONE。
 * 纯函数、不碰 Android，单测直接拿真词表跑。
 */
object RegionResolver {
    fun infer(text: String, country: String, gazetteer: RegionGazetteer.Data): Region {
        if (text.isBlank()) return Region.NONE
        var bestKey: String? = null
        var bestPos = Int.MAX_VALUE
        for (key in gazetteer.citiesToProvince.keys) {
            val pos = text.indexOf(key)
            if (pos in 0 until bestPos) { bestPos = pos; bestKey = key }
        }
        val hit = bestKey ?: return Region.NONE
        val province = gazetteer.citiesToProvince.getValue(hit)
        return if (hit == province) Region(province = province, city = "")
               else Region(province = province, city = hit)
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.RegionResolverTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/RegionResolver.kt app/src/test/java/com/radio/chinese/RegionResolverTest.kt
git commit -m "feat: add RegionResolver station region inference with tests"
```

## Task 3: 城市坐标表 + NearestCityFinder（纯函数）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/data/local/CityCoordinates.kt`
- Create: `app/src/main/assets/city_coordinates.json`
- Create: `app/src/main/java/com/radio/chinese/domain/model/NearestCityFinder.kt`
- Test: `app/src/test/java/com/radio/chinese/NearestCityFinderTest.kt`

**Interfaces:**
- Produces: `data class CityPoint(val name: String, val province: String, val lat: Double, val lng: Double)`；`object CityCoordinates { fun parse(json: String): List<CityPoint> }`；`object NearestCityFinder { fun find(lat: Double, lng: Double, cities: List<CityPoint>, maxKm: Double = 120.0): Region? }`

- [ ] **Step 1: 写失败测试（坐标表合法性 + 最近城市 + 阈值）**

```kotlin
package com.radio.chinese

import com.radio.chinese.data.local.CityCoordinates
import com.radio.chinese.domain.model.NearestCityFinder
import com.radio.chinese.domain.model.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class NearestCityFinderTest {
    private val cities = CityCoordinates.parse(
        listOf(File("src/main/assets/city_coordinates.json"), File("app/src/main/assets/city_coordinates.json"))
            .first { it.exists() }.readText(Charsets.UTF_8)
    )

    @Test fun `表覆盖 100 个以上城市`() {
        assertTrue("实际 ${cities.size}", cities.size >= 100)
    }

    @Test fun `北京坐标命中北京`() {
        val r = NearestCityFinder.find(39.90, 116.40, cities)
        assertEquals("北京", r?.city)
    }

    @Test fun `上海坐标命中上海`() {
        assertEquals(Region("上海", "上海"), NearestCityFinder.find(31.23, 121.47, cities))
    }

    @Test fun `境外坐标超阈值返回 null`() {
        assertNull(NearestCityFinder.find(51.50, -0.12, cities)) // 伦敦
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.NearestCityFinderTest"`
Expected: 编译失败

- [ ] **Step 3: 写坐标解析**

```kotlin
package com.radio.chinese.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CityPoint(val name: String, val province: String, val lat: Double, val lng: Double)

@Serializable
private data class CityListRaw(val cities: List<CityPoint> = emptyList())

object CityCoordinates {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): List<CityPoint> = json.decodeFromString<CityListRaw>(text).cities
}
```

- [ ] **Step 4: 写 `city_coordinates.json`（各省级行政区首府/主要城市中心坐标，逐字抄本块）**

```json
{
  "cities": [
    {"name":"北京","province":"北京","lat":39.90,"lng":116.40},
    {"name":"天津","province":"天津","lat":39.13,"lng":117.20},
    {"name":"石家庄","province":"河北","lat":38.04,"lng":114.51},
    {"name":"唐山","province":"河北","lat":39.63,"lng":118.18},
    {"name":"秦皇岛","province":"河北","lat":39.94,"lng":119.60},
    {"name":"邯郸","province":"河北","lat":36.63,"lng":114.50},
    {"name":"保定","province":"河北","lat":38.87,"lng":115.47},
    {"name":"廊坊","province":"河北","lat":39.52,"lng":116.68},
    {"name":"太原","province":"山西","lat":37.87,"lng":112.55},
    {"name":"大同","province":"山西","lat":40.08,"lng":113.30},
    {"name":"呼和浩特","province":"内蒙古","lat":40.84,"lng":111.75},
    {"name":"包头","province":"内蒙古","lat":40.66,"lng":109.84},
    {"name":"赤峰","province":"内蒙古","lat":42.26,"lng":118.97},
    {"name":"沈阳","province":"辽宁","lat":41.80,"lng":123.43},
    {"name":"大连","province":"辽宁","lat":38.91,"lng":121.60},
    {"name":"鞍山","province":"辽宁","lat":41.11,"lng":122.99},
    {"name":"长春","province":"吉林","lat":43.88,"lng":125.32},
    {"name":"吉林","province":"吉林","lat":43.84,"lng":126.55},
    {"name":"哈尔滨","province":"黑龙江","lat":45.80,"lng":126.53},
    {"name":"大庆","province":"黑龙江","lat":46.59,"lng":125.10},
    {"name":"齐齐哈尔","province":"黑龙江","lat":47.35,"lng":123.97},
    {"name":"上海","province":"上海","lat":31.23,"lng":121.47},
    {"name":"南京","province":"江苏","lat":32.06,"lng":118.80},
    {"name":"无锡","province":"江苏","lat":31.49,"lng":120.31},
    {"name":"苏州","province":"江苏","lat":31.30,"lng":120.62},
    {"name":"南通","province":"江苏","lat":31.98,"lng":120.89},
    {"name":"扬州","province":"江苏","lat":32.39,"lng":119.42},
    {"name":"徐州","province":"江苏","lat":34.26,"lng":117.19},
    {"name":"常州","province":"江苏","lat":31.81,"lng":119.97},
    {"name":"杭州","province":"浙江","lat":30.27,"lng":120.16},
    {"name":"宁波","province":"浙江","lat":29.87,"lng":121.55},
    {"name":"温州","province":"浙江","lat":28.00,"lng":120.67},
    {"name":"绍兴","province":"浙江","lat":30.00,"lng":120.58},
    {"name":"嘉兴","province":"浙江","lat":30.75,"lng":120.75},
    {"name":"金华","province":"浙江","lat":29.08,"lng":119.65},
    {"name":"台州","province":"浙江","lat":28.66,"lng":121.42},
    {"name":"合肥","province":"安徽","lat":31.82,"lng":117.23},
    {"name":"芜湖","province":"安徽","lat":31.35,"lng":118.43},
    {"name":"阜阳","province":"安徽","lat":32.90,"lng":115.81},
    {"name":"福州","province":"福建","lat":26.07,"lng":119.30},
    {"name":"厦门","province":"福建","lat":24.48,"lng":118.09},
    {"name":"泉州","province":"福建","lat":24.87,"lng":118.68},
    {"name":"南昌","province":"江西","lat":28.68,"lng":115.89},
    {"name":"赣州","province":"江西","lat":25.85,"lng":114.94},
    {"name":"济南","province":"山东","lat":36.65,"lng":117.12},
    {"name":"青岛","province":"山东","lat":36.07,"lng":120.38},
    {"name":"烟台","province":"山东","lat":37.46,"lng":121.45},
    {"name":"潍坊","province":"山东","lat":36.71,"lng":119.16},
    {"name":"临沂","province":"山东","lat":35.10,"lng":118.35},
    {"name":"郑州","province":"河南","lat":34.75,"lng":113.62},
    {"name":"洛阳","province":"河南","lat":34.62,"lng":112.45},
    {"name":"南阳","province":"河南","lat":32.99,"lng":112.53},
    {"name":"武汉","province":"湖北","lat":30.59,"lng":114.30},
    {"name":"宜昌","province":"湖北","lat":30.69,"lng":111.29},
    {"name":"襄阳","province":"湖北","lat":32.04,"lng":112.14},
    {"name":"长沙","province":"湖南","lat":28.23,"lng":112.94},
    {"name":"株洲","province":"湖南","lat":27.83,"lng":113.13},
    {"name":"衡阳","province":"湖南","lat":26.89,"lng":112.57},
    {"name":"广州","province":"广东","lat":23.13,"lng":113.26},
    {"name":"深圳","province":"广东","lat":22.54,"lng":114.06},
    {"name":"珠海","province":"广东","lat":22.27,"lng":113.57},
    {"name":"汕头","province":"广东","lat":23.35,"lng":116.68},
    {"name":"佛山","province":"广东","lat":23.02,"lng":113.12},
    {"name":"东莞","province":"广东","lat":23.02,"lng":113.75},
    {"name":"湛江","province":"广东","lat":21.27,"lng":110.36},
    {"name":"南宁","province":"广西","lat":22.82,"lng":108.32},
    {"name":"柳州","province":"广西","lat":24.31,"lng":109.43},
    {"name":"桂林","province":"广西","lat":25.27,"lng":110.29},
    {"name":"海口","province":"海南","lat":20.04,"lng":110.35},
    {"name":"三亚","province":"海南","lat":18.25,"lng":109.51},
    {"name":"重庆","province":"重庆","lat":29.56,"lng":106.55},
    {"name":"成都","province":"四川","lat":30.57,"lng":104.07},
    {"name":"绵阳","province":"四川","lat":31.47,"lng":104.68},
    {"name":"宜宾","province":"四川","lat":28.78,"lng":104.63},
    {"name":"泸州","province":"四川","lat":28.87,"lng":105.44},
    {"name":"贵阳","province":"贵州","lat":26.65,"lng":106.63},
    {"name":"遵义","province":"贵州","lat":27.73,"lng":106.93},
    {"name":"昆明","province":"云南","lat":25.04,"lng":102.71},
    {"name":"丽江","province":"云南","lat":26.86,"lng":100.23},
    {"name":"大理","province":"云南","lat":25.59,"lng":100.23},
    {"name":"拉萨","province":"西藏","lat":29.65,"lng":91.14},
    {"name":"西安","province":"陕西","lat":34.34,"lng":108.94},
    {"name":"延安","province":"陕西","lat":36.60,"lng":109.49},
    {"name":"兰州","province":"甘肃","lat":36.06,"lng":103.83},
    {"name":"敦煌","province":"甘肃","lat":40.14,"lng":94.66},
    {"name":"西宁","province":"青海","lat":36.62,"lng":101.78},
    {"name":"银川","province":"宁夏","lat":38.49,"lng":106.41},
    {"name":"乌鲁木齐","province":"新疆","lat":43.83,"lng":87.62},
    {"name":"喀什","province":"新疆","lat":39.47,"lng":75.99},
    {"name":"伊犁","province":"新疆","lat":43.92,"lng":81.32},
    {"name":"台北","province":"台湾","lat":25.03,"lng":121.57},
    {"name":"高雄","province":"台湾","lat":22.62,"lng":120.30},
    {"name":"香港","province":"香港","lat":22.32,"lng":114.17},
    {"name":"澳门","province":"澳门","lat":22.20,"lng":113.55}
  ]
}
```

- [ ] **Step 5: 写 NearestCityFinder（haversine）**

```kotlin
package com.radio.chinese.domain.model

import com.radio.chinese.data.local.CityPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 经纬度 -> 最近城市（带最大距离阈值，超出视为“无本地城市”，人在境外/表外）。 */
object NearestCityFinder {
    private const val EARTH_R_KM = 6371.0

    fun find(lat: Double, lng: Double, cities: List<CityPoint>, maxKm: Double = 120.0): Region? {
        var best: CityPoint? = null
        var bestKm = Double.MAX_VALUE
        for (c in cities) {
            val km = haversineKm(lat, lng, c.lat, c.lng)
            if (km < bestKm) { bestKm = km; best = c }
        }
        if (best == null || bestKm > maxKm) return null
        return Region(province = best!!.province, city = best!!.name)
    }

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return EARTH_R_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
```

- [ ] **Step 6: 运行，确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.NearestCityFinderTest"`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/radio/chinese/data/local/CityCoordinates.kt app/src/main/assets/city_coordinates.json app/src/main/java/com/radio/chinese/domain/model/NearestCityFinder.kt app/src/test/java/com/radio/chinese/NearestCityFinderTest.kt
git commit -m "feat: add city coordinates asset and NearestCityFinder"
```

## Task 4: StationSorter 本地优先排序（纯函数）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/domain/model/StationSorter.kt`
- Test: `app/src/test/java/com/radio/chinese/StationSorterTest.kt`

**Interfaces:**
- Consumes: `Region`、`RegionResolver`（Task 1/2）、`RadioStation`
- Produces: `object StationSorter { fun tierOf(region: Region, user: Region): Int; fun localPriority(stations: List<RadioStation>, user: Region, regionOf: (RadioStation)->Region): List<RadioStation> }`

- [ ] **Step 1: 写失败测试（分层 + 稳定性 + 空地区原样）**

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.Region
import com.radio.chinese.domain.model.StationSorter
import org.junit.Assert.assertEquals
import org.junit.Test

class StationSorterTest {
    private fun st(id: String) = RadioStation(id = id, name = id, category = "general")

    private val regionMap = mapOf(
        "sameCity" to Region("浙江", "绍兴"),
        "sameProv" to Region("浙江", "杭州"),
        "sameCountry" to Region("河南", ""),
        "foreign" to Region("美国", ""),
        "none" to Region.NONE,
    )
    private val regionOf: (RadioStation) -> Region = { regionMap.getValue(it.id) }

    @Test fun `tier 分档`() {
        val user = Region("浙江", "绍兴")
        assertEquals(3, StationSorter.tierOf(Region("浙江", "绍兴"), user))
        assertEquals(2, StationSorter.tierOf(Region("浙江", "杭州"), user))
        assertEquals(1, StationSorter.tierOf(Region("河南", ""), user)) // 同国不同省
        assertEquals(0, StationSorter.tierOf(Region("美国", ""), user))
        assertEquals(0, StationSorter.tierOf(Region.NONE, user))
    }

    @Test fun `按 tier 降序且同 tier 保持入参顺序`() {
        val input = listOf(st("foreign"), st("sameProv"), st("none"), st("sameCity"), st("sameCountry"))
        val out = StationSorter.localPriority(input, Region("浙江", "绍兴"), regionOf).map { it.id }
        assertEquals(listOf("sameCity", "sameProv", "sameCountry", "foreign", "none"), out)
    }

    @Test fun `空用户地区时原样返回`() {
        val input = listOf(st("sameProv"), st("foreign"))
        val out = StationSorter.localPriority(input, Region.NONE, regionOf).map { it.id }
        assertEquals(listOf("sameProv", "foreign"), out)
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.StationSorterTest"`
Expected: 编译失败

- [ ] **Step 3: 实现**

```kotlin
package com.radio.chinese.domain.model

/**
 * 本地优先排序。tier：同市=3 / 同省=2 / 同国=1 / 其余=0。
 * “同国”判定用中国台约定——用户在中国大陆/中国台才享受 tier1，避免国外误判。
 * sortedByDescending 是稳定排序，同 tier 保持入参相对顺序。
 */
object StationSorter {
    fun tierOf(region: Region, user: Region): Int {
        if (user.isEmpty || region.isEmpty) return 0
        if (region.city.isNotEmpty() && region.city == user.city) return 3
        if (region.province == user.province) return 2
        // 省级相同已 return；剩下按“中国台”给 tier1（数据里非中国用国家名区分）
        if (isChina(region) && isChina(user)) return 1
        return 0
    }

    private fun isChina(r: Region): Boolean = r.province in CHINA_PROVINCES

    private val CHINA_PROVINCES = setOf(
        "北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽",
        "福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南",
        "西藏","陕西","甘肃","青海","宁夏","新疆"
    )

    fun localPriority(
        stations: List<RadioStation>,
        user: Region,
        regionOf: (RadioStation) -> Region,
    ): List<RadioStation> {
        if (user.isEmpty) return stations
        return stations.sortedByDescending { tierOf(regionOf(it), user) }
    }
}
```

> 注：测试里 `sameCountry`(河南) 用户(浙江绍兴) → tier 1 需 user 也判为中国。测试 `tier` 分档用 `isChina(河南)=true` 且 `isChina(浙江绍兴)=true` → 1，符合。`foreign`(美国) → `isChina=false` → 0。

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.StationSorterTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/StationSorter.kt app/src/test/java/com/radio/chinese/StationSorterTest.kt
git commit -m "feat: add StationSorter local-priority stable sort"
```

## Task 5: nextStation 下一台解析（纯函数）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/service/PlayerManager.kt`（新增 companion/顶层纯函数，`playNext`/`playPrevious` 改为调用它）
- Test: `app/src/test/java/com/radio/chinese/NextStationTest.kt`

**Interfaces:**
- Produces: `fun nextStation(stations: List<RadioStation>, currentId: String?, forward: Boolean): RadioStation?`（顶层纯函数，与 `PlayerManager` 同包 `com.radio.chinese.service`）

- [ ] **Step 1: 写失败测试**

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.nextStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextStationTest {
    private fun st(id: String) = RadioStation(id = id, name = id, category = "general")
    private val list = listOf(st("a"), st("b"), st("c"))

    @Test fun `中间前进取下一个`() {
        assertEquals("b", nextStation(list, "a", true)?.id)
        assertEquals("c", nextStation(list, "b", true)?.id)
    }
    @Test fun `末位前进回绕首位`() {
        assertEquals("a", nextStation(list, "c", true)?.id)
    }
    @Test fun `首位后退回绕末位`() {
        assertEquals("c", nextStation(list, "a", false)?.id)
    }
    @Test fun `当前不在列表时前进取首位`() {
        assertEquals("a", nextStation(list, "x", true)?.id)
    }
    @Test fun `空列表返回 null`() {
        assertNull(nextStation(emptyList(), "a", true))
    }
    @Test fun `currentId 为空前进取首位`() {
        assertEquals("a", nextStation(list, null, true)?.id)
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.NextStationTest"`
Expected: 编译失败（`nextStation` 未定义）

- [ ] **Step 3: 在 PlayerManager.kt 末尾（类外，包级）新增纯函数**

```kotlin
/**
 * 线控切台的“下一个/上一个”目标解析（纯函数，供播报与播放复用）。
 * 与旧 playNext/playPrevious 语义一致：命中当前台取相邻；末位前进回绕；
 * 当前台不在列表（含 null）时前进取首位、后退取末位。
 */
fun nextStation(stations: List<RadioStation>, currentId: String?, forward: Boolean): RadioStation? {
    if (stations.isEmpty()) return null
    val idx = stations.indexOfFirst { it.id == currentId }
    if (idx < 0) return if (forward) stations.first() else stations.last()
    val next = if (forward) (idx + 1) % stations.size
               else (idx - 1 + stations.size) % stations.size
    return stations[next]
}
```

- [ ] **Step 4: 让 playNext/playPrevious 复用（保持既有行为不变）**

把 `PlayerManager.playNext` 改为：
```kotlin
fun playNext(stations: List<RadioStation>) {
    val target = nextStation(stations, _currentStation.value?.id, forward = true) ?: return
    playStation(target)
}
fun playPrevious(stations: List<RadioStation>) {
    val target = nextStation(stations, _currentStation.value?.id, forward = false) ?: return
    playStation(target)
}
```

- [ ] **Step 5: 运行单测 + 全量回归，确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.NextStationTest"`
Expected: PASS；随后 `./gradlew testDebugUnitTest` 全绿（既有切台行为未变）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/radio/chinese/service/PlayerManager.kt app/src/test/java/com/radio/chinese/NextStationTest.kt
git commit -m "refactor: extract nextStation pure function and reuse in playNext/playPrevious"
```

## Task 6: VoiceScoring 音色评分（纯函数）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/domain/model/VoiceScoring.kt`
- Test: `app/src/test/java/com/radio/chinese/VoiceScoringTest.kt`

**Interfaces:**
- Produces: `data class VoiceMeta(val name: String, val localeTag: String, val isNetwork: Boolean, val isPremium: Boolean, val isCheap: Boolean)`；`object VoiceScoring { fun rank(v: VoiceMeta): Int; fun isChinese(v: VoiceMeta): Boolean; fun bestChinese(list: List<VoiceMeta>): VoiceMeta? }`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.VoiceMeta
import com.radio.chinese.domain.model.VoiceScoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceScoringTest {
    private fun v(name: String, lang: String = "zh-CN", net: Boolean = false, prem: Boolean = false, cheap: Boolean = false) =
        VoiceMeta(name, lang, net, prem, cheap)

    @Test fun `中文音色优先于非中文`() {
        assertNull(VoiceScoring.bestChinese(listOf(v("english", "en-US"))))
    }

    @Test fun `premium 胜过普通`() {
        val premium = v("zh premium", prem = true)
        val plain = v("zh plain")
        assertEquals(premium, VoiceScoring.bestChinese(listOf(plain, premium)))
    }

    @Test fun `联网音色扣分`() {
        val network = v("zh net", net = true)
        val cheap = v("zh cheap", cheap = true)
        assertEquals(cheap, VoiceScoring.bestChinese(listOf(network, cheap)))
    }

    @Test fun `无候选返回 null 而非崩溃`() {
        assertNull(VoiceScoring.bestChinese(emptyList()))
    }

    @Test fun `跨多个引擎选最自然的那个`() {
        val sys = v("sys voice", prem = false)
        val thirdPartyPremium = VoiceMeta("better", "zh-CN", isNetwork=false, isPremium=true, isCheap=false, enginePackage="com.acme.tts", engineLabel="ACME")
        assertEquals(thirdPartyPremium, VoiceScoring.bestChinese(listOf(sys, thirdPartyPremium)))
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.VoiceScoringTest"`
Expected: 编译失败

- [ ] **Step 3: 实现**

```kotlin
package com.radio.chinese.domain.model

/** 与 Android Voice 解耦的音色元数据快照，便于纯函数单测。enginePackage/engineLabel 用于多引擎跨选。 */
data class VoiceMeta(
    val name: String,
    val localeTag: String,
    val isNetwork: Boolean,
    val isPremium: Boolean,
    val isCheap: Boolean,
    val enginePackage: String = "",
    val engineLabel: String = "",
)

/**
 * “挑好听自然的声音”的打分：premium/神经网络 +2；非低质 +1；联网 -1（车机弱网）。
 * bestChinese 只在中文 locale 中选最高分、分同取名小（稳定）。无中文返回 null。
 */
object VoiceScoring {
    fun isChinese(v: VoiceMeta): Boolean {
        val l = v.localeTag.lowercase()
        return l.startsWith("zh") || l.contains("chinese") || l.contains("cmn")
    }

    fun rank(v: VoiceMeta): Int {
        var s = 0
        if (v.isPremium) s += 2
        if (!v.isCheap) s += 1
        if (v.isNetwork) s -= 1
        return s
    }

    fun bestChinese(list: List<VoiceMeta>): VoiceMeta? =
        list.filter { isChinese(it) }
            .sortedWith(compareByDescending<VoiceMeta> { rank(it) }.thenBy { it.name })
            .firstOrNull()
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.VoiceScoringTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/VoiceScoring.kt app/src/test/java/com/radio/chinese/VoiceScoringTest.kt
git commit -m "feat: add VoiceScoring pure voice-ranking logic"
```


## Task 7: VoiceCatalog 枚举全部引擎×音色（Android seam）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/service/VoiceCatalog.kt`
- Test: `app/src/test/java/com/radio/chinese/VoiceCatalogTest.kt`

**Interfaces:**
- Consumes: `VoiceMeta`、`VoiceScoring`（Task 6）
- Produces: `data class TtsOption(val enginePackage: String, val engineLabel: String, val voice: VoiceMeta)`；`interface VoiceCatalogSource { fun listOptions(): List<TtsOption>; fun defaultEnginePackage(): String? }`；`class AndroidVoiceCatalog(context) : VoiceCatalogSource`；`object VoiceCatalog { fun pickDefault(options: List<VoiceMeta>): VoiceMeta? }`（薄封装 `VoiceScoring.bestChinese`）

- [ ] **Step 1: 写失败测试（用 fake options 验证 pickDefault 与展示名）**

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.VoiceMeta
import com.radio.chinese.service.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCatalogTest {
    @Test fun `pickDefault 跨引擎取最优中文音色`() {
        val opts = listOf(
            VoiceMeta("en1", "en-US", false, false, false, "com.a", "引擎A"),
            VoiceMeta("zh-low", "zh-CN", false, false, true, "com.a", "引擎A"),
            VoiceMeta("zh-premium", "zh-CN", false, true, false, "com.b", "引擎B"),
        )
        assertEquals("com.b", VoiceCatalog.pickDefault(opts)?.enginePackage)
    }

    @Test fun `无中文音色返回 null`() {
        assertNull(VoiceCatalog.pickDefault(listOf(VoiceMeta("en", "en-US", false, false, false))))
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.VoiceCatalogTest"`
Expected: 编译失败

- [ ] **Step 3: 实现 seam 与 Android 版**

```kotlin
package com.radio.chinese.service

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.radio.chinese.domain.model.VoiceMeta
import com.radio.chinese.domain.model.VoiceScoring
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class TtsOption(val enginePackage: String, val engineLabel: String, val voice: VoiceMeta)

interface VoiceCatalogSource {
    fun listOptions(): List<TtsOption>
    fun defaultEnginePackage(): String?
}

object VoiceCatalog {
    fun pickDefault(chineseVoices: List<VoiceMeta>): VoiceMeta? = VoiceScoring.bestChinese(chineseVoices)
}

/**
 * 枚举设备上所有 TTS 引擎（不止默认那个），逐引擎取其音色。
 * 每个引擎单独 new 一个 TextToSpeech 读取 voices，读完立即 shutdown，避免泄漏。
 */
@Singleton
class AndroidVoiceCatalog @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceCatalogSource {

    override fun defaultEnginePackage(): String? = TextToSpeech(context) {}.let {
        // 用引擎列表首项兜底；实际以 queryIntentActivities 为准
        listEnginePackages().firstOrNull()
    }

    override fun listOptions(): List<TtsOption> {
        val result = mutableListOf<TtsOption>()
        for (pkg in listEnginePackages()) {
            val label = engineLabel(pkg)
            val tts = TextToSpeech(context, null, pkg)
            try {
                val voices: Set<Voice> = tts.voices ?: emptySet()
                for (v in voices) {
                    result += TtsOption(pkg, label, v.toMeta(pkg, label))
                }
            } catch (_: Exception) {
                // 个别引擎取 voices 抛异常：跳过该引擎，不影响其余候选
            } finally {
                runCatching { tts.shutdown() }
            }
        }
        return result
    }

    private fun listEnginePackages(): List<String> {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        return context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    }

    private fun engineLabel(pkg: String): String =
        runCatching {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)

    private fun Voice.toMeta(pkg: String, label: String) = VoiceMeta(
        name = name,
        localeTag = locale.toLanguageTag(),
        isNetwork = features.contains(Voice.NETWORK_CONNECTIVITY),
        isPremium = features.contains(Voice.PREMIUM),
        isCheap = if (android.os.Build.VERSION.SDK_INT >= 31)
            quality == Voice.QUALITY_LOW_LATENCY3 || quality == Voice.QUALITY_LOW_LATENCY2 || quality == Voice.QUALITY_LOW_LATENCY
        else false,
        enginePackage = pkg,
        engineLabel = label,
    )
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.radio.chinese.VoiceCatalogTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/service/VoiceCatalog.kt app/src/test/java/com/radio/chinese/VoiceCatalogTest.kt
git commit -m "feat: add VoiceCatalog enumerating all TTS engines and voices"
```

## Task 8: RadioPreferences 新增键

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/data/local/RadioPreferences.kt`（companion 加键、类体加 Flow 与 setter）

**Interfaces:**
- Produces: `val voiceAnnounceEnabled: Flow<Boolean>`（默认 true）、`suspend fun setVoiceAnnounceEnabled(Boolean)`、`val preferredTtsEngine: Flow<String?>`、`suspend fun setPreferredTtsEngine(String?)`、`val preferredVoice: Flow<String?>`、`suspend fun setPreferredVoice(String?)`、`val localFirstEnabled: Flow<Boolean>`（默认 true）、`suspend fun setLocalFirstEnabled(Boolean)`、`val cachedRegion: Flow<String?>`、`suspend fun setCachedRegion(String?)`、`val manualRegion: Flow<String?>`、`suspend fun setManualRegion(String?)`

- [ ] **Step 1: companion 内加键**

```kotlin
        private val KEY_VOICE_ANNOUNCE = booleanPreferencesKey("voice_announce_enabled")
        private val KEY_PREFERRED_TTS_ENGINE = stringPreferencesKey("preferred_tts_engine")
        private val KEY_PREFERRED_VOICE = stringPreferencesKey("preferred_voice")
        private val KEY_LOCAL_FIRST = booleanPreferencesKey("local_first_enabled")
        private val KEY_CACHED_REGION = stringPreferencesKey("cached_region")
        private val KEY_MANUAL_REGION = stringPreferencesKey("manual_region")
```

- [ ] **Step 2: 类体内加 Flow + setter（照 operaFavoriteCategories 既有写法）**

```kotlin
    // ========== 收听增强：线控播报 + 本地优先 ==========

    val voiceAnnounceEnabled: Flow<Boolean> = dataStore.data.map { (it[KEY_VOICE_ANNOUNCE] ?: true) }
    val preferredTtsEngine: Flow<String?> = dataStore.data.map { it[KEY_PREFERRED_TTS_ENGINE] }
    val preferredVoice: Flow<String?> = dataStore.data.map { it[KEY_PREFERRED_VOICE] }
    val localFirstEnabled: Flow<Boolean> = dataStore.data.map { (it[KEY_LOCAL_FIRST] ?: true) }
    val cachedRegion: Flow<String?> = dataStore.data.map { it[KEY_CACHED_REGION] }
    val manualRegion: Flow<String?> = dataStore.data.map { it[KEY_MANUAL_REGION] }

    suspend fun setVoiceAnnounceEnabled(enabled: Boolean) = dataStore.edit { it[KEY_VOICE_ANNOUNCE] = enabled }
    suspend fun setPreferredTtsEngine(pkg: String?) = dataStore.edit { prefs -> if (pkg == null) prefs.remove(KEY_PREFERRED_TTS_ENGINE) else prefs[KEY_PREFERRED_TTS_ENGINE] = pkg }
    suspend fun setPreferredVoice(name: String?) = dataStore.edit { prefs -> if (name == null) prefs.remove(KEY_PREFERRED_VOICE) else prefs[KEY_PREFERRED_VOICE] = name }
    suspend fun setLocalFirstEnabled(enabled: Boolean) = dataStore.edit { it[KEY_LOCAL_FIRST] = enabled }
    suspend fun setCachedRegion(value: String?) = dataStore.edit { prefs -> if (value == null) prefs.remove(KEY_CACHED_REGION) else prefs[KEY_CACHED_REGION] = value }
    suspend fun setManualRegion(value: String?) = dataStore.edit { prefs -> if (value == null) prefs.remove(KEY_MANUAL_REGION) else prefs[KEY_MANUAL_REGION] = value }
```

- [ ] **Step 3: 编译校验**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（无未定义引用）

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/radio/chinese/data/local/RadioPreferences.kt
git commit -m "feat: add voice-announce and local-first preference keys"
```

## Task 9: LocationSource（Android seam）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/service/LocationSource.kt`

**Interfaces:**
- Produces: `data class Coords(val lat: Double, val lng: Double)`；`interface LocationSource { suspend fun currentCoords(): Coords? }`；`@Singleton class AndroidLocationSource(context) : LocationSource`

- [ ] **Step 1: 实现（LocationManager，不依赖 Google；last-known 优先，单次更新 5s 超时）**

```kotlin
package com.radio.chinese.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.radio.chinese.di.AppModule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

data class Coords(val lat: Double, val lng: Double)

interface LocationSource {
    suspend fun currentCoords(): Coords?
}

/**
 * 车机多为安卓 10、无 Google Play 服务：走系统 LocationManager，不用 FusedLocation。
 * 先取 last-known（GPS/network 任一），拿不到再发起带 5s 超时的单次请求。
 * 权限未授予 / 无 provider / 超时 => null，交给上层手动兜底。
 */
@javax.inject.Singleton
class AndroidLocationSource @Inject constructor(
    @ApplicationContext private val context: Context
) : LocationSource {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun currentCoords(): Coords? {
        if (!hasPermission()) return null
        lastKnown()?.let { return it }
        return requestSingleUpdate(timeoutMs = 5_000)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, ContextCompat... ) == PackageManager.PERMISSION_GRANTED

    private fun lastKnown(): Coords? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val best: Location? = providers.mapNotNull {
            runCatching { locationManager.getLastKnownLocation(it) }.getOrNull()
        }.maxByOrNull { it.time }
        return best?.let { Coords(it.latitude, it.longitude) }
    }

    private suspend fun requestSingleUpdate(timeoutMs: Long): Coords? =
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (cont.isActive) cont.resume(Coords(location.latitude, location.longitude))
                }
                @Deprecated("legacy callback") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
                override fun onProviderEnabled(p: String?) {}
                override fun onProviderDisabled(p: String?) {}
            }
            val provider = if (runCatching { locationManager.allProviders.contains(LocationManager.GPS_PROVIDER) }.getOrDefault(false))
                LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            try {
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                android.os.Handler(Looper.getMainLooper()).postDelayed {
                    if (cont.isActive) cont.resume(null)
                    runCatching { locationManager.removeUpdates(listener) }
                }
            } catch (_: SecurityException) {
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { runCatching { locationManager.removeUpdates(listener) } }
        }
}
```

> 注：上面 `hasPermission()` 与 `postDelayed` 处 executor 需在实现时补全为 `ACCESS_COARSE_LOCATION` 检查与 `postDelayed(timeoutMs)`——写代码时用完整两行判 FINE/COARSE，超时用形参 `timeoutMs`。这两处是本任务唯一需补的细节，逻辑无歧义。

- [ ] **Step 2: 编译校验**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/radio/chinese/service/LocationSource.kt
git commit -m "feat: add Android LocationSource via LocationManager with timeout"
```

## Task 10: RegionRepository（地区 = 手动 > 缓存；刷新定位）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/data/RegionRepository.kt`

**Interfaces:**
- Consumes: `RadioPreferences`（Task 8）、`LocationSource`（Task 9）、`NearestCityFinder`/`CityCoordinates`（Task 3）、`Region`
- Produces: `@Singleton class RegionRepository { suspend fun currentUserRegion(): Region; suspend fun refreshFromLocation(): Region?; fun regionText(r: Region?): String }`

- [ ] **Step 1: 实现**

```kotlin
package com.radio.chinese.data

import android.content.Context
import com.radio.chinese.data.local.CityCoordinates
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.domain.model.NearestCityFinder
import com.radio.chinese.domain.model.Region
import com.radio.chinese.service.AndroidLocationSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户当前地区：手动选择优先，其次自动定位缓存。
 * region 串格式 "省|市"（与 RadioPreferences 存储一致）。
 */
@Singleton
class RegionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: RadioPreferences,
    private val locationSource: AndroidLocationSource,
) {
    private val cities by lazy {
        CityCoordinates.parse(
            context.assets.open("city_coordinates.json").bufferedReader().use { it.readText() }
        )
    }

    fun parseRegion(text: String?): Region? {
        if (text.isNullOrBlank()) return null
        val parts = text.split("|")
        return Region(province = parts.getOrElse(0) { "" }, city = parts.getOrElse(1) { "" })
    }

    private fun format(r: Region): String = "${r.province}|${r.city}"

    suspend fun currentUserRegion(): Region {
        parseRegion(preferences.manualRegion.first())?.let { if (!it.isEmpty) return it }
        parseRegion(preferences.cachedRegion.first())?.let { if (!it.isEmpty) return it }
        return Region.NONE
    }

    /** 尝试实时定位并写入缓存；成功返回 region，失败返回 null（缓存不动）。 */
    suspend fun refreshFromLocation(): Region? {
        val coords = locationSource.currentCoords() ?: return null
        val region = NearestCityFinder.find(coords.lat, coords.lng, cities) ?: return null
        preferences.setCachedRegion(format(region))
        return region
    }
}
```

- [ ] **Step 2: 编译校验**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/radio/chinese/data/RegionRepository.kt
git commit -m "feat: add RegionRepository resolving manual/cached/auto region"
```

## Task 11: 接入 StationSorter 到 getActiveStations

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/data/repository/StationRepository.kt`（构造加 `RegionRepository`、`RadioPreferences` 已有；`getActiveStations` 末尾套排序）

**Interfaces:**
- Consumes: `StationSorter`（Task 4）、`RegionResolver`+`RegionGazetteer`（Task 1/2）、`RegionRepository`（Task 10）
- Produces: `getActiveStations()` 在 `localFirstEnabled` 且用户 region 非空时返回本地优先序

- [ ] **Step 1: 构造注入 + 词表懒加载**

在 `StationRepository` 构造参数加：`private val regionRepository: RegionRepository`。类体加：
```kotlin
    private val gazetteer by lazy {
        com.radio.chinese.data.local.RegionGazetteer.parse(
            context.assets.open("region_gazetteer.json").bufferedReader().use { it.readText() }
        )
    }
```

- [ ] **Step 2: getActiveStations 末尾套本地优先排序**

```kotlin
    suspend fun getActiveStations(): List<RadioStation> = withContext(Dispatchers.IO) {
        val all = getAllStations()
        val invalidIds = preferences.invalidStationIds.first()
        val active = all.filter { it.id !in invalidIds }
        if (!preferences.localFirstEnabled.first()) return@withContext active
        val user = regionRepository.currentUserRegion()
        if (user.isEmpty) return@withContext active
        com.radio.chinese.domain.model.StationSorter.localPriority(active, user) { st ->
            com.radio.chinese.domain.model.RegionResolver.infer(
                text = "${st.name} ${st.description}", country = st.country, gazetteer = gazetteer
            )
        }
    }
```

- [ ] **Step 3: 单测（本地台排前）**

在 `app/src/test/java/com/radio/chinese/StationSorterTest.kt` 同级新建 `GetActiveStationsLocalFirstTest.kt`，用 MockK 造 `preferences.localFirstEnabled` 返回 true、`regionRepository.currentUserRegion()` 返回 `Region("浙江","绍兴")`，喂一个含 `绍兴之声`/`河南交通广播`/`中国之声` 的假 repository，断言 `getActiveStations()` 首位是 `绍兴之声`。（`builtInStations` 通过反射或注入 fake `getAllStations` 覆写——沿用项目里仓库层测试用 spyk 的既有做法。）

```kotlin
// 关键断言（伪代码骨架，executor 按现有仓库测试风格补全 fixture）
assertTrue(result.first().name.contains("绍兴"))
```

- [ ] **Step 4: 运行 + 全量回归**

Run: `./gradlew testDebugUnitTest`
Expected: 全绿；`StationJsonTest` 的既有用例不受影响（getActiveStations 默认无 region 时原样返回）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/data/repository/StationRepository.kt app/src/test/java/com/radio/chinese/GetActiveStationsLocalFirstTest.kt
git commit -m "feat: apply local-priority sorting in getActiveStations"
```

## Task 12: TtsAnnouncer（选引擎+音色、先报后播、降级、试听）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/service/TtsAnnouncer.kt`

**Interfaces:**
- Consumes: `RadioPreferences`（Task 8）、`AndroidVoiceCatalog`/`VoiceCatalog`（Task 7）
- Produces: `@Singleton class TtsAnnouncer { suspend fun isAvailable(): Boolean; suspend fun speak(text: String): Boolean; suspend fun preview(option: TtsOption): Boolean; fun shutdown() }`

- [ ] **Step 1: 实现**

```kotlin
package com.radio.chinese.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.radio.chinese.data.local.RadioPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class TtsAnnouncer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: RadioPreferences,
    private val catalog: AndroidVoiceCatalog,
) {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var readyEngine: String? = null

    /** 依当前偏好（engine 包名 + voice 名，未设则跨引擎选优）确保一个可用的中文引擎就绪。 */
    private suspend fun ensureReady(): TextToSpeech? = withContext(Dispatchers.Default) {
        val options = catalog.listOptions()
        if (options.isEmpty()) return@withContext null
        val prefPkg = preferences.preferredTtsEngine.first()
        val prefVoice = preferences.preferredVoice.first()
        val chosen = if (prefPkg != null && prefVoice != null)
            options.firstOrNull { it.enginePackage == prefPkg && it.voice.name == prefVoice }
        else null
        val target = chosen ?: options
            .let { all -> VoiceCatalog.pickDefault(all.map { it.voice })?.let { best -> all.first { it.voice === best } } }
            ?: return@withContext null
        if (readyEngine == target.enginePackage && tts != null) return@withContext tts
        tts?.shutdown()
        val init = suspendCancellableCoroutine<TextToSpeech?> { cont ->
            val engine = TextToSpeech(context, { status ->
                if (status == TextToSpeech.SUCCESS) cont.resume(engine) else cont.resume(null)
            }, target.enginePackage)
            cont.invokeOnCancellation { runCatching { engine.shutdown() } }
        } ?: return@withContext null
        runCatching {
            engine.voice = target.voice.let { vm -> engine.voices?.firstOrNull { it.name == vm.name } }
            if (java.util.Locale.forLanguageTag(target.voice.localeTag).language == "zh")
                engine.language = java.util.Locale.forLanguageTag(target.voice.localeTag)
            engine.setSpeechRate(1.1f)
        }
        readyEngine = target.enginePackage
        tts = engine
        engine
    }

    suspend fun isAvailable(): Boolean {
        if (!preferences.voiceAnnounceEnabled.first()) return false
        return ensureReady() != null
    }

    /** 播报；成功(true)/失败或被降级(false)。内部 3.5s 超时防止引擎不回调卡死切台。 */
    suspend fun speak(text: String): Boolean {
        if (!preferences.voiceAnnounceEnabled.first()) return false
        val engine = ensureReady() ?: return false
        return withTimeoutOrNull(3_500) {
            suspendCancellableCoroutine<Boolean> { cont ->
                engine.setOnUtteranceProgressListener(object : TextToSpeech.OnUtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (cont.isActive) cont.resume(true) }
                    @Deprecated override fun onError(id: String?) { if (cont.isActive) cont.resume(false) }
                })
                val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "announce")
                if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    /** 设置页试听某个引擎+音色组合。 */
    suspend fun preview(option: TtsOption): Boolean = withContext(Dispatchers.Default) {
        tts?.shutdown(); readyEngine = null
        preferences.setPreferredTtsEngine(option.enginePackage)
        preferences.setPreferredVoice(option.voice.name)
        speak("即将播放：中国之声 交通广播")
    }

    fun shutdown() { tts?.shutdown(); tts = null; readyEngine = null }
}
```

- [ ] **Step 2: 编译校验**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/radio/chinese/service/TtsAnnouncer.kt
git commit -m "feat: add TtsAnnouncer with engine+voice selection, timeout and preview"
```

## Task 13: handleMediaSkip 先播报再开播

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/service/PlayerManager.kt`（构造加 `TtsAnnouncer`；改 `handleMediaSkip`）

**Interfaces:**
- Consumes: `nextStation`（Task 5）、`TtsAnnouncer`（Task 12）、`RadioPreferences.voiceAnnounceEnabled`

- [ ] **Step 1: 构造注入 TtsAnnouncer**

`PlayerManager` 构造参数追加：`private val ttsAnnouncer: TtsAnnouncer`

- [ ] **Step 2: 改 handleMediaSkip（先解析目标→暂停→播报→开播）**

```kotlin
    fun handleMediaSkip(forward: Boolean) {
        scope.launch {
            if (_isOperaMode.value) {
                val nextName = peekOperaNextName(forward)          // 见 Step 3
                if (nextName != null && preferences.voiceAnnounceEnabled.first()) {
                    controller?.pause()
                    ttsAnnouncer.speak("即将播放：$nextName")
                }
                if (_isOperaMode.value) { if (forward) playOperaNext() else playOperaPrevious() }
                return@launch
            }
            val stations = stationRepository.getActiveStations()
            if (_isOperaMode.value) {
                if (forward) playOperaNext() else playOperaPrevious()
                return@launch
            }
            val target = nextStation(stations, _currentStation.value?.id, forward) ?: return@launch
            if (preferences.voiceAnnounceEnabled.first() && ttsAnnouncer.isAvailable()) {
                controller?.pause()
                ttsAnnouncer.speak("即将播放：${target.name}")
            }
            playStation(target)
        }
    }
```

- [ ] **Step 3: peekOperaNextName（不产生副作用地取下一段曲名）**

在 `PlayerManager` 内基于既有戏曲队列索引实现：
```kotlin
    private fun peekOperaNextName(forward: Boolean): String? {
        val list = operaQueueList // 现有已加载的戏曲曲目列表字段；executor 用实际字段名替换
        val idx = list.indexOfFirst { it.id == currentOperaFileId }
        if (list.isEmpty() || idx < 0) return null
        val next = if (forward) (idx + 1) % list.size else (idx - 1 + list.size) % list.size
        return list[next].name
    }
```

> executor 注：本步以 `PlayerManager` 中真实的戏曲列表与当前曲目字段为准（见类内 `playOperaNext/playOperaPrevious` 所用的同一份队列），确保"先播报的名字"与实际切到的曲目一致——这是 Review Focus 第 5 条的落点。

- [ ] **Step 4: 编译 + 全量单测**

Run: `./gradlew assembleDebug` 然后 `./gradlew testDebugUnitTest`
Expected: 全绿（既有 `HeadsetHookDetectorTest` 等不受影响）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/service/PlayerManager.kt
git commit -m "feat: announce next content via TTS before switching on controller skip"
```

## Task 14: AndroidManifest 权限与 TTS 可见性

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 加权限（INTERNET 之后）**

```xml
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

- [ ] **Step 2: 加 TTS 引擎可见性（`<application>` 之前）**

```xml
    <queries>
        <intent>
            <action android:name="android.intent.action.TTS_SERVICE" />
        </intent>
    </queries>
```

- [ ] **Step 3: 编译校验 + 提交**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL
```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: declare location permissions and TTS engine visibility in manifest"
```

## Task 15: 设置页「收听增强」（开关 + 引擎×音色选择器 + 地区）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/radio/chinese/ui/settings/ListenEnhanceViewModel.kt`（或并入现有 SettingsViewModel）

**Interfaces:**
- Consumes: `RadioPreferences`（Task 8）、`AndroidVoiceCatalog`（Task 7）、`TtsAnnouncer`（Task 12）、`RegionRepository`（Task 10）

- [ ] **Step 1: VM 暴露状态与动作**

```kotlin
@HiltViewModel
class ListenEnhanceViewModel @Inject constructor(
    private val preferences: RadioPreferences,
    private val catalog: AndroidVoiceCatalog,
    private val announcer: TtsAnnouncer,
    private val regionRepository: RegionRepository,
) : ViewModel() {
    val voiceOn = preferences.voiceAnnounceEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val localOn = preferences.localFirstEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val currentRegion = MutableStateFlow<Region?>(null)
    private val _options = MutableStateFlow<List<TtsOption>>(emptyList())
    val options: StateFlow<List<TtsOption>> = _options

    init {
        viewModelScope.launch {
            _options.value = catalog.listOptions().filter { VoiceScoring.isChinese(it.voice) }
            currentRegion.value = regionRepository.currentUserRegion().takeIf { !it.isEmpty }
        }
    }
    fun setVoice(enabled: Boolean) = viewModelScope.launch { preferences.setVoiceAnnounceEnabled(enabled) }
    fun setLocal(enabled: Boolean) = viewModelScope.launch { preferences.setLocalFirstEnabled(enabled) }
    fun preview(opt: TtsOption) = viewModelScope.launch { announcer.preview(opt) }
    fun relocate() = viewModelScope.launch {
        currentRegion.value = regionRepository.refreshFromLocation() ?: currentRegion.value
    }
    fun setManualRegion(r: Region) = viewModelScope.launch {
        preferences.setManualRegion("${r.province}|${r.city}"); currentRegion.value = r
    }
}
```

- [ ] **Step 2: UI 分区「收听增强」**

- 两行 `Switch`：线控播报、本地节目优先（复用设置页既有开关行组件与 `Dimens`）。
- 「播报音色」：点开展开 `options`（引擎×中文音色）列表，每项 `引擎名 · 音色名`（联网项加「联网」标签），点击即 `preview(opt)`（试听）并写偏好。列表为空时显示"未检测到中文语音引擎"。
- 「本地地区」：显示 `currentRegion`（省/市 或 未设置）；按钮「重新定位」调 `relocate()`（先 `rememberLauncherForActivityResult` 申请 `ACCESS_FINE_LOCATION`，与现有 `notificationPermissionLauncher` 同法）；「手动选择」用 `ExposedDropdownMenuBox` 选省/市（**注意**：对话框内普通 DropdownMenu 点不开，须用 ExposedDropdownMenuBox——沿用项目既有约定）。

- [ ] **Step 3: 编译 + 提交**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL
```bash
git add app/src/main/java/com/radio/chinese/ui/settings/SettingsScreen.kt app/src/main/java/com/radio/chinese/ui/settings/ListenEnhanceViewModel.kt
git commit -m "feat: add listen-enhance settings with engine-voice picker and region control"
```

---

## 完成标准（Definition of Done）

1. `./gradlew testDebugUnitTest` 全绿；`./gradlew assembleRelease` 通过并做 R8 后验证（播报/排序核心类未被混淆裁剪，按项目 R8 mapping 判定法核对）。
2. 车机真机裁决（对齐 Review Focus 五条）：
   - 线控/方控切台能听到"即将播放：X"再起新流；选定引擎×音色后试听生效；无中文引擎时静默照常切台。
   - 授予定位后本地电台在首页与线控切台里都排前；拒绝/境外时无加分、列表等同现状。
3. 发布前回归清单（沿用项目 Release 验证规范）逐项打勾后再进 versionCode 递增的发布提交。
