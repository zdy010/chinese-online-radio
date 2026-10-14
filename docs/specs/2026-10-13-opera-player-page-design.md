# 音频库独立播放页设计（OperaPlayerScreen）

日期：2026-10-13 ｜ 状态：已与用户逐节确认 ｜ 版本目标：v1.0.110+

## 1. 背景与目标

戏曲/音频库播放的全部控制面目前挤在音频库页底部的 `OperaMiniPlayerBar`
（进度拖动、±10 秒、上/下一段、播放暂停、循环、停止），这是戏曲播放的唯一控制面，
小面积下操作困难，也没有封面与歌词的呈现空间。

目标：把播放操作独立到一个全屏播放页（形态对齐电台 `PlayerScreen`），
并在页内提供封面与歌词（同名 `.lrc` 附属文件，左右滑动切换）。

**明确不做**（用户已确认）：唱段列表页内切换、睡眠定时器、在线歌词 API、
`.txt` 无时间轴容错、嘟嘟桌面专用卡片协议（列为后续 spike）。

## 2. 已确认的需求共识

1. 全屏控制面：现有播放条九个能力全部搬入并放大，一个不减。
2. 底部 `OperaMiniPlayerBar` **完整保留**，不缩水任何快捷操作；点击其标题区进入新页。
3. 新页附加歌词：来源为同名 `.lrc` 附属文件（本地 / WebDAV / M3U 三类源自动探测），
   无歌词优雅降级。
4. 新页附加专辑封面：内嵌 ID3 封面优先，其次同目录同名 `.jpg/.png` 附属文件，
   都无则保持音符占位圆。
5. 封面与歌词**都有**时内容区左右滑动切换；只有一样时静态显示不可滑；都无显示占位。
6. 嘟嘟桌面插件借力方案：主线补全 MediaSession 元数据，桌面卡片实测列为后续 spike。

## 3. 设计

### 3.1 导航与入口

- `Screen` 新增 `data object OperaPlayer : Screen("opera_player")`，注册进
  `MainScreen.kt` 的 `RadioNavGraph`。**不带路径参数**：页面唯一状态源是
  `PlayerManager` 的 opera 状态流（历史上双状态源导致过进度不一致，不得引入第二份）。
- 新页不显示底部双 Tab（与电台 PlayerScreen 一致，`showBottomBar` 逻辑不动）。
- `OperaMiniPlayerBar` 增加可选参数 `onClick: (() -> Unit)? = null`：
  只让标题信息行（封面占位圆 + 标题/时间列）可点，`AudioMainScreen` 注入
  `onOpenPlayer: () -> Unit` 回调（由 MainScreen 传 `navController.navigate`，
  与 RadioScreen 的 `onNavigateToPlayer` 同款风格）；滑条与按钮行命中区不变。
  标题行末端（停止按钮左侧）加一个小展开图标（`OpenInFull`）提示入口——中老年用户
  需要可见 affordance。`onClick` 为 null 时行为与现在完全一致（回归保险）。
- 新页顶栏返回 = `popBackStack()`，播放不中断。
- 新页"停止" = `stopOpera()` 后自动 `popBackStack()`（避免停在无内容的死页）。
- 无播放内容时直接进入该路由：显示"未播放任何唱段"空态 + 返回按钮，不崩溃。

### 3.2 页面布局与车机档

新文件：
- `ui/player/OperaPlayerScreen.kt` —— 页面与播控（与电台 PlayerScreen 同包同风格）
- `ui/player/OperaPlayerViewModel.kt` —— Hilt，负责歌词/封面的 sidecar 加载与歌词状态机；
  播控动作直接调 `PlayerManager` 既有方法：`togglePlayPause / playOperaPrevious /
  playOperaNext / seekOperaTo / seekOperaForward(10) / seekOperaBackward(10) /
  cycleOperaRepeatMode / stopOpera`，不经由本 VM 转发（单一状态源原则的例外只有
  歌词/封面这些本 VM 自有的新状态）。

手机档（竖屏单列）自上而下：
1. TopAppBar（返回 + "正在播放"）
2. 唱段名（大）＋ 来源/专辑名（小）＋ 时间 · 码率
3. 内容区（3.3 节，`weight(1f)`）
4. 可拖动进度条 + 当前/总时长
5. 控件行：循环模式 ｜ 上一段 ｜ 播放/暂停（大） ｜ 下一段 ｜ ±10 秒 ｜ 停止

车机档（`isCarSurface()` / `usesRailNavigation()` 既有判定）：横屏双栏——
左栏信息 + 内容区，右栏进度条 + 大控件群；所有可点控件 `Dimens.TouchMin`（88dp）
命中区；±10 秒置于主播放键两侧。尺寸一律走 `Dimens` 形态 token，不新造硬编码 dp。

两档能力对等：九项能力全部呈现，无删减——可拖动滑条、后退 10 秒、上一段、
播放/暂停、前进 10 秒、下一段、循环模式、停止八个控件 + 标题/时间码率信息行。
页面只 collect `PlayerManager` 的 opera 状态流
（`operaFile / operaPlaylist / operaIndex / operaPosition / operaDuration /
operaBitrate / operaRepeatMode / isPlaying / operaContext / operaMediaMetadata`）。

### 3.3 内容区：封面 ↔ 歌词 Pager

内容区为 `HorizontalPager`，页数由可用内容决定：

| 封面 | 歌词 | 呈现 |
|---|---|---|
| 有 | 有 | 两页可左右滑，底部圆点指示 |
| 有 | 无 | 封面页静态，无滑动 |
| 无 | 有 | 歌词页静态，无滑动 |
| 无 | 无 | 音符占位圆 + 唱段名（保持现有占位样式） |

- 封面页：大封面图 + 唱段名。封面来源优先级：**内嵌 ID3**
  （`player.currentMediaMetadata.artworkData/artworkUri`，零网络）→
  **同名图片**（`.jpg`/`.png` 各试一次，走 sidecar 链路）→ 无则按表降级。
  WebDAV 图片需鉴权：不走 Coil 直链，随源凭证用 OkHttp 取字节后解码。
- 歌词页：LazyColumn 逐行滚动，当前行高亮（primary 色 + 放大一档）；
  currentIndex 由 `operaPosition` 推导；**点击歌词行 seek 到该行时间**。

### 3.4 歌词/封面链路

**播放上下文**（歌词/封面找回"源"的前提，现在不存在，需新增）：

- `PlayerManager` 新增 `data class OperaPlayContext(sourceId: Long, trackPath: String)`
  与 `operaContext: StateFlow<OperaPlayContext?>`；同时新增
  `operaMediaMetadata: StateFlow<MediaMetadata?>`（监听 `onMediaMetadataChanged`，
  供页面取内嵌封面 artworkData/artworkUri）。
- `OperaAudioFile` 增加 `sourcePath: String? = null`（WebDAV href / 本地路径 / M3U 条目 URL），
  构造点同步填充。
- `playOperaFile` 增加可选参数 `context: OperaPlayContext? = null`；
  上/下一段时 `trackPath` 取自当前 `file.sourcePath`（缺省回退 `downloadUrl`）。
- 写入点：`AudioLibraryViewModel` 的 `playTrack / playTrackWithSource / playFavorite /
  playRecent` 传 `(source.id, track.path)`；`AudioLocalTab` 本地直播放传
  `sourceId = 0`（约定：0 = 直接按文件系统路径读取，无需库记录与鉴权）。
- `stopOpera()` 清空 context。

**附属文件读取**：`AudioBrowser` 接口扩展两个带默认实现的方法：

```kotlin
suspend fun readTextFile(path: String): Result<String>   // 默认 Result.failure(UnsupportedOperationException)
suspend fun readBinaryFile(path: String): Result<ByteArray>
```

四个 browser 分别实现：`WebDavBrowser`（`WebDavClient` 新增 GET 文本/字节能力，
复用现有 OkHttp client 与 authHeader）、`LocalBrowser`（文件/ContentResolver 读取）、
`M3uBrowser` / `HttpBrowser`（注入的 OkHttp GET）。凭证复用
`repository.getSource(sourceId)` 既有密码链路（credentialManager）。

**路径推导**：纯函数 `sidecarPath(base: String, ext: String): String`——
取路径/URL 最后一段，替换尾扩展名（无扩展名则追加），保留 URL 编码与查询参数原样。
`.lrc` 未命中再试 `.LRC`；图片同理。

**LRC 解析**：纯函数 `LrcParser.parse(text): List<LrcLine>`（`LrcLine(timeMs, text)`）。
支持一行多时间戳、2/3 位小数秒、`[mm:ss]`；`[ti:]` 等元数据标签忽略；空文本行剔除；
按时间排序；坏行跳过不中断。

**加载时机与竞态防护**：只在进入全屏页时加载歌词与封面（mini 条不触发任何 sidecar
请求，列表页零额外流量）。每次请求以当前 `fileId` 标记，段落切换后迟到响应直接丢弃。
内存缓存按 path 键（小容量 LRU），不落盘。

### 3.5 MediaSession 元数据补全（嘟嘟桌面借力）

`RadioService` 是 `MediaSession.Builder(this, player)`，会话元数据随 ExoPlayer 自动透出。
主线工作：

1. 戏曲 `MediaItem` 构造已带 title/artist/album（现状保持）；
2. 内嵌 ID3 封面依赖 Media3 默认合并行为进入 session——**以真机
   `adb dumpsys media_session` 实测为裁决**；若实测 artwork 未透出，追加显式注入；
3. 后续 spike（独立小课题，不在本 spec 实现范围）：在 FYT 真机验证嘟嘟桌面当前版本
   的"通用音乐"卡片能否识别并展示我们的播放（标题/专辑/封面/播控），
   结论决定是否需要追加工作。不为其编写任何私有协议。

## 4. 错误处理与降级

**总原则：附属内容永不阻塞主控制面。**

- 无 `.lrc` / 网络超时（协程 withTimeout 8s）/ 源不支持读取 / context 缺失 / 解析结果为空
  → 歌词不进 Pager，内容区按 3.3 表三态退化；不弹错、不打 toast。
- 封面下载或解码失败 → 回占位圆。
- 降级空态文案提示下一步："该唱段暂无歌词，在音频同目录放同名 .lrc 即可显示歌词"。
- 换段后不轮询补拉；歌词随段落切换由新一次的加载触发。

## 5. 测试策略（TDD + 真机裁决）

单元测试先行：
1. `LrcParserTest`：多时间戳、小数位、坏行、元数据标签、排序、空文本。
2. `SidecarPathTest`：本地路径 / WebDAV href / 带查询参数的 M3U URL / 无扩展名 / 大写扩展名。
3. `OperaPlayContext` 生命周期：点播设置、切段更新、stop 清空；`sourceId=0` 本地约定。
4. `OperaPlayerViewModel` 状态机（fake browser）：成功、404、超时、换段丢弃迟到响应、
   封面优先级（内嵌 → sidecar → 无）。

UI 自动化（.qtest PowerShell + uiautomator；含冷启动就绪轮询与权限预授权）：
1. **回归基线**：音频库 mini 条九个控件 bounds 逐项断言不丢、手机档原页面渲染与现状一致；
2. 点标题行 → 进新页；返回 → 播放继续；新页与 mini 条播控状态双向同步；
3. 新页全控件：播放/暂停、seek、±10 秒、上/下段、循环标签、停止 → 自动返回列表；
4. 内容区三态矩阵：都有 → 可滑 + 圆点；只一样 → 无滑动；都无 → 占位；点击歌词行 seek 生效；
5. 车机档：横屏双栏，density 160 / 240 两档，88dp 命中区几何断言（车机档对话框芯片验收规范）。

真机裁决：
- FYT 车机 + 123 云盘真实源，放一个带同名 `.lrc` 的测试唱段做端到端验证
  （**前置依赖：用户在云盘目录自备一个测试 .lrc**）；
- `adb dumpsys media_session` 确认戏曲播放元数据与 artwork 透出情况（嘟嘟桌面 spike 起点）。

## 6. 涉及文件清单

| 文件 | 动作 |
|---|---|
| `ui/navigation/Screen.kt` | 新增 OperaPlayer 路由 |
| `ui/MainScreen.kt` | NavHost 注册 + AudioMainScreen 注入 onOpenPlayer |
| `ui/common/MiniPlayerBar.kt` | OperaMiniPlayerBar 增加可选 onClick 与展开图标 |
| `ui/audio/AudioMainScreen.kt` | 透传 onOpenPlayer |
| `ui/player/OperaPlayerScreen.kt` | 新建 |
| `ui/player/OperaPlayerViewModel.kt` | 新建（歌词/封面加载与状态机） |
| `service/PlayerManager.kt` | operaContext + playOperaFile context 参数 + sourcePath |
| `service/RadioService.kt` | 视 dumpsys 实测结果决定是否需要 artwork 显式注入 |
| `domain/AudioBrowser.kt` | readTextFile / readBinaryFile 默认方法 |
| `domain/browser/WebDavBrowser.kt` 等四个 | 实现附属文件读取 |
| `service/WebDavClient.kt` | GET 文本/字节能力 |
| `domain/sidecar/SidecarPath.kt`、`domain/sidecar/LrcParser.kt` | 新建纯函数 + 单测 |
| `data/entity`（OperaAudioFile 所在模型文件） | sourcePath 字段 |
| `ui/library/AudioLibraryViewModel.kt`、`ui/audio/AudioLocalTab.kt` | 点播写入 context |

## 7. 验收标准（三态）

- **bounded 验收**：九控件回归断言全过；新页路由进出与播控同步全部通过；
  三态 Pager 矩阵通过；车机两档密度几何断言通过。
- **architectural 验收**：播放状态单一来源不回退（新页只读 PlayerManager）；
  列表页零新增网络请求（sidecar 仅在全屏页触发）；真机 .lrc 端到端成立。
- **人工回查**：CodeReview 子智能体评审后人工回查结论。

## 8. 裁决记录

### 8.1 模拟器验收（2026-10-14，opera_player_check.ps1）

ALL PASS 21/21：手机档 19 项（迷你条九控件回归/点暂停不跳页/新页全控件/
本地 .lrc 歌词加载+高亮跟随+点行 seek 生效/返回不中断/停止自动回列表），
车机档横屏 density 160/240 两档全部可点控件 ≥ 88dp。
过程中修掉两个真缺陷：① 页面 info/slider/controls 三个 composable lambda 变量裸写未调用，
只渲染了歌词；② 车机档八按钮单行需 704dp 超出 420dp 右栏被压缩，改两行簇+右栏 520dp。
本地源 sidecar 在 Android 16 模拟器上经 MediaStore DATA 路径直读成功（作用域存储未拦住）。

### 8.2 MediaSession 元数据（dumpsys，模拟器 2026-10-14）

会话存在且 active，state=PLAYING，controllers=8，
`metadata description=demo.m4a, 本地, 本地`（title/artist/album 全部透出），
launchIntent 存在（外部卡片点击可拉起本应用）。
本次样本 m4a 无内嵌封面，artwork 字段未出现属素材缺失非链路缺陷；
Media3 默认合并机制未被推翻，**待真机带封面唱段复测**后再定是否需显式注入（spec 3.5 预案）。

### 8.3 待真机裁决（需用户配合，不阻塞合入）

- FYT 车机 + 123 云盘源：同名 .lrc 端到端（歌词显示/点行 seek/换段不挂旧词）；
- 内嵌封面曲目的 artwork 透出复测；
- 嘟嘟桌面通用音乐卡片识别与展示（后续 spike 立项依据）。
