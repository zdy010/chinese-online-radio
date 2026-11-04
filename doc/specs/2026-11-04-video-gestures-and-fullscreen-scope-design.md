# 视频全屏范围修正 + 控制层手势 · 设计文档

- 日期：2026-11-04
- 前置：`doc/specs/2026-11-03-video-fullscreen-controls-design.md`（已实现并合入 main `bc2e3cc`）
- 起因：用户在**手机上**实测报告「视频播放成功后，看不到全屏、自动横屏、手动旋转」
- 状态：待用户审阅 → 通过后调 writing-plans

---

## 1. 根因结论（先说清楚为什么看不到）

不是 R8 打掉了功能，也不是渲染失败。按钮与手势装不装只由一个纯函数决定：

```kotlin
// VideoControlsPolicy（现状）
fun showsFullScreenButton(isCarSurface, hasVideoTrack, isFullScreen) =
    isFullScreen || (!isCarSurface && hasVideoTrack)
//                          ^^^^^^^^^^^^^ 这一项把 Car 形态整个排除
```

而 `isCarSurface()` 在这三种情况下都会是 `true`：

1. 设置里「界面模式」= 车机（`resolveUiSurface` 的 `mode=2` 强制档）；
2. 手机 `smallestScreenWidthDp ≥ 600` 且横着拿（自动档规则：`sw≥600 && 横屏`）；
3. 车机本身恒横屏。

模拟器实测反证了这条链：手机竖屏（sw=411）时按钮存在（`descr='全屏' bounds=[902,405][965,468]`），切到车机几何后同一位置查不到任何按钮节点。

**第二个原因同样致命但属于可用性**：控制层 3 秒自动收起，而"点画面唤出"这件事没有任何提示。不知道要点的用户看到的就是"这个 App 没有全屏功能"。

---

## 2. 本轮决定（推翻上一轮三条）

| # | 议题 | 决定 | 推翻的是 |
|---|---|---|---|
| G1 | 全屏入口范围 | **手机与车机都给** | F6「车机档不给全屏按钮」 |
| G2 | 手势层范围 | **手机与车机都装**（音量/亮度/双击） | F1「不做手势层」 |
| G3 | 手动旋转 | **给旋转按钮** | F2「不给独立旋转按钮」 |
| G4 | 直播上的滑动 | **按有无时长区分**：有时长才装水平 seek；直播不拦水平手势 | — |
| G5 | 可发现性 | 控制层**不再定时自动收起**，改为常驻 + 点画面切换显隐 | spec §4.3 的 3 秒自动收起 |

车机档给全屏入口不是形式主义：车机 1280x720 面板上非全屏时画面只占左侧约 45%（右侧是台名/简介），**"全屏"在车机上的真实含义是"画面独占整块面板"**。

---

## 3. 设计

### 3.1 决策集中在纯函数（`domain/model/VideoControlsPolicy.kt`）

```kotlin
object VideoControlsPolicy {
    /** 全屏入口：只要有画面就给，不再区分形态。车机上含义是"独占面板"。 */
    fun showsFullScreenButton(hasVideoTrack: Boolean, isFullScreen: Boolean): Boolean =
        isFullScreen || hasVideoTrack

    /**
     * 旋转按钮：只给能真的转的设备。车机恒横屏，给了就是"按了没反应"——
     * 这是本项目明令避免的反模式（给了控件却不响应）。
     */
    fun showsRotateButton(isCarSurface: Boolean, isFullScreen: Boolean): Boolean =
        !isCarSurface && isFullScreen

    /** 水平 seek 手势只在有时长时有意义；直播装了会表现为"滑了没反应"或跳回起点。 */
    fun showsSeekGestures(hasDuration: Boolean): Boolean = hasDuration

    /** 音量/亮度/双击：与时长无关，两种形态都装。 */
    fun showsSystemGestures(hasVideoTrack: Boolean): Boolean = hasVideoTrack

    /** 临时数值提示（音量/亮度）的停留时长。G5 后唯一的定时隐藏行为。 */
    const val LEVEL_HINT_MS: Long = 1000L
}
```

两处连带改动必须一起做，否则旧断言会反过来钉住新设计：

- `showsFullScreenButton` **删掉 `isCarSurface` 参数**（不是忽略它）：留着参数就会有人再把条件加回去。
- `CONTROLS_AUTO_HIDE_MS` **直接删除**，同时删掉 `VideoControlsPolicyTest` 里那句 `assertEquals(3000L, CONTROLS_AUTO_HIDE_MS)`。“先留一版再删”会留下一个没人用、但被测试钉住的常量，下次谁改它都得先弄明白它为什么不生效。

### 3.2 全屏语义按形态分岔（关键，不能只按方向理解）

| 形态 | 进入全屏时 | 方向请求 |
|---|---|---|
| 手机 | 画面独占整屏 + 隐藏系统栏 | `SCREEN_ORIENTATION_LANDSCAPE`（固定横屏，不跟随用户的自动旋转开关） |
| 车机 | 画面独占整块面板（其余区块不渲染） | **不发方向请求**（恒横屏，发了也是无效操作） |

实现上：`rememberVideoFullScreen` 增加 `canRotate: Boolean` 入参（由调用方按 `isCarSurface()` 传），`LaunchedEffect` 里 `if (canRotate)` 才动 `requestedOrientation`；系统栏隐藏两边都做。退出全屏时同样只在 `canRotate` 时复位方向。

### 3.3 手势层（`ui/common/VideoGestureLayer.kt`，新增）

装在 `VideoStageFrame` 的画面之上，**只在 `showsSystemGestures` 为真时安装**。

| 手势 | 作用 | 实现 | 生效条件 |
|---|---|---|---|
| 竖直滑动·左半屏 | 亮度 | `activity.window.attributes.screenBrightness`（0f..1f，**不用 WRITE_SETTINGS 权限**） | 两边 |
| 竖直滑动·右半屏 | 音量 | `AudioManager.adjustStreamVolume(STREAM_MUSIC, ...)` | 两边 |
| 双击 | 暂停/继续 | `playerManager.togglePlayPause()` | 两边 |
| 水平滑动 | 快进/快退（±10s 步进，跟手方向） | `seekOperaForward/Backward` | **仅 `hasDuration`**（G4） |
| 单击 | 切换控制层显隐（G5） | 本地状态 | 两边 |

三条必须写死的取舍：

- **单击与双击共存**：`detectTapGestures` 的单击要等双击超时（约 300ms）才触发，所以"点画面收起控制层"会有轻微延迟。接受，并在实现注释里写明，避免后来者当成 bug 去"优化"掉双击。
- **直播不安装水平检测器**：这样水平方向的手势原样交给页面/列表，不会抢掉既有交互。竖直方向仍被手势层吃掉（亮度/音量），这是车机触屏上"看视频时调亮度"的必要代价，写进文档。
- **亮度必须还原**：`DisposableEffect onDispose` 把 `screenBrightness = NaN`（= 跟随系统），否则用户退出视频页后屏幕一直停在视频设的亮度。这条是"离开页面复位"的一类，和方向/系统栏同等重要。

### 3.4 控制层外观与可发现性（G5）

- 控制层**默认显示且不自动收起**；点画面切换显隐。
- 按钮排布：右上角一行三个 —— 旋转（仅手机全屏时）、全屏/退出全屏；左上角保留返回（非全屏时页面本来就有顶栏返回，全屏时靠系统返回键退全屏，逻辑不变）。
- 音量/亮度调节时给一个短暂的居中数值提示（1 秒后消失）；这是唯一保留的定时收起行为，因为它遮的是临时信息而非入口。
- 命中区：手机 `Dimens.TouchMin`(48dp)，车机 `Dimens.TouchMin`(88dp)，一律走 token，不写裸 dp。

### 3.5 两个播放页的接入

- `PlayerScreen`（电台直播）：`hasDuration` 恒为 `false` → 只装音量/亮度/双击/单击，不装水平 seek。全屏入口两边都给。
- `OperaPlayerScreen`（云盘点播）：`hasDuration = playback.duration > 0L`（复用项目既有判据）→ 全套装。
- 两页的 `mediaVisual`/`contentArea` 继续用 `showsPictureSurface`，全屏时画面层不消失（上一轮 I1 的修复不回退）。

---

## 4. 错误处理与降级

| 情形 | 处理 |
|---|---|
| `LocalContext` 不是 Activity（预览/嵌入） | 手势层不安装，全屏按钮不安装；不崩不报错 |
| 车机 ROM 忽略 `hide(systemBars())` | 静默降级为"只独占不沉浸"，不提示 |
| 音量流被系统策略限制（个别车机） | `adjustStreamVolume` 抛异常时捕获并记日志，手势继续可用（不闪退） |
| 亮度设了但系统开了自动亮度 | 窗口亮度优先级更高，退出时还原 NaN 即回到系统行为 |
| 用户在播放中切到纯音频台 | `hasVideoTrack` 变 false → 画面层仍在（全屏态），手势层随 `showsSystemGestures` 关掉，避免"对着黑屏调亮度" |

---

## 5. 测试与验收

### 5.1 JVM 单测（`VideoControlsPolicyTest` 扩充）

| 用例 | 断言 |
|---|---|
| 车机档有画面也必须给全屏入口 | `showsFullScreenButton(hasVideoTrack=true, isFullScreen=false) == true`（**这条就是本轮回归守卫**） |
| 无画面不给 | `showsFullScreenButton(false, false) == false` |
| 全屏时恒给 | `showsFullScreenButton(false, true) == true` |
| 旋转按钮只在手机全屏给 | 车机全屏 `false`；手机全屏 `true`；手机非全屏 `false` |
| 水平 seek 只给有时长 | `showsSeekGestures(true)=true` / `showsSeekGestures(false)=false` |
| 音量亮度与时长无关 | `showsSystemGestures(true)=true` / `(false)=false` |
| 常量改动 | `LEVEL_HINT_MS == 1000L`；`CONTROLS_AUTO_HIDE_MS` 已不存在（删掉旧断言，新增编译期保证） |

手势层与全屏组件是 Composable/Android API，**JVM 层无法单测（须标未验证）**，由下面真机项覆盖。

### 5.2 真机验收（`tools/video_acceptance.ps1` 追加 F6–F10）

| # | 判据 |
|---|---|
| F6 | **车机几何（1280x720@160）下播视频，点画面能看到全屏按钮**（上一轮就是这里查不到节点） |
| F7 | 车机点全屏后画面独占面板：`uiautomator` 中台名/简介/播控节点数 0；**画面尺寸用截图量，不用节点 bounds**（本项目已实测 bounds 会裁切失真，忽高忽矮是裁切、稳定偏矮才是缺陷）：截图上非背景像素行跨度 ≥ 屏高 85% |
| F8 | 手机竖屏点全屏 → 横屏（截图宽高翻转）+ 系统栏隐藏；按返回键先退全屏 |
| F9 | 控制层**不自动收起**：进入视频页 10 秒后按钮节点仍在 |
| F10 | 手势按形态正确：直播页水平滑动**不产生 seek**（`VideoGate`/播放位置日志无跳变）；点播页水平滑动位置变化 ≥8s；竖直左半改窗口亮度、右半改系统音量；双击切暂停 |
| 重跑 | V1（纯音频台零回退）、V2/V6（车机几何与播控）、V8/V9/V10（全屏与复位）、R8 复跑 |

**已知环境限制**：这台模拟器的虚拟传感器会压过应用的方向请求（自动旋转开/关各复现一次），F8 的"真的转横屏"必须在真机确认；模拟器上只能验到请求写入窗口（`dumpsys` 的 `requestedOrientation`）。

---

## 6. 非目标

- 画中画 / 小窗（仍未做，F3 不变）
- 字幕渲染、多音轨选择、投屏
- `WRITE_SETTINGS` 系统亮度权限（用窗口亮度替代，无需权限）
- 车机上的水平 seek（G4 判定为无时长即不装，与形态无关）

---

## 7. 交付

- 文档：本文件；实现计划落 `doc/plans/2026-11-04-video-gestures-and-fullscreen-scope-plan.md`
- 构建：EGG 自测包先行；R8 复跑 F6–F10
- Git：spec 单独一次提交（Conventional Commit 英文）；不 push、不 merge，除非用户明确要求
