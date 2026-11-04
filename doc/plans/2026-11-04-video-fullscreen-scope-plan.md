# 视频全屏范围修正（第一批）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让全屏入口在手机与车机两种形态都可用（车机"全屏"= 画面独占面板），补上旋转按钮与控制层常驻，并防住"全屏期间形态判定翻转"导致的入口消失与方向卡死。

**Architecture:** 判定继续集中在纯函数 `VideoControlsPolicy`（新增 `showsRotateButton` 与 `nextOrientation`，删除 `showsFullScreenButton` 的 `isCarSurface` 参数与 `CONTROLS_AUTO_HIDE_MS`）；`rememberVideoFullScreen(canRotate)` 只把**进入全屏的方向请求**受 `canRotate` 控制，**退出与离开页面的方向复位无条件执行**；`VideoStageFrame` 的控制层改为常驻（不再定时收起）并增加旋转按钮；两个播放页在全屏时跳过整条形态分支、由画面独占。

**Tech Stack:** Kotlin 2.1.0 / Compose BOM 2024.12.01 / Media3 1.5.1（零新增依赖）/ androidx.activity `BackHandler` / JUnit 4.13.2 / PowerShell + uiautomator 真机验收。

**Spec:** `doc/specs/2026-11-04-video-gestures-and-fullscreen-scope-design.md`（只做 §0 的第一批；§7 手势层属第二批，本计划不含）

## Global Constraints

- **只做第一批**：不实现手势层的音量/亮度/双击/水平 seek（§7 明确留给第二批）。
- 零新增 Gradle 依赖。
- `domain/model/` 新增/修改**禁止 import Android 与 media3**；因此旋转目标用本地枚举 `VideoOrientation` 表达，`ActivityInfo` 常量只出现在 `ui/common/`。
- 全屏入口：`showsFullScreenButton` **删掉 `isCarSurface` 参数**（留着就会有人把条件加回去）。
- `CONTROLS_AUTO_HIDE_MS` **删除**，同步删掉 `VideoControlsPolicyTest` 里那条 `assertEquals(3000L, ...)` 断言。
- **退出全屏与离开页面的方向复位不得受 `canRotate` 控制**（B5）：`SCREEN_ORIENTATION_UNSPECIFIED` 对恒横屏设备是无害空操作。
- 控制层**不放应用内返回按钮**（B6）：返回交给系统返回键，上一轮 V9 已实现。
- 命中区一律走 `Dimens` token（手机 `TouchMin`=48dp、车机 `TouchMin`=88dp），禁裸 dp。
- **非全屏路径的 modifier 链与子项顺序必须与改造前逐字一致**（手机档零像素回退，V1 硬红线）。
- 不许破坏既有不变式：`VideoTrackGateTest`（默认关闸、页面意图语义）、`VideoControlsPolicyTest` 的 `showsPictureSurface`/`showsStationDetails`（上一轮 I1/C1 的守卫）、`BrowserFactoryTest`、`OperaSpeedTest`。
- 安装 APK 只取 debug 目录内 mtime 最新的产物并打印时间戳；构建失败时 `adb install` 仍会报 Success 装旧包（本轮踩过）。
- 提交用 Conventional Commit 英文标题，每任务只提自己的文件。

## Review Focus

spec 暗示但测试不会天然覆盖、真人一定撞上的五条，逐条落到任务步骤：

1. **车机形态下没有全屏入口** —— 这正是用户报的 bug 本体（Task 1 单测 + Task 6 F6）。
2. **全屏期间形态判定翻转**：入口消失（Task 1 单测守住）+ 方向不复位把人永久卡在横屏（Task 2 的无条件复位 + Task 6 F8）。
3. **控制层常驻后，手机非全屏时从画面下滑还能不能翻页** —— 单击检测不应吃掉滚动，但必须实测（Task 6 F11）。
4. **旋转按钮出现在错误的地方**：车机恒横屏给了就是"按了没反应"，手机非全屏给了会跟系统旋转打架（Task 1 单测 + Task 6 F10）。
5. **断流/切到纯音频台时退出入口消失**（上一轮 I1 的修复不得回退）—— Task 2 保留 `showsPictureSurface` 语义，Task 5 F12 复验。

---

## 执行顺序修正（计划自审发现，以本节为准）

原划分按“模块”分任务，没按“能不能编译过”分。实际依赖环是：
`VideoControlsPolicy` → `VideoStageFrame`（唯一消费者）→ `VideoFullScreenState` → 两个播放页。
照原顺序，改完策略参数与删常量后 `VideoStageFrame` 编译不过，**那一步的“跑测试”根本执行不到** ——
因为 `testDebugUnitTest` 依赖主源编译。

按下面的顺序做，每个任务结束时自己就是可编译、可跑测的。**任务小节标题已按新编号命名，但正文在文件里仍按原模块顺序排列**（Task 2 的正文在 Task 1 之前）：执行时以本表顺序为准，不要从上往下顺着做。

| 编号 | 内容 |
|---|---|
| **Task 1** | `VideoFullScreen` 加 `canRotate: Boolean = true`（带默认值，调用点不动就能编译），加 `isLandscape`/`rotate`，方向复位改为无条件 |
| **Task 2** | `VideoControlsPolicy`（改参数/新增旋转策略/删常量）**与 `VideoStageFrame` 的改动合并为同一提交**（两者必须同时生效才编译得过） |
| **Task 3** | `PlayerScreen` 传 `canRotate` + 全屏独占面板 |
| **Task 4** | `OperaPlayerScreen` 传 `canRotate` 并核对 contentArea |
| **Task 5** | 真机验收 F6–F12 + 基线重跑 + R8 |

两个具体调整：

1. `rememberVideoFullScreen(canRotate: Boolean)` 带默认值 `= true`（“能转”），使 Task 1 不依赖任何调用点改动即可编译；播放页在 Task 3/4 里显式传 `isCarSurface().not()`。
2. 原计划里“先做另一个任务的步骤再回来跑本步”的绕法作废 —— 策略与它的唯一消费者当一个提交，不需要绕。

---

### Task 2: VideoControlsPolicy 修订 + VideoStageFrame 控制层（同一提交）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/domain/model/VideoControlsPolicy.kt`
- Modify: `app/src/test/java/com/radio/chinese/VideoControlsPolicyTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `VideoControlsPolicy.showsFullScreenButton(hasVideoTrack: Boolean, isFullScreen: Boolean): Boolean`（**参数由 3 个减为 2 个**）
  - `VideoControlsPolicy.showsRotateButton(isCarSurface: Boolean, isFullScreen: Boolean): Boolean`
  - `VideoControlsPolicy.nextOrientation(currentLandscape: Boolean): VideoOrientation`
  - `VideoControlsPolicy.showsStationDetails(isFullScreen: Boolean): Boolean`（不变）
  - `VideoControlsPolicy.showsPictureSurface(hasVideoTrack: Boolean, isFullScreen: Boolean): Boolean`（不变）
  - `enum class VideoOrientation { Landscape, Portrait }`（同文件）
  - **删除** `CONTROLS_AUTO_HIDE_MS`

- [ ] **Step 1: 先改测试（RED）**

在 `VideoControlsPolicyTest.kt` 中：

① 把三处 `showsFullScreenButton(isCarSurface = ..., hasVideoTrack = ..., isFullScreen = ...)` 的具名实参改为两参数形式；② 删除 `控制层收起延时是 3 秒` 整个测试；③ 追加下面三条：

```kotlin
    /**
     * 本轮回归守卫：车机形态也必须给全屏入口。
     *
     * 用户报的 bug 就是这里 —— 上一版把 `!isCarSurface` 写进条件，
     * 而「界面模式=车机」「sw≥600 且横着拿」「车机恒横屏」三种情况都会判成 Car，
     * 于是手机上按了画面什么入口都没有。车机上「全屏」的含义是画面独占面板。
     */
    @Test
    fun `车机形态也必须给全屏入口`() {
        assertTrue(VideoControlsPolicy.showsFullScreenButton(hasVideoTrack = true, isFullScreen = false))
        assertTrue(VideoControlsPolicy.showsFullScreenButton(hasVideoTrack = true, isFullScreen = true))
    }

    @Test
    fun `旋转按钮只在手机全屏态给`() {
        // 车机恒横屏：给了就是"按了没反应"，本项目明令避免给了控件却不响应
        assertFalse(VideoControlsPolicy.showsRotateButton(isCarSurface = true, isFullScreen = true))
        assertFalse(VideoControlsPolicy.showsRotateButton(isCarSurface = true, isFullScreen = false))
        // 手机非全屏：方向该由系统与用户自己决定，应用不插队
        assertFalse(VideoControlsPolicy.showsRotateButton(isCarSurface = false, isFullScreen = false))
        // 手机全屏：横看竖看由用户挑
        assertTrue(VideoControlsPolicy.showsRotateButton(isCarSurface = false, isFullScreen = true))
    }

    @Test
    fun `旋转按钮在当前横屏时切竖屏，当前竖屏时切横屏`() {
        assertEquals(VideoOrientation.Portrait, VideoControlsPolicy.nextOrientation(currentLandscape = true))
        assertEquals(VideoOrientation.Landscape, VideoControlsPolicy.nextOrientation(currentLandscape = false))
    }
```

同时补 import：`import com.radio.chinese.domain.model.VideoOrientation`。

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.VideoControlsPolicyTest"`
Expected: 编译失败，报 `Too many arguments for showsFullScreenButton`、`Unresolved reference: showsRotateButton` / `nextOrientation` / `VideoOrientation`

- [ ] **Step 3: 改实现**

`VideoControlsPolicy.kt` 整份替换为：

```kotlin
package com.radio.chinese.domain.model

/** 旋转按钮要切到的目标方向。枚举而非 ActivityInfo 常量：domain 层不得 import Android。 */
enum class VideoOrientation { Landscape, Portrait }

/**
 * 视频控制层的决策，纯 Kotlin（不 import Android/media3），因此能在 JVM 测试集里跑。
 * 与 `VideoTrackGate`、`VideoMedia` 同一手法：判定集中在这里，落地是薄胶水。
 */
object VideoControlsPolicy {

    /**
     * 全屏入口该不该出现。
     *
     * 只看"有没有画面"和"是不是已经在全屏"，**不再看形态**：上一版加了 `!isCarSurface`，
     * 而「界面模式=车机」「sw≥600 且横屏」「车机恒横屏」都会判成 Car，
     * 于是入口在手机上也会凭空消失。车机上「全屏」的含义是画面独占整块面板。
     *
     * `isFullScreen` 优先：已在全屏就必须给退出入口 —— 大屏手机点全屏后强制横屏，
     * 形态判定会当场翻转，若条件里有任何形态判断，按钮会在用户眼前消失。
     */
    fun showsFullScreenButton(hasVideoTrack: Boolean, isFullScreen: Boolean): Boolean =
        isFullScreen || hasVideoTrack

    /**
     * 旋转按钮该不该出现：只在「手机形态 + 已在全屏」时给。
     *
     * 车机恒横屏，按了不会有任何变化 —— 那是"给了控件却不响应"，本项目的反模式。
     * 手机非全屏时方向归系统与用户自己，应用不插队。
     */
    fun showsRotateButton(isCarSurface: Boolean, isFullScreen: Boolean): Boolean =
        !isCarSurface && isFullScreen

    /** 旋转按钮按下后切到哪个方向：横竖互切。 */
    fun nextOrientation(currentLandscape: Boolean): VideoOrientation =
        if (currentLandscape) VideoOrientation.Portrait else VideoOrientation.Landscape

    /**
     * 页面细节区块（台名/频率/简介/标签/EPG/节目源/播控）要不要渲染。
     *
     * 全屏时必须不渲染：车机档非全屏布局走 Row 左右分栏，留着台名块就吃掉一半屏宽，
     * 画面只占左半边 —— 用户点了「全屏」结果看到半屏画面半屏台名，像应用坏了。
     */
    fun showsStationDetails(isFullScreen: Boolean): Boolean = !isFullScreen

    /**
     * 画面层要不要渲染。
     *
     * `isFullScreen` 优先：全屏时即使视频轨消失（直播断流、或方控切到纯音频台），
     * 画面层也必须还在 —— 退出入口就长在这一层上。层掉了人就锁在横屏沉浸态里。
     */
    fun showsPictureSurface(hasVideoTrack: Boolean, isFullScreen: Boolean): Boolean =
        isFullScreen || hasVideoTrack
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.VideoControlsPolicyTest"`
Expected: PASS（5 个用例）。若 `VideoStageFrame.kt` 编译失败（它还在传三个参数），属预期 —— 那是 Task 3 的范围；本步只需测试文件自身可编译。若 Gradle 因主源文件编译失败而跑不起来，先做 Task 3 的 Step 2 再回来跑本步，并在台账记一行顺序调整原因。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/VideoControlsPolicy.kt app/src/test/java/com/radio/chinese/VideoControlsPolicyTest.kt
git commit -m "feat(domain): give the fullscreen entry on every surface and add rotate policy"
```

---

### Task 1: VideoFullScreen 接受 canRotate 并把方向复位改成无条件

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/common/VideoFullScreen.kt`

**Interfaces:**
- Consumes: `VideoControlsPolicy.nextOrientation(currentLandscape)`、`VideoOrientation`（Task 1）
- Produces:
  - `VideoFullScreenState(isFullScreen: Boolean, isLandscape: Boolean, toggle: () -> Unit, rotate: () -> Unit)`
  - `@Composable fun rememberVideoFullScreen(canRotate: Boolean): VideoFullScreenState`

- [ ] **Step 1: 整份替换实现**

```kotlin
package com.radio.chinese.ui.common

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.radio.chinese.domain.model.VideoControlsPolicy
import com.radio.chinese.domain.model.VideoOrientation

/**
 * 视频全屏态的持有者，也是全应用唯一会改屏幕方向与系统栏的地方。
 *
 * @param canRotate 这台设备现在是不是"手机形态"。它**只约束进入全屏时的方向请求**：
 *   车机恒横屏，发横屏请求是无意义操作。
 *   退出全屏与离开页面的方向复位**不受它约束**（B5）：`isCarSurface()` 会在全屏期间
 *   翻转（sw≥600 的手机一点全屏就横屏、判定当场变 Car），若复位也看它，
 *   这次请求发了、下次跳过，用户就被永久卡在横屏里。
 *   UNSPECIFIED 对恒横屏设备是无害的空操作，所以复位永远执行。
 *
 * 其余三条硬规矩不变：
 * 0. 进入全屏一律横屏（除非用户按过旋转按钮）；退出全屏一律交还系统。
 * 1. 状态用 `rememberSaveable`，命令由 `LaunchedEffect` 统一执行 —— 万一发生重建
 *    （密度变化不在 configChanges 里，实测仍重建），标志位活下来、命令被重放。
 * 2. 返回键先退全屏、不退页面。
 * 3. 控制层里不放应用内返回按钮，返回交给系统返回键（B6）。
 */
class VideoFullScreenState(
    val isFullScreen: Boolean,
    val isLandscape: Boolean,
    val toggle: () -> Unit,
    val rotate: () -> Unit,
)

@Composable
fun rememberVideoFullScreen(canRotate: Boolean): VideoFullScreenState {
    val context = LocalContext.current
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var rotateOverride by rememberSaveable { mutableStateOf<VideoOrientation?>(null) }

    val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(fullScreen, rotateOverride, canRotate) {
        val activity = context as? android.app.Activity
        if (activity == null) {
            android.util.Log.w("VideoFullScreen", "上下文不是 Activity，全屏命令跳过")
            return@LaunchedEffect
        }
        val controller: WindowInsetsControllerCompat =
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullScreen) {
            // 进入全屏：只有可转设备才发方向请求；用户按过旋转按钮时尊重他的选择
            if (canRotate) {
                // 进入全屏一律横屏；用户按过旋转按钮时尊重他的选择
                val target = rotateOverride ?: VideoOrientation.Landscape
                activity.requestedOrientation = when (target) {
                    VideoOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    VideoOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            // 退出全屏：不受 canRotate 约束，永远复位
            rotateOverride = null
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 离开页面必须复位，不管当前是不是全屏、也不管 canRotate 现在是什么
    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? android.app.Activity ?: return@onDispose
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = fullScreen) { fullScreen = false }

    return remember(fullScreen, isLandscape) {
        VideoFullScreenState(
            isFullScreen = fullScreen,
            isLandscape = isLandscape,
            toggle = { fullScreen = !fullScreen },
            // 旋转按钮只在全屏态出现，此处已是全屏；切到与当前相反的一侧
            rotate = {
                rotateOverride = VideoControlsPolicy.nextOrientation(currentLandscape = isLandscape)
            },
        )
    }
}
```

- [ ] **Step 2: 编译（预期会因调用点报错）**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: 报 `No value passed for parameter canRotate`（两个播放页还没传参）与 `Unresolved reference: CONTROLS_AUTO_HIDE_MS`（`VideoStageFrame`）。这两条分别由 Task 4/5 与 Task 3 消除，**本任务不单独提交绿的状态**：Task 3 编译过后再一并提交本任务文件。

- [ ] **Step 3: 提交（与 Task 3 一起编译通过后再提）**

```bash
git add app/src/main/java/com/radio/chinese/ui/common/VideoFullScreen.kt
git commit -m "feat(ui): gate fullscreen orientation request and always restore it on exit"
```

---

### Task 3: VideoStageFrame 控制层常驻 + 旋转按钮

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/common/VideoStageFrame.kt`

**Interfaces:**
- Consumes: `showsFullScreenButton(hasVideoTrack, isFullScreen)`、`showsRotateButton(isCarSurface, isFullScreen)`（Task 1）、`VideoFullScreenState.rotate`（Task 2）
- Produces: `VideoStageFrame(playerManager, fullScreen, modifier)`（签名不变）

- [ ] **Step 1: 换判据、删自动收起**

把 `interactive` 的计算与自动收起整块替换为：

```kotlin
    val hasVideo by playerManager.hasVideoTrack.collectAsState()
    val interactive = VideoControlsPolicy.showsFullScreenButton(
        hasVideoTrack = hasVideo,
        isFullScreen = fullScreen.isFullScreen,
    )
    val showRotate = VideoControlsPolicy.showsRotateButton(
        isCarSurface = isCarSurface(),
        isFullScreen = fullScreen.isFullScreen,
    )

    // 控制层常驻（G5）：上一版 3 秒自动收起，而"点画面才能唤出"没有任何提示，
    // 不知道要点的人看到的就是"这个 App 没有全屏功能"。改为默认显示、点画面切换。
    var controlsVisible by remember { mutableStateOf(true) }
```

并**删除**原 `LaunchedEffect(controlsVisible, fullScreen.isFullScreen, interactive) { ... delay(...) ... }` 整块与 `import kotlinx.coroutines.delay`。

- [ ] **Step 2: 加旋转按钮**

在 `if (controlsVisible) {` 块内、全屏按钮**之前**插入：

```kotlin
            if (showRotate) {
                IconButton(
                    onClick = {
                        fullScreen.rotate()
                        controlsVisible = true
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = -Dimens.TouchMin)   // 与全屏按钮并排，不重叠
                        .size(Dimens.TouchMin),
                ) {
                    Icon(
                        imageVector = Icons.Default.ScreenRotation,
                        contentDescription = "切换横竖屏",
                    )
                }
            }
```

补 import：`androidx.compose.foundation.layout.offset`、`androidx.compose.material.icons.filled.ScreenRotation`。

- [ ] **Step 3: 更新组件文档注释**

把类头注释里"车机档非全屏时完全不装……按钮 3 秒收起"那段改成与新判据一致：入口只看有没有画面；旋转按钮只在手机全屏给（入参是 `canRotateAtEntry`，不是当前形态，否则大屏手机一进全屏按钮就消失）；控制层不再定时收起。

**执行时更正**：本步原要求写“单击不抢页面滚动（由验收 F11 实测守住，不靠推理）”—— 那句已删除。F11 本轮**未能证实这一条**（本页在 1080x2424 不溢出，且旧信号用的“文本集合变化”会被缓冲/换源类状态文案污染），注释已改为显式标“未证实 + 第二批开工前必须先定论”。不得把未验证的东西写成“实测守住”。

- [ ] **Step 4: 编译 + 全量单测**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: SUCCESS（Task 1/2/3 的编译错误到此应全部消除）

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全绿，且 `VideoControlsPolicyTest` 5 条通过

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/common/VideoStageFrame.kt
git commit -m "feat(ui): keep video controls visible and add rotate entry"
```

---

### Task 3: 电台页接入（车机独占面板）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/player/PlayerScreen.kt`

**Interfaces:**
- Consumes: `rememberVideoFullScreen(canRotate)`（Task 2）、`showsStationDetails`（Task 1）
- Produces: 无

- [ ] **Step 1: 传 canRotate**

把 `val fullScreen = rememberVideoFullScreen()` 改为：

```kotlin
    // 车机恒横屏：canRotate=false，进入全屏只独占面板、不发方向请求；
    // 但退出与离开的方向复位在组件内部是无条件的，不依赖这个值。
    val fullScreen = rememberVideoFullScreen(canRotate = isCarSurface().not())
```

- [ ] **Step 2: 全屏时跳过整条形态分支**

在 `Column { ... }` 内，把现有的 `if (isCarSurface()) { Row {...} } else { ... }` 外面加一层全屏分支：

```kotlin
                if (fullScreen.isFullScreen) {
                    // 全屏独占：不走形态分支。车机上这一步让画面从"Row 左半 45%"变成整块面板。
                    // 画面层会被移出原组合位置、AndroidView 重建，但闸门由页面意图决定（上一轮
                    // Amendment），不会因此收闸拆解码器 —— 最多重绑一次面，不闪黑。
                    VideoStageFrame(
                        playerManager = playerManager,
                        fullScreen = fullScreen,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (isCarSurface()) {
                    /* 原车机 Row 分支，一字不改 */
                } else {
                    /* 原手机竖屏分支，一字不改 */
                }
```

**非全屏的两条分支必须逐字保留**（V1 零像素回退红线）。

- [ ] **Step 3: 细节区块判据不变**

确认已有的 `if (VideoControlsPolicy.showsStationDetails(fullScreen.isFullScreen))` 与 `if (!fullScreen.isFullScreen) { ...细节区块... }` 仍生效；全屏时台名/EPG/节目源/播控全部不渲染。

- [ ] **Step 4: 编译 + 全量单测**

Run: `.\gradlew.bat :app:assembleDebug`
Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 均成功

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/player/PlayerScreen.kt
git commit -m "feat(ui): let the station player fill the panel when fullscreen"
```

---

### Task 4: 点播页接入

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/player/OperaPlayerScreen.kt`

**Interfaces:**
- Consumes: `rememberVideoFullScreen(canRotate)`（Task 2）
- Produces: 无

- [ ] **Step 1: 传 canRotate**

```kotlin
    val fullScreen = rememberVideoFullScreen(canRotate = isCarSurface().not())
```

- [ ] **Step 2: contentArea 在全屏时撑满**

`contentArea` 已经是 `contentArea(Modifier.weight(1f))` 调用；全屏时 `info()/slider()/controls()` 已被跳过，画面拿 `weight(1f)` 即可占满整列。确认 `contentArea` 内部走 `showsPictureSurface(hasVideo, fullScreen.isFullScreen)`，把有画面那一支的 modifier 保持不变（`m`），**不要**在这里再套 `fillMaxSize()`：`weight(1f)` 在无兄弟时已经等于独占，再加 `fillMaxSize` 会在滚动容器里拿到无界高度（上一轮真机实测画面塌成 0 的同类坑）。

- [ ] **Step 3: 编译 + 全量单测**

Run: `.\gradlew.bat :app:assembleDebug`
Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 均成功

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/player/OperaPlayerScreen.kt
git commit -m "feat(ui): align opera player fullscreen with the surface-aware gate"
```

---

### Task 5: 真机验收 F6–F12 + 基线重跑

**Files:**
- Modify: `tools/video_acceptance.ps1`

**Interfaces:**
- Consumes: 前五个任务全部产出
- Produces: F6–F12 与 V1/V2/V3/V6 的量化结论

- [ ] **Step 1: 起设备并装包（含新鲜度校验）**

```powershell
.\gradlew.bat :app:assembleDebug
$apk = Get-ChildItem app\build\outputs\apk\debug -Filter *.apk | Sort-Object LastWriteTime -Descending | Select-Object -First 1
"apk=$($apk.Name) mtime=$($apk.LastWriteTime)"   # 必须打印：构建失败时 install 仍会报 Success 装旧包
adb install -r $apk.FullName
if ((adb shell settings get global airplane_mode_on).Trim() -eq "1") { adb shell cmd connectivity airplane-mode disable }
adb shell svc wifi enable; Start-Sleep -Seconds 6
adb shell pm grant com.radio.chinese.debug android.permission.POST_NOTIFICATIONS
```

- [ ] **Step 2: F6 车机形态必须有全屏入口**

```powershell
adb shell wm density 160; adb shell wm size 1280x720; adb shell settings put system user_rotation 0
```

进「人间卫视」播放页 → 点画面 → `uiautomator` 里必须出现 `content-desc="全屏"` 的节点。
**上一轮正是在这里查不到任何节点，这条就是用户报的 bug 本体。**

- [ ] **Step 3: F7 车机全屏=独占面板**

点全屏后断言：台名/简介/播控节点数 0；画面尺寸**用截图量**（不用节点 bounds，本项目已实测 bounds 会裁切失真）：非背景像素行跨度 ≥ 屏高 85%。

- [ ] **Step 4: F8/F9/F10**

- F8：手机竖屏点全屏 → 截图宽高互换 + 系统栏隐藏；`dumpsys window` 里 `requestedOrientation` 应为 `SCREEN_ORIENTATION_LANDSCAPE`；按返回键先退全屏。
- F9：进入视频页后等 12 秒（> 原 3 秒阈值），`content-desc="全屏"` 节点**仍在**（证明控制层不再自动收起）。
- F10：手机全屏态存在 `content-desc="切换横竖屏"`，按下后截图宽高互换且仍为全屏态；车机全屏态该节点**不存在**。

- [ ] **Step 5: F11 手机非全屏仍能从画面下滑翻页（B2 的基线守卫）**

手机几何、非全屏态：记录当前可见文本集合 → 在画面区域内 `input swipe` 向下滑 → 再取可见文本集合。断言两次集合**不同**（页面确实滚动了）。
这条同时是第二批装拖动手势前必须先立住的对照：第二批做完后它必须仍然成立，因为那时拖动只在全屏生效。

- [ ] **Step 6: F12 断流时退出入口不丢（I1 不回退）**

全屏播放中把网络关掉（`adb shell svc wifi disable`），等状态转为「所有节目源均无法播放」后点画面，断言 `content-desc="退出全屏"` 仍在；恢复网络。

- [ ] **Step 7: 重跑基线**

```powershell
.\tools\video_acceptance.ps1 -OnlyV 1
.\tools\video_acceptance.ps1 -OnlyV 2
.\tools\video_acceptance.ps1 -OnlyV 3
.\tools\video_acceptance.ps1 -OnlyV 6
```

Expected：四项全 PASS。V1 尤其关键 —— 非全屏 modifier 链若被改毛，这里就会红。

- [ ] **Step 8: R8 复跑（F6/F8/F9/F10/F11）**

```powershell
.\gradlew.bat :app:assembleRelease -PallowEggInRelease=true
```

装 `*-release-EGG.apk`（包名无 `.debug`，脚本传 `-Pkg com.radio.chinese`），复跑 F6/F8/F9/F10/F11。判定以 `mapping.txt` 为准。

- [ ] **Step 9: 全量单测 + 提交**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全绿

```bash
git add tools/video_acceptance.ps1
git commit -m "test(ui): verify fullscreen entry on every surface and rotate controls"
```

---

## 计划级自查

**1. Spec 覆盖**：§2 G1→Task 1/3/4；G3→Task 1/2/3/4；G5→Task 3；G6→Task 2/4/5；§3.1→Task 1；§3.2（B5）→Task 2；§3.3（B4）→Task 2 Step 3 + Task 3 Step 2；§3.4（B6）→Task 3；§3.5→Task 4/5；§4→Task 2（非 Activity、复位无条件）+ Task 3（判据）；§5.1→Task 1；§5.2 F6–F12→Task 6；§6 非目标→无任务（正确）。§7 手势层**故意不覆盖**（第二批）。

**2. 占位符**：Task 4 Step 2 的 `/* 原车机 Row 分支，一字不改 */` 是对既有代码的引用而非留白，且 V1 重跑会抓住任何改动；其余无 TBD/TODO。

**3. 类型一致性**：`showsFullScreenButton(hasVideoTrack, isFullScreen)` 在 Task 1 定义、Task 3 消费一致；`showsRotateButton(isCarSurface, isFullScreen)` 一致；`VideoOrientation`/`nextOrientation(currentLandscape)` 在 Task 1 定义、Task 2 消费一致；`VideoFullScreenState(isFullScreen, isLandscape, toggle, rotate)` 在 Task 2 定义、Task 3 消费一致；`rememberVideoFullScreen(canRotate)` 在 Task 2 定义、Task 4/5 消费一致。

**4. Review Focus**：五条分别由 Task 1（1、2 前半、4）、Task 2（2 后半）、Task 6 F11（3）、Task 1/6 F10（4）、Task 3+6 F12（5）覆盖。

**5. 已知的执行顺序坑**：Task 1 Step 4 与 Task 2 Step 2 都会遇到"主源文件还没改完导致编译失败"，计划里已写明如何跨过并在台账记录，不允许为了跑绿而临时改测试断言。

**6. 自查修掉的**：Task 2 原先写 `nextOrientation(currentLandscape = !isLandscape)` 再用 Step 3 改成 `?: VideoOrientation.Landscape` —— 计划不该留"先写错再改"的台阶（实现者会照抄第一行），已直接写成最终形态并把多余步骤删掉、后续步骤重新编号。
