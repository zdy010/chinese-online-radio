# iptv Tab（iptv-org 公开直播流）· 设计文档

- 日期：2026-11-06（同日按自审 review 重写，见 §13）
- 需求：新增一个名为 `iptv` 的一级 tab，位置在「电台」与「音频库」之间，收录 https://github.com/iptv-org/iptv 的公开直播流
- 状态：待用户审阅 → 通过后调 writing-plans
- 本文所有数字、字段名、URL 形态都是**实测所得**（探针在 `.superpowers/tmp/iptv-*.ps1`，git-ignored）。凡未实测的推断都在句中标「未验证」

---

## 0. 决定台账（用户已定，逐条可追溯）

| # | 议题 | 决定 |
|---|---|---|
| V1 | 收录范围 | **全收，不按国家与语言过滤** |
| V2 | 成人内容 | **收录但默认隐藏**，设置页给开关 |
| V3 | 清单送达方式 | **构建期工具拉取生成 assets json**（运行时不依赖 GitHub 可达） |
| V4 | 数据模型 | **复用 `RadioStation`** + 独立数据文件 + id 前缀 `iptv:` |
| V5 | tab 内部形态 | **搜索优先 + 分类/国家/最近/收藏入口**，不平铺全量 |
| V6 | 播放页 | **新建专用 `IptvPlayerScreen`** |
| V7 | 无频道归属的流 | **收**，名字从 URL 路径派生（细则见 §2.2） |
| V8 | 分类建模 | **新建 `IptvCategory`**，不动 `StationCategory` |
| V9 | 加载策略 | **懒加载内存单例**，首页/电台页完全不碰 |
| V10 | 失效处理 | **不做全量体检**，靠既有多源回退 + 单台检测 |
| V11 | 方控滚轮/媒体键上下台 | **跟当前页走**：在 iptv 播放页切 iptv 列表，回电台页恢复原语义 |
| V12 | 频道别名 `alt_names` | **入库**，并接进 `StationFilter` 参与匹配 |

### ⚠️ V1 覆盖了项目现行版权红线（必须留档）

项目红线是「电台音频源仅收录官方公开流」（成人内容当初就是按这条整体下线的）。iptv-org 的**政策**确实写着只收录电视台自己网站上公开可看的流，但实测清单里混着大量**裸 IP 与境外机房转播**，这类不属于官方公开流：

```
26  183.207.248.71     ← 裸 IP（该 host 下 26 条流是 26 个不同频道）
17  39.134.115.163     ← 裸 IP
14  74.91.26.218       ← 裸 IP，境外机房
12  38.75.136.137      ← 裸 IP，境外机房
 6  news.cgtn.com / livealone302.iqilu.com / play-live-hls.jxtvcn.com.cn / video10.qtv.com.cn  ← 官方 ✅
```

本设计按用户决定全收。为保留追溯能力，同步工具**额外产出 `doc/iptv_nonofficial_hosts.md`**，列出"流主机名与该台官方站点不同主域、或该台无 website"的条目与条数。这份清单不是产品功能，是审计线索。

---

## 1. 数据源实测事实

| 事实 | 实测值 |
|---|---|
| 全库频道 / 全库流 | 31,744 / 18,458 |
| 有流且有频道元数据的频道 | 10,338 |
| **无频道元数据的流（direct）** | **2,097 条**。注意不是 1,941：差的 156 条是 `channel` 字段有值但 channels.json 里查不到该 id。另：两次探针分别得到 2,096 与 2,097（差 1 条，空字串与 null 的判定差异），**以工具输出为准**，不拿我手算的数当结论 |
| **入库记录总数** | **实测 12,419** = 10,337 频道记录 + 2,082 direct 记录（与估算 12,435 差 16 条：部分频道没有合法 http(s) 流，被工具剔除）。工具重跑后实测拆分为 10,337 + 2,096 = **12,419**，以每次输出为准 |
| 真正带 header 的流 | 实测 **1,266 个 url**（只 UA 925、只 Referer 183、两者都有 158），占全部 18,438 条流的 6.9%。这就是 Task 6 那条通路要服务的全部对象，也是“只 UA 或只 Referer 都必须生效”的依据 |
| 空名频道 | 存在（实测）：部分 iptv-org 频道 `name` 为空，工具必须兜底到 network / 流 host / 频道 id，否则列表出空白行 |
| `is_nsfw=true` 频道 | 374 个标记，但**其中只有 3 条流**（实测）→ 真正入库的成人记录只有 **2 条**（Tele Posadas.ar / Retroclub.ar，分类是 general/music） |
| `xxx` 分类频道 | 354 个，**被流引用的 = 0 条** → 入库清单里根本不会出现 `tags` 含 `xxx` 的条目 |
| `replaced_by` 非空 | 639（上游标记已替换/下线） |
| json 体积 | 实测 **6.33 MB 未压缩**（包装形状 + 字段比估算时全）；APK 增量与压缩率以验收实测为准 |
| iptv 分类码 | **30 个**：`auto, animation, business, classic, comedy, cooking, culture, documentary, education, entertainment, family, general, interactive, kids, legislative, lifestyle, movies, music, news, outdoor, public, relax, religious, science, series, shop, sports, travel, weather, xxx`（无空 id） |
| iptv 国家码 | 250 个 |
| direct 流取名可用率 | 分层取名后 **2,097/2,097 有名**，去重 **1,660 种**；剩余高频重名是 `master`×174、`manifest`×50、`live`×40、`hls`×36。工具实测需两级消歧（追加 host 后再加序号）才能做到全库名字唯一：375 组靠 host 分开，122 组靠序号 |

> 上面两行把 V2 的实际作用面说清了：**成人开关今天只管 2 条记录**。
> 逻辑仍必须做（上游随时会加流），但验收时不能拿“隐藏了几百个频道”当预期，
> T9 只能验那 2 条的有无。

### 真实字段名（探针核实）

```jsonc
// channels.json
{ "id":"CCTV1.cn", "name":"CCTV 1", "alt_names":[], "network":null, "owners":[],
  "country":"CN", "categories":["general"], "is_nsfw":false,
  "launched":null, "closed":null, "replaced_by":null, "website":"https://..." }

// streams.json —— user_agent / referrer 决定能不能播出来，见 §6
{ "channel":"CCTV1.cn", "feed":null, "title":"CCTV 1 (1080p)",
  "url":"https://...", "quality":"1080p", "labels":[],
  "user_agent":null, "referrer":null }

// countries.json —— 字段是 name / code / languages / flag，不是 id
{ "name":"Afghanistan", "code":"AF", "languages":["pus"], "flag":"🇦🇫" }

// categories.json —— id / name / description
```

`channels[].categories` 是分类 **id 数组**（小写），与 `categories.json` 的 `id` 同源。

---

## 2. 收录与归一化规则

### 2.1 频道记录（10,338 条）

| 字段 | 取值规则 |
|---|---|
| `id` | `"iptv:" + channel.id` |
| `name` | `channel.name` |
| `altNames` | `channel.alt_names`（V12，参与搜索） |
| `category` | 固定 `"general"`。**不用 `"tv"`**：`StationCategory.labelOf()` 对未知 key 会**原样回显**，填 `tv` 会让收藏页/管理页直接显示内部 key |
| `streamUrl` | 第一条流的 url（仅作 `allSources()` 兜底） |
| `sources[]` | 该频道全部流：`url` + **`quality`**=stream.quality + `userAgent` + `referrer`。**`label` 一律留空**，理由见 §2.3 |
| `website` | `channel.website` |
| `description` | `channel.network`，为空则 `channel.name` |
| `country` | **中文国名**，由现成的 `StationRegion.ISO2_CN` 从 `code` 映射；表里没有的码回落 `countries.json` 的英文 `name`。**不新建第二张国家名表**（项目原则：同一件事不留两个真相来源） |
| `tags` | `channel.categories` 原样 |
| `nsfw` | `channel.is_nsfw` |

**不做的过滤**：国家、语言、是否官方（V1）。**做的过滤**：url 为空、url 非 http(s)、同频道内 url 重复。

### 2.2 direct 记录（2,097 条）的取名规则

按 host 归并**不可取**：实测 `183.207.248.71` 一个 host 下 26 条流的尾段是 `donghuawg`/`xinjiangstv`/`G_NJSB`，是 26 个不同频道，归并等于丢信息。改用**分层取名**：

1. 取 URL path 从末尾往前**第一个有信息量的段**：含字母、且不在 junk 名单
   `index.m3u8, playlist.m3u8, master, manifest, live, hls, stream, chunklist*, 纯数字, 空`；
2. 去掉 `.m3u8` / `.mpd` / `.index` 后缀；
3. 所有段都是 junk → 用 host；
4. **同名冲突时追加短 host**（如 `master (ottera.tv)`），保证列表每行可区分；
5. `id` = `iptv:direct:<host>:<url 的 sha1 前 8 位>`（与 name 解耦，改名不影响收藏）。

同步工具必须断言"最终 name 无重复"，否则报错退出。

### 2.3 为什么 `label` 必须留空、quality 单开字段（review C2）

`StationSource.label` 现在有三处用途，塞分辨率会直接污染用户可见文本：

```kotlin
// PlayerManager L396  → 状态文案会变成「正在尝试节目源 1080p…」
_status.value = "正在尝试节目源 ${source.label.ifEmpty { "${index+1}/$total" }}…"
// PlayerManager L403  → 通知栏「艺术家」一栏显示 1080p
.setArtist(source.label.ifEmpty { station.frequency.ifEmpty { station.description } })
// PlayerScreen L557   → 源名，空时回落「源 N」（这是我们要的）
```

所以：`StationSource` 新增 `quality` 字段承载分辨率，`label` 留空 → UI 自动显示「源 1 / 源 2」，通知栏 artist 回落 `station.description`。

---

## 3. 构建期同步工具

`tools/iptv_sync.ps1`，产物两个：

1. `app/src/main/assets/iptv_channels.json`（入库）
2. `doc/iptv_nonofficial_hosts.md`（审计线索，入库）

四条硬规矩：

- **代理**：`curl -x http://127.0.0.1:7897`，与 git 推送同一套一次性代理约定；**不修改任何全局配置**。
- **产出前校验，不合格就拒绝写入**：四个 json 必须解析成功；输入下限取**基线的 80%**（`channels ≥ 25000`、`streams ≥ 14500`），最终记录数 `≥ 10000`。任一不满足 → 报错退出。理由：写死今日值会让上游正常波动就把构建卡死，而"卡死"会被误读成网络问题。
- **name 唯一性断言**：§2.2 第 4 步消歧后仍有重名 → 报错，不产出。
- **打印最终条数与体积**并写进提交信息，否则"清单变了多少"只能靠人猜。

分类映射（§4.2）必须覆盖 `categories.json` 全部 id；遇到未知 id 写入「其他」并在输出里告警，不默默吞掉。

工具**不接进 Gradle 构建**：清单更新 = 人工跑一次 + 提交 + 发版。理由：构建期网络依赖会破坏项目的离线构建约束。

---

## 4. 数据模型与分类

### 4.1 模型改动（全部带默认值，现有数据零迁移）

```kotlin
@Serializable
data class StationSource(
    val url: String,
    val label: String = "",
    val bitrate: Int = 0,
    val quality: String = "",     // 新增：分辨率走这里，不借用 label（§2.3）
    val userAgent: String = "",   // 新增
    val referrer: String = "",    // 新增
)

@Serializable
data class RadioStation(
    ...,
    val altNames: List<String> = emptyList(),   // 新增（V12）
    val nsfw: Boolean = false,                  // 新增（V2）
)
```

`nsfw` 用字段而不是从 `tags` 派生，理由：它要参与 §5.2 的默认过滤，每次从 tags 判会把"成人"语义散在两处（分类 id `xxx` 与标记各算一份），而字段是单一真相。派生只做**补充**：`isAdult = nsfw || tags.contains("xxx")`，两者取并集，封装成一个纯函数。

三个新字段全部有默认值 → `radio_stations.json` 与用户自建电台的既有 JSON 无需迁移即可解析，由 §10.1 用仓库真实文件钉住。

### 4.2 分类：新建 `IptvCategory`，不动 `StationCategory`

30 个条目**逐字对照 §1 的实测 id 清单**（初版我漏了 `family`、还把 `shop` 编成 `FINANCE_LIKE`）：

```kotlin
enum class IptvCategory(val key: String, val label: String) {
    AUTO("auto", "汽车"), ANIMATION("animation", "动画"), BUSINESS("business", "商业"),
    CLASSIC("classic", "经典"), COMEDY("comedy", "喜剧"), COOKING("cooking", "美食"),
    CULTURE("culture", "人文"), DOCUMENTARY("documentary", "纪录"), EDUCATION("education", "教育"),
    ENTERTAINMENT("entertainment", "娱乐"), FAMILY("family", "家庭"), GENERAL("general", "综合"),
    INTERACTIVE("interactive", "互动"), KIDS("kids", "少儿"), LEGISLATIVE("legislative", "议会"),
    LIFESTYLE("lifestyle", "生活"), MOVIES("movies", "电影"), MUSIC("music", "音乐"),
    NEWS("news", "新闻"), OUTDOOR("outdoor", "户外"), PUBLIC("public", "公共"),
    RELAX("relax", "休闲"), RELIGIOUS("religious", "宗教"), SCIENCE("science", "科学"),
    SERIES("series", "剧集"), SHOP("shop", "购物"), SPORTS("sports", "体育"),
    TRAVEL("travel", "旅行"), WEATHER("weather", "天气"), ADULT(IptvAdult.adultCategoryKey, "成人");
        // ADULT 的 key 不复写字面量：常量只能有一个出处。本行早期写的是 ADULT("xxx", ...)，
        // 那会让下一个照 spec 执行的人把已修好的单一出处改回去（Task 1 审查 I-2 修的就是这个）。

    companion object {
        /** 未知 key 回落「其他」，不抛异常：上游随时可能加新分类 */
        fun labelOf(key: String): String
    }
}
```

不动 `StationCategory` 的理由：它的 `ordered()` 是管理页与添加电台下拉的取值来源，加十几个枚举会让那些页面长出与"电台"语义不符的格子（V8）。

---

## 5. 解析与查找

### 5.1 独立仓库 + `getStationById` 回落

新增 `data/repository/IptvStationRepository`：

```kotlin
@Singleton
class IptvStationRepository @Inject constructor(@ApplicationContext context: Context) {
    suspend fun all(): List<RadioStation>                 // 懒加载 5.25MB / 12,435 条
    suspend fun byId(id: String): RadioStation?
    fun isIptvId(id: String): Boolean = id.startsWith("iptv:")
    suspend fun headersFor(url: String): StreamHeaders?   // 只含非空 header
}
```

`StationRepository.getStationById(id)` 加一条回落：

```kotlin
suspend fun getStationById(id: String): RadioStation? =
    getAllStations().find { it.id == id }
        ?: if (id.startsWith("iptv:")) iptvRepository.byId(id) else null
```

**回落到底服务谁（review C1 纠正）**：我初版写"播放器靠 `getStationById` 解析台" —— 那是错的，`PlayerManager.playStation(station: RadioStation)` 收的是**对象**。真正按 id 解析、且**失败静默**的是这里：

```kotlin
// PlayerViewModel L122-131
fun loadStation(stationId: String) {
    val station = stationRepository.getStationById(stationId)
    if (station != null) { playerManager.playStation(station) }   // null 就什么都不做
}
```

所以回落服务的场景是路由 `player/{stationId}`：通知栏点回、媒体键恢复、进程死亡后重建、上下台跳转。不加的症状是"播放页停在旧台或空白，无任何报错"，编译期与单测都抓不到。

**同时明确不做的合并**：`getAllStations()` / `getActiveStations()` / `searchStations()` 保持只返回电台。否则 451 条变 12,885 条，首页列表、分类网格、地区页、EPG、"检测全部"全被拖下水，而 V5/V9 定的恰恰是"iptv 自成一页、懒加载"。

### 5.2 NSFW 过滤

`all()` 返回未过滤全集；过滤在 ViewModel：`isAdult = nsfw || tags.contains("xxx")`（纯函数），开关关时剔除。设置项 `iptv_adult_enabled`，默认 `false`，落现有 `RadioPreferences`。

---

## 6. 播放链路：HTTP header 注入

iptv-org 每条流可带 `user_agent` / `referrer`。现状：`RadioService` L121 建 `DefaultHttpDataSource.Factory()` 时既没 `setUserAgent` 也没有 per-item header。

**API 约束（review 纠正）**：media3 的 `MediaItem` 不支持携带 per-item header，且 `DataSource.Factory.createDataSource()` **拿不到 URI**（URI 到 `open(DataSpec)` 才知道）。我初版写的"委托工厂 + 创建时按 uri 查表"照的是不存在的 API，已推翻。

可行做法是**复用项目已有的同一个模式** —— `RadioService` 现在就是在工厂 lambda 里读 WebDAV 单例的当前凭证（"动态 Auth：仅在 WebDAV 凭据已设置时添加"）：

```kotlin
// domain/model/StreamHeaders.kt（纯 Kotlin，可 JVM 测）
data class StreamHeaders(val userAgent: String, val referrer: String)

// service/StreamHeaderStore.kt
@Singleton class StreamHeaderStore { @Volatile var current: StreamHeaders? = null }

// RadioService：工厂 lambda 内，与现有动态 Auth 同一位置
val h = streamHeaderStore.current
if (h != null) { /* 见下方"必须实测" */ }
```

**必须实测的一件事（review I3）**：`setUserAgent()` 与 `setDefaultRequestHeaders()` 的覆盖关系我没有验证过 —— User-Agent 本身就是一个 header，两个 API 谁后写谁赢、还是互相清掉，静态读代码判不了。落地方式：工厂里设完之后打一行诊断日志（`iptv-headers ua=<非空?> ref=<非空?>`），真机 T6 读 logcat 断言；若互相覆盖，改为只用 `setDefaultRequestHeaders` 一次写全（含 `User-Agent` 项）。**不允许把没验证的 API 行为写进代码注释当事实。**

其余要点：

- `current == null` 时行为与现在**逐字一致**（纯音频台零回退红线）。
- header 由 `PlayerManager` 在起播前按当前源 url 查出并写入；**非 iptv 台一律写 null，且 stop 时清空**。
- 已知风险：`StreamHeaderStore` 是全局可变单例，与项目踩过的"WebDAV 单例凭证跨源串写"同一类。边界：单播放器、起播串行、写入紧贴 `setMediaItem`，故不并发。将来引入画中画/第二路播放时必须改成随 MediaItem 传递。
- UA 字符串来自 iptv-org，**不得写进代码常量**，只存在于生成的 assets json。

---

## 7. UI

### 7.1 一级 tab

```kotlin
data object Iptv : Screen("iptv")                       // Screen.kt

BottomNavItem(Screen.Radio,   "电台",   …),
BottomNavItem(Screen.Iptv,    "iptv",   Icons.Default.Tv / Icons.Outlined.Tv),
BottomNavItem(Screen.Audio,   "音频库", …),
BottomNavItem(Screen.Settings,"设置",   …),
```

`showBottomBar` 的路由白名单必须同步加 `Screen.Iptv.route` —— 漏掉的症状是"进了 iptv 页底部栏消失，出不来"。图标用 `Tv`（本项目 `CategoryIcons` 已在用，确定存在），不用没核实过的 `LiveTv`。

### 7.2 iptv 页内部（V5）

顶部搜索框 + 四宫格入口（分类 / 国家 / 最近 / 收藏），**不平铺全量列表**。

**分类入口盖不到全部记录（Task 3 审查 I-2，实测）**：清单里有 **3,557 条记录 tags 为空**
（含全部 2,096 条 direct 记录与 1,461 条无分类频道）。按 tags 聚合的分类入口永远到不了它们，
它们只能靠搜索 / 国家 / 最近 / 收藏到达。这是数据形状决定的，不是 bug；
但**不得把分类入口当成“能逛完整个库”的导航**，也不得为了补这个洞而发明一个
有 3,557 条的「其他」格子（那会把入口区顶满，真正有内容的分类反而看不到）。
另：分组必须**先翻译成中文标签再聚合**，否则上游新增两个未抄录分类时两个格子都叫「其他」，
列表按标签做 key 会直接抛 "Key was already used" 闪退。

**搜索必须复用 `StationFilter`（review I1）**：`HomeViewModel` L99 用的就是它，而 `CategoryScreen` 里留着一条注释明令「筛选规则不在这页里重写一遍，否则两页搜出不同结果」。做法是给 `StationFilter` 增加对 `altNames` 的匹配（V12），iptv 页调 `StationFilter.apply(iptvVisible, category=null, query)`。初版我写的"内存过滤 name/alt_names/country"是自造第二套规则，且声称匹配一个 §2 里根本没入库的字段。

列表复用 `StationListItem` + `rememberStationRowOnClick`（行点击语义与现有页一致是项目红线）。iptv 行不传 `nowProgram` / `hasEpg`（EPG 非目标，默认值即可，不挂"正在播出"假标记）。车机档 `LazyVerticalGrid(GridCells.Adaptive(300.dp))`。

### 7.3 专用播放页 `IptvPlayerScreen`（V6）

- 复用 `VideoStageFrame` + `rememberVideoFullScreen(canRotate = isCarSurface().not())`：全屏、旋转按钮、控制层常驻、方向复位全部白拿，不复制实现。
- **必须显式接视频闸门**（初版漏了，症状是"iptv 永远只有声音没画面"）：
  `LaunchedEffect(hasVideoTrack) { playerManager.setVideoPictureWanted(hasVideoTrack) }` +
  `DisposableEffect(Unit) { onDispose { setVideoPictureWanted(false) } }`。闸门默认关着，不接这句解码器不会开视频轨。
- 上下台（V11）：**跟当前页走**。做法是把"当前可见集合"作为切台基准的唯一来源 —— 电台页传电台列表，iptv 页传 iptv 列表，`PlayerManager` 的 `pendingSkipTargetId` 与方控滚轮回调都读这一个集合，不再各自去 `StationRepository` 取全量电台。若现有实现把基准列表硬编码在 `PlayerManager` 内部，本条就是必须改的第一件事（实现计划里单列任务，先核实再动）。
- 新增：频道号（列表序号）、多源列表与手动切源、quality 展示（走新 `quality` 字段，不借 label）。
- 车机档：画面优先，控件**保持单行**（项目红线「播放页控件必须保持单行不可拆多行」）。
- 播放状态一律读 `PlayerManager`，与 `OperaPlayerScreen` 同构（无参数路由）。

### 7.4 设置页

新增「显示成人频道（iptv）」，默认关。打开后列表与搜索才含成人条目。

---

## 8. 收藏 / 最近 / 可用性的接线（review C1 整段重写）

**我初版说"显示名靠 §5.1 的 `getStationById` 回落解决" —— 错的。** HomeViewModel 不经过那个函数：

```kotlin
// HomeViewModel L122-124：最近播放从 _stations 里找，找不到就 mapNotNull 静默丢掉
val recentStations = combine(_recentIds, _stations) { ids, all ->
    ids.mapNotNull { id -> all.find { it.id == id } }
}
// L99：收藏/列表同样只吃 _stations（电台）
```

后果：用户收藏 iptv 台后，**电台收藏页与最近页里那一条根本不存在**（不是空白行，是消失），无报错、编译期与单测都抓不到。

正确的接线：

| 现有能力 | iptv 的处理 |
|---|---|
| 收藏（`FavoriteRepository` 存 id 集合） | 集合本身共用，**但显示由各自页面解析**：iptv 页用 `IptvStationRepository.all()` ∩ `favoriteIds` 且带 `iptv:` 前缀；电台页继续用 `_stations` ∩ `favoriteIds` |
| 最近（`preferences.radioRecentStationIds`） | 同上，iptv 页自己按前缀过滤解析 |
| 双向隔离 | **天然成立且不需要额外代码**：电台页的 `_stations` 里没有 iptv 条目，iptv 页的 `all()` 里没有电台条目。但必须由测试钉住，否则将来谁把两个来源合并成一份列表，隔离就无声失效 |
| 节目源可用性打分（`SourceAvailabilityStore`） | 直接可用（按 stationId + url 存） |
| 「检测全部」 | **不纳入**（V10） |
| EPG | **不接入**（§11） |

---

## 9. 错误处理与降级

| 情形 | 处理 |
|---|---|
| `assets/iptv_channels.json` 缺失或解析失败 | iptv tab 显示空态 + 说明，**不崩**；`getStationById` 回落返回 null，播放页停在当前台 |
| 单条流 403 / 超时 | 既有多源回退切下一条；全失败提示「所有节目源均无法播放」 |
| 频道有 `replaced_by` | 照常收录（V1），`description` 追加「上游标记已被 X 替换」 |
| 12,435 条首次加载 | 懒加载 + `Dispatchers.IO`，加载期显示 Loading，不阻塞其它 tab |
| 内存占用 | **未验证**。粗估对象图 10–15 MB，需在真机（尤其车机低内存机型）实测；超限时改按分类分片加载。不得在实现注释里写成已确认数字 |

---

## 10. 测试与验收

### 10.1 JVM 单测

| 用例 | 断言 |
|---|---|
| 真实 assets 解析闸门 | 读仓库 `iptv_channels.json`：记录数 ≥ 10000、每条有 `id`/`name`/至少 1 个 source、id 全以 `iptv:` 开头且**全库唯一**、**name 全库唯一**（§2.2 第 4 步） |
| 现有数据零迁移 | 读真实 `radio_stations.json` 解析成功，`quality`/`userAgent`/`referrer`/`altNames`/`nsfw` 均取默认值 |
| label 不被污染 | 生成的每条 `StationSource.label` 为空，`quality` 承载分辨率（§2.3） |
| direct 取名分层 | `…/donghuawg/donghuawg` → `donghuawg`；`…/index.m3u8` 且上级为 `gusa-racecentral` → 取上级；全 junk → host；重名 → 带 host 后缀 |
| junk 段名单 | `master`/`manifest`/`live`/`hls`/`stream`/纯数字 不作为名字 |
| 国家中文名 | `CN→中国`、`US→美国`；表里没有的码回落英文名且**不是空字串**；断言**未新建第二张国家表**（复用 `StationRegion.ISO2_CN`） |
| `IptvCategory` | 枚举 key 覆盖 `categories.json` 全部 30 个 id、未知 key 回落「其他」、`xxx` 判为成人 |
| 成人判定并集 | `nsfw=true` 或 `tags` 含 `xxx` 都算成人；开关关时两者都被剔除 |
| `StationFilter` 认别名 | 用 `altNames` 里的写法能搜到，且**电台页与 iptv 页对同一 query 走同一套规则**（防两套真相） |
| `getStationById` 回落 | `iptv:` 前缀查得到；非前缀 id **不会**去查 iptv 仓库（避免每次电台查找都过一遍 12k 条） |
| `loadStation` 不静默失败 | 给 `PlayerViewModel.loadStation("iptv:…")` 一条测试：解析失败时必须走 error 分支，不允许无声返回 |
| 收藏/最近双向隔离 | iptv 收藏在 iptv 页可见、在电台收藏页不可见；反向同理 |
| header 查表 | 有 header 的流命中；无 header 的流**不产生任何 header**（零回退守卫） |
| 上下台基准 | 从 iptv 页起播后，切台基准集合是 iptv 列表；回电台页后恢复电台列表 |

### 10.2 真机验收（新增 `tools/iptv_acceptance.ps1`）

| # | 判据 |
|---|---|
| T1 | 底部导航 4 项且顺序为 电台 / iptv / 音频库 / 设置；进 iptv 后底部栏仍在 |
| T2 | iptv 首屏是搜索框 + 四入口，没有平铺万条列表 |
| T3 | 分类入口可进，条数与 json 实际计数一致（抽 3 类核对） |
| T4 | 国家入口 CN 显示「中国」不是「CN」 |
| T5 | 搜索命中，含一条只用 `alt_names` 写法才能搜到的台；含一条 direct 记录按域名/路径名搜到的台 |
| T6 | 播一条带 `user_agent` 的流：出声/出画，且 `logcat` 里那行 header 诊断显示 ua/ref 都非空（§6 的覆盖关系以这条为准） |
| T7 | 全屏按钮存在并可进全屏（复用上一批已验判据） |
| T8 | 收藏一个 iptv 台 → iptv 收藏页可见；电台收藏页**不含它** |
| T9 | NSFW 默认关：搜成人关键字无结果；打开后有结果 |
| T10 | 车机几何（1280x720@160）下列表与播放页布局正常、控件单行 |
| T11 | 纯音频电台回归：现有 V1/V6 判据仍 PASS（新字段与 header 通路不得影响老路径） |
| T12 | 车机方控滚轮/媒体键：在 iptv 播放页切到的是 iptv 相邻台，在电台页切到的是电台相邻台（V11） |
| T13 | 通知栏/媒体键恢复：从 iptv 播放页退出后再点通知栏回到同一个台（验证 §5.1 回落真的服务这条路径） |
| T14 | R8 混淆包复跑 T1–T8、T11–T13 |
| 记录 | 实测 APK 体积与首次进入 iptv 页的加载耗时，写进台账 |

---

## 11. 非目标

- EPG / 节目单关联（iptv-org 的 epg 仓库与频道 id 关联不可靠）
- 运行时更新清单、远端索引镜像（V3 定为构建期；将来做走自有服务器，沿用"凭证零分发"路线）
- 全量健康检测（12k 条）
- 国家/语言过滤（V1 明确不做）
- 拼音搜索索引（12k 条建索引是另一件事）
- 时移/回看（`feed` 字段本期不入库）
- 本地视频文件（C 期，用户已明确"本地过滤不用改"）

---

## 12. 交付

- 文档：本文件；实现计划落 `doc/plans/2026-11-06-iptv-tab-plan.md`
- 产物：`assets/iptv_channels.json`、`doc/iptv_nonofficial_hosts.md`、`tools/iptv_sync.ps1`、`tools/iptv_acceptance.ps1`
- 包体：实测压缩后 850 KB，APK 6.14 → 预计约 6.97 MB；验收时实测记录真实值
- Git：spec 单独提交；实现按任务分层提交；**每个任务完成后立即派 code review**（用户既定流程）；不 push、不 merge 除非明确要求

---

## 13. 自审记录（本轮 review 改了什么）

| 编号 | 我原来写的 | 实际 | 处理 |
|---|---|---|---|
| **C1** | "播放器靠 `getStationById` 解析，回落否则点了没反应"；"收藏显示名靠回落解决" | `playStation` 收对象不查 id；真正查 id 且 null 静默的是 `PlayerViewModel.loadStation`；收藏/最近走 `HomeViewModel._stations` + `mapNotNull`，**回落救不了它们** | §5.1 理由改写、§8 整段重写，并新增 T13 与"loadStation 不静默失败"测试 |
| **C2** | `sources[].label = quality` | `label` 会进状态文案「正在尝试节目源 X…」与通知栏 artist | 新增 `StationSource.quality` 字段，label 留空；§2.3 记录三处证据 |
| I1 | iptv 页自造内存过滤，且声称匹配 `alt_names` | `StationFilter` 是全项目共用入口，CategoryScreen 有"不许重写"注释；而 `alt_names` 我根本没入库 | 改为扩展 `StationFilter` 认 `altNames`（V12），并加"两页同一套规则"测试 |
| I2 | direct 记录名取 host | 同一 host 下 26 条是 26 个不同频道，归并等于丢信息；而另一批尾段全是 `playlist.m3u8` | 改为分层取名 + junk 名单 + 同名追加 host，工具断言无重名（实测 2,097 条 → 1,660 种） |
| I3 | `setUserAgent()` 与 `setDefaultRequestHeaders()` 一起用 | 覆盖关系未验证（UA 本身就是 header） | §6 改为"必须实测"，由 T6 的 header 诊断日志定论，不许写进注释当事实 |
| I4 | 未定义上下台语义 | 现有 `pendingSkipTargetId`/滚轮基准是电台列表 | V11 定为跟当前页走，基准集合单一来源；T12 验收 |
| 其它 | 入库 10,338 条 / direct 1,941 / json 4.44MB / `IptvCategory` 漏 family / `category="tv"` / `HeaderAwareDataSourceFactory` / `LiveTv` 图标 / `headersTable()` 残留 / 阈值写死今日值 / 内存当结论 | 逐条实测或核实后纠正 | §1、§2、§4.2、§6、§3、§9 对应改写 |
