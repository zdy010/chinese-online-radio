# 视频播放（内核 + B 云盘视频 + A 播放页画面）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给纯音频的中文在线电台加一条视频通路：云盘视频文件点播能出画面，电台播放页在流本身带视频轨时自动长出画面，且不看视频时代码路径与耗电和改造前完全一致。

**Architecture:** 全 App 仍只有一个 `ExoPlayer`（在 `RadioService` 里）。新增 `VideoPlayerMount` 单例把那个实例在同进程内公布出来，UI 侧 `VideoStage` 用 `PlayerView` 绑它（`MediaController` 绑面会抛异常）。视频轨默认禁用，只有画面挂载时才解禁 —— 这条闸门是本设计的全部风险防线。有没有画面不猜数据，一律看播放器运行时 `currentTracks`。

**Tech Stack:** Kotlin 2.1.0 / Jetpack Compose (BOM 2024.12.01) / Media3 1.5.1（exoplayer + exoplayer-hls + session + ui，**零新增依赖**）/ Hilt 2.53.1 / JUnit 4.13.2 + MockK 1.13.13 / PowerShell + uiautomator 做 UI 回归。

**Spec:** `doc/specs/2026-11-02-video-playback-kernel-ab-design.md`（本计划逐节实现它；两者一起读）

## Global Constraints

- 不新增任何 Gradle 依赖：`media3-ui` 已在 `app/build.gradle.kts`（spec §2.4）。
- 不改 `app/src/main/assets/radio_stations.json`，不给 `RadioStation` 加 `hasVideo` 字段，不动 Room 表（D8）。
- 不新增导航路由、不新增底部 Tab（D3）。
- 不做画中画、不做双播放器、不做行车遮罩、不做视频下载缓存、不做字幕渲染（spec §1 非目标）。
- `domain/model/` 新增文件**禁止 import media3 与 Android 框架类**，必须是纯 Kotlin（否则 JVM 单测跑不起来）。
- 车机档硬指标：1280x720 恒横屏下播控行 `bounds.maxY ≤ 720`；手机档无视频内容时渲染结果与改造前逐像素一致。
- 新 UI 代码只准引用 `Dimens` token，禁止裸 dp 字面量。
- 测试命令统一用 Windows PowerShell 形式 `.\gradlew.bat ...`，命令保持简短（长命令与全角引号会让 gradlew 出现假失败与残留输出）。
- 安装 debug 包只用固定产物路径 `app\build\outputs\apk\debug\app-debug.apk`，禁止按历史 APK 文件名通配取包（会静默装旧包，测试跑在旧代码上）。
- 每次提交只提本任务涉及的文件，Conventional Commit 英文标题，不与其它任务混提。

## Review Focus

这六类输入/状态没有任何一个任务的测试天然覆盖，但真机上一定会遇到。每条都在对应任务里钉了用例：

1. **视频轨禁用后 `Tracks` 是否还上报视频组** —— 若不上报，画面永远挂不上（Task 4 专门裁决，给出兜底写法）。
2. **手机档播纯音频台时多出不可见节点** —— 只要 `VideoStage` 进了组合树（哪怕高度 0），insets 与间距就变，像素回退。必须用 `if` 分支控制是否进入组合树（Task 7/9 断言 0 新增可见节点）。
3. **页面转场交叉淡入时旧 Stage 的 `onDispose` 摘掉新 Stage 的面** —— 表现为进第二个视频页黑屏。所有权必须认实例（Task 6 覆盖）。
4. **退出播放页后闸门没收** —— 后台持续解码视频，发热耗电。`setVideoStageMounted(false)` 必须真的写回禁用（Task 10 V3 断言）。
5. **旋转重建后残留陈旧 `PlayerView` 引用** —— 第二次进页面黑屏。`releaseStage` 认实例 + Activity 重建重挂（Task 10 V5 覆盖）。
6. **车机档画面给固定高度** —— 播控被顶出首屏，用户连暂停都按不到（Task 10 V6 断言 `weight` 分配且 `maxY ≤ 720`）。

---

### Task 1: VideoMedia 扩展名判定（纯函数）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/domain/model/VideoMedia.kt`
- Test: `app/src/test/java/com/radio/chinese/VideoMediaTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `com.radio.chinese.domain.model.VideoMedia.looksLikeVideo(fileNameOrHref: String): Boolean`

- [ ] **Step 1: 写失败的测试**

新建 `app/src/test/java/com/radio/chinese/VideoMediaTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.VideoMedia
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这个文件像不像视频」的正向判定，只用于列表徽标。
 *
 * 刻意把 m3u8 和裸名文件都当反例钉住：判成视频会在列表上误导用户，
 * 而云盘里的 m3u8 常常是歌单或电台清单。能不能播不由这里管，
 * 由 PlayableTrack 的黑名单与播放器运行时真相决定。
 */
class VideoMediaTest {

    @Test
    fun `常见视频容器都命中，大小写都算`() {
        for (ext in listOf("mp4", "mkv", "mov", "webm", "flv", "avi", "mpg",
                           "mpeg", "wmv", "m4v", "rm", "rmvb", "3gp", "vob", "ts", "m2ts")) {
            assertTrue(".$ext 应判为视频", VideoMedia.looksLikeVideo("穆桂英挂帅.$ext"))
            assertTrue(".$ext 大写应判为视频", VideoMedia.looksLikeVideo("穆桂英挂帅.${ext.uppercase()}"))
        }
    }

    @Test
    fun `音频与歌词不是视频`() {
        for (ext in listOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "lrc", "txt", "srt", "jpg")) {
            assertFalse(".$ext 不该判为视频", VideoMedia.looksLikeVideo("唱段.$ext"))
        }
    }

    @Test
    fun `m3u8 与裸名文件一律不标`() {
        // 歧义太大：云盘里的 m3u8 可能是歌单/电台清单；无扩展名宁少不多
        assertFalse(VideoMedia.looksLikeVideo("直播.m3u8"))
        assertFalse(VideoMedia.looksLikeVideo("花木兰"))
        assertFalse(VideoMedia.looksLikeVideo("花木兰."))
    }

    @Test
    fun `带路径的 href 只认最后一段`() {
        assertTrue(VideoMedia.looksLikeVideo("/webdav/%E6%88%8F%E6%9B%B2/穆桂英.mp4"))
        assertFalse(VideoMedia.looksLikeVideo("/webdav/常香玉/花木兰.lrc"))
        // 目录名里带 .mp4 也不能骗过判定
        assertFalse(VideoMedia.looksLikeVideo("/mp4/2021/说明.txt"))
    }

    @Test
    fun `空与空白不 panic`() {
        assertFalse(VideoMedia.looksLikeVideo(""))
        assertFalse(VideoMedia.looksLikeVideo("   "))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.VideoMediaTest"`
Expected: 编译失败，`Unresolved reference: VideoMedia`

- [ ] **Step 3: 写最小实现**

新建 `app/src/main/java/com/radio/chinese/domain/model/VideoMedia.kt`：

```kotlin
package com.radio.chinese.domain.model

/**
 * 「这个文件名像不像视频」的正向判定。
 *
 * 只用于列表徽标，不参与能不能播的判断：能不能播仍由 `PlayableTrack` 的黑名单决定，
 * 有没有真画面由播放器运行时 `currentTracks` 决定（见 spec §2.3、§5）。
 *
 * 刻意不含 `m3u8`：云盘里的 m3u8 可能是歌单或电台清单，标成视频会误导用户。
 * 用白名单而不是黑名单（与 `PlayableTrack` 相反）：这里是「多标一个」的代价，
 * 判错只是徽标不准，不会像 `PlayableTrack` 那样悄悄删掉能播的条目。
 */
object VideoMedia {

    /** 明确按视频对待的扩展名（不含点、已小写）。 */
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "mov", "webm", "flv", "avi", "mpg", "mpeg", "wmv",
        "m4v", "rm", "rmvb", "3gp", "vob", "ts", "m2ts",
    )

    /**
     * [fileNameOrHref] 可以是显示名，也可以是带路径的 PROPFIND href；
     * 只按最后一段的扩展名判断，与 `PlayableTrack.isPlayable` 同一派生法。
     */
    fun looksLikeVideo(fileNameOrHref: String): Boolean {
        val trimmed = fileNameOrHref.trim()
        if (trimmed.isEmpty()) return false
        val base = trimmed.substringAfterLast('/')
        val ext = base.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.VideoMediaTest"`
Expected: PASS（5 个用例全绿）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/VideoMedia.kt app/src/test/java/com/radio/chinese/VideoMediaTest.kt
git commit -m "feat(domain): add video file extension predicate"
```

---

### Task 2: VideoTrackGate 闸门决策（纯函数，红线守卫）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/domain/model/VideoTrackGate.kt`
- Test: `app/src/test/java/com/radio/chinese/VideoTrackGateTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `VideoTrackGate.shouldDecodeVideo(stageMounted: Boolean): Boolean`、`VideoTrackGate.DEFAULT_VIDEO_TRACK_DISABLED: Boolean`

- [ ] **Step 1: 写失败的测试**

新建 `app/src/test/java/com/radio/chinese/VideoTrackGateTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.VideoTrackGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频轨闸门的红线。
 *
 * 「没挂画面就绝不解码视频」是整个视频设计唯一 preventing 现状回退的东西：
 * 一旦有人把它改成默认开，所有只听声的电台都要多解码一路视频，
 * 耗电、发热、起播耗时全变差，而单测与 UI 回归都看不出来。所以这条要单独钉死。
 */
class VideoTrackGateTest {

    @Test
    fun `没有画面挂载时一律不解码视频`() {
        assertFalse(VideoTrackGate.shouldDecodeVideo(stageMounted = false))
    }

    @Test
    fun `画面挂载后才解码视频`() {
        assertTrue(VideoTrackGate.shouldDecodeVideo(stageMounted = true))
    }

    @Test
    fun `出厂默认是禁用视频轨`() {
        assertTrue(VideoTrackGate.DEFAULT_VIDEO_TRACK_DISABLED)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.VideoTrackGateTest"`
Expected: 编译失败，`Unresolved reference: VideoTrackGate`

- [ ] **Step 3: 写最小实现**

新建 `app/src/main/java/com/radio/chinese/domain/model/VideoTrackGate.kt`：

```kotlin
package com.radio.chinese.domain.model

/**
 * 视频轨闸门：要不要让播放器解码视频。
 *
 * 全应用只认这一个判据，且刻意不 import media3 —— 保持纯 Kotlin 才能在本项目
 * 没有 Robolectric 的 JVM 测试集里跑起来（与 `OperaSpeed` 同一手法：判定集中在纯函数，
 * 落地是薄胶水）。
 *
 * 默认关。画面没挂上就绝不解码视频，以此保证「只听声」的现有行为、起播耗时与功耗
 * 和改造前完全一致（spec §5.2）。
 */
object VideoTrackGate {

    /** 出厂默认：没有画面挂载时视频轨必须处于禁用态。 */
    const val DEFAULT_VIDEO_TRACK_DISABLED: Boolean = true

    /** 唯一的闸门决策。`stageMounted = false` 时恒为 false —— 这条是回归守卫的对象。 */
    fun shouldDecodeVideo(stageMounted: Boolean): Boolean = stageMounted
}
```

- [ ] **Step 4: 跑既有不变式测试，确认没碰坏**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.PlayableTrackTest" --tests "com.radio.chinese.OperaSpeedTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/VideoTrackGate.kt app/src/test/java/com/radio/chinese/VideoTrackGateTest.kt
git commit -m "feat(domain): add video track decode gate with default-closed rule"
```

---

### Task 3: VideoPlayerMount 注册表 + RadioService 上电即禁视频轨

**Files:**
- Create: `app/src/main/java/com/radio/chinese/service/VideoPlayerMount.kt`
- Modify: `app/src/main/java/com/radio/chinese/service/RadioService.kt`（`initializePlayer()` 约 L108-153、`onDestroy()` 约 L274-282）

**Interfaces:**
- Consumes: `VideoTrackGate.DEFAULT_VIDEO_TRACK_DISABLED`（Task 2）
- Produces:
  - `VideoPlayerMount.player: ExoPlayer?`
  - `VideoPlayerMount.playerFlow: StateFlow<ExoPlayer?>`
  - `VideoPlayerMount.attach(player: ExoPlayer)` / `detach(player: ExoPlayer)`
  - `VideoPlayerMount.acquireStage(view: PlayerView): Boolean` / `releaseStage(view: PlayerView): Boolean`
  - `VideoPlayerMount.mountedView: PlayerView?`

- [ ] **Step 1: 确认没有别处直接 new PlayerManager**

Run: `Select-String -Path app/src/main/java/com/radio/chinese -Pattern "PlayerManager(" -Recurse`
Expected: 只出现构造声明与注入点。若发现测试里直接构造 `PlayerManager(...)`，记下位置 —— Task 5 给它加构造参数时要同步补。

- [ ] **Step 2: 写注册表**

新建 `app/src/main/java/com/radio/chinese/service/VideoPlayerMount.kt`：

```kotlin
package com.radio.chinese.service

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 同进程内公布 `RadioService` 建起来的那个 ExoPlayer，供 UI 绑视频输出面。
 *
 * 为什么需要它：`PlayerManager` 通过 `MediaController`（会话代理）控播，而 Media3 的
 * `MediaController` 不支持 `setVideoSurface*` —— 调了就抛 `UnsupportedOperationException`，
 * 画面必须绑在播放器所在进程的那个对象上。`RadioService` 在清单里没有 `android:process`，
 * 与 Activity 同进程，所以这里把实例交出去是安全的。
 *
 * 只允许主线程读写（`RadioService.onCreate` 与 Compose 都在主线程）。
 */
@Singleton
class VideoPlayerMount @Inject constructor() {

    private val _player = MutableStateFlow<ExoPlayer?>(null)

    /** 冷启动直接进视频页时播放器可能还没建起来，UI 订阅这个流等它。 */
    val playerFlow: StateFlow<ExoPlayer?> = _player

    var player: ExoPlayer? = null
        private set

    /** 当前唯一持有画面的 `PlayerView`；转场交叠时后来者顶掉前者。 */
    var mountedView: PlayerView? = null
        private set

    fun attach(player: ExoPlayer) {
        this.player = player
        _player.value = player
    }

    /** 认实例再摘：旧 Service 实例销毁时不得把新建好的实例摘空。 */
    fun detach(player: ExoPlayer) {
        if (this.player === player) {
            this.player = null
            this.mountedView = null
            _player.value = null
        }
    }

    /** 声明画面所有权。恒返回 true —— 后来者总是拿到所有权，前者被顶掉。 */
    fun acquireStage(view: PlayerView): Boolean {
        mountedView = view
        return true
    }

    /** 释放画面所有权。只有持有者本人释放才返回 true；被顶掉的前者返回 false，不得去 setPlayer(null)。 */
    fun releaseStage(view: PlayerView): Boolean {
        if (mountedView === view) {
            mountedView = null
            return true
        }
        return false
    }
}
```

- [ ] **Step 3: RadioService 注入并挂上**

在 `RadioService.kt` 的注入区（`keyBindings` 之后）加：

```kotlin
    /** 视频画面要绑这个播放器实例本身，MediaController 绑不了面 */
    @Inject
    lateinit var videoPlayerMount: VideoPlayerMount
```

在 `initializePlayer()` 里 `exoPlayer = player` 之前插入（建好即禁视频轨 + 上表）：

```kotlin
        // 默认不解码视频：没有画面挂载时绝不白耗硬解与电量。这是「只听声行为零回退」的落点。
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, VideoTrackGate.DEFAULT_VIDEO_TRACK_DISABLED)
            .build()
        videoPlayerMount.attach(player)
```

补 import：`import com.radio.chinese.domain.model.VideoTrackGate`

- [ ] **Step 4: onDestroy 先摘表再释放**

把 `RadioService.onDestroy()` 改成（detach 必须在 `release()` 之前，否则 UI 可能拿到已释放的播放器）：

```kotlin
    override fun onDestroy() {
        exoPlayer?.let { videoPlayerMount.detach(it) }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayer = null
        super.onDestroy()
    }
```

- [ ] **Step 5: 编译并跑全量单测**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全绿（本任务不该让任何既有用例变红）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/radio/chinese/service/VideoPlayerMount.kt app/src/main/java/com/radio/chinese/service/RadioService.kt
git commit -m "feat(service): publish exoplayer for video surfaces and disable video track by default"
```

---

### Task 4: hasVideoTrack 上报 + §5.4 未证前提裁决（决定性任务）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/service/PlayerManager.kt`（构造注入区 L36-42、`setupListeners()` L193-267）

**Interfaces:**
- Consumes: `VideoPlayerMount`（Task 3）
- Produces: `PlayerManager.hasVideoTrack: StateFlow<Boolean>`

> **本任务不允许写成功能就跳过验证。** spec §5.4 的前提（禁用后 `Tracks` 仍上报视频组）在此裁决，结论决定 Task 5 的实现分支。

- [ ] **Step 1: 注入注册表并加状态**

`PlayerManager` 构造参数追加 `val videoPlayerMount: VideoPlayerMount`（放在 `ttsAnnouncer` 之后）。注意不写 `private`：UI 侧要从这个属性拿注册表，沿用「页面直调 PlayerManager」的既有约定，不给 Composable 新增 Hilt 注入点。

在戏曲状态区之前加：

```kotlin
    /** 当前条目是否带可显示的视频轨。真相来自播放器，不猜 URL、不改数据（spec §2/§5）。 */
    private val _hasVideoTrack = MutableStateFlow(false)
    val hasVideoTrack: StateFlow<Boolean> = _hasVideoTrack
```

- [ ] **Step 2: 加 onTracksChanged 与诊断日志**

在 `setupListeners()` 的 `Player.Listener` 内、`onPlaybackStateChanged` 之后加：

```kotlin
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val video = tracks.groups.any {
                    it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.trackCount > 0
                }
                _hasVideoTrack.value = video
                // Task 4 裁决用：禁用状态下播放器还不上报视频组，是主路线与兜底路线的分岔点
                val p = videoPlayerMount.player
                android.util.Log.d(
                    "VideoProbe",
                    "groups=" + tracks.groups.joinToString(";") {
                        "type=${it.type} n=${it.trackCount} supported=${it.isSupported}"
                    } + " disabled=" + p?.trackSelectionParameters?.disabledTrackTypes
                )
            }
```

- [ ] **Step 3: 装到设备/模拟器**

Run: `.\gradlew.bat :app:assembleDebug`
Run: `adb install -r app\build\outputs\apk\debug\app-debug.apk`
Run: `adb logcat -c`

- [ ] **Step 4: 正例取样（带画面的台）**

启动 App → 电台页 → 分类「电视伴音」→ 进 **人间卫视** 的播放页，等 10 秒。

Run: `adb logcat -d -s VideoProbe:D`

Expected（主路线成立的样子）：出现含 `type=2 n>=1` 的行，且 `disabled=` 集合含 `2`。

> **常量以设备为准（已实测修正）**：media3 1.5.1 是 **VIDEO=2、AUDIO=1、TEXT=3、METADATA=5、IMAGE=4、UNKNOWN=-1**。
> 本步原写“`C.TRACK_TYPE_VIDEO == 1`”是错的（那套是 ExoPlayer 2.x 时代的编号），已按设备打印改正。

- [ ] **Step 5: 反例取样（必须仍无视频，否则判据本身假阳）**

回列表，进 **CCTV-5体育伴音** 播放页，等 10 秒。

Run: `adb logcat -d -s VideoProbe:D`

Expected：所有行只有 `type=1`（音频）与 `type=5`（元数据），**不出现 `type=2`**。若反例也报了 `type=2`，说明判据不可信，本任务不得通过，停下报告。

- [ ] **Step 6: 写下裁决并同步进 spec**

把 Step 4/5 的原始日志贴进 `doc/specs/2026-11-02-video-playback-kernel-ab-design.md` 的 §5.4 末尾（新增「实测记录」小节），并明确写一行：

- ~~若 Step 4 未出现 `type=1` 而 Step 6 前人工确认该流确有画面 → 采用兜底路线~~

**实测结论（2026-11-02）：主路线成立。** 人间卫视报了 `type=2 n=1 supported=true` 且同时 `disabled=[2]`；
北京卫视全程无 `type=2`。原始日志与常量自证过程见 spec §5.4.1。因此 **Task 5 的 Step 4（`probeVideoTrackOnce`）不实施**。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/radio/chinese/service/PlayerManager.kt doc/specs/2026-11-02-video-playback-kernel-ab-design.md
git commit -m "feat(player): report whether the current item carries a video track"
```

---

### Task 5: 闸门接线 setVideoStageMounted

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/service/PlayerManager.kt`
- Test: `app/src/test/java/com/radio/chinese/VideoStageMountTest.kt`（若走主路线则本任务无新纯函数，测试仍建在 Task 2，此处只补状态流转）

**Interfaces:**
- Consumes: `VideoTrackGate.shouldDecodeVideo`（Task 2）、`VideoPlayerMount.playerFlow`（Task 3）
- Produces: `PlayerManager.setVideoStageMounted(mounted: Boolean)`、`PlayerManager.videoStageMounted: StateFlow<Boolean>`、`PlayerManager.videoPlayerMount: VideoPlayerMount`（对外暴露给 UI）

- [ ] **Step 1: 加挂载状态**

`PlayerManager` 内加（注册表属性 Task 4 已作为 public `val videoPlayerMount` 注入，本任务直接用它，不再新增别名）：

```kotlin
    private val _videoStageMounted = MutableStateFlow(false)
    val videoStageMounted: StateFlow<Boolean> = _videoStageMounted
```

- [ ] **Step 2: 写闸门落地函数**

```kotlin
    /**
     * VideoStage 挂载/卸载时调用。幂等：重复 true / 重复 false 不产生副作用。
     *
     * 失败只记日志不写 `_error`：`_error` 是播放页唯一的红色情绪通道，
     * 正常起播不该红框一闪（既有踩坑记录）。
     */
    fun setVideoStageMounted(mounted: Boolean) {
        if (_videoStageMounted.value == mounted) return
        _videoStageMounted.value = mounted
        applyVideoTrackGate()
    }

    private fun applyVideoTrackGate() {
        scope.launch {
            val p = videoPlayerMount.player
            if (p == null) {
                android.util.Log.w("VideoGate", "播放器尚未建起，闸门待下次挂载再落")
                return@launch
            }
            val decode = VideoTrackGate.shouldDecodeVideo(_videoStageMounted.value)
            try {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, !decode)
                    .build()
            } catch (e: IllegalStateException) {
                // ExoPlayer 校验 application thread；此处 scope 是 Main，正常不会走到
                android.util.Log.e("VideoGate", "视频轨闸门写入失败", e)
            }
        }
    }
```

补 import：`import com.radio.chinese.domain.model.VideoTrackGate`

- [ ] **Step 3: 换条目不重置闸门（明确写进注释）**

在 `playStation()` 与 `playOperaFile()` 的文档注释各加一行，防止后来者"顺手清一下"：

```kotlin
        // 注意：不动视频轨闸门。画面挂载期间换源/换段应保持解禁，收闸只发生在 setVideoStageMounted(false)。
```

同时在两个方法开头加 `_hasVideoTrack.value = false`（新条目的真相要等它的 `onTracksChanged`，沿用旧结论会让上一个台的画面骗过这一台）：

```kotlin
        _hasVideoTrack.value = false
```

- [ ] **Step 4:（不实施 —— Task 4 已定主路线）加一拍探测**

> Task 4 实测确认禁用态下 `Tracks` 仍上报视频组（spec §5.4.1），所以下面的 `probeVideoTrackOnce()` **不写**。
> 保留代码文本仅供将来发现新的设备不报情形时取用。

```kotlin
    /**
     * 播放页进入时探测一次：临时解禁 → 等 onTracksChanged（上限 400ms）→ 无视频则立即禁回。
     * 只在进入播放页这条路发生，列表/后台/通知栏一律不探测。
     */
    fun probeVideoTrackOnce() {
        if (_videoStageMounted.value) return
        scope.launch {
            val p = videoPlayerMount.player ?: return@launch
            val before = _hasVideoTrack.value
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false).build()
            withTimeoutOrNull(400L) { _hasVideoTrack.first { it != before } }
            if (!_hasVideoTrack.value) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true).build()
            }
        }
    }
```

补 import：`import kotlinx.coroutines.withTimeoutOrNull`

- [ ] **Step 5: 编译 + 全量单测**

Run: `.\gradlew.bat :app:assembleDebug`
Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 均成功

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/radio/chinese/service/PlayerManager.kt
git commit -m "feat(player): gate video decoding on video stage mount state"
```

---

### Task 6: VideoStage 画面组件

**Files:**
- Create: `app/src/main/java/com/radio/chinese/ui/common/VideoStage.kt`

**Interfaces:**
- Consumes: `PlayerManager.videoPlayerMount`、`PlayerManager.setVideoStageMounted`（Task 5）
- Produces: `@Composable fun VideoStage(playerManager: PlayerManager, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit = {})`

- [ ] **Step 1: 写组件**

新建 `app/src/main/java/com/radio/chinese/ui/common/VideoStage.kt`：

```kotlin
package com.radio.chinese.ui.common

import android.graphics.Color as AndroidColor
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import com.radio.chinese.service.PlayerManager

/**
 * 全站唯一的视频画面组件。
 *
 * 绑的是 `RadioService` 那个 ExoPlayer 实例（经 `VideoPlayerMount` 交出），
 * **不是** `MediaController` —— controller 调 setVideoSurface* 会抛异常。
 *
 * 尺寸一律由外部 `modifier` 决定（调用点用 `weight` 拿空间），本组件不写固定宽高：
 * 车机档给固定高度会把播控顶出 720 高的首屏，用户连暂停都按不到。
 *
 * @param placeholder 播放器还没建起时的占位（避免布局跳动），默认空。
 */
@Composable
fun VideoStage(
    playerManager: PlayerManager,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = {},
) {
    val view = LocalView.current
    val mount = playerManager.videoPlayerMount
    val player by mount.playerFlow.collectAsState()

    val playerView = AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setResizeMode(C.RESIZE_MODE_FIT)   // 自带黑边适配，不拉伸不变形
                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
            }
        },
        update = { pv ->
            // 只在实例真的变了才写：每次重组都调 setPlayer 会触发重新准备
            if (pv.player !== player) pv.player = player
        },
    )

    // 所有权声明与闸门开关放在副作用阶段，不在 update 里写 StateFlow（那是重组期）
    DisposableEffect(playerView) {
        mount.acquireStage(playerView)
        playerManager.setVideoStageMounted(true)
        onDispose {
            // 被后来的 Stage 顶掉时不得去 setPlayer(null)，否则会把新页面的面摘黑
            if (mount.releaseStage(playerView)) {
                playerView.player = null
                playerManager.setVideoStageMounted(false)
            }
        }
    }

    // 播放中保持屏幕常亮；离开组合时还原，避免影响电台只听声时的灭屏
    val window = (view.context as? android.app.Activity)?.window
    DisposableEffect(window, player) {
        if (player != null) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    if (player == null) placeholder()
}
```

> 若当前 media3 版本没有 `setShutterBackgroundColor`，删掉那一行即可 —— 它只影响快门底色，不参与任何设计判定。

- [ ] **Step 2: 编译**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL。若 `PlayerView` 的 `useController` / `player` 属性写法报错，改用 `setUseController(false)` / `setPlayer(...)` 显式 setter，语义不变。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/common/VideoStage.kt
git commit -m "feat(ui): add video stage bound to the service exoplayer"
```

---

### Task 7: B 期 —— 点播页出画面 + 歌词收进底部弹窗

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/player/OperaPlayerScreen.kt`（`info()` L104-120、`contentArea` L140-142、`Column` 主体 L147-152）

**Interfaces:**
- Consumes: `VideoStage`（Task 6）、`playerManager.hasVideoTrack`（Task 4）
- Produces: 无新对外接口

- [ ] **Step 1: 取 hasVideoTrack**

在 `val state by viewModel.uiState.collectAsState()` 之后加：

```kotlin
    val hasVideo by playerManager.hasVideoTrack.collectAsState()
```

- [ ] **Step 2: contentArea 改分支（关键：用 if 控制是否进入组合树）**

把

```kotlin
        val contentArea: @Composable (Modifier) -> Unit = { m ->
            OperaContentArea(m, state, onLineClick = { viewModel.seekToLine(it) })
        }
```

改为

```kotlin
        // 有画面时整块内容区让给视频；无画面时必须走 else 分支——
        // 让 VideoStage 进组合树再压高度/insets 会改变手机档的像素结果（spec §7 零回退红线）。
        val hasCoverOrLyrics = state.coverBytes != null ||
            (state.lyricsStatus == LyricsStatus.READY && state.lines.isNotEmpty())
        val contentArea: @Composable (Modifier) -> Unit = { m ->
            if (hasVideo) {
                VideoStage(playerManager = playerManager, modifier = m)
            } else {
                OperaContentArea(m, state, onLineClick = { viewModel.seekToLine(it) })
            }
        }
```

- [ ] **Step 3: info 行加「歌词」芯片，唤出底部弹窗**

在 `info` lambda 的 `Column(...)` 外包一层 `Row`，标题列 `weight(1f)`，右侧加：

```kotlin
                if (hasVideo && hasCoverOrLyrics) {
                    var sheetOpen by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { sheetOpen = true },
                        modifier = if (isCarSurface()) Modifier.heightIn(min = Dimens.TouchSecondary) else Modifier
                    ) { Text("歌词") }
                    if (sheetOpen) {
                        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
                            OperaContentArea(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                state = state,
                                onLineClick = { viewModel.seekToLine(it) },
                            )
                        }
                    }
                }
```

补 import：`androidx.compose.material3.ModalBottomSheet`、`androidx.compose.foundation.layout.Row`、`androidx.compose.foundation.layout.IntrinsicSize`、`com.radio.chinese.ui.common.VideoStage`。

- [ ] **Step 4: 单行控件红线自查**

确认 `slider()` 与 `controls()` 的调用（L150-151）**一字未改**，且没有新增第二行控件行。这条是既有硬规范：播放页控件必须保持单行。

- [ ] **Step 5: 编译 + 全量单测**

Run: `.\gradlew.bat :app:assembleDebug`
Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 均成功

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/player/OperaPlayerScreen.kt
git commit -m "feat(ui): show video picture on the opera player with lyrics in a sheet"
```

---

### Task 8: B 期 —— 云盘列表标出视频文件

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/library/BrowseScreen.kt`（行内 `isHlsStream` 块 L130-132）
- Modify: `app/src/main/java/com/radio/chinese/ui/library/SearchResultsList.kt`（`SearchResultRow` 内渲染 `crumb` 的 `Text` 之后）

**Interfaces:**
- Consumes: `VideoMedia.looksLikeVideo`（Task 1）
- Produces: 无

- [ ] **Step 1: BrowseScreen 加芯片**

紧跟现有这段之后插入同款芯片：

```kotlin
            if (item.isHlsStream) {
                Text("HLS 直播流", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
```

插入：

```kotlin
            // 只标徽标，不改能不能播：真相仍由播放器运行时的视频轨决定
            if (VideoMedia.looksLikeVideo(item.name)) {
                Text("视频", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
```

补 import：`import com.radio.chinese.domain.model.VideoMedia`

- [ ] **Step 2: SearchResultsList 加同款**

在 `SearchResultRow` 内渲染 `crumb` 的那行 `Text(...)` 之后，同一 `Column` 内插入：

```kotlin
            if (VideoMedia.looksLikeVideo(name)) {
                Text("视频", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
```

补 import：`import com.radio.chinese.domain.model.VideoMedia`

- [ ] **Step 3: 编译**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/library/BrowseScreen.kt app/src/main/java/com/radio/chinese/ui/library/SearchResultsList.kt
git commit -m "feat(library): mark video files in browse and search lists"
```

---

### Task 9: A 期 —— 电台播放页出画面 + 无画面遮罩

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/player/PlayerViewModel.kt`（`PlayerUiState` L22+、`combine` 块 L68-90）
- Modify: `app/src/main/java/com/radio/chinese/ui/player/PlayerScreen.kt`（签名、`CoverBlock` 两处 L182-187 与 L197-202）
- Modify: `app/src/main/java/com/radio/chinese/ui/MainScreen.kt`（`PlayerScreen(...)` 调用 L90-94）

**Interfaces:**
- Consumes: `VideoStage`（Task 6）、`PlayerManager.hasVideoTrack`（Task 4）
- Produces: `PlayerUiState.hasVideoTrack: Boolean`

- [ ] **Step 1: uiState 带上 hasVideoTrack**

`PlayerUiState` 增加字段：

```kotlin
    val hasVideoTrack: Boolean = false,
```

在 `combine` 的最外层输入里并入 `playerManager.hasVideoTrack`，并在构造 `PlayerUiState` 处填 `hasVideoTrack = videoTrack`。
（当前是三层 combine 嵌套：把最外层改为 `combine(flowOfStation, flowOfPlayInfo, flowOfSourceInfo, playerManager.hasVideoTrack) { a, b, c, videoTrack -> ... }`；`combine` 支持 4 参数重载，无需引入新依赖。）

- [ ] **Step 2: PlayerScreen 接 playerManager**

`PlayerScreen` 参数表加 `playerManager: PlayerManager,`；`MainScreen.kt` 的调用处补 `playerManager = playerManager,`（`RadioNavGraph` 已有该参数）。

- [ ] **Step 3: 两处 CoverBlock 换成同一条分支**

在两个分支前定义一次，避免写重：

```kotlin
            val mediaVisual: @Composable (Int) -> Unit = { size ->
                if (uiState.hasVideoTrack) {
                    Box {
                        VideoStage(playerManager = playerManager, modifier = Modifier.fillMaxWidth())
                        // 已经报过有视频轨、但当前源实际没画面时保持占位不 collapse，
                        // 否则整页布局会随轮转来回跳
                        if (!uiState.isPlaying && uiState.playbackState == Player.STATE_BUFFERING) {
                            Text(
                                "画面准备中…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                } else {
                    CoverBlock(
                        url = station.logoUrl,
                        name = station.name,
                        isPlaying = uiState.isPlaying,
                        size = size,
                    )
                }
            }
```

车机档 `Box(Modifier.weight(1f))` 内改为 `mediaVisual(coverSize)`；手机档 `else` 分支同样改为 `mediaVisual(coverSize)`，其后的 `Spacer(Modifier.height(gapAfterCover))` 保持不变。

补 import：`com.radio.chinese.ui.common.VideoStage`、`androidx.media3.common.Player`（若文件尚未引入）。

- [ ] **Step 4: 车机档不得给固定高度**

自查：`mediaVisual` 在车机档只靠 `Modifier.weight(1f)` 的容器拿宽度，内部不出现任何 `Modifier.height(<数字>)`。这是已知坑（整块撑高会把播控顶出首屏）。

- [ ] **Step 5: 编译 + 全量单测**

Run: `.\gradlew.bat :app:assembleDebug`
Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 均成功

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/player/PlayerViewModel.kt app/src/main/java/com/radio/chinese/ui/player/PlayerScreen.kt app/src/main/java/com/radio/chinese/ui/MainScreen.kt
git commit -m "feat(ui): show video picture on the station player"
```

---

### Task 10: 验收脚本 + R8 回归 + EGG 交付

**Files:**
- Create: `.qtest/video_acceptance.ps1`（验收脚本，非产品代码）

**Interfaces:**
- Consumes: 前九个任务的全部产出
- Produces: V1–V7 的量化结论

- [ ] **Step 1: 写 V1（手机档零回退）**

脚本要点（每步都要 `adb shell uiautomator dump` 后取新产物，禁止拿历史文件当本轮结论）：

```powershell
$dev = (adb get-serialno)
function Dump($tag) {
  adb -s $dev shell uiautomator dump /sdcard/w.xml | Out-Null
  adb -s $dev pull /sdcard/w.xml ".qtest\video_$tag.xml"
  Remove-Item ".qtest\video_$tag.txt" -ErrorAction SilentlyContinue
  adb -s $dev shell uiautomator dump /sdcard/w.xml > ".qtest\video_$tag.txt"
}
```

判据：进一个**纯音频台**播放页（如 CCTV-5体育伴音），节点数与关键 bounds 与改造前基线一致，`VideoStage` 相关节点 0 个。基线取 Task 3 之前那次提交的产物，或现有 `.qtest/51_fixed_home.xml` 系列同名页面对照。

- [ ] **Step 2: 写 V2/V3（画面真伪与闸门收回）**

- V2：云盘放一个 mp4，进点播页。取画面区矩形中心，**连抓两帧截图做差分**（不用单阈值判黑），两帧有差异才算真在动 —— 单阈值会把深色画面误判成黑屏，本项目已因此假失败过。
- V3：退出点播页 3 秒后 `adb logcat -d -s VideoGate:D VideoProbe:D`，断言末行 `disabled=` 集合含 `2`（视频轨重新禁用；media3 1.5.1 的 VIDEO=2），且音频仍在播（`isPlaying` 为真）。

- [ ] **Step 3: 写 V4（方控与通知栏）**

视频播放中按一次媒体 NEXT 键，断言：不丢音频焦点、通知栏仍只一条、画面区随 `hasVideoTrack` 收起。

```powershell
adb -s $dev shell input keyevent 87   # KEYCODE_MEDIA_NEXT = 87（不是 88，88 是 PREVIOUS）
```

- [ ] **Step 4: 写 V5（旋转重挂）**

`adb shell uiautomator dump` 前设横屏再设竖屏各一次，断言播放未中断（`VideoProbe` 持续出 `type=2`）、第二次进页面画面仍动。

- [ ] **Step 5: 写 V6（车机首屏几何）**

1280x720 车机档，画面 + 播控同屏时断言 `controls` 行节点 `bounds.maxY <= 720`。取 bounds 时注意 uiautomator 的裁切失真：忽高忽矮是裁切，稳定偏矮才是缺陷。

- [ ] **Step 6: 跑全量单测**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全绿，含 `BrowserFactoryTest`（`assertSame` 凭证不变式）与 `OperaSpeedTest`（电台恒 1.0）—— 这两个不许红。

- [ ] **Step 7: R8 混淆包回归（V7）**

Run: `.\gradlew.bat :app:assembleRelease`
按 spec §9.2 V7 在混淆包上重跑 V2/V3/V6。判定以 `mapping.txt` 为准，不看堆栈字符串猜。

- [ ] **Step 8: 出 EGG 自测包并记 SHA-256**

沿用项目既有 EGG 包构建与命名规范产出 `chinese-online-radio-<ver>-release-EGG.apk`，并 `Get-FileHash -Algorithm SHA256 <apk>` 记录到交付说明。

- [ ] **Step 9: 提交**

```bash
git add .qtest/video_acceptance.ps1
git commit -m "test(ui): add video playback acceptance script"
```

---

## 计划级自查结论

- **Spec 覆盖**：§5.1→Task 3，§5.2→Task 2，§5.3→Task 4/5，§5.4→Task 4（裁决任务），§5.5→Task 6，§6.1→Task 7/8，§6.2/§6.3→Task 9，§7→Task 6/9/10，§9.1→Task 1/2/6 步骤，§9.2→Task 10。无遗漏。
- **占位符**：无 TBD/TODO；两处「若 API 不存在」均为给定确切删行动作，不是留白。
- **类型一致性**：`shouldDecodeVideo(stageMounted)`、`setVideoStageMounted(mounted)`、`acquireStage/releaseStage`、`hasVideoTrack`、`looksLikeVideo(fileNameOrHref)` 在各任务间拼写与签名一致。
- **未覆盖项如实标注**：`onTracksChanged` 的 `Tracks` 解析是薄胶水，**无 JVM 单测**，由 Task 4 的真机日志与 Task 10 的 V2/V3 覆盖（spec §5.3 已标"未验证"须由真机裁决）。
