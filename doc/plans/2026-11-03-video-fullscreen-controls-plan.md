# 视频全屏与沉浸控制 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给已有的视频画面加上「全屏（自动横屏）+ 沉浸系统栏 + 点画面唤出控制层」，并保证进出全屏不闪黑、离开页面必复位方向与系统栏。

**Architecture:** 全屏状态由页面级 `rememberVideoFullScreen()` 持有，系统命令（`requestedOrientation` + `WindowInsetsController`）集中在一个 `LaunchedEffect` 里执行，返回键用 `BackHandler` 先退全屏。画面外壳 `VideoStageFrame` 包住现有 `VideoStage`，只加一个全屏按钮与点按唤出手势。**关键修正（见下方 Amendment）**：视频轨闸门的所有权从「哪个 View 挂着」上移到「页面要不要画面」，因为 `PlayerScreen` 的画面格嵌在 `isCarSurface()` 分支里，旋转时分支必换，靠"保持节点同一"根本保证不了不闪。

**Tech Stack:** Kotlin 2.1.0 / Compose BOM 2024.12.01 / Media3 1.5.1（已有，零新增依赖）/ androidx.core `WindowCompat` / androidx.activity `BackHandler` / JUnit 4.13.2 / PowerShell + uiautomator 真机验收。

**Spec:** `doc/specs/2026-11-03-video-fullscreen-controls-design.md`（本计划实现它，除 §Amendment 一处有意的偏离）

---

## Amendment（计划阶段发现的 spec 缺陷，须用户确认）

**spec §3.2 说**：「全屏不换子树 —— `VideoStage` 始终待在同一个组合位置，所以画面实例连续、闸门不往返」。

**做不到的地方**：`PlayerScreen` 的画面格位于

```kotlin
if (isCarSurface()) { Row { Box(Modifier.weight(1f)) { mediaVisual(coverSize) } ; StationInfoBlock(...) } }
else { mediaVisual(coverSize) ; Spacer(...) ; StationInfoBlock(...) }
```

而本特性的核心动作就是**强制横屏**。大屏手机（`sw≥600`）竖屏时 `isCarSurface()=false`，点全屏转横屏后判定翻成 `true` → **分支切换 → 整棵子树换位置 → `AndroidView` 必然销毁重建**。spec 想用"不换子树"保证的东西，恰好在最需要它的设备上保不住。

**本计划的修法**：把闸门的判据从 **View 挂载** 改为 **页面意图**：

| | 原（已上线的内核） | 本计划 |
|---|---|---|
| 谁开关闸 | `VideoStage` 的 `DisposableEffect`（View 生命周期） | 播放页的 `LaunchedEffect(hasVideoTrack)` + `onDispose`（页面生命周期） |
| API | `PlayerManager.setVideoStageMounted(mounted)` | `PlayerManager.setVideoPictureWanted(wanted)` |
| View 所有权 | 同上，混在一起 | 仍留在 `VideoStage`（`acquireStage`/`releaseStage` + `setPlayer`），只管绑面不管闸 |

这样布局爱怎么换子树都行：只要页面还开着且 `hasVideoTrack` 为真，闸门就一直开着，View 重建只是重新绑一次面，**不会拆解码器**。V8 的判据也随之变强：进出全屏期间 `VideoGate` 不该出现任何新日志（因为 wanted 从未变化）。

**代价若错**：如果页面忘记 `onDispose` 收 wanted，后台会持续解码视频（发热耗电）—— 所以 Task 3 把这条做成"漏了就编译不过/测不出"的形状，Task 7 用 V3 与 V10 双向钉。

---

## Global Constraints

- **零新增 Gradle 依赖**：`WindowCompat` 来自已有 `androidx.core:core-ktx`，`BackHandler` 来自已有 `androidx.activity:activity-compose`，图标来自已有 `material-icons-extended`。
- `domain/model/` 新增文件**禁止 import Android 与 media3**（必须 JVM 可单测）。
- 新 UI 代码只准引用 `Dimens` token，禁止裸 dp 字面量（`PaddingValues()` 默认零值，不用 `0.dp`）。
- **非全屏路径的 modifier 链必须与改造前逐字节一致** —— 手机档零像素回退是硬红线（V1）。做法：只在 `if (isFullScreen)` 分支里给不同的 modifier，`else` 分支原样保留。
- 播放页播控保持**单行不可拆多行**；全屏层不新增第二行控件。
- 车机档**不提供"进入全屏"的入口**，但**已在全屏时必须能退出**（spec F6 + §4.1）。
- `AndroidManifest` 的 `configChanges` **不含 `density`**（实测密度变化仍重建是合理行为，不要为它兜状态）。
- 不许破坏既有不变式：`BrowserFactoryTest`（WebDAV 凭证 `assertSame`）、`OperaSpeedTest`（电台恒 1.0）、`VideoTrackGateTest`（默认关闸）。
- 测试命令用 `.\gradlew.bat ...`；安装 APK 只取 debug 目录内 **mtime 最新**的产物并打印时间戳（历史坑：构建失败但 `adb install` 仍报 Success，装的是旧包）。
- 提交用 Conventional Commit 英文标题，每任务只提自己的文件。

## Review Focus

spec 未写测试、但真人一定撞上的六条，逐条落到任务的步骤里：

1. **大屏手机点全屏后形态跳成车机档** —— 退出按钮绝不能消失，否则用户被锁在全屏里（Task 1 单测 + Task 7 V11）。
2. **离开播放页时方向/系统栏没复位** —— 用户带着横屏且无状态栏回到列表页（Task 3 的 `onDispose` + Task 7 V10）。
3. **全屏态按返回键直接退出页面** —— 用户以为返回键是退全屏（Task 3 的 `BackHandler` + Task 7 V9）。
4. **控制层不自动收起** —— 按钮常驻盖住画面（Task 4 的 `LaunchedEffect` + Task 7 V8 截图）。
5. **改造把非全屏路径的 modifier 链改毛** —— 手机档像素回退，肉眼难查（Task 5/6 要求 `else` 分支原样保留 + Task 7 重跑 V1）。
6. **闸门从 View 迁到页面时漏一条收闸路径** —— 后台持续解码视频（Task 2 改语义 + Task 5/6 两页各自 `onDispose` + Task 7 重跑 V3）。

---

### Task 1: VideoControlsPolicy（纯函数 + 单测）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/domain/model/VideoControlsPolicy.kt`
- Test: `app/src/test/java/com/radio/chinese/VideoControlsPolicyTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `VideoControlsPolicy.showsFullScreenButton(isCarSurface: Boolean, hasVideoTrack: Boolean, isFullScreen: Boolean): Boolean`、`VideoControlsPolicy.CONTROLS_AUTO_HIDE_MS: Long`

- [ ] **Step 1: 写失败的测试**

新建 `app/src/test/java/com/radio/chinese/VideoControlsPolicyTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.VideoControlsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全屏按钮该不该出现、控制层多久收起。
 *
 * 最要紧的是最后那条「车机档但已在全屏 → true」：大屏手机竖屏时不是车机档、有按钮，
 * 点下去强制横屏后 sw≥600 且横屏 会让形态判定跳成车机档。如果那时还按
 * 「车机档不给按钮」走，退出全屏的按钮会自己消失，用户只能靠系统返回键出来。
 */
class VideoControlsPolicyTest {

    @Test
    fun `车机档非全屏不给进入入口`() {
        assertFalse(
            VideoControlsPolicy.showsFullScreenButton(isCarSurface = true, hasVideoTrack = true, isFullScreen = false)
        )
    }

    @Test
    fun `没有视频轨时任何形态都不给按钮`() {
        assertFalse(
            VideoControlsPolicy.showsFullScreenButton(isCarSurface = false, hasVideoTrack = false, isFullScreen = false)
        )
    }

    @Test
    fun `手机档有画面才给按钮`() {
        assertTrue(
            VideoControlsPolicy.showsFullScreenButton(isCarSurface = false, hasVideoTrack = true, isFullScreen = false)
        )
    }

    @Test
    fun `已在全屏就必须给退出入口，即使形态跳成了车机档`() {
        assertTrue(
            VideoControlsPolicy.showsFullScreenButton(isCarSurface = true, hasVideoTrack = true, isFullScreen = true)
        )
        assertTrue(
            VideoControlsPolicy.showsFullScreenButton(isCarSurface = false, hasVideoTrack = true, isFullScreen = true)
        )
    }

    @Test
    fun `控制层收起延时是 3 秒`() {
        assertEquals(3000L, VideoControlsPolicy.CONTROLS_AUTO_HIDE_MS)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.VideoControlsPolicyTest"`
Expected: 编译失败，`Unresolved reference: VideoControlsPolicy`

- [ ] **Step 3: 写最小实现**

新建 `app/src/main/java/com/radio/chinese/domain/model/VideoControlsPolicy.kt`：

```kotlin
package com.radio.chinese.domain.model

/**
 * 视频控制层的决策，纯 Kotlin（不 import Android/media3），因此能在 JVM 测试集里跑。
 * 与 `VideoTrackGate`、`VideoMedia` 同一手法：判定集中在这里，落地是薄胶水。
 */
object VideoControlsPolicy {

    /**
     * 全屏按钮该不该出现。
     *
     * `isFullScreen` 优先：已在全屏就必须给退出入口。这条不是凑数分支 ——
     * 大屏手机点全屏后强制横屏，形态判定会跳成车机档，若按「车机档不给按钮」
     * 处理，按钮会自己消失，用户被锁在全屏里。
     * 所以车机档那条规则只约束**进入**，不约束**退出**。
     */
    fun showsFullScreenButton(
        isCarSurface: Boolean,
        hasVideoTrack: Boolean,
        isFullScreen: Boolean,
    ): Boolean = isFullScreen || (!isCarSurface && hasVideoTrack)

    /** 控制层自动收起延时（毫秒）。 */
    const val CONTROLS_AUTO_HIDE_MS: Long = 3000L
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.VideoControlsPolicyTest"`
Expected: PASS（5 个用例全绿）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/VideoControlsPolicy.kt app/src/test/java/com/radio/chinese/VideoControlsPolicyTest.kt
git commit -m "feat(domain): add video controls visibility policy with fullscreen exit guard"
```

---

### Task 2: 闸门所有权从 View 上移到页面

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/domain/model/VideoTrackGate.kt`
- Modify: `app/src/test/java/com/radio/chinese/VideoTrackGateTest.kt`
- Modify: `app/src/main/java/com/radio/chinese/service/PlayerManager.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/common/VideoStage.kt`

**Interfaces:**
- Consumes: `VideoTrackGate.shouldDecodeVideo(...)`（既有）
- Produces:
  - `VideoTrackGate.shouldDecodeVideo(pictureWanted: Boolean): Boolean`（**参数改名**）
  - `PlayerManager.setVideoPictureWanted(wanted: Boolean)`（**取代** `setVideoStageMounted`）
  - `PlayerManager.videoPictureWanted: StateFlow<Boolean>`（**取代** `videoStageMounted`）

- [ ] **Step 1: 先改测试（RED）**

把 `app/src/test/java/com/radio/chinese/VideoTrackGateTest.kt` 里三处 `stageMounted =` 具名实参改为 `pictureWanted =`，并在文件末尾追加一条把语义钉住的用例：

```kotlin
    /**
     * 闸门的输入是「页面要不要画面」，不是「哪个 View 正挂着面」。
     *
     * 这条区分很重要：PlayerScreen 的画面格嵌在 isCarSurface() 分支里，强制横屏会让
     * 分支换位置、AndroidView 重建。若闸门跟着 View 生命周期走，一进全屏就收闸再开闸，
     * 解码器被拆建，用户看到闪黑。跟着页面意图走就完全不受布局影响。
     */
    @Test
    fun `闸门输入是页面意图而非视图挂载`() {
        assertFalse(VideoTrackGate.shouldDecodeVideo(pictureWanted = false))
        assertTrue(VideoTrackGate.shouldDecodeVideo(pictureWanted = true))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.VideoTrackGateTest"`
Expected: 编译失败，`Unknown name 'pictureWanted'`（或 `No parameter with name 'pictureWanted' found`）

- [ ] **Step 3: 改纯函数签名**

`domain/model/VideoTrackGate.kt` 中把函数换成（注释同步改，`DEFAULT_VIDEO_TRACK_DISABLED` 与 `shouldReapplyGate` 不动）：

```kotlin
    /**
     * 唯一的闸门决策。输入是「页面要不要画面」，不是「View 挂没挂载」。
     *
     * `pictureWanted = false` 时恒为 false —— 这条是回归守卫的对象：
     * 一旦有人改成默认要画面，所有只听声的电台都要多解码一路视频，
     * 耗电、发热、起播耗时全变差，而单测与 UI 回归都看不出来。
     */
    fun shouldDecodeVideo(pictureWanted: Boolean): Boolean = pictureWanted
```

- [ ] **Step 4: PlayerManager 改名并换状态**

`service/PlayerManager.kt` 中把：

```kotlin
    /** 画面是否挂着。它单一决定视频轨要不要解码（经 [VideoTrackGate]）。 */
    private val _videoStageMounted = MutableStateFlow(false)
    val videoStageMounted: StateFlow<Boolean> = _videoStageMounted
```

换成：

```kotlin
    /**
     * 页面要不要画面。闸门只认它，不认 View 生命周期 ——
     * 因为 PlayerScreen 的画面格嵌在 isCarSurface() 分支里，旋转会让它重建，
     * 跟着 View 走就会在进出全屏时把解码器拆一遍（闪黑）。
     */
    private val _videoPictureWanted = MutableStateFlow(false)
    val videoPictureWanted: StateFlow<Boolean> = _videoPictureWanted
```

把 `setVideoStageMounted` 整个函数换成：

```kotlin
    /**
     * 播放页声明「我要看画面 / 我不要了」。幂等：重复值不产生副作用。
     *
     * 必须在页面离开时传 false，否则视频轨会一直解禁、后台持续解码。
     * 失败只记日志不写 `_error`：`_error` 是播放页唯一的红色情绪通道，
     * 正常起播不该红框一闪（既有踩坑记录）。
     */
    fun setVideoPictureWanted(wanted: Boolean) {
        val previous = _videoPictureWanted.value
        val replaced = videoPlayerMount.player !== lastGatedPlayer
        if (!VideoTrackGate.shouldReapplyGate(previous, wanted, replaced)) return
        _videoPictureWanted.value = wanted
        applyVideoTrackGate()
    }
```

`applyVideoTrackGate()` 内两处 `_videoStageMounted.value` 全换成 `_videoPictureWanted.value`（日志文案同步：`"pictureWanted=${_videoPictureWanted.value} decode=$decode"`）。

- [ ] **Step 5: VideoStage 不再开关闸**

`ui/common/VideoStage.kt` 中把 `DisposableEffect(playerView) { ... }` 块里的两行闸门调用删掉，只留 View 所有权：

```kotlin
    // 这里只管 View 所有权（谁绑面），不管视频轨闸门 —— 闸门归页面（见 PlayerManager.setVideoPictureWanted）
    if (playerView != null) {
        val pv = playerView
        DisposableEffect(pv) {
            mount.acquireStage(pv)
            onDispose {
                // 被后来的 Stage 顶掉时不得去 setPlayer(null)，否则会把新页面的面摘黑
                if (mount.releaseStage(pv)) {
                    pv.player = null
                }
            }
        }
    }
```

- [ ] **Step 6: 编译 + 全量单测**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL。若报 `Unresolved reference: setVideoStageMounted`，说明还有调用点没迁完 —— 逐个改成 `setVideoPictureWanted`（当前只有 `VideoStage.kt` 一处，本任务 Step 5 已处理）。

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全绿（`VideoTrackGateTest` 4 条 + 既有用例）

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/VideoTrackGate.kt app/src/test/java/com/radio/chinese/VideoTrackGateTest.kt app/src/main/java/com/radio/chinese/service/PlayerManager.kt app/src/main/java/com/radio/chinese/ui/common/VideoStage.kt
git commit -m "refactor(player): key the video track gate on page intent instead of view mount"
```

---

### Task 3: VideoFullScreen 状态与系统命令

**Files:**
- Create: `app/src/main/java/com/radio/chinese/ui/common/VideoFullScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml`（`MainActivity` 节点）

**Interfaces:**
- Consumes: 无
- Produces: `VideoFullScreenState(isFullScreen: Boolean, toggle: () -> Unit)`、`@Composable rememberVideoFullScreen(): VideoFullScreenState`

- [ ] **Step 1: 写组件**

新建 `app/src/main/java/com/radio/chinese/ui/common/VideoFullScreen.kt`：

```kotlin
package com.radio.chinese.ui.common

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 视频全屏态的持有者，也是全应用唯一会改屏幕方向与系统栏的地方。
 *
 * 三条硬规矩：
 * 1. 状态用 `rememberSaveable`，系统命令由 `LaunchedEffect(fullScreen)` 统一执行 ——
 *    万一发生重建（例如密度变化不在 configChanges 里，实测仍重建），标志位活下来，
 *    命令会被重放，不会出现「标志说在全屏、系统栏却是显示的」。
 * 2. 返回键先退全屏、不退页面。
 * 3. 离开组合时无条件复位方向与系统栏，不看当前状态 —— 否则用户带着横屏回到列表页。
 *
 * 退出全屏用 SCREEN_ORIENTATION_UNSPECIFIED（跟随系统），不硬锁竖屏：
 * 用户原本竖屏就回竖屏，原本手动横屏就留在横屏，不该被应用掰。
 */
class VideoFullScreenState(
    val isFullScreen: Boolean,
    val toggle: () -> Unit,
)

@Composable
fun rememberVideoFullScreen(): VideoFullScreenState {
    val context = LocalContext.current
    var fullScreen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(fullScreen) {
        val activity = context as? android.app.Activity
        if (activity == null) {
            android.util.Log.w("VideoFullScreen", "上下文不是 Activity，全屏命令跳过")
            return@LaunchedEffect
        }
        val controller: WindowInsetsControllerCompat =
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullScreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 离开页面必须复位，不管当前是不是全屏
    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? android.app.Activity ?: return@onDispose
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 全屏时返回键归我：先退全屏，第二次才离开页面
    BackHandler(enabled = fullScreen) { fullScreen = false }

    return remember(fullScreen) {
        VideoFullScreenState(fullScreen) { fullScreen = !fullScreen }
    }
}
```

- [ ] **Step 2: 加 configChanges**

`AndroidManifest.xml` 的 `MainActivity` 节点加一行（**故意不含 `density`**：实测密度变更仍重建是合理行为）：

```xml
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"
```

- [ ] **Step 3: 编译**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 全量单测**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全绿。本任务的组件是 Composable，**JVM 层无单测（未验证）**，由 Task 7 的 V9/V10 真机断言覆盖。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/common/VideoFullScreen.kt app/src/main/AndroidManifest.xml
git commit -m "feat(ui): add video fullscreen state owning orientation and system bars"
```

---

### Task 4: VideoStageFrame 画面外壳

**Files:**
- Create: `app/src/main/java/com/radio/chinese/ui/common/VideoStageFrame.kt`

**Interfaces:**
- Consumes: `VideoStage(playerManager, modifier, placeholder)`（既有）、`VideoFullScreenState`（Task 3）、`VideoControlsPolicy`（Task 1）、`PlayerManager.hasVideoTrack`（既有）
- Produces: `@Composable fun VideoStageFrame(playerManager: PlayerManager, fullScreen: VideoFullScreenState, modifier: Modifier = Modifier)`

- [ ] **Step 1: 写组件**

新建 `app/src/main/java/com/radio/chinese/ui/common/VideoStageFrame.kt`：

```kotlin
package com.radio.chinese.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.radio.chinese.domain.model.VideoControlsPolicy
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface
import kotlinx.coroutines.delay

/**
 * 画面 + 控制层的唯一外壳。`VideoStage` 继续只管绑面，本层只管"要不要给入口"。
 *
 * 手势与按钮的安装条件同一条判据（`showsFullScreenButton`）：
 * 车机档非全屏时**完全不装**，画面区不吞点击，今天的行为（滚动、页面手势）一律不变；
 * 但已在全屏时必须装 —— 否则按钮 3 秒收起后再也唤不回来，只剩系统返回键一条路。
 */
@Composable
fun VideoStageFrame(
    playerManager: PlayerManager,
    fullScreen: VideoFullScreenState,
    modifier: Modifier = Modifier,
) {
    val hasVideo by playerManager.hasVideoTrack.collectAsState()
    val interactive = VideoControlsPolicy.showsFullScreenButton(
        isCarSurface = isCarSurface(),
        hasVideoTrack = hasVideo,
        isFullScreen = fullScreen.isFullScreen,
    )

    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible, fullScreen.isFullScreen, interactive) {
        if (!interactive) return@LaunchedEffect
        if (controlsVisible) {
            delay(VideoControlsPolicy.CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Box(modifier) {
        VideoStage(playerManager = playerManager, modifier = Modifier.matchParentSize())

        if (interactive) {
            // 透明手势层：点画面切换控制层。放在画面之上、按钮之下。
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { controlsVisible = !controlsVisible } }
            )
            if (controlsVisible) {
                IconButton(
                    onClick = {
                        fullScreen.toggle()
                        controlsVisible = true
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(Dimens.TouchMin),
                ) {
                    Icon(
                        imageVector = if (fullScreen.isFullScreen) Icons.Default.FullscreenExit
                                      else Icons.Default.Fullscreen,
                        contentDescription = if (fullScreen.isFullScreen) "退出全屏" else "全屏",
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: 编译**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL。若 `Icons.Default.Fullscreen` / `FullscreenExit` 报未解析，改用 `Icons.AutoMirrored.Filled.Fullscreen`（material-icons-extended 在新版本里对箭头类图标做了 AutoMirrored 迁移，全屏图标不受影响，但报错就按编译器提示换）。

- [ ] **Step 3: 全量单测**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全绿。本组件 Composable，**JVM 层无单测（未验证）**，由 Task 7 的 V8/V11 覆盖。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/common/VideoStageFrame.kt
git commit -m "feat(ui): add video stage frame with tap-to-toggle fullscreen control"
```

---

### Task 5: 点播页接入（OperaPlayerScreen）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/player/OperaPlayerScreen.kt`（Scaffold L72-87、`contentArea` L169-176、主体 Column L182-187）

**Interfaces:**
- Consumes: `VideoStageFrame`（Task 4）、`rememberVideoFullScreen`（Task 3）、`PlayerManager.setVideoPictureWanted`（Task 2）
- Produces: 无新对外接口

- [ ] **Step 1: 取全屏状态并声明画面意图**

在 `val hasVideo by playerManager.hasVideoTrack.collectAsState()` 之后加：

```kotlin
    val fullScreen = rememberVideoFullScreen()

    // 闸门跟着页面意图走：页面开着且有视频轨就要画面，离开页面立刻收闸。
    // 不放在 VideoStage 的生命周期里 —— 那会让布局重建（旋转、换分支）拆一遍解码器。
    LaunchedEffect(hasVideo) { playerManager.setVideoPictureWanted(hasVideo) }
    DisposableEffect(Unit) {
        onDispose { playerManager.setVideoPictureWanted(false) }
    }
```

补 import：`com.radio.chinese.ui.common.VideoFullScreen`（同包 `ui.common`，实际写 `com.radio.chinese.ui.common.rememberVideoFullScreen`）、`androidx.compose.ui.layout`（不需要）。

- [ ] **Step 2: Scaffold 在全屏时收掉自己的 chrome**

把 `Scaffold(` 那两行参数改成（**非全屏分支必须逐字不变**，否则手机档像素回退）：

```kotlin
    Scaffold(
        contentWindowInsets = if (fullScreen.isFullScreen) WindowInsets(0) else NestedWindowInsets,
        topBar = {
            if (fullScreen.isFullScreen) {
                // 全屏：顶栏整块不渲染，返回与退出入口交给画面控制层
            } else {
                TopAppBar(
                    windowInsets = NestedWindowInsets,
                    // 与电台 PlayerScreen 同理：车机档抬顶栏短边到 88dp，返回键才拿得到完整命中区
                    modifier = if (isCarSurface()) Modifier.heightIn(min = Dimens.TouchMin) else Modifier,
                    title = { Text("正在播放") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack, modifier = if (isCarSurface()) Modifier.size(Dimens.TouchMin) else Modifier) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        }
    ) { padding ->
```

- [ ] **Step 3: contentArea 换成 frame**

把有画面那一支从 `VideoStage(...)` 换成：

```kotlin
            if (hasVideo) {
                VideoStageFrame(playerManager = playerManager, fullScreen = fullScreen, modifier = m)
            } else {
                OperaContentArea(m, state, onLineClick = { viewModel.seekToLine(it) })
            }
```

- [ ] **Step 4: 主体 Column 的 padding 在全屏时归零**

把

```kotlin
        Column(Modifier.fillMaxSize().padding(padding)) {
            info()
            contentArea(Modifier.weight(1f))
            slider()
            controls()
        }
```

改成（非全屏路径的 modifier 链与子项顺序**一字未动**）：

```kotlin
        Column(Modifier.fillMaxSize().padding(if (fullScreen.isFullScreen) PaddingValues() else padding)) {
            if (!fullScreen.isFullScreen) info()
            contentArea(Modifier.weight(1f))
            if (!fullScreen.isFullScreen) {
                slider()
                controls()
            }
        }
```

补 import：`androidx.compose.foundation.layout.PaddingValues`、`androidx.compose.foundation.layout.WindowInsets`、`com.radio.chinese.ui.common.VideoStageFrame`、`com.radio.chinese.ui.common.rememberVideoFullScreen`。

- [ ] **Step 5: 单行播控红线自查**

确认 `slider()` 与 `controls()` 仍在同一个 `if` 块里、没有新增第二行控件；全屏时它们整块消失（spec F5 已接受的取舍）。

- [ ] **Step 6: 编译 + 全量单测**

Run: `.\gradlew.bat :app:assembleDebug`
Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 均成功

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/player/OperaPlayerScreen.kt
git commit -m "feat(ui): wire fullscreen controls into the opera player"
```

---

### Task 6: 电台页接入（PlayerScreen）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/player/PlayerScreen.kt`（Scaffold L88-90、`mediaVisual` L167-190、主体 Column L157-164）

**Interfaces:**
- Consumes: `VideoStageFrame`（Task 4）、`rememberVideoFullScreen`（Task 3）、`PlayerManager.setVideoPictureWanted`（Task 2）
- Produces: 无

- [ ] **Step 1: 全屏状态与画面意图**

在 `val uiState by viewModel.uiState.collectAsState()` 之后加：

```kotlin
    val fullScreen = rememberVideoFullScreen()

    // 同 OperaPlayerScreen：闸门跟页面意图，不跟 View 生命周期。
    // 本页画面格嵌在 isCarSurface() 分支里，强制横屏会让分支换位置、AndroidView 重建，
    // 若闸门跟着 View 走，一进全屏就收闸再开闸 —— 用户看到闪黑。
    LaunchedEffect(uiState.hasVideoTrack) { playerManager.setVideoPictureWanted(uiState.hasVideoTrack) }
    DisposableEffect(Unit) {
        onDispose { playerManager.setVideoPictureWanted(false) }
    }
```

- [ ] **Step 2: Scaffold 在全屏时收掉顶栏与 insets**

把 `Scaffold(` 的前两行改成（`else` 分支内容与顺序**逐字保留**）：

```kotlin
    Scaffold(
        contentWindowInsets = if (fullScreen.isFullScreen) WindowInsets(0) else NestedWindowInsets,
        topBar = {
            if (fullScreen.isFullScreen) {
                // 全屏：顶栏（含收藏、定时）整块不渲染，退出入口交给画面控制层
            } else {
                TopAppBar(
                    windowInsets = NestedWindowInsets,
                    // 顶栏默认 64dp 高，不把顶栏本身抬到 88dp，里面的按钮就只能拿到 64dp 短边
                    modifier = if (isCarSurface()) Modifier.heightIn(min = Dimens.TouchMin) else Modifier,
                    title = { Text("正在播放") },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = if (isCarSurface()) Modifier.size(Dimens.TouchMin) else Modifier
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        // ……原有收藏与定时按钮整段照抄，一字不改……
                    }
                )
            }
        }
    ) { padding ->
```

- [ ] **Step 3: mediaVisual 换成 frame，全屏时撑满**

把 `mediaVisual` 换成：

```kotlin
                val mediaVisual: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { size ->
                    if (uiState.hasVideoTrack) {
                        // 非全屏：随宽度自适应的 16:9 有界盒（本页在 verticalScroll 里，没有 weight 可用）
                        // 全屏：整屏撑满，滚动容器同时被摘掉（见下面的 Column modifier）
                        VideoStageFrame(
                            playerManager = playerManager,
                            fullScreen = fullScreen,
                            modifier = if (fullScreen.isFullScreen) Modifier.fillMaxSize()
                                       else Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        )
                    } else {
                        CoverBlock(
                            url = station.logoUrl,
                            name = station.name,
                            isPlaying = uiState.isPlaying,
                            size = size
                        )
                    }
                }
```

- [ ] **Step 4: 主体 Column 在全屏时摘掉滚动与内边距**

把

```kotlin
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.GapHuge, vertical = pageVerticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
```

改成（非全屏的链式顺序完全一致）：

```kotlin
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (fullScreen.isFullScreen) PaddingValues() else padding)
                    .then(
                        if (fullScreen.isFullScreen) Modifier
                        else Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Dimens.GapHuge, vertical = pageVerticalPadding)
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
```

- [ ] **Step 5: 全屏时跳过其余区块**

在 `Column` 内，除 `mediaVisual` 所在的分支外，其余兄弟区块整段包进 `if (!fullScreen.isFullScreen) { ... }`：EPG 卡（`NowOnAirCard` 那块）、`PlaybackStatusIndicator`、`SourceSelector`、播控 `Row`。

**注意 `isCarSurface()` 分支本身不要动**：全屏时仍走原来的分支结构，只是里面的兄弟区块不渲染。这样即使形态判定跳变，也只是走本来就走的那条分支，不引入新布局。

- [ ] **Step 6: 编译 + 全量单测**

Run: `.\gradlew.bat :app:assembleDebug`
Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 均成功

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/player/PlayerScreen.kt
git commit -m "feat(ui): wire fullscreen controls into the station player"
```

---

### Task 7: 真机验收 V8–V12 + 基线重跑

**Files:**
- Modify: `tools/video_acceptance.ps1`（追加 V7–V11 段）

**Interfaces:**
- Consumes: 前六个任务全部产出
- Produces: V8–V12 与 V1/V2/V3/V6 的量化结论

- [ ] **Step 1: 装包并做环境前置**

```powershell
.\gradlew.bat :app:assembleDebug
$apk = Get-ChildItem app\build\outputs\apk\debug -Filter *.apk | Sort-Object LastWriteTime -Descending | Select-Object -First 1
"apk=$($apk.Name) mtime=$($apk.LastWriteTime)"   # 必须打印时间戳：构建失败时 install 仍会报 Success 装旧包
adb install -r $apk.FullName
if ((adb shell settings get global airplane_mode_on).Trim() -eq "1") { adb shell cmd connectivity airplane-mode disable }
adb shell pm grant com.radio.chinese.debug android.permission.POST_NOTIFICATIONS
```

- [ ] **Step 2: V8 进出全屏不闪（Amendment 的主判据）**

进「人间卫视」播放页 → 点画面唤出按钮 → 点全屏 → 等 5 秒 → 点退出全屏。

```powershell
adb logcat -d -s VideoGate:D VideoProbe:D
```

Expected：`VideoGate` 只在**进入播放页**时出现一条 `pictureWanted=true decode=true`，**进出全屏全程不再出现任何新条目**（wanted 从未变化）。若出现 `decode=false` 紧跟 `decode=true` 的往返，说明还有别处在动闸门 —— 停下排查，不要改成"看起来能过"的判据。

- [ ] **Step 3: V9 返回键先退全屏**

全屏态 `adb shell input keyevent 4` 一次 → `uiautomator dump` 应仍在播放页（含画面、无台名区）；再按一次才回到列表页。

- [ ] **Step 4: V10 离开页面复位**

全屏态连续返回两次离开页面后断言：

```powershell
adb shell dumpsys window | Select-String "mRotation|mOrientation"    # 应回到跟随系统（rotation 与用户设置一致）
adb shell dumpsys window | Select-String "mSystemBarsBehavior|mIme"   # 系统栏可见：stable 不含 HIDDEN
adb logcat -d -s VideoGate:D | Select-Object -Last 1                   # 末条必须 pictureWanted=false decode=false
```

- [ ] **Step 5: V11 大屏机形态跳变下退出入口不消失**

```powershell
adb shell settings put system user_rotation 0
adb shell wm density 160 ; adb shell wm size 1280x720
```

进播放页 → 断言按钮存在（`uiautomator dump` 里 `content-desc` 含「全屏」）→ 点全屏 → 此时 `isCarSurface()` 应为 true → **再点画面唤出，必须仍能看到「退出全屏」且 bounds 可点**；截图人工核对按钮没被裁切、没撑破布局。测完 `wm size reset ; wm density reset`。

- [ ] **Step 6: 重跑基线 V1 / V2 / V3 / V6**

`configChanges` 是全局改动，所有页面在旋转时不再重建，必须确认没漂：

```powershell
.\tools\video_acceptance.ps1 -OnlyV 1
.\tools\video_acceptance.ps1 -OnlyV 2
.\tools\video_acceptance.ps1 -OnlyV 3
.\tools\video_acceptance.ps1 -OnlyV 6
```

Expected：四项全 PASS。V1 尤其注意 —— 非全屏路径的 modifier 链若被改毛，这里就会红。

- [ ] **Step 7: V12 R8 混淆包复跑**

```powershell
.\gradlew.bat :app:assembleRelease -PallowEggInRelease=true
```

装 `*-release-EGG.apk`（包名无 `.debug`，脚本里 `-Pkg com.radio.chinese`），复跑 V8 / V9 / V10。判定以 `mapping.txt` 为准，不看堆栈字符串猜。

- [ ] **Step 8: 全量单测 + 提交**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全绿

```bash
git add tools/video_acceptance.ps1
git commit -m "test(ui): cover fullscreen, back handling and system bar restore"
```

---

## 计划级自查

**1. Spec 覆盖**：§4.1→Task 1；§4.2→Task 3；§4.3→Task 4；§4.4→Task 5/6；§4.5→Task 3 Step 2；§5.1→Task 1/2；§5.2 V8–V12→Task 7；§6 错误处理→分散在 Task 3（非 Activity、ROM 降级）、Task 4（车机档不装手势）、Task 5/6（onDispose 收闸）；§7 非目标→无任务（正确）。**唯一偏离是 Amendment，已在文件顶部单列。**

**2. 占位符**：无 TBD/TODO。Task 6 Step 2 的 `actions = { ……照抄…… }` 是**有意的**：那段是既有代码，计划不重复 40 行，但明确要求"一字不改"，且 V1 重跑会抓住任何改动。

**3. 类型一致性**：`showsFullScreenButton(isCarSurface, hasVideoTrack, isFullScreen)` 在 Task 1 定义、Task 4 调用，参数名与顺序一致；`setVideoPictureWanted(wanted)` 在 Task 2 定义、Task 5/6 调用一致；`VideoFullScreenState(isFullScreen, toggle)` 在 Task 3 定义、Task 4/5/6 消费一致；`VideoStageFrame(playerManager, fullScreen, modifier)` 三处调用签名一致。

**4. Review Focus**：六条分别落在 Task 1（1）、Task 3（2、3）、Task 4（4）、Task 5/6（5）、Task 2+5+6（6），并由 Task 7 的 V8–V11 与基线重跑做真机收口。
