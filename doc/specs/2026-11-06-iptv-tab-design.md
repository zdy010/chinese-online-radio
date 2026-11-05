# iptv Tab（iptv-org 公开直播流）· 设计文档

- 日期：2026-11-06
- 需求：新增一个名为 `iptv` 的一级 tab，位置在「电台」与「音频库」之间，收录 https://github.com/iptv-org/iptv 的公开直播流
- 状态：待用户审阅 → 通过后调 writing-plans
- 本文所有数字与字段名都是**实测所得**（探针脚本在 `.superpowers/tmp/iptv-*.ps1`，git-ignored），不是文档转述

---

## 0. 决定台账（用户已定，逐条可追溯）

| # | 议题 | 决定 |
|---|---|---|
| V1 | 收录范围 | **全收，不按国家与语言过滤** |
| V2 | 成人内容 | **收录但默认隐藏**，设置页给开关 |
| V3 | 清单送达方式 | **构建期工具拉取生成 assets json**（运行时不依赖 GitHub 可达） |
| V4 | 数据模型 | **复用 `RadioStation`** + 独立数据文件 + id 前缀 `iptv:` |
| V5 | tab 内部形态 | **搜索优先 + 分类/国家/最近/收藏入口**，不平铺全量 |
| V6 | 播放页 | **新建专用 `IptvPlayerScreen`**（频道号、上下台、全屏优先） |
| V7 | 无频道归属的流 | **收，用域名当名字** |
| V8 | 分类建模 | **新建 `IptvCategory`**，不动 `StationCategory` |
| V9 | 加载策略 | **懒加载内存单例**，首页/电台页完全不碰 |
| V10 | 失效处理 | **不做全量体检**，靠既有多源回退 + 单台检测 |

### ⚠️ V1 覆盖了项目现行版权红线（必须留档）

项目红线是「电台音频源仅收录官方公开流」（成人内容当初就是按这条整体下线的）。iptv-org 的**政策**确实写着只收录电视台自己网站上公开可看的流，但实测清单里混着**大量裸 IP 与境外机房转播**，这类不属于官方公开流：

```
26  183.207.248.71     ← 裸 IP
17  39.134.115.163     ← 裸 IP
14  74.91.26.218       ← 裸 IP，境外机房
12  38.75.136.137      ← 裸 IP，境外机房
11  112.25.48.68       ← 裸 IP
 6  news.cgtn.com / livealone302.iqilu.com / play-live-hls.jxtvcn.com.cn / video10.qtv.com.cn  ← 官方 ✅
```

本设计按用户决定执行全收。为保留追溯能力，同步工具**额外产出一份 `doc/iptv_nonofficial_hosts.md`**，列出所有"流主机名与该台官方站点不同主域、或该台无 website"的条目与条数。这份清单不是产品功能，是审计线索。

---

## 1. 数据源实测事实

| 事实 | 实测值 |
|---|---|
| 全库频道 | 31,744 |
| 全库流 | 18,458 |
| **入库记录总数** | **12,434** = 10,338 个有流频道 + 2,096 条无频道归属（或指向无元数据频道 id）的 direct 记录 |
| 无频道元数据的流 | **2,096 条**（V7 决定收，用域名命名）。注意不是 1,941：差的 155 条是 `channel` 字段有值但 channels.json 里查不到该 id |
| `is_nsfw=true` 频道 | 374（V2 决定收录但默认隐藏） |
| `replaced_by` 非空 | 639（被上游标记为已替换/下线） |
| 生成的 json 体积 | **5.25 MB 未压缩 → 850 KB 压缩后（15.8%）**；APK 6.14 MB → 预计约 **6.97 MB** |
| iptv 分类码 | **30 个**，实际 id：`auto, animation, business, classic, comedy, cooking, culture, documentary, education, entertainment, family, general, interactive, kids, legislative, lifestyle, movies, music, news, outdoor, public, relax, religious, science, series, shop, sports, travel, weather, xxx`（无空 id） |
| iptv 国家码 | 250 个 |

### 真实字段名（探针核实，不是猜）

```jsonc
// channels.json 元素
{ "id":"CCTV1.cn", "name":"CCTV 1", "alt_names":[], "network":null, "owners":[],
  "country":"CN", "categories":["general"], "is_nsfw":false,
  "launched":null, "closed":null, "replaced_by":null, "website":"https://..." }

// streams.json 元素
{ "channel":"CCTV1.cn", "feed":null, "title":"CCTV 1 (1080p)",
  "url":"https://...", "quality":"1080p", "labels":[],
  "user_agent":null, "referrer":null }        // ← 这两个字段决定能不能播出来，见 §6

// countries.json 元素：字段是 name / code / languages / flag —— 不是 id
{ "name":"Afghanistan", "code":"AF", "languages":["pus"], "flag":"🇦🇫" }

// categories.json 元素：id / name / description
```

`channels[].categories` 是**分类 id 数组**（小写，如 `["news","business"]`），与 `categories.json` 的 `id` 同源。

---

## 2. 收录与归一化规则

一条记录 = 一个频道 + 它的全部流（多源）。

| 字段 | 取值规则 |
|---|---|
| `id` | `"iptv:" + channel.id`（如 `iptv:CCTV1.cn`）；无归属流走 `iptv:direct:<host>:<url的sha1前8位>` |
| `name` | `channel.name`；无频道元数据时取流 URL 的 host 去掉 `www.` 并把点换成空格（V7） |
| `category` | 固定 `"general"`。**不用 `"tv"`**：`StationCategory.labelOf()` 对未知 key 会原样回显，填 `tv` 会让收藏页/管理页直接显示内部 key。iptv 自己的分类走 `tags` + `IptvCategory`（§4.2） |
| `streamUrl` | 第一条流的 url（仅作 `allSources()` 兜底） |
| `sources[]` | 该频道全部流：`url` + `label`=quality + `userAgent` + `referrer` |
| `website` | `channel.website` |
| `description` | `channel.network`，为空则 `channel.name` |
| `country` | **中文国名**，由 `StationRegion.ISO2_CN` 从 `code` 映射；表里没有的码回落 `countries.json` 的英文 `name` |
| `tags` | `channel.categories` 原样（分类 id） |
| `nsfw` | `channel.is_nsfw`（新增字段，见 §4） |

**不做的过滤**：国家、语言、是否官方（V1）。**做的过滤**：`url` 为空、`url` 非 http(s)、同一频道内 url 重复。

---

## 3. 构建期同步工具

新增 `tools/iptv_sync.ps1`，产物两个：

1. `app/src/main/assets/iptv_channels.json`（入库，实测基准 12,434 条 / 5.25 MB）
2. `doc/iptv_nonofficial_hosts.md`（审计线索，入库）

三条硬规矩（都是本项目踩过的坑）：

- **代理**：`curl -x http://127.0.0.1:7897`，与 git 推送同一套一次性代理约定；**不修改任何全局配置**。
- **产出前校验，不合格就拒绝写入**：四个 json 必须解析成功、`channels ≥ 30000`、`streams ≥ 15000`、**最终记录数 ≥ 11000**（基准 12,434，留一成余量容忍上游浮动）。任一不满足 → 报错退出，**绝不留下半成品或旧文件冒充新清单**。
- **失败即失败**：任何一步网络错误都 throw 退出，不允许“拉不到就用上次生成的”却对外说是最新的。

工具还要**打印最终条数与体积**并写进提交信息，否则“清单变了多少”只能靠人猜。分类映射表（§4.2）必须能覆盖 `categories.json` 里出现的**全部** id；工具遇到未知 id 时写入 `其他` 并在输出里告警，不默默吞掉。

工具不参与 Gradle 构建（不接进 `preBuild`）：清单更新 = 人工跑一次 + 提交 + 发版。理由：构建期网络依赖会让离线构建与 CI 不稳定，而项目已有离线构建约束。

---

## 4. 数据模型与分类

### 4.1 模型改动（最小面）

```kotlin
// Models.kt
@Serializable
data class StationSource(
    val url: String,
    val label: String = "",
    val bitrate: Int = 0,
    val userAgent: String = "",   // 新增，默认空 → 现有 radio_stations.json 一字不动
    val referrer: String = "",    // 新增，同上
)

@Serializable
data class RadioStation(
    ...,
    val nsfw: Boolean = false,    // 新增，默认 false → 现有数据不受影响
)
```

三个新字段全部带默认值，`radio_stations.json` 与用户自建电台的既有 JSON 无需迁移即可解析。这一条由单测钉住（用仓库里真实的 `radio_stations.json` 跑一遍解析）。

### 4.2 分类：新建 `IptvCategory`，不动 `StationCategory`

`domain/model/IptvCategory.kt`：30 个 iptv 分类 id → 中文 label。

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
    TRAVEL("travel", "旅行"), WEATHER("weather", "天气"), ADULT("xxx", "成人");

    companion object {
        /** 未知 key 回落「其他」，不抛异常：上游随时可能加新分类 */
        fun labelOf(key: String): String = ... 
    }
}
```

上面这 30 个条目是**逐字对照 §1 的实测 id 清单写的**（初版我漏了 `family`、还把 `shop` 编成了 `FINANCE_LIKE`），实现时由 §10.1 的覆盖测试钉住：枚举 key 集合必须包含 `categories.json` 里出现的全部 id。

理由：`StationCategory.ordered()` 是管理页与添加电台下拉的取值来源，加十几个枚举会让那些页面长出与"电台"语义不符的格子（V8）。iptv 的分类入口只服务 iptv tab。

`xxx` 分类与 `is_nsfw` 是两回事：前者是 iptv 给的分类，后者是标记。两者都算"成人"，过滤时取**并集**。

---

## 5. 解析与查找（本设计最容易做错的一处）

### 5.1 独立仓库 + 单点回退

新增 `data/repository/IptvStationRepository`：

```kotlin
@Singleton
class IptvStationRepository @Inject constructor(@ApplicationContext context: Context) {
    /** 懒加载：只有第一次访问才解析 5.25MB（12,434 条）；后续复用内存单例 */
    suspend fun all(): List<RadioStation>
    suspend fun byId(id: String): RadioStation?          // 内部走 all()
    fun isIptvId(id: String): Boolean = id.startsWith("iptv:")
    suspend fun headersTable(): Map<String, StreamHeaders>  // url → (userAgent, referrer)
}
```

**关键决定**：`StationRepository.getStationById(id)` 增加一条回落 —— 当 `id` 以 `iptv:` 开头且在本仓库查不到时，转查 `IptvStationRepository`。

```kotlin
suspend fun getStationById(id: String): RadioStation? =
    getAllStations().find { it.id == id }
        ?: if (id.startsWith("iptv:")) iptvRepository.byId(id) else null
```

为什么必须这样做（而不是"iptv 用自己的仓库、各调用点自己判"）：播放器、收藏、最近、节目源可用性打分这些路径**全都靠 `getStationById` 解析台**。若它返回 null，症状是"点了 iptv 频道没反应"或"收藏了却打不开"，而不是编译错误 —— 属于最容易漏到真机才暴露的那类。

同时明确**不做的合并**：`getAllStations()` / `getActiveStations()` / `searchStations()` **保持只返回电台**，不掺 iptv。否则 451 条变 10,789 条，首页列表、分类网格、地区页、EPG、"检测全部"全都要跟着重算，而 V5/V9 定的恰恰是"iptv 自成一页、懒加载"。

### 5.2 NSFW 过滤

`IptvStationRepository.all()` 返回**未过滤**全集；过滤发生在 ViewModel：

```kotlin
val visible = if (settings.iptvAdultEnabled) all else all.filter { !it.nsfw && !it.hasAdultTag }
```

设置项 `iptv_adult_enabled`，默认 `false`，落在现有 `RadioPreferences`。

---

## 6. 播放链路：HTTP header 注入（决定"能不能播出来"）

iptv-org 的每条流可带 `user_agent` 与 `referrer`。现状：

```kotlin
// RadioService.kt  L121
val httpFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
// 没有 setUserAgent，也没有 per-item header
```

而 media3 的 `MediaItem` **不支持携带 per-item HTTP header**，且 `DataSource.Factory.createDataSource()` **拿不到 URI**（URI 要到 `open(DataSpec)` 才知道）。所以“在工厂创建时按 uri 查表”这条路不存在 —— 本节初版就是照这个不存在的 API 写的，已推翻。

可行的做法是**复用项目已有的同一个模式**：`RadioService` 现在就是在工厂 lambda 里读 WebDAV 单例的当前凭证（“动态 Auth：仅在 WebDAV 凭据已设置时添加”）。header 同理：

```kotlin
// domain/model/StreamHeaders.kt（纯 Kotlin，可 JVM 测）
data class StreamHeaders(val userAgent: String, val referrer: String) {
    fun isEmpty() = userAgent.isBlank() && referrer.isBlank()
}

// service/StreamHeaderStore.kt
@Singleton
class StreamHeaderStore @Inject constructor() {
    @Volatile var current: StreamHeaders? = null   // PlayerManager 起播前写入，stop 时清空
}

// RadioService：工厂 lambda 内（与现有 WebDAV 动态 Auth 同一位置）
val h = streamHeaderStore.current
if (h != null && !h.isEmpty()) {
    httpFactory.setUserAgent(h.userAgent)
    httpFactory.setDefaultRequestHeaders(buildMap {
        if (h.referrer.isNotBlank()) put("Referer", h.referrer)
    })
}
```

要点：

- `current == null` 时**行为与现在逐字一致**（纯音频台零回退红线）。
- header 由 `PlayerManager` 在起播前从 `IptvStationRepository` 按当前源 url 查出并写入；非 iptv 台一律写 null。
- **已知风险（必须写清）**：`StreamHeaderStore` 是全局可变单例，与项目已经踩过的“WebDAV 单例凭证跳源串写”同一类。边界：本应用是单播放器且起播串行，写入紧贴在 `setMediaItem` 之前、`stop` 时清空，因此不并发。代价：将来引入画中画/第二路播放时，这里必须改成随 MediaItem 传递（用 `MetadataEntry` 携带）。
- UA 字符串来自 iptv-org，**不得写进代码常量**，只存在于生成的 assets json 里。
- 查表逻辑（url → headers，只含非空）是纯函数，进 JVM 单测。

风险与降级：若某条流的 header 在 json 里为 null 但实际要求 header，表现为播放失败 → 走既有多源回退 → 全失败提示「所有节目源均无法播放」。这是可接受的既有行为，不新增错误路径。

---

## 7. UI

### 7.1 一级 tab

```kotlin
// Screen.kt
data object Iptv : Screen("iptv")

// MainScreen.kt 底部导航顺序（插在电台与音频库之间）
BottomNavItem(Screen.Radio,   "电台",   …),
BottomNavItem(Screen.Iptv,    "iptv",   Icons.Default.Tv / Icons.Outlined.Tv),
BottomNavItem(Screen.Audio,   "音频库", …),
BottomNavItem(Screen.Settings,"设置",   …),
```

`showBottomBar` 的路由白名单必须同步加上 `Screen.Iptv.route` —— 漏掉的症状是"进了 iptv 页底部栏消失，出不来"。

### 7.2 iptv 页内部（V5）

```
SearchBarRow（复用现有组件与占位样式）
入口四宫格：分类 / 国家 / 最近 / 收藏
分类 → IptvCategory 列表（带条数）
国家 → 按 country 分组列表（250 项，带条数）
```

不平铺全量列表。列表页复用 `StationListItem` + `rememberStationRowOnClick`（行点击语义与现有页一致是项目红线），车机档 `LazyVerticalGrid(GridCells.Adaptive(300.dp))`。

搜索：V1 不做国家/语言过滤，意味着搜索要能命中 10,338 条。搜索实现走 `IptvStationRepository.all()` 的内存过滤（name / alt_names / country），**不接现有拼音索引**（那套是给电台建的，10k 条建索引属另一件事，列非目标）。

### 7.3 专用播放页 `IptvPlayerScreen`（V6）

- 复用刚上线的 `VideoStageFrame` + `rememberVideoFullScreen(canRotate = isCarSurface().not())`，因此全屏、旋转按钮、控制层常驻、闸门全部白拿，不复制实现。
- **必须显式接视频闸门**（初版漏了这一句，症状是“iptv 永远只有声音没画面”）：与另外两个播放页同构，
  `LaunchedEffect(hasVideoTrack) { playerManager.setVideoPictureWanted(hasVideoTrack) }` +
  `DisposableEffect(Unit) { onDispose { setVideoPictureWanted(false) } }`。闸门默认关着，不接这句就解码器不会开视频轨。
- 新增：上下台按钮（在当前可见集合内导航）、频道号（列表序号）、多源列表与手动切源。
- 车机档：画面优先，控件单行（项目红线「播放页控件必须保持单行不可拆多行」）。
- 播放状态一律读 `PlayerManager`，与 `OperaPlayerScreen` 同构（无参数路由）。

### 7.4 设置页

新增一项「显示成人频道（iptv）」，默认关。文案不隐藏开关位置：打开后列表与搜索才含成人条目。

---

## 8. 收藏 / 最近 / 可用性的接线

| 现有能力 | iptv 接进来的方式 | 不接的后果 |
|---|---|---|
| 电台收藏（stationId 集合） | id 带 `iptv:` 前缀天然不冲突；显示名靠 §5.1 的 `getStationById` 回落 | 收藏了却看不见/打不开 |
| 最近播放 | 同上 | 最近列表里是空白行 |
| 节目源可用性打分（`SourceAvailabilityStore`） | 直接可用（按 stationId+url 存） | 多源回退退化成固定顺序 |
| 「检测全部」 | **不纳入**（10k 条跑不完，V10） | — |
| EPG | **不接入**（见 §11） | — |

iptv 页自己的「收藏 / 最近」入口读的是同一份集合，但按 `iptv:` 前缀过滤 —— 电台页的收藏列表同样按前缀**排除** iptv 条目，否则电台收藏页会混进一屏电视塔。这一条是双向的，必须两边都写。

---

## 9. 错误处理与降级

| 情形 | 处理 |
|---|---|
| `assets/iptv_channels.json` 缺失或解析失败 | iptv tab 显示空态 + 说明，**不崩**；`getStationById` 回落返回 null，播放入口不出现 |
| 单条流 403 / 超时 | 既有多源回退切下一条；全失败提示「所有节目源均无法播放」 |
| 频道有 `replaced_by` | 照常收录（V1 全收），但 `description` 追加「上游标记已被 X 替换」，便于用户判断 |
| 10,338 条首次加载 | 懒加载 + `Dispatchers.IO`；加载期显示 Loading 态，不阻塞其它 tab |
| 内存 | 单例常驻一份 `List<RadioStation>`；实测估算约 10–15 MB 对象图，车机低内存机型需在真机观察，超限时改为按分类分片加载（本期不做，先量） |

---

## 10. 测试与验收

### 10.1 JVM 单测

| 用例 | 断言 |
|---|---|
| 真实 assets 解析闸门 | 读仓库里 `iptv_channels.json`：记录数 ≥ 11000（基准 12,434）、每条有 `id`/`name`/至少 1 个 source、id 全部以 `iptv:` 开头且全库唯一 |
| 现有电台数据不受新字段影响 | 读真实 `radio_stations.json` 解析成功，且 `userAgent`/`referrer`/`nsfw` 取默认值 |
| 无归属流的域名命名 | `iptv:direct:` 记录的 name 由 host 派生，不含 `www.`、不含协议 |
| 国家中文名映射 | `CN→中国`、`US→美国`；表里没有的码回落英文名且**不是空字串** |
| `IptvCategory` | 枚举 key 覆盖 `categories.json` 全部 30 个 id、未知 key 回落「其他」、`xxx` 判为成人 |
| 视频闸门接线 | `IptvPlayerScreen` 的 composition 必须调用 `setVideoPictureWanted(true)`（用现有 ViewModel 测试手法钉住），离开页面调 false |
| header 不串台 | 播纯音频台时 `StreamHeaderStore.current` 必须为 null；播完 iptv 再播电台，写入被清空 |
| NSFW 过滤 | 开关关时结果不含 `nsfw=true` 与 `xxx` 分类；开时含 |
| header 查表 | 有 header 的流命中、无 header 的流**不产生任何 header**（零回退守卫） |
| `getStationById` 回落 | `iptv:` 前缀查得到；非前缀 id 不会去查 iptv 仓库 |
| 收藏双向隔离 | 电台收藏列表不含 iptv id；iptv 收藏列表不含电台 id |

### 10.2 真机验收（新增 `tools/iptv_acceptance.ps1`）

| # | 判据 |
|---|---|
| T1 | 底部导航 4 项且顺序为 电台 / iptv / 音频库 / 设置；进 iptv 后底部栏仍在 |
| T2 | iptv 首屏是搜索框 + 四入口，**没有平铺万条列表** |
| T3 | 分类入口能进、条数与 json 内实际计数一致（抽 3 类核对） |
| T4 | 国家入口列出条目，CN 显示「中国」不是「CN」 |
| T5 | 搜索关键字命中（含一条无归属流的域名搜索） |
| T6 | 播一条已知可用流：出声/出画；`logcat` 里能看到 header 生效（诊断日志一行） |
| T7 | 全屏按钮存在并可进全屏（复用上一批已验的判据） |
| T8 | 收藏一个 iptv 台 → iptv 收藏页可见；回电台收藏页**不含它** |
| T9 | NSFW 默认关：搜索成人频道关键字无结果；设置里打开后有结果 |
| T10 | 车机几何（1280x720@160）下列表与播放页布局正常、控件单行 |
| T11 | 纯音频电台回归：现有 V1/V6 判据仍 PASS（新字段与 header 工厂不得影响老路径） |
| T12 | R8 混淆包复跑 T1–T8、T11 |

---

## 11. 非目标

- EPG / 节目单关联（iptv-org 的 epg 仓库与频道 id 关联不可靠，且需要额外抓取）
- 运行时更新清单、远端索引镜像（V3 定为构建期；将来要做走自有服务器，沿用"凭证零分发"路线）
- 全量健康检测（10k 条）
- 国家/语言过滤（V1 明确不做）
- 拼音搜索索引（10k 条建索引是另一件事）
- 时移/回看（`feed` 字段本期只透传，不做入口）
- 本地视频文件（C 期，用户已明确"本地过滤不用改"）

---

## 12. 交付

- 文档：本文件；实现计划落 `doc/plans/2026-11-06-iptv-tab-plan.md`
- 产物变化：新增 `assets/iptv_channels.json`（实测 12,434 条 / 5.25 MB 未压缩）、`doc/iptv_nonofficial_hosts.md`、`tools/iptv_sync.ps1`、`tools/iptv_acceptance.ps1`
- 包体：实测压缩后 **850 KB**（15.8%），APK 6.14 MB → 预计约 **6.97 MB**；验收时实测并记录真实值
- Git：spec 单独一次提交；实现按任务分层提交；不 push、不 merge，除非用户明确要求
