# 节目标签功能（A-2）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给电台加受控中文标签词表与按标签浏览，并把塞在 `frequency`/`description` 里的「国家 · 语言 · 流派」还原成规范字段。

**Architecture:** 标签词表是 Kotlin 枚举 `StationTag` 单一来源（照 `StationCategory` 的做法）；`radio_stations.json` 里静态写入 `tags/country/language/description`，由可重跑的 Python 脚本派生并带断言；UI 侧新增 `TagScreen` 路由，副标题改由一个纯函数统一渲染，三处调用点共用。

**Tech Stack:** Kotlin + Jetpack Compose (Material3)、kotlinx.serialization、Hilt、JUnit4（本计划新增）、Python 3.14 + requests、uiautomator（设备验收）。

**Spec:** [doc/specs/2026-10-11-station-tags-design.md](../specs/2026-10-11-station-tags-design.md) —— 本计划从它出发，实现时两份文件一起读。

## Global Constraints

以下逐条来自 spec 与项目既有规范，每个任务都隐含受约束：

- 分类与标签的**唯一定义在 Kotlin 枚举**：`app/src/main/java/com/radio/chinese/domain/model/StationCategory.kt`、新增 `StationTag.kt`。UI 层禁止再写第二份表。
- 标签 key 用 `jingju/yuju/yueju/yueopera/...`；**越剧=`yueju`、粤剧=`yueopera`**；**乡村音乐=`countrymusic`**（不得用 `country`，与新字段同名）。
- 不与分类重复打标：分类已有 news/traffic/sports/finance/opera，标签只打细一层（`localnews`、`sportslive`…）。
- 语言与地区**不是标签**，是 `language`/`country` 字段。
- 每台标签派生上限 6 个；详情页最多显示 4 个，顺序：戏曲曲艺 → 内容 → 音乐风格 → 场景。
- 简介红线：模板句只能使用 *分类 + 国家/地区 + 语言 + 已派生标签*；**禁止**覆盖范围、成立年份、隶属关系、"著名/权威"类断言。
- 尺寸一律引用 `Dimens`（`app/src/main/java/com/radio/chinese/ui/theme/Dimens.kt`），不写裸 dp。命中区 `Dimens.TouchMin`：手机 48dp、车机 88dp。
- 对话框内的下拉必须用 `ExposedDropdownMenuBox`，普通 `DropdownMenu` 在对话框里点不开（已踩过）。
- 视口不足时**整块隐藏可降级内容**，不得把播控/确定按钮挤出可视区（已踩过）。
- 迁移脚本必须幂等；A-1 基线备份 `radio_stations.json.bak`（349 台）**不得覆盖**，本次备份名 `radio_stations.json.pretag.bak`。
- 版本号来自 `git rev-list --count HEAD`，不由人工改数字；推送需一次性代理覆盖 `-c http.proxy=http://127.0.0.1:7897`（不写入 git config）。
- PowerShell 里没有 heredoc，且 `python -c "..."` 的嵌套引号会被卡在续行提示符；**所有 Python 逻辑一律写成 `tools/*.py` 文件再跑**。控制台需 `reconfigure(encoding="utf-8")`。

## Review Focus

以下是 spec 隐含但未成文、最容易伤到真实使用的输入/失效模式。每一行都在对应任务的测试里被钉住：

1. **JSON 里有词表外的 tag key**（手改、或旧脚本跑出来的）→ 界面上应显示原 key 而不是崩溃或显示空白；未知 key 在测试期就该红灯。（Task 1、Task 2）
2. **旧版本升级上来的自定义电台 JSON 完全没有 `tags/country/language` 三个键** → 反序列化必须不崩且 `tags` 为空；否则用户在管理页看到的是一片空的电台。（Task 1）
3. **`frequency` 是真频率的大陆台（`FM 88.0`/`AM 846`）被拆分逻辑误清空** → 副标题丢频率，老用户认不出台。（Task 3、Task 4）
4. **从标签页返回后 pager 掉回「全部」页签** → 用户每次都要重新点页签，标签功能变成惩罚。（Task 6）
5. **车机 240dpi 档横屏只有 480dp 高，标签 chips 把播放键挤出首屏** → 暂停键按不到是安全事故。（Task 7）

---

## 文件结构

| 动作 | 路径 | 职责 |
|---|---|---|
| Create | `app/src/main/java/com/radio/chinese/domain/model/StationTag.kt` | 标签词表唯一来源（key/中文/分组/顺序 + 查表与回落） |
| Modify | `app/src/main/java/com/radio/chinese/domain/model/Models.kt` | `RadioStation` 加 `tags/country/language` |
| Create | `app/src/main/java/com/radio/chinese/data/local/StationJson.kt` | 纯函数：assets JSON 文本 → `List<RadioStation>`（JVM 可测） |
| Modify | `app/src/main/java/com/radio/chinese/data/repository/StationRepository.kt` | 委托 `StationJson`；加按标签查询与标签计数 |
| Create | `app/src/main/java/com/radio/chinese/ui/common/StationSubtitle.kt` | 副标题唯一规则的纯函数 + Composable |
| Create | `app/src/main/java/com/radio/chinese/ui/home/StationFilter.kt` | 分类×搜索×标签 的纯筛选函数（JVM 可测） |
| Modify | `ui/home/HomeScreen.kt`、`ui/player/PlayerScreen.kt`、`ui/manage/ManageScreen.kt` | 副标题调用点；详情页标签 chips；添加对话框标签多选 |
| Modify | `ui/home/HomeViewModel.kt`、`ui/category/CategoryScreen.kt` | `selectedTag` 状态；入口卡 |
| Create | `app/src/main/java/com/radio/chinese/ui/tag/TagScreen.kt` | 标签浏览页（标签云态 + 该标签电台列表态） |
| Modify | `ui/navigation/Screen.kt`、`ui/MainScreen.kt` | `Screen.Tags` 路由 |
| Modify | `app/build.gradle.kts` | 加 `testImplementation("junit:junit:4.13.2")` |
| Create | `app/src/test/java/com/radio/chinese/{StationTagTest,RadioStationCompatTest,StationJsonTest,StationSubtitleTest,StationFilterTest}.kt` | JVM 单测 |
| Create | `tools/derive_tags.py`、`tools/test_derive_tags.py` | 派生迁移 + 简介 + 断言（TDD） |
| Create | `tools/rb_provenance.json` | 派生依据的精简溯源（入库，保证可重跑） |
| Modify | `app/src/main/assets/radio_stations.json` | 写入 `tags/country/language/description` |
| Create | `tools/tag_ui_check.ps1` | 手机三档 / 车机两档的标签页几何断言 |

---

## Task 1: 标签词表、模型字段与 junit 地基

**Files:**
- Modify: `app/build.gradle.kts`（dependencies 段末尾，第 169 行后）
- Create: `app/src/main/java/com/radio/chinese/domain/model/StationTag.kt`
- Modify: `app/src/main/java/com/radio/chinese/domain/model/Models.kt:12-36`
- Create: `app/src/test/java/com/radio/chinese/StationTagTest.kt`
- Create: `app/src/test/java/com/radio/chinese/RadioStationCompatTest.kt`

**Interfaces:**
- Consumes: 无（地基）
- Produces:
  - `enum class StationTag(val key: String, val label: String, val group: TagGroup)`
  - `enum class TagGroup(val label: String)`，值顺序 `Opera, Content, Music, Scene`
  - `StationTag.Companion.fromKey(String): StationTag?`、`labelOf(String): String`、`isValidKey(String): Boolean`、`orderedByGroup(): List<Pair<TagGroup, List<StationTag>>>`、`byKeyOrder(keys: List<String>, limit: Int): List<StationTag>`
  - `RadioStation.tags: List<String>`、`country: String`、`language: String`（均带默认值）

- [ ] **Step 1: 加 junit 依赖**

`app/build.gradle.kts` 的 `dependencies {` 块末尾（现第 169 行 `implementation("androidx.security:security-crypto:1.1.0-alpha06")` 之后）追加：

```kotlin
    // JVM 单元测试：本项目此前没有任何单测能力，标签词表/JSON 解析/副标题规则
    // 都是"运行时静默错"的类别，必须有编译期之外的闸
    testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: 写失败的词表测试**

Create `app/src/test/java/com/radio/chinese/StationTagTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.StationTag
import com.radio.chinese.domain.model.TagGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationTagTest {
    @Test
    fun `key 全局唯一`() {
        val keys = StationTag.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `四个分组都非空`() {
        for (g in TagGroup.entries) {
            assertTrue("${g.name} 组为空", StationTag.entries.any { it.group == g })
        }
    }

    @Test
    fun `越剧与粤剧 key 不冲突`() {
        assertEquals("越剧", StationTag.labelOf("yueju"))
        assertEquals("粤剧", StationTag.labelOf("yueopera"))
    }

    @Test
    fun `乡村音乐不占用 country 这个 key`() {
        assertFalse(StationTag.isValidKey("country"))
        assertTrue(StationTag.isValidKey("countrymusic"))
        assertEquals("乡村", StationTag.labelOf("countrymusic"))
    }

    @Test
    fun `未知 key 回落显示原值而不是空或崩`() {
        assertEquals("不存在的标签", StationTag.labelOf("不存在的标签"))
        assertNull(StationTag.fromKey("nope"))
        assertFalse(StationTag.isValidKey("nope"))
    }

    @Test
    fun `标签不与分类语义重复`() {
        // 纪律 1：这些词是分类的活，不该出现在词表里
        for (banned in listOf("news", "sports", "traffic", "finance", "opera", "music")) {
            assertFalse("词表里不该有与分类同名的 key: $banned", StationTag.isValidKey(banned))
        }
    }

    @Test
    fun `byKeyOrder 丢弃未知 key 并按分组顺序取前 N`() {
        val picked = StationTag.byKeyOrder(listOf("rock", "没这个", "jingju"), limit = 4)
        assertEquals(listOf("jingju", "rock"), picked.map { it.key })
    }

    @Test
    fun `分组顺序为戏曲 内容 风格 场景`() {
        assertEquals(
            listOf(TagGroup.Opera, TagGroup.Content, TagGroup.Music, TagGroup.Scene),
            StationTag.orderedByGroup().map { it.first }
        )
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.StationTagTest" --console=plain`
Expected: 编译失败（`unresolved reference: StationTag` / `TagGroup`）

- [ ] **Step 4: 实现词表**

Create `app/src/main/java/com/radio/chinese/domain/model/StationTag.kt`：

```kotlin
package com.radio.chinese.domain.model

/**
 * 标签分组。顺序即展示顺序：先最具体的戏曲曲种，再内容，再音乐风格，最后是场景。
 */
enum class TagGroup(val label: String) {
    Opera("戏曲曲艺"),
    Content("内容"),
    Music("音乐风格"),
    Scene("场景"),
}

/**
 * 节目标签词表：全项目唯一来源。
 *
 * 与 [StationCategory] 同一手法，是因为分类表当年在四处各写一份、取值还不一致
 * （ManageScreen 那份漏了 tv_audio）。标签比分类更容易漂：多写一份，
 * 半年后就会出现「越剧/粤剧」这类互相打架的叫法。
 *
 * 两条纪律（改动前先读）：
 *  1. 不与分类重复：分类已有新闻/交通/体育/财经/戏曲，这里只打细一层
 *     （localnews、worldnews、sportslive…），打同名词等于零信息量筛选。
 *  2. 语言与地区不是标签：它们是 RadioStation.country / .language 字段，
 *     由副标题负责显示，避免出现两处真源。
 *
 * 命名上规避了两处真实冲突：越剧与粤剧拼音同为 yueju，故粤剧用 yueopera；
 * 乡村音乐的 key 不能叫 country（与字段同名），故用 countrymusic。
 * key 一旦写入 assets JSON 就很难改，改名要重跑派生迁移。
 */
enum class StationTag(val key: String, val label: String, val group: TagGroup) {
    // 戏曲曲艺
    JINGJU("jingju", "京剧", TagGroup.Opera),
    YUJU("yuju", "豫剧", TagGroup.Opera),
    YUEJU("yueju", "越剧", TagGroup.Opera),
    YUEOPERA("yueopera", "粤剧", TagGroup.Opera),
    HUANGMEI("huangmei", "黄梅戏", TagGroup.Opera),
    PINGJU("pingju", "评剧", TagGroup.Opera),
    KUNQU("kunqu", "昆曲", TagGroup.Opera),
    QINQIANG("qinqiang", "秦腔", TagGroup.Opera),
    CHUANJU("chuanju", "川剧", TagGroup.Opera),
    HUJV("hujv", "沪剧", TagGroup.Opera),
    PINGTAN("pingtan", "评弹", TagGroup.Opera),
    CROSSTALK("crosstalk", "相声", TagGroup.Opera),

    // 内容
    LOCALNEWS("localnews", "本地新闻", TagGroup.Content),
    WORLDNEWS("worldnews", "国际新闻", TagGroup.Content),
    MILITARY("military", "国防军事", TagGroup.Content),
    HEALTH("health", "健康医药", TagGroup.Content),
    EDU("edu", "教育", TagGroup.Content),
    KIDS("kids", "少儿", TagGroup.Content),
    NIGHTTALK("nighttalk", "夜间清谈", TagGroup.Content),
    STORYTELLING("storytelling", "评书故事", TagGroup.Content),
    HISTORY("history", "历史人文", TagGroup.Content),
    FINCOMMENT("fincomment", "财经评述", TagGroup.Content),
    TECH("tech", "科技", TagGroup.Content),
    SPORTSLIVE("sportslive", "赛事解说", TagGroup.Content),
    TRAFFICNOW("trafficnow", "实时路况", TagGroup.Content),
    WEATHER("weather", "天气", TagGroup.Content),

    // 音乐风格
    OLDIES("oldies", "怀旧老歌", TagGroup.Music),
    HITS80("hits80", "八十年代", TagGroup.Music),
    ROCK("rock", "摇滚", TagGroup.Music),
    FOLK("folk", "民谣", TagGroup.Music),
    JAZZ("jazz", "爵士", TagGroup.Music),
    CLASSICAL("classical", "古典", TagGroup.Music),
    LIGHTMUSIC("lightmusic", "轻音乐", TagGroup.Music),
    BLUES("blues", "蓝调", TagGroup.Music),
    COUNTRYMUSIC("countrymusic", "乡村", TagGroup.Music),
    ELECTRONIC("electronic", "电子舞曲", TagGroup.Music),
    HIPHOP("hiphop", "嘻哈", TagGroup.Music),
    RNB("rnb", "节奏布鲁斯", TagGroup.Music),
    METAL("metal", "金属", TagGroup.Music),
    CPOP("cpop", "华语流行", TagGroup.Music),
    POP("pop", "欧美流行", TagGroup.Music),
    INSTRUMENTAL("instrumental", "纯音乐", TagGroup.Music),

    // 场景
    DRIVE("drive", "行车", TagGroup.Scene),
    SLEEP("sleep", "助眠放松", TagGroup.Scene),
    BUDDHIST("buddhist", "佛教禅修", TagGroup.Scene),
    LANGLEARN("langlearn", "外语学习", TagGroup.Scene),
    FITNESS("fitness", "运动健身", TagGroup.Scene),
    CHAT("chat", "闲聊陪伴", TagGroup.Scene),
    ;

    companion object {
        private val index: Map<String, StationTag> = entries.associateBy { it.key }

        /** 顺序即分组声明顺序，供派生脚本与详情页 chips 复用同一套优先级。 */
        val keyOrder: List<String> = entries.map { it.key }

        fun fromKey(key: String): StationTag? = index[key]

        /** 未知 key 回落显示原值：宁显示英文 key 也不给一片空白（同 StationCategory.labelOf）。 */
        fun labelOf(key: String): String = index[key]?.label ?: key

        fun isValidKey(key: String): Boolean = index.containsKey(key)

        fun orderedByGroup(): List<Pair<TagGroup, List<StationTag>>> =
            TagGroup.entries.map { g -> g to entries.filter { it.group == g } }

        /** 按分组优先级排序取前 limit 个，词表外的 key 直接丢弃（不显示垃圾）。 */
        fun byKeyOrder(keys: List<String>, limit: Int): List<StationTag> =
            (keys.mapNotNull { index[it] }.distinct().sortedBy { keyOrder.indexOf(it.key) }).take(limit)
    }
}
```

- [ ] **Step 5: 给模型加三个字段**

Modify `Models.kt`，在 `RadioStation` 的 `sources` 参数后追加（保持全部有默认值，旧数据才能反序列化）：

```kotlin
    val sources: List<StationSource> = emptyList(),
    /** 标签 key 列表，取值必须能在 StationTag 里查到；不存中文，避免两处真源 */
    val tags: List<String> = emptyList(),
    /** 中文国家/地区名。此前被塞在 frequency 里当副标题用，现还原为字段 */
    val country: String = "",
    /** 中文语言名，同上 */
    val language: String = "",
```

- [ ] **Step 6: 写兼容性测试（旧数据无这三个键）**

Create `app/src/test/java/com/radio/chinese/RadioStationCompatTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义电台是以 List<RadioStation> 的 JSON 存在 DataStore 里的。
 * 老版本存下的记录没有 tags/country/language，升级后必须安静地当空处理；
 * 这里失败等于用户管理页里的自定义电台直接消失。
 */
class RadioStationCompatTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `旧自定义电台 JSON 缺三个新字段仍能解析`() {
        val legacy = """[{"id":"custom_1","name":"我的台","category":"news",
            |"streamUrl":"https://a/b.mp3","logoUrl":"","frequency":"FM 90.0",
            |"description":"","website":"","sources":[]}]""".trimMargin()
        val list = json.decodeFromString<List<RadioStation>>(legacy)
        assertEquals(1, list.size)
        assertEquals("我的台", list[0].name)
        assertTrue(list[0].tags.isEmpty())
        assertEquals("", list[0].country)
        assertEquals("", list[0].language)
    }

    @Test
    fun `新字段写入再读出一致，且未知字段被忽略`() {
        val station = RadioStation(
            id = "custom_2", name = "豫剧台", category = "opera", streamUrl = "https://a/b.m3u8",
            tags = listOf("yuju", "history"), country = "中国", language = "华语",
        )
        val roundTrip = json.decodeFromString<List<RadioStation>>(json.encodeToString(listOf(station)))
        assertEquals(station, roundTrip.single())
    }
}
```

- [ ] **Step 7: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: `StationTagTest`、`RadioStationCompatTest` 全绿

- [ ] **Step 8: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/radio/chinese/domain/model/ app/src/test/java/com/radio/chinese/
git commit -m "feat: 标签词表进 StationTag 枚举，模型加 tags/country/language，补 junit 单测能力"
```

---

## Task 2: assets 解析抽出纯函数 + 副标题统一渲染

**Files:**
- Create: `app/src/main/java/com/radio/chinese/data/local/StationJson.kt`
- Modify: `app/src/main/java/com/radio/chinese/data/repository/StationRepository.kt:60-95`
- Create: `app/src/main/java/com/radio/chinese/ui/common/StationSubtitle.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/home/HomeScreen.kt:235-241`
- Modify: `app/src/main/java/com/radio/chinese/ui/player/PlayerScreen.kt:357-365`
- Modify: `app/src/main/java/com/radio/chinese/ui/manage/ManageScreen.kt:282-289`
- Create: `app/src/test/java/com/radio/chinese/StationJsonTest.kt`
- Create: `app/src/test/java/com/radio/chinese/StationSubtitleTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `StationTag`、`RadioStation.tags/country/language`
- Produces:
  - `object StationJson { fun parse(text: String): List<RadioStation> }`
  - `fun stationSubtitle(station: RadioStation, categoryLabel: String): String`（纯函数，非 Composable）
  - `@Composable fun StationSubtitleText(station: RadioStation, style: TextStyle, textAlign: TextAlign? = null, modifier: Modifier = Modifier)`

- [ ] **Step 1: 写失败测试：真 assets 文件必须逐条合法**

Create `app/src/test/java/com/radio/chinese/StationJsonTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.data.local.StationJson
import com.radio.chinese.domain.model.StationCategory
import com.radio.chinese.domain.model.StationTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 直接读仓库里那份 assets JSON。这条测试是词表与数据之间的闸：
 * 任何人手改了一条 "tags":["京剧"] 这种中文 key，或派生脚本漂出新词，
 * 都必须在构建期变红，而不是运行时安静地显示英文原词。
 */
class StationJsonTest {
    private val stations by lazy { StationJson.parse(assetsFile().readText(Charsets.UTF_8)) }

    private fun assetsFile(): File {
        // 单元测试的工作目录是模块目录；为兼容不同 Gradle 版本再退一层
        val candidates = listOf(
            File("src/main/assets/radio_stations.json"),
            File("app/src/main/assets/radio_stations.json"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("找不到 radio_stations.json，尝试过: " + candidates)
    }

    @Test
    fun `台数不少于 465`() {
        assertTrue("实际 ${stations.size}", stations.size >= 465)
    }

    @Test
    fun `每个 tag 都在词表里`() {
        val bad = stations.flatMap { st -> st.tags.filter { !StationTag.isValidKey(it) } .map { st.id to it } }
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
    fun `单台标签不超过 6 个且无重复`() {
        val over = stations.filter { it.tags.size > 6 }.map { it.id }
        val dup = stations.filter { it.tags.size != it.tags.toSet().size }.map { it.id }
        assertTrue("超过 6 个标签: $over", over.isEmpty())
        assertTrue("标签重复: $dup", dup.isEmpty())
    }

    @Test
    fun `id 全局唯一`() {
        assertEquals(stations.size, stations.map { it.id }.toSet().size)
    }
}
```

Create `app/src/test/java/com/radio/chinese/StationSubtitleTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.ui.common.stationSubtitle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 副标题在首页列表、播放器、管理页三处共用同一个纯函数。
 * 三处各写一遍时的老 bug 是：同一台在两个页面显示的副标题不一样。
 */
class StationSubtitleTest {
    private fun st(freq: String = "", country: String = "", lang: String = "") =
        RadioStation(id = "x", name = "x", category = "news", streamUrl = "https://a/b",
                     frequency = freq, country = country, language = lang)

    @Test
    fun `真频率优先，大陆台的 FM 值不能被国家盖掉`() {
        assertEquals("FM 88.0", stationSubtitle(st(freq = "FM 88.0"), "新闻"))
    }

    @Test
    fun `无频率时用 国家 · 语言`() {
        assertEquals("德国 · 英语", stationSubtitle(st(country = "德国", lang = "英语"), "新闻"))
        assertEquals("英语", stationSubtitle(st(country = "", lang = "英语"), "新闻").let {
            stationSubtitle(st(lang = "英语"), "新闻")
        })
    }

    @Test
    fun `只有国家时不出现尾随分隔符`() {
        assertEquals("德国", stationSubtitle(st(country = "德国"), "新闻"))
    }

    @Test
    fun `全空时回落分类中文名`() {
        assertEquals("新闻", stationSubtitle(st(), "新闻"))
    }

    @Test
    fun `分类名也为空时返回空串不渲染该行`() {
        assertEquals("", stationSubtitle(st(), ""))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.StationJsonTest" --console=plain`
Expected: 编译失败（`StationJson`、`stationSubtitle` 未定义）

- [ ] **Step 3: 抽出 assets 解析纯函数**

Create `app/src/main/java/com/radio/chinese/data/local/StationJson.kt`。把 `StationRepository.loadBuiltInStations()` 里那段手工 `JsonObject` 解析整段搬来并补三个新字段，逻辑保持等价：

```kotlin
package com.radio.chinese.data.local

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 内置清单的解析。放在这里而不是留在 StationRepository：
 * Repository 要 Context 读 assets，JVM 单测拿不到；把解析做成纯函数后，
 * 真 JSON 文件的合法性才能进构建期闸门（见 StationJsonTest）。
 */
object StationJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<RadioStation> {
        val root = json.parseToJsonElement(text).jsonObject
        val arr = root["stations"]?.jsonArray ?: return emptyList()
        return arr.map { el ->
            val o = el.jsonObject
            val sources = o["sources"]?.jsonArray?.map { so ->
                val x = so.jsonObject
                StationSource(
                    url = x["url"]?.jsonPrimitive?.content ?: "",
                    label = x["label"]?.jsonPrimitive?.content ?: "",
                    bitrate = x["bitrate"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                )
            } ?: emptyList()
            RadioStation(
                id = o["id"]?.jsonPrimitive?.content ?: "",
                name = o["name"]?.jsonPrimitive?.content ?: "",
                category = o["category"]?.jsonPrimitive?.content ?: "general",
                streamUrl = o["streamUrl"]?.jsonPrimitive?.content ?: "",
                sources = sources,
                logoUrl = o["logoUrl"]?.jsonPrimitive?.content ?: "",
                frequency = o["frequency"]?.jsonPrimitive?.content ?: "",
                description = o["description"]?.jsonPrimitive?.content ?: "",
                website = o["website"]?.jsonPrimitive?.content ?: "",
                tags = o["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNullBlank() } ?: emptyList(),
                country = o["country"]?.jsonPrimitive?.content ?: "",
                language = o["language"]?.jsonPrimitive?.content ?: "",
            )
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullBlank(): String? =
        content.takeIf { it.isNotBlank() }
}
```

Modify `StationRepository.loadBuiltInStations()`，删掉原来那段手工解析（第 67-91 行），换成：

```kotlin
        val jsonString = context.assets.open("radio_stations.json")
            .bufferedReader()
            .use { it.readText() }
        val stations = com.radio.chinese.data.local.StationJson.parse(jsonString)
```

同时删掉该文件里因此不再使用的 `JsonObject/jsonArray/jsonObject/jsonPrimitive` import（`encodeToString` 相关的保留）。

- [ ] **Step 4: 实现副标题纯函数**

Create `app/src/main/java/com/radio/chinese/ui/common/StationSubtitle.kt`：

```kotlin
package com.radio.chinese.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationCategory

/**
 * 电台副标题的唯一规则。
 *
 * 历史上「国家 · 语言」被塞在 frequency 字符串里，三处页面各自拼接，
 * 于是同一台在首页显示「德国 · 英语」、在管理页显示空白。规则：
 * 真频率 > 国家 · 语言 > 只有国家 > 只有语言 > 分类中文名 > 空串（调用方不渲染该行）。
 */
fun stationSubtitle(station: RadioStation, categoryLabel: String): String {
    val place = listOf(station.country, station.language).filter { it.isNotBlank() }.joinToString(" · ")
    return when {
        station.frequency.isNotBlank() -> station.frequency
        place.isNotBlank() -> place
        categoryLabel.isNotBlank() -> categoryLabel
        else -> ""
    }
}

/** 手机/车机都能用的副标题文本；空结论时整块不渲染，不留空行。 */
@Composable
fun StationSubtitleText(
    station: RadioStation,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    textAlign: TextAlign? = null,
) {
    val text = stationSubtitle(station, StationCategory.labelOf(station.category))
    if (text.isNotBlank()) {
        Text(
            text = text,
            style = style,
            textAlign = textAlign,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}
```

- [ ] **Step 5: 三处调用点改成共用它**

`HomeScreen.kt` 第 235-241 行的 `if (station.frequency.isNotEmpty()) { Text(...) }` 整块替换为：

```kotlin
                StationSubtitleText(station = station)
```

`PlayerScreen.kt` 第 357-365 行（`if (station.frequency.isNotEmpty())` 那块，含前面的 `Spacer`）替换为：

```kotlin
        StationSubtitleText(
            station = station,
            modifier = Modifier.padding(top = Dimens.GapTiny),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
```

`ManageScreen.kt` 第 282-289 行的 `buildString` 改为以 `stationSubtitle` 起头（**保留**后面的 ` · 检测信息` 拼接）：

```kotlin
                val subtitle = buildString {
                    append(stationSubtitle(station, StationCategory.labelOf(station.category)))
                    if (checkResult != null && checkResult.status != StreamStatus.CHECKING) {
                        if (isNotEmpty()) append(" · ")
                        append(checkResult.message)
                    }
                }
```

三处都要补 import：`com.radio.chinese.ui.common.StationSubtitleText`（ManageScreen 另需 `com.radio.chinese.ui.common.stationSubtitle` 与 `com.radio.chinese.domain.model.StationCategory`）。

- [ ] **Step 6: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: 全绿。若 `StationJsonTest.台数不少于 465` 失败，说明解析有回归——先修解析，别改测试期望值。

- [ ] **Step 7: 编译并安装，肉眼核对三处副标题一致（临时手测，正式矩阵在 Task 6）**

```bash
./gradlew :app:assembleDebug --console=plain
$env:LOCALAPPDATA + "\Android\Sdk\platform-tools\adb.exe" install -r app/build/outputs/apk/debug/chinese-online-radio-1.0.96-debug.apk
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/radio/chinese/data/local/StationJson.kt app/src/main/java/com/radio/chinese/data/repository/StationRepository.kt app/src/main/java/com/radio/chinese/ui/common/StationSubtitle.kt app/src/main/java/com/radio/chinese/ui/home/HomeScreen.kt app/src/main/java/com/radio/chinese/ui/player/PlayerScreen.kt app/src/main/java/com/radio/chinese/ui/manage/ManageScreen.kt app/src/test/java/com/radio/chinese/
git commit -m "refactor: assets 解析抽出纯函数并补新字段，副标题统一由 stationSubtitle 渲染三处"
```

---

## Task 3: 派生脚本（标签 + 字段拆分 + 简介）与 dry-run

**Files:**
- Create: `tools/derive_tags.py`
- Create: `tools/test_derive_tags.py`
- Create: `tools/rb_provenance.json`（由脚本 `provenance` 子命令生成）

**Interfaces:**
- Consumes: `app/src/main/assets/radio_stations.json`（当前 465 台）、A-1 的 `.qtest/rb_pool.json`
- Produces（`derive_tags.py` 的函数名，Task 4 与测试都按这些调用）:
  - `tags_from_name(name: str) -> list[str]`
  - `map_rb_tags(tags: str) -> list[str]`
  - `split_frequency(freq: str) -> tuple[str, str, str]`（country, language, 保留的 frequency）
  - `strip_desc(desc: str) -> str`（识别"全球节目源 · …"占位并返回 ""，真简介原样返回）
  - `template_desc(country, language, category_label, tag_labels) -> str`
  - `clean_meta(html: str) -> str`
  - `derive_station(st, prov, meta_text=None) -> dict`
  - `validate(before: list, after: list) -> list[str]`（返回问题列表，空即通过）
  - `build_provenance(pool_path, stations) -> list[dict]`
  - CLI: `provenance` / `dry-run` / `apply` 三个子命令

- [ ] **Step 1: 先写失败测试**

Create `tools/test_derive_tags.py`（沿用 A-1 风格：控制台 reconfigure，纯逻辑不打网）：

```python
"""derive_tags 的纯逻辑测试。

重点是三类会静默污染清单的错误：拼音撞名（越剧/粤剧）、把真频率当「国家 · 语言」
拆掉、以及把 adult contemporary 这种音乐格式当成成人内容。
"""
import unittest

import derive_tags as dt


class TestTagsFromName(unittest.TestCase):
    def test_dialect_names(self):
        self.assertIn("yuju", dt.tags_from_name("河南豫剧广播"))
        self.assertIn("yueju", dt.tags_from_name("听·越剧"))
        self.assertIn("yueopera", dt.tags_from_name("潮州粤曲广播"))

    def test_generic_opera_name_gets_no_dialect_tag(self):
        """只写「戏曲」的台拿不到剧种标签——这是词表纪律的已知代价，不许乱猜。"""
        self.assertEqual(dt.tags_from_name("河南戏曲广播"), [])

    def test_storytelling_and_crosstalk(self):
        self.assertIn("storytelling", dt.tags_from_name("单田芳评书广播"))
        self.assertIn("crosstalk", dt.tags_from_name("天津相声广播"))

    def test_classic_fm_is_classical_not_oldies(self):
        self.assertIn("classical", dt.tags_from_name("Classic FM"))

    def test_no_banned_category_duplicate_keys(self):
        """派生结果里不允许出现与分类同名的词。"""
        for name in ["北京新闻广播", "浙江交通之声", "山东体育休闲广播"]:
            for tag in dt.tags_from_name(name):
                self.assertNotIn(tag, dt.BANNED_KEYS, f"{name} 派生出禁用语义 {tag}")


class TestMapRbTags(unittest.TestCase):
    def test_adult_contemporary_is_a_music_format_not_porn(self):
        """实测坑：子串匹配 adult 会误杀 Heart UK 这类主流台。"""
        self.assertEqual(dt.map_rb_tags("adult contemporary,pop"), ["lightmusic", "pop"])
        self.assertEqual(dt.map_rb_tags("hot adult contemporary"), ["lightmusic"])

    def test_smooth_jazz_and_classic_hits(self):
        self.assertEqual(dt.map_rb_tags("smooth jazz"), ["jazz"])
        self.assertEqual(dt.map_rb_tags("classic hits,oldies"), ["oldies"])

    def test_unknown_tags_are_dropped(self):
        self.assertEqual(dt.map_rb_tags("waynesboro,full service"), [])

    def test_output_is_deduped_and_capped(self):
        tags = dt.map_rb_tags("rock,classic rock,metal,punk,blues,jazz,folk,country,oldies")
        self.assertLessEqual(len(tags), 6)
        self.assertEqual(len(tags), len(set(tags)))


class TestSplitFrequency(unittest.TestCase):
    def test_real_fm_value_is_kept(self):
        self.assertEqual(dt.split_frequency("FM 88.0"), ("", "", "FM 88.0"))
        self.assertEqual(dt.split_frequency("AM 846"), ("", "", "AM 846"))

    def test_padded_country_language_is_extracted(self):
        self.assertEqual(dt.split_frequency("德国 · 英语"), ("德国", "英语", ""))

    def test_region_only_value(self):
        self.assertEqual(dt.split_frequency("中国澳门 · 华语"), ("中国澳门", "华语", ""))

    def test_empty_stays_empty(self):
        self.assertEqual(dt.split_frequency(""), ("", "", ""))


class TestDescription(unittest.TestCase):
    def test_placeholder_desc_is_replaced_but_real_desc_is_kept(self):
        self.assertEqual(dt.strip_desc("全球节目源 · 德国 · 综合"), "")
        self.assertEqual(dt.strip_desc("中央人民广播电台第一套节目"), "中央人民广播电台第一套节目")

    def test_template_uses_only_verified_fields(self):
        got = dt.template_desc("德国", "英语", "新闻", ["本地新闻"])
        self.assertIn("德国", got)
        self.assertIn("英语", got)
        # 红线：不许出现覆盖范围、年份、"著名/权威"这类未验证断言
        for banned in ("覆盖", "成立", "创建于", "著名", "权威", "领先", "唯一"):
            self.assertNotIn(banned, got)

    def test_template_without_any_info_is_empty(self):
        self.assertEqual(dt.template_desc("", "", "", []), "")

    def test_no_fake_source_claim_when_meta_missing(self):
        """模板句不许长得像官网自述（不得带分隔符）。"""
        got = dt.template_desc("英国", "英语", "音乐", ["爵士"])
        self.assertNotIn("—", got)
        self.assertNotIn(" - ", got)


class TestCleanMeta(unittest.TestCase):
    def test_extracts_meta_description_and_title(self):
        html = ('<html><head><title>Heart UK</title>'
                '<meta name="description" content="  The UK\'s biggest hits.  ">'
                "</head><body>x</body></html>")
        self.assertEqual(dt.clean_meta(html), "The UK's biggest hits.")

    def test_prefers_og_description_over_meta(self):
        html = ('<meta name="description" content="short">'
                '<meta property="og:description" content="longer official blurb">')
        self.assertEqual(dt.clean_meta(html), "longer official blurb")

    def test_strips_html_and_truncates(self):
        html = '<meta name="description" content="a <b>tag</b> ' + "x" * 200 + '">'
        got = dt.clean_meta(html)
        self.assertNotIn("<", got)
        self.assertLessEqual(len(got), 48)

    def test_nothing_useful_returns_empty(self):
        self.assertEqual(dt.clean_meta("<html><head></head></html>"), "")

    def test_marketing_promise_is_rejected(self):
        """官网自述含营销断言时退回模板句，不把宣传语当事实。"""
        html = '<meta name="description" content="award-winning best radio station">'
        self.assertEqual(dt.clean_meta(html), "")


class TestValidate(unittest.TestCase):
    BASE = [{"id": "a", "name": "A", "category": "opera", "streamUrl": "https://x/1",
             "logoUrl": "", "frequency": "FM 88.0", "description": "旧简介", "website": "",
             "sources": [{"url": "https://x/1", "label": "", "bitrate": 0}]}]

    def test_clean_change_passes(self):
        after = [dict(self.BASE[0], tags=["yuju"], country="河南", language="华语")]
        self.assertEqual(dt.validate(self.BASE, after), [])

    def test_stream_urls_must_not_change(self):
        """本次只改文字与字段，动到流地址就是越界（A-1 验过的那些源不能失效）。"""
        after = [dict(self.BASE[0], streamUrl="https://x/dead", tags=["yuju"])]
        self.assertTrue(any("streamUrl" in p for p in dt.validate(self.BASE, after)))

    def test_unknown_tag_is_reported(self):
        after = [dict(self.BASE[0], tags=["京剧"])]
        self.assertTrue(any("词表外" in p for p in dt.validate(self.BASE, after)))

    def test_station_count_must_not_shrink(self):
        self.assertTrue(any("台数" in p for p in dt.validate(self.BASE, [])))

    def test_country_without_language_reported(self):
        after = [dict(self.BASE[0], country="德国", language="")]
        self.assertTrue(any("语言" in p for p in dt.validate(self.BASE, after)))

    def test_real_frequency_cannot_be_emptied(self):
        after = [dict(self.BASE[0], frequency="", tags=["yuju"])]
        self.assertTrue(any("频率" in p for p in dt.validate(self.BASE, after)))

    def test_too_many_tags_reported(self):
        after = [dict(self.BASE[0], tags=["yuju", "jingju", "rock", "jazz", "pop", "folk", "blues"])]
        self.assertTrue(any("标签数" in p for p in dt.validate(self.BASE, after)))


class TestKeyVocabulary(unittest.TestCase):
    def test_vocabulary_size_and_ambiguity_guards(self):
        self.assertGreaterEqual(len(dt.TAG_KEYS), 48)
        self.assertIn("yueopera", dt.TAG_KEYS)
        self.assertIn("countrymusic", dt.TAG_KEYS)
        self.assertNotIn("country", dt.TAG_KEYS)

    def test_language_whitelist(self):
        self.assertIn("华语", dt.LANGUAGES)
        self.assertNotIn("德语 · 英国", dt.LANGUAGES)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 跑测试确认失败**

Run: `python tools/test_derive_tags.py -v`（在 `tools/` 下跑，或 `python -m unittest discover -s tools -p 'test_*.py'`）
Expected: `ModuleNotFoundError: No module named 'derive_tags'`

- [ ] **Step 3: 实现派生脚本**

Create `tools/derive_tags.py`。要点：词表 key 列表与 Kotlin `StationTag` **逐项一致**（脚本内注释写明"两处必须同步，改 key 先改枚举"）；`provenance` 子命令把 A-1 的池子精简成入库文件；`dry-run` 只出报告不写盘。完整实现见本任务 Step 4 跑通的代码结构，规则表如下（实现时逐条照抄，不得自行扩充）：

```python
# 与 StationTag.kt 同步的词表：改 key 必须先改枚举，再改这里
TAG_KEYS = [ ... 48 个 key，顺序与枚举声明一致 ... ]
BANNED_KEYS = {"news", "sports", "traffic", "finance", "opera", "music"}   # 与分类重复的语义
LANGUAGES = {"华语", "英语", "粤语", "闽南语", "客家语", "日语", "韩语", "法语",
             "德语", "西班牙语", "俄语", "意大利语", "葡萄牙语", "阿拉伯语", "朝鲜语", "中文"}

NAME_RULES = [  # (关键词元组, 标签 key)，按优先级排列
    (("豫剧", "河南戏曲"), ["yuju"]),
    (("京剧", "京戏"), ["jingju"]),
    (("越剧",), ["yueju"]),
    (("粤剧", "粤曲", "广府"), ["yueopera"]),
    (("黄梅戏", "黄梅调"), ["huangmei"]),
    (("评剧",), ["pingju"]),
    (("昆曲", "昆剧"), ["kunqu"]),
    (("秦腔",), ["qinqiang"]),
    (("川剧",), ["chuanju"]),
    (("沪剧",), ["hujv"]),
    (("评弹", "弹词"), ["pingtan"]),
    (("相声", "鼓曲", "曲艺"), ["crosstalk"]),
    (("评书", "书会", "故事"), ["storytelling"]),
    (("古典音乐", "classical", "CLASSIC FM"), ["classical"]),
    (("爵士", "jazz"), ["jazz"]),
    (("摇滚", "rock"), ["rock"]),
    (("民谣", "folk"), ["folk"]),
    (("蓝调", "blues"), ["blues"]),
    (("乡村", "country"), ["countrymusic"]),
    (("电子", "house", "trance", "techno", "dance"), ["electronic"]),
    (("嘻哈", "hiphop", "rap"), ["hiphop"]),
    (("金属", "metal", "hard rock"), ["metal"]),
    (("老歌", "oldies", "golden", "revival"), ["oldies"]),
    (("80s", "80 后", "eighties", "80年代"), ["hits80"]),
    (("流行", "hits", "top 40", "cpop"), ["cpop"]),
    (("纯音乐", "instrumental", "lounge"), ["instrumental"]),
    (("轻音乐", "relax", "calm", "smooth", "adult contemporary"), ["lightmusic"]),
    (("路况", "交通", "traffic"), ["trafficnow"]),
    (("天气", "气象", "weather"), ["weather"]),
    (("军事", "国防"), ["military"]),
    (("健康", "医药", "中医"), ["health"]),
    (("教育", "教学", "校园"), ["edu"]),
    (("少儿", "儿童", "kids", "children"), ["kids"]),
    (("历史", "人文", "文化", "heritage"), ["history"]),
    (("财经", "金融", "经济", "business"), ["fincomment"]),
    (("科技", "IT", "tech"), ["tech"]),
    (("体育", "赛事", "足球", "sport"), ["sportslive"]),
    (("夜话", "情感", "深夜", "talk"), ["nighttalk"]),
    (("佛教", "禅", "念经", "法师"), ["buddhist"]),
    (("助眠", "睡眠", "sleep"), ["sleep"]),
    (("外语", "英语学", "lang learn"), ["langlearn"]),
    (("健身", "运动", "fitness"), ["fitness"]),
]
# 本省新闻识别：以省级地名 + 新闻 组合判本地新闻，纯靠台名，不引入外部事实
PROVINCES = ("北京", "上海", "天津", "重庆", "浙江", "江苏", "广东", "广西", "湖南", "湖北",
             "河南", "河北", "山东", "山西", "陕西", "安徽", "福建", "江西", "四川", "贵州",
             "云南", "辽宁", "吉林", "黑龙江", "内蒙古", "宁夏", "新疆", "青海", "甘肃", "海南", "西藏")
RB_TAG_MAP = {  # RadioBrowser 英文标签 → 词表 key；映射不到的直接丢弃
    "adult contemporary": "lightmusic", "hot adult contemporary": "lightmusic",
    "adult hits": "oldies", "classic hits": "oldies", "oldies": "oldies",
    "smooth jazz": "jazz", "jazz": "jazz", "classic rock": "rock", "rock": "rock",
    "album tracks": "rock", "progressive rock": "rock", "heavy metal": "metal",
    "punk": "rock", "blues": "blues", "folk": "folk", "country": "countrymusic",
    "classical": "classical", "chillout": "lightmusic", "ambient": "instrumental",
    "dance": "electronic", "club": "electronic", "house": "electronic", "trance": "electronic",
    "hiphop": "hiphop", "hip hop": "hiphop", "rnb": "rnb", "r&b": "rnb",
    "pop": "pop", "pop music": "pop", "hits": "pop", "80s": "hits80", "70s": "oldies",
    "90s": "oldies", "00s": "oldies", "news": "localnews", "local news": "localnews",
    "world news": "worldnews", "talk": "nighttalk", "talk show": "nighttalk",
    "public radio": "", "information": "localnews", "weather": "weather",
    "traffic": "trafficnow", "local traffic & weather": "trafficnow",
    "sports": "sportslive", "sport": "sportslive", "kids": "kids", "children": "kids",
    "education": "edu", "educational": "edu", "health": "health",
    "religion & spirituality": "buddhist", "culture": "history", "storytelling": "storytelling",
    "literature": "storytelling", "poetry": "storytelling", "opera": "jingju",
}
META_BANNED = ("award", "best", "#1", "no.1", "leading", "premier", "famous", "权威", "著名")
```

关键函数实现要求（逐条可测，别写成大泥球）：

```python
def tags_from_name(name: str) -> list:
    """台名派生标签。注意「河南戏曲广播」只写戏曲、不写剧种，返回空：
    词表没有「剧种不详的戏曲」这一层，猜成豫剧就是编内容（spec §4 已知局限）。
    另外新闻类要单独判：命中省份 + 「新闻」→ localnews，不靠 NAME_RULES 的泛化词。"""

def map_rb_tags(tags: str) -> list:
    """RB 英文标签按 RB_TAG_MAP 精确 token 映射；命中不了直接丢，不做模糊匹配
    （waynesboro、full service 这类脏词就是在这里被挡掉的）。上限 6 去重。"""

def split_frequency(freq: str) -> tuple:
    """含 FM/AM/数字 的当真实频率原样保留；形如「A · B」的拆成 country/language，
    language 必须落在 LANGUAGES 白名单里，否则整串退回频率（不猜）。"""

def strip_desc(desc: str) -> str:
    """以「全球节目源」开头的当占位符，返回空串；其余视为真简介原样保留。"""

def template_desc(country, language, category_label, tag_labels) -> str:
    """只用已核实字段拼一句中文。四样全空时返回空串，不写「暂无介绍」这种废话。"""

def clean_meta(html: str) -> str:
    """og:description 优先，其次 meta description，最后 <title>；去标签、折叠空白、
    截断 48 字；命中 META_BANNED 营销断言的当没有（返回空串）；与台名相同的自述也当没有。"""

def derive_station(st, prov, meta_text=None, today=None) -> dict:
    """合并派生：tags（台名优先、RB 补充、上限 6，按 StationTag.keyOrder 排序）、
    country/language、description（真简介 > 官网自述「模板句 — 原文」> 模板句）。"""

def validate(before, after) -> list:
    """逐条比对，返回问题字符串列表：台数缩水 / id 增减 / streamUrl 或 sources 变化 /
    词表外 tag / 标签数>6 或重复 / country 非空但 language 空 / language 不在白名单 /
    原本有真频率的被清空 / 未知 category。"""
```

CLI 三个子命令：

```
python tools/derive_tags.py provenance          # 生成 tools/rb_provenance.json
python tools/derive_tags.py dry-run             # 只出报告：覆盖率、简介三个来源计数、问题清单
python tools/derive_tags.py apply               # 备份 .pretag.bak 后写 assets；validate 不过就拒绝写
```

- [ ] **Step 4: 跑测试确认通过**

Run: `python -m unittest discover -s tools -p 'test_derive_tags.py'` → 全绿
再跑整套：`python -m unittest discover -s tools -p 'test_*.py'` → A-1 那 111 项 + 本次新增全部通过

- [ ] **Step 5: 生成溯源文件并跑 dry-run**

```bash
python tools/derive_tags.py provenance
python tools/derive_tags.py dry-run | Tee-Object -FilePath .qtest/derive_dry.txt
```

Expected 输出含：标签覆盖率百分比与 `0 标签` 名单、简介三个来源计数（真简介/官网自述/模板句）、country/language 拆出的台数、`validate` 问题列表为空。

- [ ] **Step 6: 暂停并向用户报覆盖率**

spec §7 约定：覆盖率硬底线 40%，低于则停下讨论"要不要加中间层戏曲标签"。**把 dry-run 的三个数原样报给用户，拿到继续的答复再进 Task 4。**

- [ ] **Step 7: Commit（只提交脚本与溯源，不动 assets）**

```bash
git add tools/derive_tags.py tools/test_derive_tags.py tools/rb_provenance.json
git commit -m "feat: 标签派生与字段拆分脚本（含简介三层取材与断言），先 dry-run"
```

---

## Task 4: 应用迁移，写入 assets

**Files:**
- Modify: `app/src/main/assets/radio_stations.json`
- Create: `app/src/main/assets/radio_stations.json.pretag.bak`（脚本产物，不入库）

**Interfaces:**
- Consumes: Task 3 的 `derive_station`、`validate`、`tools/rb_provenance.json`
- Produces: 带 `tags/country/language/新 description` 的内置清单；A-1 审计基线不变

- [ ] **Step 1: 落盘**

```bash
python tools/derive_tags.py apply
```
Expected: 打印"已写入 …（备份 radio_stations.json.pretag.bak）"，且写盘前 `validate` 返回空列表。

- [ ] **Step 2: 复跑 JVM 侧闸（读的就是新 assets）**

```bash
./gradlew :app:testDebugUnitTest --console=plain
```
Expected: `StationJsonTest` 全绿——尤其 `每个 tag 都在词表里`、`country 非空时 language 也必须非空`、`真频率没被清掉`。

- [ ] **Step 3: 复跑 A-1 审计 + 独立数一遍变化**

```bash
python tools/audit_stations.py
python -c "import json;d=json.load(open('app/src/main/assets/radio_stations.json',encoding='utf-8'))['stations'];print('台数',len(d));print('有标签台数',sum(1 for s in d if s.get('tags')));print('有国家台数',sum(1 for s in d if s.get('country')));print('有简介台数',sum(1 for s in d if s.get('description')))"
```
Expected: 审计通过；台数仍 465；三个覆盖数与 dry-run 报告一致（不一致就是有东西被后面步骤改坏）。

- [ ] **Step 4: 抽样实播复验（简介被批量重写，确认没动坏流地址）**

```bash
./gradlew :app:assembleDebug --console=plain
python tools/playcheck.py --per-category "opera=2,sports=1,tv_audio=2,music=1,world=2" --pkg com.radio.chinese.debug --wait 22
```
Expected: `audio_started` 全 True；若有 False，按 A-1 流程写进 `tools/play_failed.json` 并复查是不是流地址被改动（那就是 Task 3 断言漏了，回到 Step 1 前修脚本）。

- [ ] **Step 5: Commit（数据单独一个提交，方便回滚）**

```bash
git add app/src/main/assets/radio_stations.json
git commit -m "feat: 电台清单补齐标签与国家语言字段，全球台补一句真实简介"
```

---

## Task 5: 标签查询与筛选（纯函数 + 仓库委托）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/ui/home/StationFilter.kt`
- Create: `app/src/test/java/com/radio/chinese/StationFilterTest.kt`
- Modify: `app/src/main/java/com/radio/chinese/data/repository/StationRepository.kt`（文件末尾 `// ---- 彩蛋` 之前）
- Modify: `app/src/main/java/com/radio/chinese/ui/home/HomeViewModel.kt`

**Interfaces:**
- Consumes: Task 1 的 `StationTag`/`RadioStation.tags`，Task 2 的 `stationSubtitle`
- Produces:
  - `object StationFilter { fun apply(stations, category: String?, query: String, tag: String?): List<RadioStation>; fun tagsWithCount(stations: List<RadioStation>): List<Pair<StationTag, Int>>; fun matches(station: RadioStation, query: String): Boolean }`
  - `StationRepository.getStationsByTag(tagKey: String): List<RadioStation>`、`getUsedTagsWithCount(): List<Pair<StationTag, Int>>`
  - `HomeUiState` 新增 `tagsWithCount: List<Pair<StationTag, Int>>`、`selectedTag: String?`
  - `HomeViewModel.selectTag(tag: String?)`

- [ ] **Step 1: 写失败的筛选测试**

Create `app/src/test/java/com/radio/chinese/StationFilterTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationTag
import com.radio.chinese.ui.home.StationFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签、分类、搜索三者是正交组合。这里最容易出的两个错：
 * 加了标签筛选就把分类丢掉（用户以为还能同时用），以及空标签列表页
 * 仍然出现在入口里（点进去只看到「这个标签下还没有电台」）。
 */
class StationFilterTest {
    private fun st(id: String, cat: String, vararg tags: String, name: String = id,
                   freq: String = "", country: String = "", lang: String = "") =
        RadioStation(id = id, name = name, category = cat, streamUrl = "https://a/$id",
                     frequency = freq, country = country, language = lang, tags = tags.toList())

    private val sample = listOf(
        st("1", "opera", "yuju", "history", name = "河南豫剧广播", country = "中国", lang = "华语"),
        st("2", "news", "localnews", name = "浙江新闻广播", freq = "FM 88.0"),
        st("3", "music", "jazz", name = "Linn Jazz", country = "英国", lang = "英语"),
        st("4", "opera", "unknown_tag_key", name = "脏数据台"),
    )

    @Test
    fun `标签与分类同时生效`() {
        assertEquals(listOf("1"), StationFilter.apply(sample, "opera", "", "yuju").map { it.id })
        assertTrue(StationFilter.apply(sample, "news", "", "yuju").isEmpty())
    }

    @Test
    fun `搜索能命中标签中文名`() {
        // 用户输「豫剧」不知道它是标签还是台名，行为应当是搜得到
        assertEquals(listOf("1"), StationFilter.apply(sample, null, "豫剧", null).map { it.id })
    }

    @Test
    fun `搜索能命中国家与语言`() {
        assertEquals(listOf("3"), StationFilter.apply(sample, null, "英国", null).map { it.id })
        assertEquals(listOf("3"), StationFilter.apply(sample, null, "英语", null).map { it.id })
    }

    @Test
    fun `搜索仍保留原有的频率命中能力`() {
        assertEquals(listOf("2"), StationFilter.apply(sample, null, "88.0", null).map { it.id })
    }

    @Test
    fun `只统计真有电台的标签且丢弃词表外key`() {
        val counted = StationFilter.tagsWithCount(sample).associate { it.first.key to it.second }
        assertEquals(mapOf("yuju" to 1, "history" to 1, "localnews" to 1, "jazz" to 1), counted)
    }

    @Test
    fun `标签计数按词表顺序稳定`() {
        val keys = StationFilter.tagsWithCount(sample).map { it.first.key }
        assertEquals(StationTag.keyOrder.filter { it in keys.toSet() }, keys)
    }

    @Test
    fun `空查询不过滤`() {
        assertEquals(sample.size, StationFilter.apply(sample, null, "", null).size)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.StationFilterTest" --console=plain`
Expected: 编译失败（`StationFilter` 未定义）

- [ ] **Step 3: 实现纯函数**

Create `app/src/main/java/com/radio/chinese/ui/home/StationFilter.kt`：

```kotlin
package com.radio.chinese.ui.home

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationCategory
import com.radio.chinese.domain.model.StationTag

/**
 * 电台列表的三个筛选维度：分类、搜索、标签。彼此正交，可任意组合。
 *
 * 做成纯函数而不写在 ViewModel 里，是因为这套规则要在 JVM 单测里被钉住
 * （ViewModel 依赖 Hilt 与协程作用域，测起来贵得多）。
 */
object StationFilter {

    fun matches(station: RadioStation, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val tagLabels = station.tags.map { StationTag.labelOf(it).lowercase() }
        return station.name.lowercase().contains(q) ||
            station.frequency.lowercase().contains(q) ||
            station.description.lowercase().contains(q) ||
            station.country.lowercase().contains(q) ||
            station.language.lowercase().contains(q) ||
            tagLabels.any { it.contains(q) || q.contains(it) }
    }

    fun apply(
        stations: List<RadioStation>,
        category: String?,
        query: String,
        tag: String? = null,
    ): List<RadioStation> = stations.filter { st ->
        (category == null || st.category == category) &&
            (tag == null || tag in st.tags) &&
            matches(st, query)
    }

    /** 只返回真的有电台的标签，按词表顺序；空标签不入口——点进去只会看到空列表。 */
    fun tagsWithCount(stations: List<RadioStation>): List<Pair<StationTag, Int>> {
        val counts = stations.flatMap { it.tags }.groupingBy { it }.eachCount()
        return StationTag.entries
            .filter { counts.containsKey(it.key) }
            .map { it to counts.getValue(it.key) }
    }

    /** 分类中文名，供副标题回落；单独收敛在这里，避免 UI 层各写一份。 */
    fun categoryLabel(station: RadioStation): String = StationCategory.labelOf(station.category)
}
```

- [ ] **Step 4: 仓库层委托**

Modify `StationRepository.kt`，在 `// ---- 无效标记 ----` 之前插入：

```kotlin
    // ---- 标签 ----

    /** 某标签下的有效电台。 */
    suspend fun getStationsByTag(tagKey: String): List<RadioStation> =
        com.radio.chinese.ui.home.StationFilter.apply(getActiveStations(), null, "", tagKey)

    /** 有电台的标签及其台数，词表外的 key 不会出现。 */
    suspend fun getUsedTagsWithCount(): List<Pair<com.radio.chinese.domain.model.StationTag, Int>> =
        com.radio.chinese.ui.home.StationFilter.tagsWithCount(getActiveStations())
```

- [ ] **Step 5: ViewModel 接上标签维度**

Modify `HomeViewModel.kt`：

1. `HomeUiState` 增加两个字段：

```kotlin
    val tagsWithCount: List<Pair<com.radio.chinese.domain.model.StationTag, Int>> = emptyList(),
    val selectedTag: String? = null,
```

2. 增加状态源与 setter（放在 `_selectedCategory` 旁边）：

```kotlin
    private val _selectedTag = MutableStateFlow<String?>(null)
```

```kotlin
    fun selectTag(tag: String?) {
        _selectedTag.value = tag
    }
```

3. `combine` 里把第三个组合从 `Pair(selectedCategory, searchQuery)` 扩成三元，并改用 `StationFilter`：

```kotlin
    val uiState: StateFlow<HomeUiState> = combine(
        combine(_stations, _favoriteIds) { a, b -> Pair(a, b) },
        combine(_selectedCategory, _searchQuery) { a, b -> Pair(a, b) },
        combine(_isLoading, _error) { a, b -> Pair(a, b) },
        _selectedTag
    ) { (stations, favIds), (category, query), (loading, error), tag ->
        val filtered = StationFilter.apply(stations, category, query, tag)
        // 分类条/分类网格只列"真的有电台"的分类（既有教训，标签同理，由 tagsWithCount 保证）
        val usedCategories = stationRepository.getCategories().filter { (id, _) ->
            stations.any { it.category == id }
        }
        HomeUiState(
            stations = stations,
            filteredStations = filtered,
            favoriteIds = favIds,
            categories = usedCategories,
            tagsWithCount = StationFilter.tagsWithCount(stations),
            selectedCategory = category,
            selectedTag = tag,
            searchQuery = query,
            isLoading = loading,
            error = error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())
```

4. 删除已被替换掉的私有 `filterStations(...)` 函数（避免留下第二套规则）。

- [ ] **Step 6: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --console=plain` → 全绿
Run: `./gradlew :app:assembleDebug --console=plain` → BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/radio/chinese/ui/home/StationFilter.kt app/src/main/java/com/radio/chinese/ui/home/HomeViewModel.kt app/src/main/java/com/radio/chinese/data/repository/StationRepository.kt app/src/test/java/com/radio/chinese/StationFilterTest.kt
git commit -m "feat: 电台筛选加标签维度并抽出 StationFilter 纯函数"
```

---

## Task 6: 标签浏览页与分类页入口

**Files:**
- Create: `app/src/main/java/com/radio/chinese/ui/tag/TagScreen.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/MainScreen.kt:49-95`
- Modify: `app/src/main/java/com/radio/chinese/ui/radio/RadioScreen.kt:137-150`
- Modify: `app/src/main/java/com/radio/chinese/ui/category/CategoryScreen.kt:44-101`
- Create: `tools/tag_ui_check.py`

**Interfaces:**
- Consumes: Task 5 的 `HomeUiState.tagsWithCount/selectedTag/selectTag`、`StationFilter.apply`
- Produces:
  - `Screen.Tags`：route `tags?tag={tag}`，`Screen.Tags.createRoute(tag: String?): String`
  - `@Composable fun TagScreen(initialTag: String?, onNavigateToPlayer: (String) -> Unit, onNavigateBack: () -> Unit, showTopBar: Boolean = true, viewModel: HomeViewModel = hiltViewModel())`
  - `CategoryScreen(..., onNavigateToTag: (String?) -> Unit)`

- [ ] **Step 1: 加路由**

Modify `Screen.kt`，在 `data object Manage` 之后：

```kotlin
    /** 按标签浏览。tag 为空时进标签云，非空时直接落到该标签的电台列表。 */
    data object Tags : Screen("tags?tag={tag}") {
        const val ARG = "tag"
        fun createRoute(tag: String?): String = if (tag.isNullOrBlank()) "tags?tag=" else "tags?tag=$tag"
    }
```

- [ ] **Step 2: 实现标签页（两态）**

Create `app/src/main/java/com/radio/chinese/ui/tag/TagScreen.kt`：

```kotlin
package com.radio.chinese.ui.tag

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.StationTag
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.StationListItem
import com.radio.chinese.ui.home.HomeViewModel
import com.radio.chinese.ui.theme.Dimens

/**
 * 按标签浏览电台。两态：标签云 → 该标签的电台列表。
 *
 * 第二态用页内状态而不是再开一条路由：本页是从 pager 里的分类页 push 进来的，
 * 多一层返回栈就会多一处「返回后页签掉回全部」的状态丢失点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(
    initialTag: String?,
    onNavigateToPlayer: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    showTopBar: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsState()
    var picked by rememberSaveable { mutableStateOf(initialTag?.takeIf { it.isNotBlank() && StationTag.isValidKey(it) }) }
    val title = picked?.let { StationTag.labelOf(it) } ?: "按标签浏览"

    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    windowInsets = NestedWindowInsets,
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (picked != null) { picked = null; viewModel.selectTag(null) } else onNavigateBack()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        }
    ) { padding ->
        val modifier = if (showTopBar) Modifier.padding(padding) else Modifier
        val current = picked
        if (current == null) {
            if (uiState.tagsWithCount.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Tag,
                    title = "还没有可用的标签",
                    hint = "电台数据里补入标签后，这里就会出现可浏览的分类",
                    modifier = modifier,
                )
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Dimens.ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(Dimens.GapLarge),
                ) {
                    for ((group, tags) in StationTag.orderedByGroup()) {
                        val present = tags.filter { t -> uiState.tagsWithCount.any { it.first == t } }
                        if (present.isEmpty()) continue
                        item(key = "group-${group.name}") {
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(Dimens.GapSmall))
                            // FlowRow 在本项目 minSdk 26 + Compose 版本下可用；不用它就得手算换行
                            FlowRowLayout(
                                items = present,
                                itemContent = { tag ->
                                    val count = uiState.tagsWithCount.first { it.first == tag }.second
                                    TagChip(
                                        label = "${tag.label} · $count",
                                        onClick = { picked = tag.key; viewModel.selectTag(tag.key) },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        } else {
            val stations = uiState.stations.filter { current in it.tags }
            if (stations.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Tag,
                    title = "这个标签下暂时没有电台",
                    hint = "返回挑一个别的标签",
                    modifier = modifier,
                )
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Dimens.ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
                ) {
                    items(stations, key = { it.id }) { station ->
                        StationListItem(
                            station = station,
                            isPlaying = false,
                            isFavorite = uiState.favoriteIds.contains(station.id),
                            onClick = { onNavigateToPlayer(station.id) },
                            onFavoriteClick = { viewModel.toggleFavorite(station.id) },
                        )
                    }
                }
            }
        }
    }
}

/** 标签 chip：命中区在车机档由 Dimens.TouchMin 兜底（spec 全局约束）。 */
@Composable
private fun TagChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .padding(end = Dimens.ChipGap, bottom = Dimens.GapSmall)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Dimens.GapLarge, vertical = Dimens.GapMedium),
        )
    }
}
```

> `FlowRowLayout` 是本项目已有的换行容器；若该模块尚无 FlowRow 可用，则用
> `Column` 内按每行 4 个切分 `chunks` 实现，**不要**为此新引依赖。实现前先在
> `app/src/main/java/com/radio/chinese/ui/common/Widgets.kt` 里确认有没有现成件；
> 没有就补一个最小 `FlowRowLayout`（`androidx.compose.foundation.layout.FlowRow`
> 在 Material3 已有，标注 `@OptIn(ExperimentalLayoutApi::class)` 后直接用）。

- [ ] **Step 3: 挂路由与回调**

Modify `MainScreen.kt` 的 `RadioNavGraph`，在 `composable(Screen.Manage.route)` 之后：

```kotlin
        composable(
            route = Screen.Tags.route,
            arguments = listOf(navArgument(Screen.Tags.ARG) {
                type = NavType.StringType
                defaultValue = ""
                nullable = true
            })
        ) { entry ->
            TagScreen(
                initialTag = entry.arguments?.getString(Screen.Tags.ARG),
                onNavigateToPlayer = { id -> navController.navigate(Screen.Player.createRoute(id)) },
                onNavigateBack = { navController.popBackStack() },
            )
        }
```

`RadioScreen.kt` 与 `RadioPagerHost` 的签名各加一个参数并向下传：

```kotlin
    onNavigateToTag: (String?) -> Unit,
```

`RadioPagerHost` 里第 1 页（分类）改为：

```kotlin
            1 -> CategoryScreen(showTopBar = false, viewModel = homeViewModel, searchQuery = searchQuery,
                onNavigateToPlayer = onNavigateToPlayer,
                onNavigateToTag = onNavigateToTag,
                onNavigateBack = { scope.launch { pagerState.animateScrollToPage(0) } })
```

`MainScreen.kt` 里 `RadioScreen(...)` 调用处补：

```kotlin
                onNavigateToTag = { tag -> navController.navigate(Screen.Tags.createRoute(tag)) },
```

- [ ] **Step 4: 分类页加入口**

Modify `CategoryScreen.kt`：签名加 `onNavigateToTag: (String?) -> Unit = {}`；
`else ->` 分支里 `LazyVerticalGrid` 外面包一层 `Column`，网格之上放入口行（**不占网格格子**，
以免 `items` 的 key 冲突，也不改变分类网格既有排布）：

```kotlin
                else -> Column(m) {
                    TagBrowseEntry(
                        tagCount = uiState.tagsWithCount.size,
                        onClick = { onNavigateToTag(null) },
                    )
                    LazyVerticalGrid(
                        columns = if (isCarSurface()) GridCells.Adaptive(minSize = 240.dp)
                        else GridCells.Fixed(2),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(Dimens.ScreenPadding),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
                        verticalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
                    ) { /* 原有 items(...) 不变 */ }
                }
```

`TagBrowseEntry`（放同文件私有）：

```kotlin
@Composable
private fun TagBrowseEntry(tagCount: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapSmall)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(Dimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Tag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.IconLarge),
            )
            Spacer(modifier = Modifier.width(Dimens.GapMedium))
            Column(Modifier.weight(1f)) {
                Text("按标签浏览", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (tagCount > 0) "$tagCount 个标签" else "还没有可用标签",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

- [ ] **Step 5: 设备几何断言脚本**

Create `tools/tag_ui_check.py`（复用 `ui_tap.py` 的 dump/tap 能力，参数化档位）：

```python
"""标签页的设备端几何与状态断言。

手机档与车机档都要过：spec 的 Review Focus 里两条（返回丢页签、
车机 chips 挤掉播控）只有在真机上才暴露，纸上推定不算验过。
"""
import argparse
import json
import re
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import ui_tap as ut

MIN_DP = {"phone": 48.0, "car": 88.0}


def clickable_nodes(xml: str):
    out = []
    for m in re.finditer(r"<node\b[^>]*>", xml):
        s = m.group(0)
        if 'clickable="true"' not in s:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        if not b:
            continue
        x1, y1, x2, y2 = (int(v) for v in b.groups())
        label = (re.search(r'text="([^"]*)"', s) or re.search(r'content-desc="([^"]*)"', s) or [None, ""])[1]
        out.append((label, x1, y1, x2, y2))
    return out


def check_hit_targets(xml: str, density: float, surface: str, want_text: str):
    limit = MIN_DP[surface]
    bad = []
    for label, x1, y1, x2, y2 in clickable_nodes(xml):
        if want_text and want_text not in label:
            continue
        w, h = (x2 - x1) / density, (y2 - y1) / density
        if w < limit or h < limit:
            bad.append((label[:16], round(w), round(h)))
    return bad, limit


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--density", type=float, required=True, help="160dpi=1.0；240dpi=1.5")
    p.add_argument("--surface", choices=["phone", "car"], required=True)
    p.add_argument("--pkg", default="com.radio.chinese.debug")
    p.add_argument("--tag", default="yuju", help="要打开的标签 key")
    args = p.parse_args()

    ut.goto_home(args.pkg)
    if not ut.tap("分类", max_scrolls=2):
        print("[FAIL] 进不了分类页"); return 1
    if not ut.tap("按标签浏览", max_scrolls=2):
        print("[FAIL] 找不到「按标签浏览」入口"); return 1
    cloud, _ = ut.dump("tag_cloud")
    if "个标签" not in cloud and "豫剧" not in cloud:
        print("[FAIL] 标签云没渲染出来"); return 1
    bad, limit = check_hit_targets(cloud, args.density, args.surface, "·")
    for b in bad:
        print(f"  [FAIL] chip 命中区不足 {limit}dp: {b}")
    if not ut.tap(args.tag, max_scrolls=3):
        print(f"[FAIL] 点不开标签 {args.tag}"); return 1
    lst, _ = ut.dump("tag_list")
    playing_ok = "电台" in lst or "FM" in lst
    # 返回：必须还停在「分类」页签（Review Focus #4）
    ut.adb("shell", "input", "keyevent", "KEYCODE_BACK")
    import time; time.sleep(1.5)
    ut.adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.5)
    back, _ = ut.dump("tag_back")
    tab_ok = "按标签浏览" in back
    print(f"标签列表渲染={'OK' if playing_ok else 'FAIL'} 返回停在分类页={'OK' if tab_ok else 'FAIL'}")
    ok = (not bad) and playing_ok and tab_ok
    print("标签页设备校验通过" if ok else "标签页设备校验未通过")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 6: 手机三档 + 车机两档跑一遍**

```bash
python tools/tag_ui_check.py --density 2.625 --surface phone --pkg com.radio.chinese.debug   # 1080x2424@420
python tools/tag_ui_check.py --density 1.5   --surface car  --pkg com.radio.chinese.debug   # 车机 240dpi
python tools/tag_ui_check.py --density 1.0   --surface car  --pkg com.radio.chinese.debug   # 车机 160dpi
```
Expected: 三次都打印"标签页设备校验通过"。
**若 `返回停在分类页=FAIL`**：按 spec 的退路执行——去掉 `Screen.Tags` 路由，把标签云/列表并入
`CategoryScreen` 的第三态（`selectedCategory` 之外加 `selectedView: Tags|Categories|TagStations`），
并回到本步重跑；不得靠"用户不会那样点"放过。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/radio/chinese/ui/tag/ app/src/main/java/com/radio/chinese/ui/navigation/Screen.kt app/src/main/java/com/radio/chinese/ui/MainScreen.kt app/src/main/java/com/radio/chinese/ui/radio/RadioScreen.kt app/src/main/java/com/radio/chinese/ui/category/CategoryScreen.kt tools/tag_ui_check.py
git commit -m "feat: 新增按标签浏览页与分类页入口，手机车机几何断言入脚本"
```

---

## Task 7: 播放器页标签 chips 与视口降级

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/common/StationTagChips.kt`（新建）
- Modify: `app/src/main/java/com/radio/chinese/ui/player/PlayerScreen.kt:344-378` 及签名
- Modify: `app/src/main/java/com/radio/chinese/ui/MainScreen.kt`（`Player` composable 传回调）
- Modify: `tools/tag_ui_check.py`（加 `--check-player`）

**Interfaces:**
- Consumes: `StationTag.byKeyOrder(keys, limit)`（Task 1）
- Produces: `@Composable fun StationTagChips(tags: List<String>, onTagClick: (String) -> Unit, modifier: Modifier = Modifier)`

- [ ] **Step 1: 抽 chips 组件**

Create `app/src/main/java/com/radio/chinese/ui/common/StationTagChips.kt`：

```kotlin
package com.radio.chinese.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.radio.chinese.domain.model.StationTag
import com.radio.chinese.ui.theme.Dimens

/**
 * 一台电台的标签 chips。最多 4 个，按词表分组顺序取——详情页空间有限，
 * 全铺会把播控挤出车机档首屏（项目里已因同类问题修过 迷你条/源列表 两轮）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StationTagChips(
    tags: List<String>,
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = StationTag.byKeyOrder(tags, limit = 4)
    if (shown.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimens.ChipGap),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapSmall),
    ) {
        shown.forEach { tag ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { onTagClick(tag.key) },
            ) {
                Text(
                    text = tag.label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = Dimens.GapMedium, vertical = Dimens.GapSmall),
                )
            }
        }
    }
}
```

- [ ] **Step 2: 播放器页接入并保留降级**

`PlayerScreen.kt` 的 `StationInfoBlock` 签名加 `onTagClick: (String) -> Unit`，在简介那段之后插入（**复用同一个 `showDescription` 视口开关**，不新增判断分支）：

```kotlin
        if (showDescription && station.tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Dimens.GapSmall))
            StationTagChips(
                tags = station.tags,
                onTagClick = onTagClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
```

`PlayerScreen` 顶层函数加参数 `onNavigateToTag: (String) -> Unit = {}`，
车机档左右分栏与手机档两个调用点都要把 `onTagClick = onNavigateToTag` 传下去；
`MainScreen.kt` 的 `Player` composable 补：

```kotlin
                onNavigateToTag = { tag -> navController.navigate(Screen.Tags.createRoute(tag)) },
```

- [ ] **Step 3: 车机小高度档实测**

```bash
python tools/tag_ui_check.py --density 1.5 --surface car --pkg com.radio.chinese.debug --check-player
```
Expected: 播控按钮仍在可视区内（脚本读播放键 bounds 底边 ≤ 视口高 - 迷你条高）。
若不过关：把 chips 的显示条件收紧成"仅 `station.description` 为空时显示"，并在此文件记录原因。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/radio/chinese/ui/common/StationTagChips.kt app/src/main/java/com/radio/chinese/ui/player/PlayerScreen.kt app/src/main/java/com/radio/chinese/ui/MainScreen.kt tools/tag_ui_check.py
git commit -m "feat: 播放器页展示可点击标签 chips，视口不足时整块降级隐藏"
```

---

## Task 8: 自定义电台打标签（只能选词表内的）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/manage/ManageScreen.kt:191-200` 与 `AddStationDialog`
- Modify: `app/src/main/java/com/radio/chinese/ui/manage/ManageViewModel.kt:136-166`
- Create: `app/src/test/java/com/radio/chinese/CustomStationTagsTest.kt`

> **与 spec 的一处收敛**：spec 写了"添加/编辑对话框"，但代码里**只有添加对话框，
> 没有编辑电台**（`StationActionDialog` 只有检测/标记失效/删除）。本期按计划只扩添加流程，
> 不顺手新增"编辑电台"这个原本没有的功能；给已添加的台改标签，走"删掉重加"。

**Interfaces:**
- Consumes: `StationTag.entries`（词表）、`StationTag.isValidKey`
- Produces: `ManageViewModel.addCustomStation(name, streamUrl, category, frequency, description, tags: List<String>)`；`sanitizeTags(raw: List<String>): List<String>`（纯函数，UI 与仓库共用）

- [ ] **Step 1: 写失败的防御性测试**

Create `app/src/test/java/com/radio/chinese/CustomStationTagsTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.ui.manage.sanitizeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签只能来自词表：UI 里只给复选框，但仍要防住"手工传进来一个不认识的 key"
 * （比如以后从别的版本同步了 DataStore），否则脏 key 会一路活到列表里。
 */
class CustomStationTagsTest {
    @Test
    fun `丢掉词表外的key并去重`() {
        assertEquals(listOf("yuju", "jazz"), sanitizeTags(listOf("yuju", "京剧", "yuju", "jazz")))
    }

    @Test
    fun `超过6个只留分组优先级靠前的`() {
        val raw = listOf("rock", "pop", "jazz", "folk", "blues", "oldies", "yuju", "crosstalk")
        val got = sanitizeTags(raw)
        assertEquals(6, got.size)
        assertTrue("按词表顺序保留靠前的", got.first() == "oldies")
    }

    @Test
    fun `空白与空串被忽略`() {
        assertEquals(emptyList<String>(), sanitizeTags(listOf("", "  ", "nope")))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.CustomStationTagsTest" --console=plain`
Expected: 编译失败（`sanitizeTags` 未定义）

- [ ] **Step 3: 实现 sanitizeTags 并接进 ViewModel**

在 `ManageViewModel.kt` 文件顶层（`class ManageViewModel` 之前）加：

```kotlin
/** 自定义电台的标签清洗：只认词表、去重、按词表顺序、最多 6 个。 */
fun sanitizeTags(raw: List<String>): List<String> =
    com.radio.chinese.domain.model.StationTag.byKeyOrder(
        raw.map { it.trim() }.filter { it.isNotEmpty() && com.radio.chinese.domain.model.StationTag.isValidKey(it) },
        limit = 6,
    ).map { it.key }
```

`addCustomStation(...)` 形参加 `tags: List<String> = emptyList()`，构造 `RadioStation` 时加：

```kotlin
                tags = sanitizeTags(tags),
```

- [ ] **Step 4: 添加对话框加标签多选**

`ManageScreen.kt` 的 `AddStationDialog` 签名加 `onConfirm: (name, url, category, freq, desc, tags: List<String>) -> Unit`；
调用点（第 192-200 行）改为把 `tags` 传给 `viewModel.addCustomStation(...)`。
对话框内部，在"分类"那段之后、"确定"按钮之前，插入 `ExposedDropdownMenuBox`
（**普通 DropdownMenu 在对话框里点不开**，项目已记录）：

```kotlin
                ExposedDropdownMenuBox(
                    expanded = showTagMenu,
                    onExpandedChange = { showTagMenu = it },
                ) {
                    OutlinedTextField(
                        value = if (pickedTags.isEmpty()) "不选标签"
                        else pickedTags.joinToString("、") { StationTag.labelOf(it) },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("标签（最多 6 个）") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showTagMenu) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = showTagMenu, onDismissRequest = { showTagMenu = false }) {
                        StationTag.entries.forEach { tag ->
                            val on = tag.key in pickedTags
                            DropdownMenuItem(
                                text = { Text("${tag.label} · ${tag.group.label}") },
                                trailingIcon = { if (on) Icon(Icons.Default.Check, contentDescription = null) },
                                onClick = {
                                    pickedTags = if (on) pickedTags - tag.key
                                    else if (pickedTags.size >= 6) pickedTags else pickedTags + tag.key
                                },
                            )
                        }
                    }
                }
```

状态与滚动：`var pickedTags by rememberSaveable { mutableStateOf(listOf<String>()) }`；
确认对话框正文已是 `verticalScroll`（沿用现有容器，别去掉它——超大字号下会顶掉按钮）。

- [ ] **Step 5: 跑全量测试并手测一遍添加流程**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
设备：设置 → 管理电台 → 添加电台 → 选两个标签 → 保存 → 首页搜索该标签名能命中、
标签页该标签下台数 +1。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/radio/chinese/ui/manage/ app/src/test/java/com/radio/chinese/CustomStationTagsTest.kt
git commit -m "feat: 添加自定义电台可选标签（限受控词表），脏 key 由 sanitizeTags 挡掉"
```

---

## Task 9: 全量回归、发布包与上库

**Files:**
- 无新增代码；跑齐验收矩阵并出包

- [ ] **Step 1: 全量自动化**

```bash
python -m unittest discover -s tools -p 'test_*.py'
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain
```
Expected: Python 与 JVM 测试全绿；`app/build/outputs/apk/release/chinese-online-radio-1.0.<N>-release.apk` 生成（N = 当前提交数 + 本次提交数）。

- [ ] **Step 2: 发布前清单 6 项（手机档为主）**

逐项在真机/模拟器上验，不许"看代码认为没问题"：
1. 电台列表加载（Hilt 注入变更）
2. 播放/暂停与换台（含媒体键切台）
3. 戏曲循环模式（列表/单曲/随机）
4. 字体大小持久化 + 超大字号下标签页与 chips 不溢出
5. 底部/迷你播放条布局（Scaffold.bottomBar 内边距）
6. 音频库网络 Tab 目录加载（双进度环）

- [ ] **Step 3: 手机三档 + 车机两档几何矩阵**

```bash
python tools/tag_ui_check.py --density 2.625 --surface phone --pkg com.radio.chinese.debug
python tools/tag_ui_check.py --density 1.0   --surface car   --pkg com.radio.chinese.debug
python tools/tag_ui_check.py --density 1.5   --surface car   --pkg com.radio.chinese.debug
powershell -File tools/car_assert.ps1 -name tags -minDp 88.0        # 车机命中区
powershell -File tools/car_assert.ps1 -name tags_phone -minDp 48.0  # 手机命中区
```

- [ ] **Step 4: 抽样实播（含裸 IP 台）**

```bash
python tools/playcheck.py --per-category "opera=2,sports=1,tv_audio=2,world=3,music=1" --pkg com.radio.chinese.debug --wait 22
```
Expected: 全部 `audio_started=True`；出现 False 的按 A-1 流程进 `tools/play_failed.json` 并单独提交说明。

- [ ] **Step 5: 上库与版本号确认**

```bash
git log --oneline -8
git fetch origin
git merge-base --is-ancestor origin/main HEAD   # 必须返回 0 才能快进
git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 push origin main
python tools/audit_stations.py                  # 最终清单审计
```
提交前复核：`git show HEAD:app/src/main/java/com/radio/chinese/config/EasterEggConfig.kt` 仍是占位符；
`git diff --name-only origin/main..HEAD` 无 `*.apk`、无 `keystore*`、无 `local.properties`。
版本号由 `git rev-list --count HEAD` 自动递增，构建产物文件名用通配取，不硬编码。

---

## 计划自查（写完后的核对结果）

**1. Spec 覆盖**：§2 模型 → Task 1；§3 词表 → Task 1；§4 派生与简介 → Task 3/4；
§5.1 副标题 → Task 2；§5.2 仓库与筛选 → Task 5；§5.3 标签页与入口 → Task 6；
§5.4 详情页 chips → Task 7、管理页多选 → Task 8；§6 junit → Task 1；§7 测试矩阵 → 各任务 + Task 9；
§8 风险（溯源入库 Task 3、pager 待证 Task 6 Step 6 退路、体积与回滚各提交分开）。

**2. Placeholder 扫描**：Task 6 里 `FlowRowLayout` 是唯一含糊处，已就地给出可执行判定
（先查 `Widgets.kt` 有没有现成件，没有就用 `androidx.compose.foundation.layout.FlowRow` +
`@OptIn(ExperimentalLayoutApi::class)`）；Task 3 的 `TAG_KEYS`/规则表要求逐条照抄，
测试里已钉住 key 数量下限与撞名规避。

**3. 类型一致性**：`StationTag.byKeyOrder(keys, limit)`（Task 1 定义）与 Task 7 的
`byKeyOrder(tags, limit = 4)`、Task 8 的 `byKeyOrder(..., limit = 6)` 一致；
`sanitizeTags` 仅 Task 8 定义并使用；`stationSubtitle(station, categoryLabel)`
Task 2 定义、Task 2 三处调用，Task 5 的 `StationFilter.categoryLabel` 是它的取值辅助，不重复实现。

**4. Review Focus**：5 条分别由 Task 1 `未知 key 回落`、Task 1 `旧自定义电台 JSON 缺三个新字段`、
Task 3 `test_real_fm_value_is_kept` + Task 2 `真频率优先`、Task 6 Step 6 返回页签实测、
Task 7 Step 3 车机小高度实测 钉住。
