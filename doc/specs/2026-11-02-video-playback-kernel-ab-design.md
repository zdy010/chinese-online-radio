# 视频播放：内核 + 云盘视频点播(B) + 播放页画面能力(A) · 设计文档

- 日期：2026-11-02
- 范围：**视频内核 + B 期 + A 期**。C 期（本地视频文件）、D 期（新视频内容源）只列路线与准入条件，不在本 spec 实现。
- 前置断点：`doc/HANDOFF_2026-11-01.md`
- 状态：待用户审阅 → 通过后调 writing-plans

---

## 1. 目标与非目标

### 目标

1. 给应用建立**一条**视频播放通路（内核），后续所有视频来源都挂它，不再各造轮子。
2. **B 期**：云盘（WebDAV / 123网盘）里的视频文件点播时能出画面，播控/歌词/断点续播/倍速全部继承现状。
3. **A 期**：电台播放页具备出画面能力 —— 直播流若在起播后被播放器发现有视频轨，播放页自动长出一块画面。本期定位为**能力接入**，不承诺可用台数。

### 非目标（明确排除，不得顺手做）

| 排除项 | 理由 |
|---|---|
| 画中画 / 小窗 / 悬浮窗 | 用户已选单播放器互斥，一期不做（D5） |
| 双播放器并存 | 音频焦点抢占、方控语义要重定义、通知栏只能挂一路 |
| 行车画面遮罩 / 读 CAN 车速挡位 | 用户明确不做（D4） |
| 新增底部「视频」Tab、新增导航路由 | 画面落在两个现有播放页（D3） |
| 视频文件下载缓存 | 体积大、收益不匹配；`DownloadedOpera` 那条路径不碰 |
| 字幕渲染（srt/ass）、多音轨切换 UI、投屏、全屏沉浸手势 | 一期不需要 |
| C 期 `READ_MEDIA_VIDEO` 权限与本地扫描 | 留给下一份 spec |
| 新增任何 Gradle 依赖 | 依赖已齐（见 §2.4） |

---

## 2. 实测依据（本设计的事实底座，全部可复核）

### 2.1 `tv_audio` 62 个台的真相

`app/src/main/assets/radio_stations.json` 中 `category == "tv_audio"` 共 **62** 条，按流地址归类：

| 数量 | host 与路径特征 | 实测结论 |
|---|---|---|
| 50 | `piccpndali.v.myalicdn.com/audio/*.m3u8` | 纯音频转码 |
| 5 | `wstvcpudali.v.myalicdn.com/.../index.m3u8?adapt=0&BR=audio` | 纯音频（参数明写 audio） |
| 2 | `playtv-live.ifeng.com/live/*_audio.m3u8` | 纯音频（文件名明写 audio） |
| 3 | `satellitepull.cnr.cn/live/wx*/playlist.m3u8` | 纯音频（见下） |
| 1 | `a1live.livecdn.yicai.com/live/radio_tv.m3u8` | 纯音频 |
| **1** | `5ddce30eb4b55.streamlock.net/bltvhd/bltv1/chunklist_*.m3u8`（人间卫视） | **有视频** |

**有视频的证据**（一次性探测脚本 `.qtest/probe_video_streams3.py`，标注为 throwaway，不入产品代码）：

- 人间卫视：分片 878 KB，`sps=2 pps=2 idr=2` + `adts=195` → 含 H.264 参数集与 IDR 帧。
- 北京卫视：master 只给 `#EXT-X-STREAM-INF:PROGRAM-ID=1, BANDWIDTH=232000`，**无 `RESOLUTION=`、无 `CODECS=`**；跟到二级 playlist 取一个 2.986s 分片 = 86,480 bytes（≈231 kbps），`idr=0 sps=0 pps=0 adts=149` → **一路纯 AAC 音频**。宁夏卫视、湖南卫视伴音同形。

> 由此修正 11-01 的口头判断：「A 期能让 62 个台看画面」不成立，**当下可验证的只有 1 个台**，且该台 host 是裸 IP + FMS chunklist，稳定性与"仅收录官方公开流"的版权红线都存疑。A 期因此降级为能力接入。

### 2.2 多源轮转在 A 期不是主路径

62 个 `tv_audio` 台中 `sources.size > 1` 的数量为 **0**（全部走单条 `streamUrl`）。换源规则仍要定义（§6.3），但优先级低。

### 2.3 云盘视频文件今天就已经在列表里

`domain/PlayableTrack.kt` 用**黑名单**判可播放（只剔 `lrc jpg jpeg png gif webp bmp txt md srt ass ssa vtt nfo json xml`），注释写明白名单会误杀 wav/flac/m3u8。因此 **mp4/mkv 现在就在列表里并已能点响出声** —— B 期缺的是画面，不是接入。

### 2.4 内核成立的三个技术事实

1. `AndroidManifest.xml` 中 `RadioService` **没有 `android:process`** → 与 `MainActivity` 同进程 → UI 可直接持有那个 `ExoPlayer` 对象绑面。
2. Media3 的 `MediaController` **不支持** `setVideoSurface/setVideoSurfaceHolder/setVideoSurfaceView`（抛 `UnsupportedOperationException`）→ 必须走实例注册表，不能拿 `PlayerManager` 那个 controller 绑面。
3. `app/build.gradle.kts` 已有 `media3-exoplayer` / `media3-exoplayer-hls` / `media3-session` / **`media3-ui`**（media3 1.5.1）→ `PlayerView` 与 HLS 自动识别 m3u8 都已就位，**零新增依赖**。

补充：`MainActivity` 无 `android:configChanges` → 旋转重建 Activity，画面需靠 `DisposableEffect` 重挂；播放器在服务里，音频今天已能扛住旋转。

---

## 3. 决策台账（用户已拍板，实现阶段不得重开）

| # | 议题 | 决定 |
|---|---|---|
| D1 | 来源顺序 | B 云盘视频 → A 播放页画面能力 → C 本地视频 → D 新内容源 |
| D2 | 声画关系 | 单播放器互斥；退出画面可继续只听声 |
| D3 | 画面落点 | `PlayerScreen` 与 `OperaPlayerScreen` 各自长画面；不新增 Tab |
| D4 | 行车限制 | 不做 |
| D5 | 技术方案 | 实例注册表 + 视频轨默认禁用 |
| D6 | spec 粒度 | 一份覆盖内核 + B + A |
| D7 | A 期定位 | 降级为「能力接入」，不承诺台数，文档如实写明当下仅 1 个台可验 |
| D8 | 画面判定 | 一律运行时 `currentTracks`，不改 `radio_stations.json`、不加 `hasVideo` 字段 |
| D9 | 倍速对视频 | 照常生效（视频属点播），不做特殊限制 |

---

## 4. 架构总览

### 4.1 组件与职责

| 单元 | 位置 | 做什么 | 依赖谁 | 被谁用 |
|---|---|---|---|---|
| `VideoPlayerMount` | `service/`（新增） | 同进程内公布那个 `ExoPlayer` 实例的注册表 | media3 | `RadioService` 写、`VideoStage` 读 |
| `VideoTrackGate` | `domain/model/`（新增） | **纯 Kotlin** 闸门决策：画面挂载与否 → 要不要解码视频轨 | 无（不 import media3） | `PlayerManager`、单测 |
| `VideoMedia` | `domain/model/`（新增） | **纯 Kotlin** `looksLikeVideo(name)`：列表徽标判据 | 无 | 三个列表页、单测 |
| `VideoStage` | `ui/common/`（新增） | 唯一画面组件；`PlayerView` 生命周期与绑面 | `VideoPlayerMount`、`PlayerManager` | `PlayerScreen`、`OperaPlayerScreen` |
| `PlayerManager` | `service/`（增量） | 新增 `hasVideoTrack`、`setVideoStageMounted()` | `VideoPlayerMount`、`VideoTrackGate` | 页面 |
| `RadioService` | `service/`（增量） | 建播放器后写注册表 + 上电即禁视频轨；释放时摘 | 同上 | — |

### 4.2 分层与依赖方向

- `domain/model/` 的两个新文件**不 import media3、不 import Android**，与 `domain/model/OperaSpeed.kt` 同风格（判定集中在纯函数，可 JVM 单测）。
- `Tracks` 解析只留在 `PlayerManager` 的三行胶水，不把 media3 类型往 domain 拉，遵守 `check-layer-deps`。
- 现有 `BrowserFactory.browseClient/indexClient` 分工与 WebDAV 凭证单例耦合**一律不动**（`BrowserFactoryTest` 的 `assertSame` 是不变量守卫）。

### 4.3 时序

```
RadioService.onCreate → initializePlayer()
  ├─ ExoPlayer.Builder … .build()
  ├─ player.trackSelectionParameters = buildUpon().setTrackTypeDisabled(TRACK_TYPE_VIDEO, true).build()   ← 默认不解码视频
  └─ videoPlayerMount.attach(player)

用户进入播放页且 hasVideoTrack == true
  └─ VideoStage 组成
       ├─ DisposableEffect: playerManager.setVideoStageMounted(true)
       │     └─ VideoTrackGate.shouldDecodeVideo(true) == true → mount.player 解禁视频轨
       └─ playerView.setPlayer(mount.playerFlow.value)   ← 绑的是 ExoPlayer，不是 MediaController

离开页面 / 切到无视频内容
  └─ DisposableEffect onDispose: playerView.setPlayer(null) + setVideoStageMounted(false) → 重新禁用视频轨，音频继续
```

---

## 5. 内核详细设计

### 5.1 `service/VideoPlayerMount.kt`

```kotlin
@Singleton
class VideoPlayerMount @Inject constructor() {
    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val playerFlow: StateFlow<ExoPlayer?> = _player

    /** 由 RadioService 在播放器建好后调用。仅主线程读写。 */
    fun attach(player: ExoPlayer) { _player.value = player }

    /** 摘除要认实例：旧 Service 实例销毁时不得把新实例摘空。 */
    fun detach(player: ExoPlayer) { if (_player.value === player) _player.value = null }
}
```

- 用 `StateFlow` 而不是普通字段：冷启动直接进视频页时 `RadioService` 可能尚未创建，`VideoStage` 需要能在播放器出现后自动补绑。
- `RadioService.onDestroy()` 里在 `player.release()` **之前**调 `detach(exoPlayer)`。

### 5.2 `domain/model/VideoTrackGate.kt`（纯函数，红线所在）

```kotlin
object VideoTrackGate {
    /** 默认态：没有画面挂载就不该解码视频。 */
    const val DEFAULT_VIDEO_TRACK_DISABLED = true

    /** 唯一的闸门决策。stageMounted=false 时恒为 false —— 这条是回归守卫的对象。 */
    fun shouldDecodeVideo(stageMounted: Boolean): Boolean = stageMounted
}
```

### 5.3 `PlayerManager` 的增量（主路线只加两样，播控逻辑零改动）

```kotlin
private val _hasVideoTrack = MutableStateFlow(false)
val hasVideoTrack: StateFlow<Boolean> = _hasVideoTrack

/** VideoStage 挂载/卸载时调用。幂等：重复 true / 重复 false 不产生副作用。 */
fun setVideoStageMounted(mounted: Boolean)
```

- `setupListeners()` 里已有的 `Player.Listener` 增加 `onTracksChanged(tracks)` → 遍历 `tracks.groups` 取 `type == C.TRACK_TYPE_VIDEO && trackCount > 0` 写 `_hasVideoTrack`。胶水薄、不做 JVM 单测，**由真机 V2/V3 断言覆盖（本项在 JVM 层未验证，须标未验证）**。
- `setVideoStageMounted` 内 `scope.launch`（已是 `Dispatchers.Main`）对 `videoPlayerMount.playerFlow.value` 写 `trackSelectionParameters`。`ExoPlayer.setTrackSelectionParameters` 会校验 application thread，此处即主线程，成立。
- **闸门写入失败只记 `Log.e`，不写 `_error`**：`_error` 是本页唯一的红色情绪通道，正常起播不该红框一闪（既有踩坑记录）。
- 换条目（`playQueueSource` / `startOperaOnController`）**不重置闸门**：画面在挂载期间换源，应保持解禁；只在 `setVideoStageMounted(false)` 时收。
- 全应用没有第二处写 `trackSelectionParameters`，直连设置不会与 controller 缓存打架（会话会向 controller 广播 `onTrackSelectionParametersChanged`）。
- 若 §5.4 实测后走兜底路线，本类再加第三样 `probeVideoTrackOnce()`：临时解禁 → 等 `onTracksChanged`（上限 400ms）→ 写 `hasVideoTrack` → 无视频则立即禁回。主路线下不引入该方法。

### 5.4 关键未证前提与其兜底（必须先看的结果）

本设计假设：**视频轨被禁用后，`ExoPlayer.getCurrentTracks()` 仍然上报该视频组**（以 `isSupported()=false` / 未选中形态）。这样 `hasVideoTrack` 才能在闸门关闭时判出「有画面可用」。

- 计划阶段第一个任务就是在真机/模拟器上打这条日志验证。
- **若不成立**，会撞上循环依赖：`VideoStage` 要按 `hasVideoTrack` 决定挂不挂，而兜底路线又必须先挂面才能读到视频轨。因此兜底路线改为**由页面触发探测，而不是由 `hasVideoTrack` 触发挂载**：
  1. 进入 `PlayerScreen` / `OperaPlayerScreen` 首屏 composition 即调 `PlayerManager.probeVideoTrackOnce()`；
  2. 该方法临时解禁视频轨 → 等 `onTracksChanged`（上限 400ms）→ 写 `hasVideoTrack` → **若为 false 立即禁回**；
  3. `VideoStage` 仍按 `if (hasVideoTrack)` 分支挂载，与非兜底路线写法完全一致。
  代价：每次进播放页多一次 ≤400ms 的探测窗口；列表页、后台、通知栏路径一律不探测，常态零解码不变。
- 两条路线的实现差异只在「谁触发探测」，`VideoStage`、闸门、UI 分支均同形，因此可以在计划里先按主路线写，探测结论出来后只改一处触发点。
- 判定依据与最终采用的路线写进实现计划，不许口头带过。

#### 5.4.1 实测记录（2026-11-02，模拟器 Pixel_9a / media3 1.5.1）

**结论：前提成立，采用主路线，不实现 `probeVideoTrackOnce()`。**

先纠一个错的常量记忆：从设备直接打印 `C.TRACK_TYPE_*` 得 **VIDEO=2、AUDIO=1、TEXT=3、METADATA=5、IMAGE=4、UNKNOWN=-1**（不是 ExoPlayer 2.x 时代那套）。代码按符号引用所受影响，但任何拿数字读日志的地方都要按这套读。

正例（人间卫视，`streamlock.net/bltvhd/bltv1`）—— 在视频轨处于禁用态时仍上报了视频组：

```
17:06:58.505 D VideoProbe: groups=type=2 n=1 supported=true;type=1 n=1 supported=true;
                        type=3 n=1 supported=true;type=5 n=1 supported=true disabled=[2]
```

`type=2` 即视频组且 `supported=true`，而 `disabled=[2]` 说明视频轨类型确实被禁用 —— 两件事同时成立，
所以 `hasVideoTrack` 可以在不开帷幕、不解码的前提下判出“这流有画面可用”。

反例（北京卫视，`satellitepull.cnr.cn/live/wxbtv`）—— 全程无 `type=2`，与 Python 字节探测一致：

```
17:06:09.701 D VideoProbe: groups=type=1 n=1 supported=true;type=5 n=1 supported=true disabled=[2]
```

一正一反说明判据不是假阳；同时验证了 Task 3 的默认禁用真的落到了播放器（`disabled` 里含 2）。
日志原件：`.superpowers/sdd/.../probe-beijingws.log`、`probe-rentian.log`（ workspace 产物，不入库）。

### 5.5 `ui/common/VideoStage.kt`

```kotlin
@Composable
fun VideoStage(
    playerManager: PlayerManager,
    mount: VideoPlayerMount,
    modifier: Modifier = Modifier,
)
```

- 内部 `AndroidView { PlayerView(ctx).apply { setUseController(false); setResizeMode(C.RESIZE_MODE_FIT); setShutterBackgroundColor(android.graphics.Color.TRANSPARENT) } }`。`RESIZE_MODE_FIT` 自带黑边适配，不拉伸、不变形；`setShutterBackgroundColor` 收的是 `@ColorInt Int`，不能传 Compose 的 `Color`。
- **不使用** `setKeepContentOnPlayerReset`（该 API 在 1.5 的可用性不作为设计依赖）；「最后一帧残留」由 §6.3 的 Compose 遮罩层解决。
- `DisposableEffect(Unit)`：进入 → `playerManager.setVideoStageMounted(true)` + `playerView.setPlayer(...)`；离开 → `setPlayer(null)` + `setVideoStageMounted(false)`。
- 订阅 `mount.playerFlow`，播放器晚到也能补绑；`playerFlow` 为 null 时不绑（画面区仍占位，避免布局跳动）。
- 播放中给窗口加 `FLAG_KEEP_SCREEN_ON`（`LocalView` 拿 window），离开时清。
- **单 mountedView 语义**：不做引用计数。同一时刻只允许一个 Stage 绑面；页面转场交叉淡入时后来者顶掉前者（前者 `onDispose` 若发现自己已不是当前持有者，则不做 `setPlayer(null)`）。`PlayerManager` 内记 `mountedView` 引用即可。
- **手机档无视频时零回退的实现约束**：调用点必须写 `if (hasVideoTrack) VideoStage(...) else CoverBlock(...)`，即**用分支控制是否进入组合树**；禁止"进去了但 alpha=0 / size=0"，那会带进 insets 与间距，像素就变了。

---

## 6. 分期设计

### 6.1 B 期：云盘视频文件点播

**播放路径零新增**：仍走 `PlayerManager.playOperaFile()` → `startOperaOnController()`。断点续播（`preferences.getOperaPlayPosition`）、倍速（`OperaSpeed`）、循环（`OperaRepeatMode`）、歌词封面 sidecar（`BrowserSidecarSource`）全部继承。

**画面落点**：`OperaPlayerScreen` 现有骨架是 `Column { info(); contentArea(Modifier.weight(1f)); slider(); controls() }`。

- `hasVideoTrack == true` 时，`contentArea` 的位置由 `VideoStage` 常驻占据。
- 封面/歌词改为从 `info()` 行右侧一个「歌词」芯片唤出 `ModalBottomSheet`，sheet 内复用现有 `CoverPage` / `LyricPage` 的 `HorizontalPager`（两者都无内容时不显示该芯片）。
- `slider()` 与 `controls()` 那一行**一律不动**，不新增第二行控件 —— 守「播放页控件必须保持单行不可拆多行」。

**列表徽标**：`VideoMedia.looksLikeVideo(name)` 判，命中就在副标题位给一枚「视频」芯片。**改动点两处（均为云盘路径）**：

- `ui/library/BrowseScreen.kt` —— 紧跟现有 `if (item.isHlsStream) { Text("HLS 直播流") }` 那枚芯片之后，沿用同款样式（`labelSmall`），不新造控件形状
- `ui/library/SearchResultsList.kt` —— `SearchResultRow` 内渲染 `crumb` 的那行 `Text` 之后，同一 `Column` 内

> 原列的第三处 `ui/audio/AudioLocalTab.kt` 是**本地**页签，属 C 期路径，本轮改它无意义（本地视频扫描与权限都在 C 期），已移入 §10。

**`VideoMedia` 取值集合**（不含点、小写比较，按最后一段路径取扩展名，与 `PlayableTrack` 同一派生法）：

```
mp4 mkv mov webm flv avi mpg mpeg wmv m4v rm rmvb 3gp vob ts m2ts
```

- **明确不含 `m3u8`**：云盘里的 m3u8 可能是歌单/电台清单，歧义大，交给运行时 `hasVideoTrack`。
- 无扩展名一律不标（徽标宁少不多；`PlayableTrack` 那边仍保留它，不影响能放）。

**不做**：视频下载缓存。`data/local/DownloadedOperaDao.kt` 与 `downloadedPaths` 那条链不碰。

### 6.2 A 期：`PlayerScreen` 出画面能力

- 改动点：`ui/player/PlayerScreen.kt` 的 `CoverBlock` 调用处换成 `if (hasVideoTrack) VideoStage(...) else CoverBlock(...)`。
- 车机档沿用现有 `Row`（L167-182）左右并排结构，画面在 `Box(Modifier.weight(1f))` 里；台名、频率、简介、EPG 卡（`NowOnAirCard`）、节目源列表、播控行一律不动。
- 手机档：画面占据原封面位置，竖向顺序与间距不变。
- **不改 `radio_stations.json`、不改 `RadioStation` 模型、不动 Room 表。**
- 本期验收只要求"管道通"：能用人间卫视（若连通）或任一自建带画面流验证；61 个纯音频台必须表现与今天完全一致。

### 6.3 换源与无画面占位

`hasVideoTrack` 一旦在本次条目内为真过，画面区**保持占位**；若切到的新源没有视频轨，画面区上方盖一层 Compose 遮罩显示「该节目源无画面，正在听声」，**不 collapse**（避免整页布局跳）。

两种「换」要分清，别写成同一条：

| 情形 | 触发 | 画面区表现 |
|---|---|---|
| 同一个台换节目源（`playQueueSource` 轮转） | `onTracksChanged` 给新结论 | **保持占位** + 遮罩提示，不收起 |
| 换到另一个台 / 换到另一个唱段（`playStation` / `playOperaFile`） | 新条目 prepare 后 `onTracksChanged` | 若新内容确无视频轨，按 `if (hasVideoTrack)` 分支**正常收起**，让位给封面 —— 这是切内容的合理布局变化，不算抖动 |
| `stopOpera` / 播放彻底停止 | — | 画面区与遮罩一起消失 |

---

## 7. 形态与几何红线

| 红线 | 约束 | 把关手段 |
|---|---|---|
| 手机档零回退 | 无视频内容时渲染结果与改造前逐像素一致 | uiautomator bounds 对齐现有基线 + 截图比对 |
| 车机首屏装得下播控 | 1280x720 恒横屏加画面后，`controls` 行 `bounds.maxY ≤ 720` | uiautomator bounds 断言 |
| 不给画面固定高度 | 画面一律靠 `weight` 拿空间（已知坑：整块撑 `TouchMin` 会把播控顶出首屏） | 代码审查 + bounds |
| 尺寸取值 | `VideoStage` 只准引用 `Dimens` token，圆角取 `Dimens.GapLarge`（与 `CoverPage` 一致），禁裸 dp | 代码审查 |
| 旋转 | `MainActivity` 无 `configChanges`，靠 `DisposableEffect` 卸载重挂，音频不得断 | 真机 V5 |
| 迷你条 | `MiniPlayerBar` / 音频库迷你条**不显示画面**，一期排除 | 不在改动清单 |

---

## 8. 错误处理

| 情形 | 处理 |
|---|---|
| 进页面时 `mount.playerFlow.value == null` | 画面区占位但不绑面；播放器晚到自动补绑。不报红、不弹提示 |
| 视频轨解禁失败 / 写参数异常 | `Log.e` 留痕，走只听声，不写 `_error`（红色是唯一情绪信号） |
| 硬解失败（`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` 等） | 沿用现有点播错误分支显示文案，不新增通道 |
| 有视频轨但画面全黑 | 属于真机缺陷，由 V2 的两帧差分断言拦截，不允许"看着像通过" |
| 视频播放中收到 TTS 播报 | 沿用 `announceThen` 现有暂停逻辑，不为其加特例（播报结束照常恢复，画面不受影响） |

---

## 9. 测试与验收

### 9.1 JVM 单测（`app/src/test`，现有 47 个文件同级新增）

| 文件 | 断言 |
|---|---|
| `VideoMediaTest.kt` | 各视频扩展名命中；`m3u8` 不命中；无扩展名不标；带路径的 PROPFIND href 只认最后一段；大小写混写；空串/纯空格 |
| `VideoTrackGateTest.kt` | **`shouldDecodeVideo(false) == false`（红线守卫）**；`shouldDecodeVideo(true) == true`；`DEFAULT_VIDEO_TRACK_DISABLED == true` |

- 两个被测对象必须是纯 Kotlin、零 Android/media3 import，否则 JVM 层跑不起来（AGP 单测环境下 media3 AAR 类不可靠）。
- 不许破的既有不变式：`BrowserFactoryTest`（`assertSame` 凭证单例）、`OperaSpeedTest`（电台恒 1.0）、`PlayableTrackTest`。

### 9.2 真机 / 模拟器验收（PowerShell + uiautomator，脚本须含冷启动就绪轮询与权限预授权）

| # | 场景 | 量化判据 |
|---|---|---|
| V1 | 手机档播一个纯音频台 | 播放页节点数与 bounds 与 v2.1.13 基线一致，**0 个新增可见节点** |
| V2 | 云盘放一个 mp4，进点播页 | 出现画面；画面区中心**两帧差分**非静止（不用单阈值）；`controls` 行仍完整可点 |
| V3 | V2 之后退出点播页 | 音频继续；日志断言 `trackSelectionParameters` 中 VIDEO 轨道重新 disabled |
| V4 | 视频播放中按方控下一台 | 不抢丢音频焦点；通知栏仍只一条；切台后画面区随 `hasVideoTrack` 收起 |
| V5 | 播放中旋转一次 | 播放不中断，画面重挂成功，无黑屏残留 |
| V6 | 车机档 1280x720 | `controls` 行 `bounds.maxY ≤ 720`；画面区宽高比无拉伸变形（截图复核） |
| V7 | R8 混淆 release 包 | V2/V3/V6 重跑通过（发布前回归规范） |

> 计划阶段第 1 个任务必须先完成 §5.4 那条未证前提的实测，结论写进实现计划再往下做。

---

## 10. 后续路线（不在本 spec 实现，此处只定准入条件）

- **C 期 · 本地视频文件**：需新增 `READ_MEDIA_VIDEO`（Android 13+）权限与运行时申请；`domain/browser/LocalBrowser.kt` 要按 `VideoMedia` 放行视频扩展名；列表徽标补 `ui/audio/AudioLocalTab.kt`（本轮从 B 期挑出的那一处）；验收复用 V2/V6。
- **D 期 · 新视频内容源**：受「电台音频源仅收录官方公开流」版权红线约束，收录前必须逐源核实官方出处与公开可得性；数据模型预计需要 `RadioStation` 上的媒体种类字段（本期刻意不加）。**A 期价值缺口（当下仅 1 台可看）必须由 D 期内容补齐**，这是本设计已知的、被如实记录下来的短板。
- 内核落地后，C/D 接入画面应为**零内核改动**（只需列表能列出来、点进播放路径）。若做不到，说明内核设计有缺陷，须停下返工而不是绕过。

---

## 11. 交付

- 文档：本文件（`doc/specs/2026-11-02-video-playback-kernel-ab-design.md`）；实现计划随后落 `doc/plans/`。
- 构建与交付按项目既有规范：EGG 自测包先行，release 走凭证守卫，APK 命名与 SHA-256 校验沿用现状。
- Git：spec 单独一次提交（Conventional Commit 英文），不与实现代码混提。
