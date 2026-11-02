# 视频全屏与沉浸控制 · 设计文档

- 日期：2026-11-03
- 范围：给已上线的视频画面加**全屏 + 沉浸系统栏 + 点画面唤出控制层**。不含画中画、不含手势层、不含画面内播控。
- 前置：`doc/specs/2026-11-02-video-playback-kernel-ab-design.md`（视频内核 + B + A，已实现并合入 main `aa346ac`）
- 状态：设计已获用户批准（§1 机制、§2 边界与验收），本文档待用户审阅 → 通过后调 writing-plans

---

## 1. 需求与已批决策

用户原话：「有视频画面，就要有视频的常见控制，比如全屏，旋转等等。加上」，随后逐项确认：

| # | 议题 | 用户决定 |
|---|---|---|
| F1 | 控制集 | **只要全屏 + 沉浸系统栏**。明确不要：画面内播控（暂停/进度/±10）、手势层（音量/亮度/双击/滑动） |
| F2 | 旋转语义 | **全屏就是横屏**：点全屏自动转横屏并撑满，退出恢复。不给独立旋转按钮 |
| F3 | 画中画 | **本期不做**（沿用 11-01 的 D2 排除项） |
| F4 | 技术方案 | **方案①：给 `MainActivity` 加 `configChanges`，全屏期间不重建 Activity**（先经 spike 实测才定，见 §2） |
| F5 | 全屏层的按钮 | **只放「退出全屏」一个按钮**，点画面唤出、3 秒自动收起。全屏态拿不到页面下方那排电台播控，用户已接受 |
| F6 | 车机档 | **不给“进入全屏”的入口**，也不动系统栏，画面区维持今天的样子。注意它只约束“进入”：一旦已经在全屏态（大屏手机强制横屏后形态可能跳成车机档），**退出入口必须始终存在**，否则用户出不来（见 §4.1） |

### 推翻的前置非目标

上一份 spec §1 的非目标表里写着「画中画 / 小窗 / 悬浮窗 一期不做」和「全屏沉浸手势 一期不需要」。F1/F2 把**全屏与沉浸**从非目标里取了出来；**画中画仍然是非目标**（F3）。

---

## 2. 现状事实与 spike 实测

### 2.1 现状（已核实）

- 全 App **零方向/沉浸处理**：没有 `requestedOrientation`、没有 `WindowInsetsController`、不隐藏系统栏。
- `MainActivity` 原本**没有 `android:configChanges`** → 旋转即重建 Activity（11-02 的 V5 已实测确认，音频不断但 Compose 树整体重建）。
- 形态判定 `UiSurface` 的自动规则是 **`smallestScreenWidthDp ≥ 600` 且横屏** → 强制横屏可能让大屏手机跳成车机档，这是本设计必须处理的风险，不是实现细节。
- `ui/common/VideoStage.kt` 的契约：只负责画面，尺寸**完全由外部 `modifier` 决定**，内部不写固定宽高；挂载时 `acquireStage` + `setVideoStageMounted(true)` 开闸，卸载时 `releaseStage` + `setVideoStageMounted(false)` 收闸。

### 2.2 spike 实测（2026-11-03，模拟器 Pixel_9a，一次性代码已撤净）

在 `MainActivity.onCreate` 与 `MainScreen` 埋日志，用 adb 强制旋转对比两种清单配置：

| 测项 | 不加 configChanges | 加 configChanges |
|---|---|---|
| 旋转后 `MainActivity.onCreate` | **触发**（`savedInstanceState=true`），组合实例 id 更换 | **不触发**，组合实例 id 不变 |
| `LocalConfiguration` 是否更新 | 更新 | **照样更新**（`orientation=2 h=411`） |
| `isCarSurface()` 在 `sw=720` 横屏 | 跳 `true` | **跳 `true`**（`orientation=2 sw=720 car=true`） |
| `sw=720` 竖屏 | `car=false` | `car=false` |

结论：**方案① 唯一的疑点（不重建时配置不更新）被实测否掉** —— 不重建也能正确重算几何与形态。附带发现：`wm density` 变更不在声明范围内，密度变化仍会重建（与全屏无关，不需要处理）。

spike 过程中还抓到两次会污染结论的事故，记录以免重犯：一次是 `gradlew` 因 `R.jar` 被占用而构建失败，但 `adb install` 仍报 Success，装的是上一版 APK —— 那轮"B 组数据"全部作废；另一次是验收脚本把滑动坐标写死 `y=1400`，在 720 高的车机屏上滑不动，误报成"找不到目标"。

---

## 3. 架构

### 3.1 单元与职责

| 单元 | 位置 | 做什么 | 依赖 |
|---|---|---|---|
| `VideoControlsPolicy` | `domain/model/`（新增，**纯 Kotlin**） | 唯一决策：该不该给全屏按钮、控制层多久自动收起 | 无 |
| `VideoFullScreen` | `ui/common/`（新增） | 全屏状态的持有者与系统命令的执行者（方向 + 系统栏 + 返回键拦截） | `LocalContext`、androidx.core |
| `VideoStageFrame` | `ui/common/`（新增） | 画面 + 控制层外壳：点画面唤出、3 秒收起、右上角按钮 | `VideoStage`、`VideoFullScreen`、`VideoControlsPolicy` |
| `VideoStage` | `ui/common/`（**不改**） | 只管画面与绑面 | 不变 |
| `PlayerScreen` / `OperaPlayerScreen` | `ui/player/`（改） | 接入 frame；全屏时关掉页面自己的 chrome | — |
| `MainActivity` | 清单（改） | 加 `configChanges` | — |

依赖方向：`domain/model` 不 import Android/media3（保持 JVM 可单测，与 `VideoTrackGate`、`VideoMedia` 同规矩）；`ui/common` 可以 import Android 框架。

### 3.2 核心机制：全屏不换子树

**这是本设计最重要的一条，也是它自己纠正过的地方。**

直觉做法是 `if (isFullScreen) 全屏层 else Scaffold{原页面}`。错 —— 那样 `VideoStage` 会被销毁重建，触发 `releaseStage → 收闸`（视频轨禁用、解码器拆除）再 `acquireStage → 开闸`，用户就看到**进出全屏闪一下黑**。这恰好把方案①花力气消灭的重建代价，从布局写法里又请了回来。

正确做法：**`VideoStage` 始终待在同一个组合位置**（就是现在 `contentArea` / `mediaVisual` 那一格），全屏只做三件事：

1. `Scaffold` 的 `topBar` 传空、`contentWindowInsets` 归零；
2. 画面那一格的 `modifier` 从 `weight(1f)` / `aspectRatio` 换成 `fillMaxSize()`；
3. 兄弟区块（信息区、进度条、播控行、节目源区）用 `if (!isFullScreen)` 跳过渲染。

**跳过兄弟节点不会重建留下来的那个节点**，所以画面实例连续、闸门不往返。全屏态因此不是"另一个页面"，而是"同一个页面把 chrome 关掉、画面撑满"。

### 3.3 时序

```
用户点画面          → controlsVisible = true（3 秒计时启动）
用户点「全屏」      → isFullScreen = true
  LaunchedEffect(isFullScreen) →
      requestedOrientation = SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      insetsController.hide(systemBars()); behavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
  同一帧：topBar 传空、insets 归零、画面 modifier 变 fillMaxSize、兄弟区块跳过渲染
  （VideoStage 实例不变 → 不收闸、不开闸、不闪黑）
用户点「退出全屏」/ 按返回键 → isFullScreen = false → 上述系统命令反向复位
用户离开播放页      → DisposableEffect onDispose 无条件复位（不看当前状态）
```

---

## 4. 详细设计

### 4.1 `domain/model/VideoControlsPolicy.kt`（纯函数）

```kotlin
object VideoControlsPolicy {
    /**
     * 全屏按钮该不该出现。
     *
     * 已在全屏 → 恒为 true。这不是凑数分支：大屏手机竖屏时不是车机档、有按钮，
     * 点下去强制横屏后 `sw≥600 且横屏` 会让形态判定**跳成车机档** —— 如果这里还按
     * 「车机档不给按钮」走，退出全屏的按钮会自己消失，用户只能靠系统返回键出来。
     * 所以 F6 的「车机档不做全屏」只约束**进入**，不约束**退出**。
     */
    fun showsFullScreenButton(
        isCarSurface: Boolean,
        hasVideoTrack: Boolean,
        isFullScreen: Boolean,
    ): Boolean = isFullScreen || (!isCarSurface && hasVideoTrack)

    /** 控制层自动收起延时。 */
    const val CONTROLS_AUTO_HIDE_MS: Long = 3000L
}
```

单测钉住：车机档非全屏 false、无视频轨 false、手机档有画面非全屏 true、**车机档但已在全屏 true**（最后这条就是上面那个漏洞的守卫）。

### 4.2 `ui/common/VideoFullScreen.kt`

```kotlin
class VideoFullScreenState(
    val isFullScreen: Boolean,
    val toggle: () -> Unit,
)

@Composable
fun rememberVideoFullScreen(): VideoFullScreenState
```

- 状态用 `rememberSaveable`，系统命令由 `LaunchedEffect(isFullScreen)` 统一执行 —— 这样万一发生重建（例如密度变化），标志位活下来，命令会被重放，不会出现"标志说在全屏、系统栏却是显示的"。
- `toggle()` 只改标志，不直接调系统 API。
- `LocalContext.current as? Activity` 拿不到 Activity 时（预览/嵌入场景）：`toggle()` 不改状态并记 `Log.w`，**不崩、不报错**。
- `BackHandler(enabled = state.isFullScreen) { 退出全屏 }` —— **返回键先退全屏，不退页面**；再按一次才离开播放页。
- `DisposableEffect(Unit) { onDispose { 无条件复位方向与系统栏 } }`，不看当前是否全屏。
- 退出全屏用 `SCREEN_ORIENTATION_UNSPECIFIED`（**跟随系统**），不是硬锁竖屏：用户原本竖屏就回竖屏，原本手动横屏就留在横屏。这是对 F2「退出恢复」的实现选择，比强行转竖屏更不打扰。

### 4.3 `ui/common/VideoStageFrame.kt`

```kotlin
@Composable
fun VideoStageFrame(
    playerManager: PlayerManager,
    fullScreen: VideoFullScreenState,
    modifier: Modifier = Modifier,
)
```

- 内部 `Box(modifier)`：`VideoStage(playerManager, Modifier.matchParentSize())` + 条件控制层。
- 点画面唤出/收起：`pointerInput` 的 `detectTapGestures`。**安装条件与控制层同一判据**（`showsFullScreenButton`）：手机档非全屏装（维持可唤出）；**车机档非全屏不装**，画面区不吞点击，今天的行为（滚动、页面手势）一律不变；车机档但已在全屏则**必须装** —— 否则按钮 3 秒自动收起后再也唤不回来，只剩系统返回键这一条路。
- 控制层只有一个按钮，位置 `Alignment.TopEnd`，内容随状态切换「全屏」/「退出全屏」；`contentDescription` 与文案一致，供 uiautomator 断言。
- 命中区按项目规范：手机档没有 `Dimens.TouchMin` 就不算达标；而**车机档只在已在全屏时出现这一层**（退出入口），此时按钮同样拿满 `Dimens.TouchMin`（88dp）。注意不是“车机档永不出现”——那会把用户锁在全屏里（见 §4.1）。
- 自动收起：`LaunchedEffect(controlsVisible, fullScreen.isFullScreen)` 延时 `VideoControlsPolicy.CONTROLS_AUTO_HIDE_MS`；全屏态下**画面正在动时也要收起**，按钮不常驻。
- 是否渲染控制层由 `VideoControlsPolicy.showsFullScreenButton(isCarSurface(), hasVideoTrack, fullScreen.isFullScreen)` 决定；`hasVideoTrack` 从 `playerManager` 读，**不在本组件里猜 URL**。

### 4.4 两个播放页的接入

**`OperaPlayerScreen`**（点播页）

- 顶部 `val fullScreen = rememberVideoFullScreen()`。
- `Scaffold` 的 `topBar` 类型是 `@Composable () -> Unit`，**不能传 null**：全屏时传空 lambda `{}`；`contentWindowInsets` 是一个 `WindowInsets` **值**（不是 lambda），全屏时传 `WindowInsets(0)`，否则传 `NestedWindowInsets`。
- 主体 `Column`：`if (!isFullScreen) info()`；`contentArea(如果全屏则 Modifier.fillMaxSize() 否则 Modifier.weight(1f))`；`if (!isFullScreen) { slider(); controls() }`。
- `contentArea` 的有画面分支从 `VideoStage(...)` 改为 `VideoStageFrame(playerManager, fullScreen, m)`。
- 全屏时「歌词」芯片不可用（`info()` 整块跳过），退出全屏即恢复 —— 这是 F5 已接受的取舍。

**`PlayerScreen`**（电台页）

- 同一套：`mediaVisual` 里的 `VideoStage(...)` 换成 `VideoStageFrame(...)`，`aspectRatio(16f/9f)` 只用于**非全屏**态；全屏态改 `fillMaxSize()`。
- 全屏时跳过：台名/简介/标签芯片、EPG 卡（`NowOnAirCard`）、节目源列表、播控行、收藏与定时按钮（它们在 `topBar` 里，随 topBar 一起消失）。
- 页面 `Column` 的 `verticalScroll` 在全屏时必须摘掉（滚动容器里给 `fillMaxSize` 是无界高度约束）。

### 4.5 `AndroidManifest.xml`

```xml
android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"
```

- **故意不含 `density`**：实测密度变更仍会重建，这是合理行为，不需要为它兜状态。
- 这是全局改动：所有页面从此在旋转时不重建。因此 §5 的重跑清单不是可选项。

---

## 5. 测试与验收

### 5.1 JVM 单测

| 文件 | 断言 |
|---|---|
| `VideoControlsPolicyTest.kt`（新增） | 车机档非全屏 → false；无视频轨 → false；手机档有画面非全屏 → true；**车机档但已在全屏 → true**；`CONTROLS_AUTO_HIDE_MS == 3000L` |

`VideoFullScreen` 与 `VideoStageFrame` 是 Android/Compose 组件，**JVM 层无法单测（须标未验证）**，由下面 V8–V11 真机断言覆盖。

### 5.2 真机验收（沿用 `tools/video_acceptance.ps1` 的取样手法：冷启动轮询、权限预授权、飞行模式检查、取 mtime 最新的 APK）

| # | 场景 | 量化判据 |
|---|---|---|
| **V8** | 手机档进全屏再退出 | `VideoGate` 日志**不得出现 `decode=false` 再 `decode=true` 的往返** —— 这是 §3.2「不换子树」是否真生效的硬证据；同时画面区两帧差分或截图证明仍在渲染 |
| **V9** | 全屏态按系统返回键 | 第一次只退全屏、仍在播放页；第二次才离开页面 |
| **V10** | 全屏态直接离开播放页（返回两次/切到别的页） | 方向恢复跟随系统、系统栏重新可见；`VideoGate` 末条为 `decode=false` |
| **V11** | 大屏机模拟：1280x720@160（`sw=720`）竖屏下进全屏 | ① 竖屏时 `isCarSurface()=false`，全屏按钮存在；② 点全屏后形态判定跳成 `car=true`，**退出按钮仍必须存在且可点**（§4.1 那条 `isFullScreen ||` 分支的守卫）；③ 全屏层内按钮按车机档命中区放大后不撑破布局 |
| **V12** | R8 混淆包复跑 | V8 / V9 / V10 全过 |
| **重跑** | 因 `configChanges` 是全局改动 | V1（手机档纯音频台零像素回退）、V2（画面出画）、V3（退出收闸）、V6（车机播控 `bounds.maxY ≤ 720`）全部不许漂 |

---

## 6. 错误处理与降级

| 情形 | 处理 |
|---|---|
| `hide(systemBars())` 在个别 ROM 无效 | 静默降级为"只撑满不沉浸"，不报错、不提示。画面可用即达标 |
| `LocalContext` 不是 Activity | `toggle()` 空操作 + `Log.w`，不崩 |
| 用户系统开了自动旋转锁 | `requestedOrientation` 是 App 层请求，优先于系统锁 → 全屏仍能横屏；由 V8 顺带确认 |
| 全屏期间发生重建（密度变化等） | `rememberSaveable` 保住标志位，`LaunchedEffect` 重放系统命令 |
| 退出全屏后方向"没回竖" | 设计如此：回的是"跟随系统"，用户手动横屏时不该被强行掰回 |

---

## 7. 非目标（本期明确不做）

- 画中画 / 小窗 / 悬浮窗（F3）
- 手势层：滑动调音量亮度、双击暂停、左右滑快进快退（F1）
- 画面内播控：暂停、进度条、±10 秒（F1，避免与页面下方既有播控重复）
- 独立的手动旋转按钮（F2）
- 字幕渲染、多音轨选择、投屏
- 车机档的全屏与沉浸（F6）

---

## 8. 交付

- 文档：本文件；实现计划随后落 `doc/plans/2026-11-03-video-fullscreen-controls-plan.md`。
- 构建：EGG 自测包先行，release 走项目凭证守卫，APK 命名与 SHA-256 沿用现状。
- Git：spec 单独一次提交（Conventional Commit 英文），不与实现代码混提；不 push、不 merge 到别的分支，除非用户明确要求。
