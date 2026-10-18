# WebDAV 全源节目搜索 设计

> 2026-10-18 · 音频库（戏曲）跨目录搜索。前置决策已由用户确认：
> - **车机档不开放**本功能：Car 档不启动索引器、不启用全源搜索入口；Car 档搜索框保持现状（当前目录内过滤），渲染结果须与改造前一致。
> - **后台索引只在 Wi‑Fi 下进行**；Wi‑Fi 断开时暂停保留进度，恢复后续爬。
> - **播放期间索引不降速、不暂停**（用户明确要求；下方字节账支持该决定）。
> - 索引未完成时的搜索：给出"正在索引中"提示，同时展示已索引部分的结果。
> - 手机档搜索框语义统一为**全源搜索**（方案 A）；跨源重复曲目按**曲名+大小归并为一条**（方案 A）。

## 0. 2026-10-18 二次修订（**本节优先于下文**）

用户看过设备实测后改了两条决定。以下条目**取代** §3/§5/§7/§10 中与它冲突的句子：

1. **索引不再自动建立，也不再限定 Wi‑Fi**——改为用户确认驱动：手机档搜索时若本机从未建立索引，弹「尚未建立索引，现在建立？」；确认后才后台起爬；选「以后再说」则本次会话不再询问（冷启动恢复询问）。网络类型不再是前提，**`NetworkGate` 已整体删除**（连带 `IndexState.PausedNoWifi`）。
   - 代价已知并认可：全量一轮响应体实测约 1.3MB/源、四源约 8MB，走蜂窝会吃这些流量。
   - 因此 §5 的“移动网络手动刷新文案”、§7 的两行 Wi‑Fi 相关态、§10 第 1/3/5 条里关于 Wi‑Fi 的断言全部作废；**车机档不索引、不搜索这条不变**。
2. **进行中不出结果**。§7 原写“提示 + 已索引部分结果一起给”，现改为：`Indexing` 阶段只显“正在建立索引，请稍后搜索（已收录 N 个曲目）”，不给半截结果；完成后**自动重跑当前搜索**，用户不必重输关键字。
3. 判定集中在纯函数 `IndexQuery.searchPhase(...)` → `SearchPhase`（`Disabled/NotIndexed/Deferred/Indexing/Ready/ReadyStale`），UI 只渲染阶段。索引过期（TTL 到或 `lastSyncAt<=0`）时**照常出结果**，只给一行可点的「索引可能已过期，点这里更新」，**不弹窗**。
4. `webdav_index_enabled` 语义由“是否自动索引”改为“**是否允许建立索引**”（关掉后不弹确认、不起爬，已建好的搜索仍可用）。
5. 远端索引发布（`2026-10-18-remote-index-publish-design.md`）**暂缓**：服务器无写通道、SSH 不可达、域名被备案阻断。

## 1. 目标与非目标

**目标**

1. 用户输入"花木兰"，在该源**任意深度**的曲目都能一次搜到，无需逐级点进目录。实测目录规模下（四源共 276 目录、19657 个音频文件）搜索响应应为本地查询级（≤100ms）。
2. 全量索引在手机 Wi‑Fi 下**一次性后台完成**：四源合计实测 75s（并发 8，PC 直连），移动端预算放宽到 ≤3 分钟；期间可取消、进度可见、失败目录可重试。
3. 索引未完成不是死胡同：搜索仍返回已收录部分的结果，并明确告知"索引进行中，结果可能不全"。
4. 索引与网络策略互不干扰：播放不卡、移动网络绝不起爬、车机档零行为变化。
5. 所有可判定逻辑（待爬集合选择、新鲜度判定、去重归并、路径→面包屑、进度聚合）都是**纯函数**，JVM 单测可钉死；真实 123pan 与车机/手机渲染走真机裁决。

**非目标（本期不做）**

- 不做 `Depth: infinity` 全树 PROPFIND——P1-8 已确认 123pan 在文件多时响应巨大/超时且很多服务端禁用；本设计只走分层 Depth:1 递归。
- 不做 WorkManager/AlarmManager 定时刷新索引（车机 ROM 省电策略会掐后台任务，`BootReceiver` 注释里已记过同类失败），只做"进入即补齐"。
- 不做拼音/模糊/分词匹配，仍是子串 `LIKE`；不做服务端搜索（WebDAV 无 SEARCH 能力）。
- 不做 M3U / HTTP 源的索引搜索（这两类是扁平播放列表，目录语义不成立）；LOCAL 源由 MediaStore 自有查询覆盖。
- 不下载音频文件、不做离线缓存正文；索引只存目录元数据。
- 不改 `Dimens` 几何、不动主题系统；Car 档布局零变化。

## 2. 实测依据（2026-10-18，本机 → `webdav.123pan.cn`，只读 PROPFIND Depth:1）

| 源 | 目录数 | 音频文件 | 全树耗时(并发8) | 单请求 p50/p90/max | 失败 |
|---|---|---|---|---|---|
| 豫剧·码A（按演唱者） | 52 | 3468 | 16.7s | 815ms / 1.58s / 8.09s | 0 |
| 豫剧·码B（按类型） | 12 | 3468 | 8.8s | 316ms / 850ms / 7.89s | 0 |
| 歌曲 | 168 | 12020 | 44.4s | 1.65s / 5.61s / 8.25s | 0 |
| 歌手 | 44 | 701 | 4.7s | 713ms / 1.45s / 2.12s | 0 |
| 合计 | 276 | 19657 | ≈75s | — | 0 |

推论（决定了设计形状）：

- **成本是 RTT 不是带宽**。每条目录项响应约 380B（根目录 52 条 = 19941B 实测），全树响应体合计 ≈8MB，75s 摊平约 110KB/s；一路 128kbps MP3 只要 16KB/s。播放与爬取同时跑没有带宽冲突，故用户"播放时不节流"的决定成立。
- 二者也不共用连接池：播放走 Media3 `DefaultHttpDataSource`（`RadioService.kt:114-121`），列表走 `WebDavClient` 的 OkHttp，无队头阻塞。
- **绝不能在用户搜索时同步遍历目录**：单请求 p50 已达 1s、慢尾 8s，歌曲源 168 目录实时爬=用户站着看一分钟。所以必须预建索引。
- 并发 8 把单请求 p50 从串行均值 271ms 顶到 815ms，但总吞吐仍占优；**并发取 6** 作为延迟/吞吐折中。
- 码A 与码B 同为 3468 首，是同一批内容的两种分类视图 → 跨源搜索必然出双倍结果，去重是必需品而非优化。

## 3. 索引存储与查询：零表结构变更

索引就是"把每个目录的列表结果都灌进 `audio_cache`"，因此复用现有实体，不新增表、不触发 Room 迁移。

- `AudioCacheEntity(sourceId, path, parentPath, name, isFolder, size, cachedAt)` 字段已够用；行级 `cachedAt` 兼作新鲜度依据。
- **待爬/过期集合由数据派生**：`SELECT DISTINCT parentPath FROM audio_cache WHERE sourceId=?` 得到已爬目录；某目录"新鲜"= 该目录行的 `min(cachedAt)` 在源 TTL（`AudioSourceEntity.cacheTtlMs`，默认 24h）内。不新增目录状态表。
  - 接受的边界：空目录（`insertAll` 空列表）不留行，因此每次都会重爬，代价 1 个请求。可接受，不为此加表。
- 目录被服务端删除时，重爬走 `deleteByParentPath` + `insertAll`，天然收敛，无需额外清理逻辑。
- **改造 `AudioCacheDao.searchByName`**，现有查询三处不够用：`LIMIT 50` 过小、只按单 `sourceId`、会命中目录行。新查询：

```kotlin
@Query("""
    SELECT * FROM audio_cache
    WHERE isFolder = 0
      AND (:sourceId IS NULL OR sourceId = :sourceId)
      AND name LIKE '%' || :query || '%'
    ORDER BY name ASC
    LIMIT 200
""")
suspend fun searchTracks(sourceId: Long?, query: String, limit: Int): List<AudioCacheEntity>
```

- 结果里带上 `parentPath`，供 UI 画面包屑与去重归并使用。
- LIKE 语义确认项（单测钉死）：Room/SQLite 的 `LIKE` 对 ASCII 大小写不敏感、对中文按码位精确匹配；`%`/`_` 需转义（用户输入含下划线时不能当通配符）。

## 4. 索引器 `SourceIndexer`

**关键安全决定：不复用 `WebDavClient` 单例。** `WebDavBrowser.listChildren()` 每次先 `applyCredentials()` 改写单例的 `serverUrl/username/password`，而单例的 `listFilesCache` key **只有 path、不含源**——四个快捷码源的 base URL 完全相同。源内并发爬取会导致 B 账号凭证发往 A 主机、同名路径（如 `/常香玉/`）跨源串数据。这是代码注释里"终审 I2"（`WebDavBrowser.readSidecar` 已因此改为显式传凭证）在列表路径上的同款缺陷。因此：

- 索引器**按源创建一个独立 `WebDavClient` 实例**（凭证在构造期固定，运行期不 mutate），源内目录并发 6、源之间串行。
- 索引器直接依赖 `AudioBrowser` + `AudioCacheDao`，**不经过** `AudioLibraryRepository.listChildren`——后者是源级 TTL 语义（`lastSyncAt`），与按目录增量不符；索引器自己做 `deleteByParentPath` + `insertAll`，写缓存语义与 repo 保持一致。
- `WebDavBrowser.listChildren` 在 `listFiles` 抛异常时并不会把异常包成 `Result.failure`，索引器统一 `runCatching` 兜住，单目录失败不中断整体。

**调度与生命周期**

```
run(source):
  若 非 Wi-Fi → 挂起等待网络回调（不取消、不清空已索引）
  frontier = BFS 队列，初值 = 根目录
  while frontier 非空 且 未取消:
     取一批(≤6) 目录：跳过 cachedAt 新鲜的目录
     并发 PROPFIND Depth:1 → 写 audio_cache → 子目录入队
     失败目录重试 1 次；仍失败记入 skipped，继续
  结束：聚合 progress（已收录曲目数 / 剩余目录数 / skipped 数）
```

- 取消：`Job.cancel()`；协程取消**不清空**已写入的索引（下次按新鲜度续爬）。
- **全部目录爬完（含 skipped 为空）时**调 `sourceDao.updateSyncTime(source.id, now)`；部分完成（取消/暂停/有跳过目录）**不写**源级时间戳，保持浏览路径原有的重拉语义。这样源级 TTL 与目录级 `cachedAt` 不会各说各话。
- **禁止复用 `AudioLibraryRepository.refreshSource`**：它现在做 `deleteBySource` 整源清空（`AudioLibraryRepository.kt:69-73`），一旦接到手动刷新就会把两万行索引抹掉、退化成重爬 75s。手动刷新改为“交由 `SourceIndexer` 重爬该源全部非新鲜目录”。
- 断点续爬无需持久化队列：进程被杀后重新触发时，新鲜目录被跳过，效果等同续爬。
- 暴露 `StateFlow<IndexState>`：`Idle / Running(progress: IndexedProgress) / PausedNoWifi(progress) / PartiallyFailed(progress, skipped) / Done(count)`。`IndexedProgress` 含 `indexedFiles`、`pendingDirs`、`skippedDirs`。
- 同一源同一时刻只允许一个索引 Job（重复触发合并，不叠并发）。

## 5. 网络闸门与形态边界（网络闸门部分已被 §0 作废；形态边界仍有效）

**`NetworkGate`（新增 seam）**：`ConnectivityManager` + `registerDefaultNetworkCallback`，判定条件 `hasCapability(NET_CAPABILITY_NOT_METERED) && (hasTransport(WIFI) || hasTransport(ETHERNET))`（以太一并放进来，车机可能走 USB 共享/有线）。提供 `isIndexingAllowed(): Boolean` 与 `allowedFlow: Flow<Boolean>`。`ACCESS_NETWORK_STATE` 已在 Manifest，无需新增权限。

- 索引的**每一个**启动点都前置该判定；运行中断网 → 转 `PausedNoWifi`，保留进度，恢复自动续爬。
- 移动网络下用户点手动刷新：给可行动文案（"当前为移动网络，索引仅在 Wi‑Fi 下进行"），**禁止做成点了没反应的死按钮**。

**形态边界（车机不开放）**

- 启动判定与入口隐藏都用现有唯一判定源 `isCarSurface()`（`ui/theme/UiSurface.kt`），页面不许自行读 `LocalConfiguration`。
- **层约束**：`SourceIndexer` 属数据层，不得读 `LocalUiSurface`（CompositionLocal）。由 Phone 档的 UI/ViewModel 决定何时启动；Car 档永不调用启动入口——这样车机档连一次 PROPFIND 都不发。
- **已知限制（必须写进验收说明）**：加装车机 density 上报随意，同样 1280x720 面板报 240dpi 时 `smallestWidthDp=480`，自动档会**判成 Phone**，于是全源搜索入口会出现在车机上。规避手段是设置页把「界面模式」手动钉为"车机"（现有能力，判定已含手动覆盖）。本期不引入额外硬件指纹判定。

## 6. 搜索 UI（仅手机档）与结果归并

- 沿用与电台页共用的 `SearchBarRow`（同一宽度、同一纵向节奏），**不新增布局几何**。
- Phone 档音频库搜索框语义换成"全源搜索"：输入非空 → 走 `searchTracks`（跨该设备全部已索引 WebDAV 源）；清空 → 回到目录浏览。目录内 `it.name.contains` 过滤仅保留给 Car 档使用。
- 结果行结构：`曲名` + 第二行面包屑 `豫剧 / 常香玉 / 花木兰`（由 `parentPath` 拆出，去掉源根与空段）。点结果行 → 直接起播该曲目（复用现有 `playTrack` 链路，路径已知，无需先导航）。
- 结果行保留收藏星标入口，与目录列表行同构。

**跨源去重（方案 A）**

- 归并键：`name.trim()` + `size`。同键的多源命中合成**一条**结果，行上标注 `N 个来源`（N>1 时）。
- 归并后主选来源：优先"该源上次播放成功"（复用 `SourceAvailabilityStore` 的可用性分），无记录时取列表中的第一个。点开后若该来源起播失败，走现有 failover 换另一来源重试一次。
- 大小相同但内容不同的风险：以 `name+size` 归并理论可误合，实测两源同为 3468 首为同一文件集，接受该口径；行上"2 个来源"的可展开详情作为逃生门（点击可看各来源路径）。

## 7. 索引中 / 空索引 / 无结果 的提示态（已被 §0 第 1–3 条修订）

沿用 `ui/common/StateViews` 既有约定（**"搜不到"与"还没索引完"是两回事，文案不得混用**——与 `AudioRecentTab`、`FavoritesScreen` 现有注释同源）：

| 状态 | 展示 |
|---|---|
| 索引进行中 + 有结果 | 顶部一条进度提示："索引进行中，已收录 12,480 个曲目，结果可能不全"；下方照常列结果 |
| 索引进行中 + 无结果 | "正在建立索引，已收录 N 个曲目，请稍后再试" + 进度 |
| 索引已暂停（无 Wi‑Fi） | "索引已暂停，连接 Wi‑Fi 后继续（已收录 N 个曲目）" |
| 从未索引 + 当前可索引 | 自动触发并进入"索引进行中"态 |
| 从未索引 + 移动网络 | "连接 Wi‑Fi 后会自动开始建立节目索引" |
| 索引完成 + 无结果 | "没有匹配「XXX」的曲目 · 换个关键字试试"（现状文案） |
| 有跳过目录 | 结果尾部一条："N 个目录未能收录，点击重试" |

## 8. 持久化与配置

`RadioPreferences` 新增键（沿用现有 `booleanPreferencesKey` 模式）：

| key | 类型 | 默认 | 用途 |
|---|---|---|---|
| `webdav_index_enabled` | Boolean | true | 后台索引总开关（手机档） |

不新增凭证存储。`AudioSourceEntity` 不动表结构；TTL 继续用现有 `cacheTtlMs`（源级），目录级新鲜度用 `audio_cache.cachedAt`。

## 9. 组件与文件清单

**新增**
- `service/SourceIndexer.kt` —— BFS 遍历、并发、取消、进度聚合（依赖 `AudioBrowser` + `AudioCacheDao`）
- `service/NetworkGate.kt` —— `ConnectivityManager` 实现 + 接口（可注入 fake）
- `domain/model/IndexQuery.kt` —— 纯函数：待爬集合选择 / 新鲜度判定 / 去重归并 / 路径→面包屑 / 进度聚合
- `ui/library/SearchResultsList.kt` —— Phone 档全源搜索结果列表（含进度提示态）

**改动**
- `data/dao/AudioCacheDao.kt` —— 新增 `searchTracks(sourceId?, query, limit)`、`distinctParentPaths(sourceId)`、`dirFreshness(sourceId)`
- `ui/library/AudioLibraryViewModel.kt` —— 搜索语义分支（Phone 全源 / Car 目录内）、索引起动与状态收集、去重结果映射
- `ui/audio/AudioMainScreen.kt` —— 进度提示条、结果列表接入；Car 档分支保持原渲染
- `data/repository/AudioLibraryRepository.kt` —— `refreshSource` 从“整源 `deleteBySource` + 重拉”改为“触发目录级重爬”，否则手动刷新会清空全树索引
- `di/AppModule.kt` —— `NetworkGate` 绑定、`SourceIndexer` 作用域（每源一 `WebDavClient` 实例的工厂）
- `AndroidManifest.xml` —— 无新增权限（`ACCESS_NETWORK_STATE` 已有）

**收尾（本次一并处理）**
- `service/OperaRepository.kt` 的 `searchFiles`/`searchLocalCache`/`searchFilesRealtime` 现为**孤儿代码**（除自身声明外无任何注入点，旧戏曲页已在 Phase 5 删除）：其"分层 Depth:1、不用 infinity"的结论已由本设计的 `SourceIndexer` 承接，实现随本功能替换，孤儿文件删除，避免留下两套搜索。
- 同文件内 `WebDavClient.listFilesCache` 的源级串数据风险（§4）在本设计中通过"每源独立实例"规避；**不改动播放路径上的现有单例行为**（凭证安全的全局整改属另一个议题，不在本 spec 范围）。

## 10. 验收指标（其中关 Wi‑Fi 的断言已被 §0 作废）

1. 手机 Wi‑Fi 下，四源（19657 曲目）全量索引 ≤3 分钟，且**不中断正在播放的曲目**（MediaSession 仍为 PLAYING，无缓冲报错）。
2. 索引完成后搜索"花木兰"响应 ≤100ms（本地查询，与网络无关）。
3. 索引途中搜索可见部分结果 + 进度提示；无 Wi‑Fi 时不发起任何 PROPFIND（用 `tcpdump`/抓包或 fake `NetworkGate` 断言调用次数=0）。
4. 车机档：音频库页截图与 `car_assert.ps1` bounds 断言与改造前**逐像素/bounds 一致**；车机档日志中无索引请求。
5. 断网续爬：索引中途断开 Wi‑Fi → 暂停；恢复后从中断处继续，不重复已新鲜目录。

## 11. 测试策略（TDD）

**纯函数单测（JVM，仿现有 `StationSorterTest`、`RegionResolverTest` 风格）**
- 待爬集合选择：新鲜跳过 / 过期入队 / 空目录重复入队 / 首次全量。
- 新鲜度判定：TTL 边界、`cachedAt` 缺失、多行目录取 `min`。
- 去重归并：同 `name+size` 跨源合一、`N 个来源` 计数、大小不同不合并、`name` 首尾空格/全角差异。
- 路径→面包屑：多级、源根、含 `/` 的曲名、超长路径截断。
- 进度聚合：`indexedFiles / pendingDirs / skippedDirs` 单调性与终态。
- LIKE 转义：输入含 `%`、`_` 时不当作通配符。

**Room DAO 测**：`searchTracks` 跨源/单源、`isFolder=0` 过滤、LIMIT、中文子串命中。

**seam 假件**：`AudioBrowser` fake（可编程目录树 + 注入延迟/异常）+ `NetworkGate` fake + 测试用虚拟时钟，断言：并发不超过 6、单目录失败重试 1 次后入 `skipped` 且不中断、取消后已写入数据保留、断网转 `PausedNoWifi`、恢复后续爬不重复请求新鲜目录。

**真机裁决（必做，不可仅凭模拟器）**
- 手机：四源真实索引一遍，记录耗时/内存峰值/Room 行数；播放中启动索引，确认出声不断。
- 车机（1280x720 恒横屏，界面模式钉"车机"）：确认无索引流量、音频库页零视觉变化。
- 弱网：移动网络下点刷新的提示文案确实出现、且零请求。
- 按项目"真机防误报"规范：未出现预期行为即判失败，不接受"看起来正常"。

## 12. 风险与未决

1. **123pan 慢尾与限流**：实测 max 单请求 8.25s，且并发越高 p50 越大。若线上触发限流（当前 0 失败样本不足以排除），策略是降并发到 2 并加长请求间隔；本设计把并发度做成构造参数，不需改结构。
2. **车机 density 误判成 Phone**（§5 已知限制）会让车机上冒出搜索入口。规避依赖用户手动钉死界面模式；若实测无法接受，后续引入"车机硬件标记"另行设计。
3. **`name+size` 归并的误合**：不同内容恰好同名同尺寸会被合成一条。真实戏曲库概率低，保留"展开各来源路径"作逃生门。
4. **索引体积**：约 2 万行 × ~200B ≈ 4MB DB。若用户源数量继续增长，需评估 `audio_cache` 总量与 TTL 回收策略（本期不设上限，仅记录）。
5. **WebDAV 凭证全局串扰的根治**不在本 spec：本设计只在索引路径绕开单例；播放/sidecar 侧的 `WebDavClient` 单例改造另列议题（与安全整改同批处理更合适）。
6. **TTL 语义分叉**已由 §4 收敛：索引跑完全部目录才写源级 `lastSyncAt`，部分完成不写；目录级 `cachedAt` 只用于索引器自己的跳过判定。两者职责分开后不会出现“刚索引完但浏览仍去联网重拉”；必须在单测中覆盖这个写入条件。
