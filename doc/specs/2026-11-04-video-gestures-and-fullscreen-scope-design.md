# 视频全屏范围修正 + 控制层手势 · 设计文档

- 日期：2026-11-04（同日按自审结果重写，见 §0 与 §8）
- 前置：`doc/specs/2026-11-03-video-fullscreen-controls-design.md`（已实现并合入 main `bc2e3cc`）
- 起因：用户在**手机上**实测报告「视频播放成功后，看不到全屏、自动横屏、手动旋转」
- 状态：待用户审阅 → 通过后调 writing-plans

---

## 0. 分批（用户已定）

| 批次 | 内容 | 解决什么 |
|---|---|---|
| **第一批（本文档 §2–§6）** | 全屏入口两种形态都给；车机"全屏"= 画面独占面板；旋转按钮（仅全屏态、仅可转设备）；控制层常驻可发现；`canRotate` 翻转防护 | **用户报的 bug 本体** |
| **第二批（本文档 §7，另出计划）** | 手势层：竖直亮度/音量、双击暂停、水平 seek | 增强项，非 bug |

拆开的理由：第一批改动小、可当天验完；第二批是整套新交互，混做会让"手势抢事件"这类问题（见 §7 的 B2）和 bug 修复在验收里互相污染。

---

## 1. 根因结论（为什么看不到）

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

模拟器实测反证这条链：手机竖屏（sw=411）时按钮存在（`descr='全屏' bounds=[902,405][965,468]`），切到车机几何后同一位置查不到任何按钮节点。

**第二个原因同样致命但属于可用性**：控制层 3 秒自动收起，而"点画面唤出"没有任何提示。不知道要点的用户看到的就是"这个 App 没这功能"。

---

## 2. 第一批决定（推翻上一轮三条）

| # | 议题 | 决定 | 推翻的是 |
|---|---|---|---|
| G1 | 全屏入口范围 | **手机与车机都给** | F6「车机档不给全屏按钮」 |
| G3 | 手动旋转 | **给旋转按钮**，但只在"全屏态 + 可真的转"时给 | F2「不给独立旋转按钮」 |
| G5 | 可发现性 | 控制层**不再定时自动收起**，改为常驻 + 点画面切换显隐 | spec §4.3 的 3 秒自动收起 |
| G6 | 全屏语义 | 手机 = 独占整屏 + 横屏 + 沉浸；**车机 = 独占整块面板，不发方向请求** | — |

车机档给全屏入口不是形式主义：车机 1280x720 面板上非全屏时画面只占左侧约 45%（右侧是台名/简介），**"全屏"在车机上的真实含义就是"画面独占整块面板"**。

---

## 3. 第一批设计

### 3.1 决策集中在纯函数（`domain/model/VideoControlsPolicy.kt`）

```kotlin
object VideoControlsPolicy {
    /**
     * 全屏入口：只要有画面就给，不再区分形态。
     * 车机上的含义是"独占面板"（G6），不是"转方向"。
     */
    fun showsFullScreenButton(hasVideoTrack: Boolean, isFullScreen: Boolean): Boolean =
        isFullScreen || hasVideoTrack

    /**
     * 旋转按钮：只在"全屏态 + 设备真的能转"时给。
     * 车机恒横屏，给了就是"按了没反应"—— 本项目明令避免给了控件却不响应。
     */
    fun showsRotateButton(isCarSurface: Boolean, isFullScreen: Boolean): Boolean =
        !isCarSurface && isFullScreen

    /** 全屏时页面细节区块（台名/简介/EPG/源/播控）一律不渲染，画面独占。 */
    fun showsStationDetails(isFullScreen: Boolean): Boolean = !isFullScreen

    /** 画面层：全屏时即使视频轨消失也要保留（上一轮 I1 的修复不回退）。 */
    fun showsPictureSurface(hasVideoTrack: Boolean, isFullScreen: Boolean): Boolean =
        isFullScreen || hasVideoTrack
}
```

两处连带改动必须一起做，否则旧断言会反过来钉住新设计：

- `showsFullScreenButton` **删掉 `isCarSurface` 参数**（不是留着忽略）：留着参数就会有人把条件加回去。
- `CONTROLS_AUTO_HIDE_MS` **直接删除**，同时删掉 `VideoControlsPolicyTest` 里 `assertEquals(3000L, CONTROLS_AUTO_HIDE_MS)` 那条断言。"先留一版再删"会留下一个没人用却被测试钉住的常量。

### 3.2 全屏状态机（`ui/common/VideoFullScreen.kt` 改造）

签名改为 `rememberVideoFullScreen(canRotate: Boolean)`，由调用方按 `isCarSurface()` 传。

| 动作 | 手机（`canRotate=true`） | 车机（`canRotate=false`） |
|---|---|---|
| 进入全屏 | `SCREEN_ORIENTATION_LANDSCAPE`（固定横屏，不跟随用户自动旋转开关）+ 隐藏系统栏 | **不发方向请求** + 隐藏系统栏 |
| 退出全屏 | `SCREEN_ORIENTATION_UNSPECIFIED` + 显示系统栏 | 显示系统栏 |
| 离开页面 | **无条件** `UNSPECIFIED` + `show(systemBars())` | 同左 |

**B5 翻转防护（必须照做）**：`isCarSurface()` 在全屏期间就可能翻转 —— 那正是本轮 bug 的根源场景。所以**方向复位不看 `canRotate`，永远执行**：`UNSPECIFIED` 对恒横屏设备是无害的空操作，而"跳过复位"会把人永久卡在横屏。只有**进入全屏时的方向请求**才受 `canRotate` 控制。

### 3.3 旋转按钮的行为（B4 定义清楚）

- 位置：控制层右上角，紧邻全屏按钮；`contentDescription` 用「切换横竖屏」。
- 行为：只在**全屏态**出现（`showsRotateButton`）。按下时在 `SCREEN_ORIENTATION_PORTRAIT` 与 `SCREEN_ORIENTATION_LANDSCAPE` 之间翻转 `requestedOrientation`，**全屏态保持不变**（页面 chrome 不回来，控制层仍在）—— 因为用户要的是"横看还是竖看"，不是"退出全屏"。
- 用固定 `PORTRAIT` 而不是 `SENSOR_PORTRAIT`（自审 Minor 6 定案，代码与本文以本节为准）：进入全屏已经用固定 `LANDSCAPE`，两侧语义必须一致 —— 都是"用户显式要求的那一侧"，不受系统自动旋转开关影响。代价：在全屏竖屏态把手机横过来不会自动转横，需再按一次按钮；这是显式选择，不是意外。
- 退出全屏时统一回到 `UNSPECIFIED`，不残留旋转选择。
- 不做的事：不给"锁定方向"开关，不持久化上次选的朝向（下次进全屏仍从横屏开始）。

### 3.4 控制层外观与可发现性（G5 / B6）

- 控制层**默认显示且不自动收起**；点画面切换显隐。
- 按钮只有两个：**旋转**（仅手机全屏态）、**全屏/退出全屏**。
- **控制层里不放返回按钮**（B6 定案）：非全屏时顶栏本来就有返回；全屏时返回交给系统返回键 —— 上一轮 V9 已实现且验过"返回键先退全屏、不退页面"。再放一个应用内返回按钮会出现两个语义不同的返回入口，是坑。
- 命中区：手机 `Dimens.TouchMin`(48dp)，车机 `Dimens.TouchMin`(88dp)，一律走 token，不写裸 dp。
- 单击用的 `detectTapGestures` 理论上不会吃掉竖直滚动（超过 touch slop 后由父级滚动容器接管），
  但**这条本轮没能在设备上证实**。同时更正一个我自己写错的归因：上一版说“流断了所以无画面、
  无画面即无手势层，F11 不可判”——**这是错的**，同轮留存 dump 里 `全屏` 按钮在位，手势层当时装着。
  F11 真正测不了的原因是两条：（1）本页在 1080x2424 下内容不溢出（dump 里 `scrollable=true` 节点数 = 0），
  没的可滚；（2）旧判据拿“文本集合变化”当信号，而播放状态文案（「正在尝试节目源」→「缓冲中」）
  本来就在变 —— 本轮那条“对照能滚、画面内不能滚”的反向证据就是这么造出来的**假 FAIL**。
  **第二批开工前必须先关掉这个口子**：先把页面变成必然溢出（字号 1.5 或短窗），用
  **稳定文本的 y 坐标位移**作信号，做“画面内 vs 画面外同距”对比。在此之前，
  不得拿它当“已验证”写进任何实现注释。

### 3.5 两个播放页的接入

- `PlayerScreen`（电台直播）：`mediaVisual` 继续走 `showsPictureSurface`；全屏时画面 modifier 用 `fillMaxSize()`，非全屏保持现有 `fillMaxWidth().aspectRatio(16f/9f)`；细节区块（台名/EPG/源/播控）按 `showsStationDetails` 跳过 —— 车机档由此从"Row 分栏"变成"画面独占面板"。
- `OperaPlayerScreen`（云盘点播）：同一套，`contentArea` 在全屏时拿 `fillMaxSize()`。
- 两页都改成 `rememberVideoFullScreen(canRotate = isCarSurface().not())`。

---

## 4. 错误处理与降级（第一批）

| 情形 | 处理 |
|---|---|
| `LocalContext` 不是 Activity（预览/嵌入） | 全屏按钮不安装；不发方向请求；不崩不报错 |
| 车机 ROM 忽略 `hide(systemBars())` | 静默降级为"只独占不沉浸"，不提示 |
| 全屏期间形态判定翻转（sw≥600 手机） | 按钮仍在（`showsFullScreenButton` 不再看形态）；退出时方向无条件复位 |
| 用户在播放中切到纯音频台 | 画面层与退出入口仍在（上一轮 I1 语义），按钮文案仍为「退出全屏」 |

---

## 5. 测试与验收（第一批）

### 5.1 JVM 单测（`VideoControlsPolicyTest` 扩充）

| 用例 | 断言 |
|---|---|
| 车机档有画面也必须给全屏入口 | `showsFullScreenButton(hasVideoTrack=true, isFullScreen=false) == true`（**本轮回归守卫**） |
| 无画面且非全屏不给 | `showsFullScreenButton(false, false) == false` |
| 全屏时恒给（退出入口不丢） | `showsFullScreenButton(false, true) == true` |
| 旋转按钮只在手机全屏给 | 车机全屏 `false`；手机全屏 `true`；手机非全屏 `false` |
| 全屏时细节区块不渲染 | `showsStationDetails(true)=false` / `(false)=true` |
| 常量清理 | `CONTROLS_AUTO_HIDE_MS` 已不存在（旧断言删除，靠编译期保证） |

`VideoFullScreen` 与 `VideoStageFrame` 是 Composable/Android API，**JVM 层无法单测（须标未验证）**，由 §5.2 覆盖。

### 5.2 真机验收（`tools/video_acceptance.ps1` 追加 F6–F11）

| # | 判据 |
|---|---|
| F6 | **车机几何（1280x720@160）下进入播放页，不点画面直接 dump**，`content-desc="全屏"` 节点必须在位（上一轮就是这里查不到节点）。控制层现在常驻，**先点画面反而会把按钮收起来**，照旧说法手工执行会把本轮修好的 bug 再造一遍 |
| F7 | 车机点全屏后画面独占面板：台名/简介/播控节点数 0；**画面尺寸用截图量不用节点 bounds**（本项目已实测 bounds 会裁切失真）：截图非背景像素行跨度 ≥ 屏高 85% |
| F8 | 手机竖屏点全屏 → 横屏（截图宽高翻转）+ 系统栏隐藏；按返回键先退全屏 |
| F9 | 控制层**不自动收起**：进入视频页 10 秒后按钮节点仍在 |
| F10 | 手机全屏态有「切换横竖屏」按钮，按下后截图宽高互换且仍为全屏态（无台名节点）；车机全屏态该按钮不存在 |
| **F11** | **手机非全屏时从画面区域滑动仍能翻页**：信号必须是稳定文本的 **y 坐标位移**（排除缓冲/换源类状态文案），且必须有硬前置：dump 里 `scrollable=true` 节点数为 0 时直接判 INCONCLUSIVE（“没的可滚”要量出来，不能由“文本没变”倒推）。对照组（画面外同距）也无位移时同样判 INCONCLUSIVE，**不得计作 PASS 也不得计作 FAIL**。本轮状态：未定 |
| 重跑 | V1（纯音频台零回退）、V2/V6（车机几何与播控）、V8/V9/V10（全屏与复位）、R8 复跑 |

**已知环境限制**：这台模拟器的虚拟传感器会压过应用的方向请求（自动旋转开/关各复现一次），F8/F10 的"真的转"必须在真机确认；模拟器上只能验到请求写入窗口（`dumpsys` 的 `requestedOrientation`）。

---

## 6. 第一批非目标

- 画中画 / 小窗（F3 不变）
- 方向锁定开关、朝向持久化
- 控制层内的应用内返回按钮（B6 定案：交给系统返回键）
- 手势层全部内容 → 见 §7，另批处理

---

## 7. 第二批预告：手势层（本轮不实现，规则先定死）

用户已定的两条规则，第二批设计必须遵守：

1. **拖动类手势只在全屏态生效**（B2 定案）。理由：手机非全屏时 `PlayerScreen` 在 `verticalScroll` 里，画面占屏很大一块，吃掉竖直拖动就等于**用户无法从画面向下滑翻页** —— 这不是"必要代价"，是我第一版 spec 只看了车机就下的错判。全屏态下页面本就不可滚，随便装。
2. **水平 seek 按有无时长装**（G4）：`hasDuration = duration > 0L`（复用项目既有判据，`OperaPlayerScreen` 的 `canSeek` 就是它）。直播不装水平检测器，手势原样交给页面。**不按形态禁用**：车机上的云盘视频有时长，照样给 seek。

第二批开工前必须先解决、当前尚未定的两件事：

- **B3 手势组合方式**：单击/双击/竖直拖/水平拖在同一个节点上怎么共存没写。多个 `pointerInput` 会互相抢事件，`detectTapGestures` 与两个 drag detector 不能简单并列。要么写一个 `awaitPointerEvent` 循环按首个超过 slop 的方向分类，要么明确分层顺序。
- **B7 亮度判据可执行性**：窗口亮度不在 uiautomator 里，验收得改成读 `dumpsys window` 的 `screenBrightness`，或在手势层加一行可断言的诊断日志。

亮度用 `window.attributes.screenBrightness`（不申请 `WRITE_SETTINGS`），`onDispose` 还原 `NaN`；音量用 `AudioManager.adjustStreamVolume(STREAM_MUSIC, ...)` 并捕获 SecurityException。

---

## 8. 自审记录（本轮改了什么）

| 编号 | 问题 | 处理 |
|---|---|---|
| B1 | §6 把“车机上的水平 seek”列为非目标，与 §3.3/G4“只按有无时长、与形态无关”直接矛盾 | 删除该非目标；§7 明确写“不按形态禁用” |
| **B2** | 我原先把"竖直拖动被手势层吃掉"当作"车机触屏的必要代价"，但手机侧 `PlayerScreen` 在 `verticalScroll` 里，这会让用户无法从画面下滑翻页 | 改为**拖动手势只在全屏生效**；并新增 F11 作为滚动基线守卫 |
| B3 | 五个手势如何共存完全没写，实现者必须自己发明 | 不掩盖：列为第二批开工前必须先定的事项 |
| B4 | 旋转按钮的行为没定义（切了还算不算全屏？退出时怎么办？） | §3.3 定死：仅全屏态、SENSOR_PORTRAIT ↔ LANDSCAPE 互切、保持全屏、退出统一 UNSPECIFIED |
| B5 | `canRotate` 由调用方传入，但 `isCarSurface()` 会在全屏期间翻转，可能跳过方向复位把人卡横屏 | §3.2 定死：进入受 `canRotate` 控制，**退出/离开无条件复位** |
| B6 | §3.4 同一句既说"左上角保留返回"又说"靠系统返回键" | 定案：控制层不放返回按钮 |
| B7 | F10 原写"竖直左半改窗口亮度"，但窗口亮度不在 uiautomator 里，判据不可执行 | 移到第二批并写明改判据方式 |
| B8 | 缺"手机非全屏仍可滚动"的回归项 | 新增 F11 |
| B9 | 四个布尔函数条件重叠，长期应收成一个 `VideoControlSet` | 记为可选重构，**不混进本轮** |

---

## 9. 交付

- 文档：本文件；第一批实现计划落 `doc/plans/2026-11-04-video-fullscreen-scope-plan.md`
- 第二批另出 spec/plan，不并入本轮
- 构建：EGG 自测包先行；R8 复跑 F6–F11
- Git：spec 单独一次提交（Conventional Commit 英文）；不 push、不 merge，除非用户明确要求
