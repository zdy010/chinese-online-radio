# 车机版 UI 改造设计（v1）

- 日期：2026-09-26
- 状态：待评审
- 范围：**只做界面形态与车机交互适配，不含硬件调谐器**（FM/AM/短波另立 spec，见 §12）
- 决策来源：本会话 brainstorming，人已确认的五项见 §11 决策清单
- 目标设备：梁山嘟嘟 S1（方易通 FYT 方案 / 展锐 UIS7862，AOSP 安卓 9~10，公签可侧载），装在标致 508 2015 款上，方控与车辆信号走加装方案的 CAN 协议盒

---

## 0. 目标与非目标

**目标**：同一个 APK 在车机上提供"远距离可读、行车中可安全操作"的形态——大触控目标、少层级、横屏充分利用宽度。"免键盘"靠 rail + 分类/收藏/最近 直达实现（而不是靠新增选台弹层），键盘仍是可选入口之一。

**非目标**：不新增功能能力（除 §4 的 ±15 秒按钮与 §5 的开机自启）；不做 AAOS Car App Library 模板（本机非原生 AAOS，`android.car` 依赖不上）；不做仪表盘/HUD 双屏；不改手机端任何视觉。

---

## 1. 术语：UiSurface（界面形态）

```kotlin
// ui/theme/UiSurface.kt（新增）
enum class UiSurface { Phone, Car }

val LocalUiSurface = compositionLocalOf { UiSurface.Phone }
```

全树唯一判定源。任何页面只允许通过 `LocalUiSurface.current == UiSurface.Car` 分支，禁止各页自己读 `LocalConfiguration` 判屏幕（避免同一棵树里出现两套判定）。

## 2. 判定规则与手动覆盖

自动判定：`smallestScreenWidthDp >= 600 && orientation == LANDSCAPE` → Car。

**必须有手动覆盖**，因为加装车机的 density 配置极随意：1280x720@160dpi 是 sw720dp（判对），但 1920x1200@480dpi 只有 sw400dp（误判成手机）、800x480@160dpi 是 sw480dp（误判）。

- 偏好存储：`RadioPreferences` 新增 `KEY_UI_MODE = intPreferencesKey("ui_mode")`，`uiMode: Flow<Int>`（0=自动 / 1=手机 / 2=车机）+ `setUiMode(Int)`
- 生效点：`ChineseRadioTheme` 新增入参 `uiMode: Int = 0`，内部算出 surface 后用 `CompositionLocalProvider(LocalUiSurface provides surface)` 包裹 content；`MainActivity` 从 `preferences.uiMode` 收集并透传
- 要求：切换后**无需重启即生效**（重组驱动）

## 3. Token 换档（零调用点改动）

已核实：`Dimens.` 全项目 127 处调用，全部位于 `ui/` 包内的 composable 上下文，非 ui 包 0 处，无顶层 `val = Dimens.x`。因此把下列 token 改为 `@Composable get()`，**127 个调用点一行不改**。

```kotlin
object Dimens {
    private val car: Boolean @Composable get() = LocalUiSurface.current == UiSurface.Car

    val TouchMin: Dp @Composable get() = if (car) 88.dp else 48.dp
    // ... 其余同理；未列出的 token 保持普通常量
}
```

| token | 手机（不变） | 车机 |
|---|---|---|
| TouchMin | 48dp | 88dp |
| RowMinHeight | 56dp | 88dp |
| ScreenPadding | 16dp | 32dp |
| ListGap | 8dp | 16dp |
| MiniPlayerHeight | 72dp | 104dp |
| OperaMiniPlayerHeight | 104dp | 144dp |
| CoverSmall | 48dp | 72dp |
| CoverLargeMax | 220dp | 320dp |
| SourceListMax | 160dp | 280dp |

新增两个 token（替换 PlayerScreen / MiniPlayerBar 里现存的裸 dp 字面量，符合"新代码禁止裸 dp"规范）：

| 新 token | 手机 | 车机 | 替换掉 |
|---|---|---|---|
| PlayButtonSize | 64dp | 96dp | `Dimens.GapHuge * 2` |
| PlayIconSize | 40dp | 56dp | `40.dp` |

**字号规则（已确认）**：车机档取"下限"而非叠乘——`effectiveFactor = if (car) maxOf(userFactor, 1.15f) else userFactor`。即车机上"标准"按"大"渲染，用户仍可选"超大"（1.3），**不会出现 1.3×1.15 的失控放大**。生效点：`ChineseRadioTheme` 内传给 `appTypography(...)` 前完成。

## 4. 页面改造清单

| 页面 | 手机档（保持原样） | 车机档 | 实现要点 |
|---|---|---|---|
| RadioScreen | 顶部 `TabRow` + `HorizontalPager` | **左侧 `NavigationRail`**（全部/分类/收藏/最近）+ 右侧内容 | pager 状态与左右滑动**保留**，rail 的 `onClick` 调 `animateScrollToPage`；布局为 `Scaffold` 内容里 `Row { NavigationRail; Box(weight(1f)) }`（M3 Scaffold 无 rail 槽位） |
| AudioMainScreen | 同上（本地/网络/收藏/最近） | 同上 rail | 同上；刷新/添加按钮仍在 `SearchBarRow` 动作槽 |
| HomeScreen 列表 | `LazyColumn` 单列 | `LazyVerticalGrid(GridCells.Adaptive(300.dp))` | `StationListItem` 直接当 cell 复用（内部已是自适应宽 Row）；`contentPadding.bottom` 继续用 `MiniPlayerHeight` |
| CategoryScreen | `GridCells.Fixed(2)` | `GridCells.Adaptive(240.dp)` | 卡片内 `40.dp/20.dp/12.dp` 裸值换 token（Icon 走 `IconLarge`） |
| FavoritesScreen / RadioRecentTab | 单列 | 同 Home 的 Adaptive grid | 复用同一分支写法 |
| PlayerScreen | 单列纵向 | **左右分栏**：左=封面+台名+频率+简介，右=状态+节目源+播放控件 | `Row { Column(weight(1f)); Column(weight(1.2f)) }` 作为 `verticalScroll` 容器的子项（Row 内 weight 是横向，与纵向滚动不冲突）；播放键 `PlayButtonSize`；收藏/定时器保持顶栏。现存的 `shortViewport`（视口 <500dp 时隐简介 + 缩封面）降级策略**保留**，仅用于 800x480 这类矮屏 |
| RadioMiniPlayerBar | 72dp 单行 | 104dp，图标与文字随 token 放大 | 高度已由 `MiniPlayerHeight` 驱动，改 token 即生效 |
| OperaMiniPlayerBar | Slider + 两行控件 | Slider **保留可拖动**，命中区抬高到 64dp；**新增 ±15 秒两个大按钮**；行高由 `OperaMiniPlayerHeight` 放大 | Slider 外层 `heightIn(min = 64.dp)`；新增 `PlayerManager.seekOperaBy(deltaMs: Long)`，内部 `seekOperaTo((pos + delta).coerceIn(0, duration))` |
| AudioLibraryScreen / BrowseScreen / AudioFavoritesTab / AudioRecentTab | 单列 | 行高 `RowMinHeight`（自动 88dp）+ 列表项文字升 `bodyLarge` | 行高与字号已经引用 §3 的 token，换档后自动生效，不新增机制 |
| SettingsScreen | 单列 | 单列 + **居中限宽 720dp**；新增三项见 §5 | 不做双列（YAGNI） |
| ManageScreen / AddStationDialog / AddSourceDialog / SleepTimerDialog | 现有布局 | 对话框 `widthIn(max = 720.dp)` 居中，控件随 token 放大 | `ExposedDropdownMenu` 方案不变（上轮已验证在对话框内可用） |
| StateViews（Loading/Empty/Error） | 图标 `CoverSmall*2` | 自动变大（走 token） | 无需改动 |

**明确不改**：`SearchBarRow` 的位置与行为（车机档只是变宽变大，不引入新的选台弹层）；页面导航结构；任何既有交互入口（依"UI 重构禁止擅自移除交互控件"规范）。

## 5. 车机专属行为

1. **方控/媒体键**：`RadioService` 已是 `MediaSessionService` 且注册了 `MediaButtonReceiver`，能力现成。本期**不加代码，只加验收项**（§7-6）。
2. **开机自启**：`RECEIVE_BOOT_COMPLETED` 权限已声明但闲置。新增 `service/BootReceiver` + manifest 注册（`exported=true`，`BOOT_COMPLETED`），行为是 **仅拉起 `RadioService` 恢复媒体会话，不自动播放**（避免一上车就出声，也绕开安卓 10 后台起 Activity 限制）。设置项「开机自启」控制，**默认关**。
3. **行车风险缓解（v1，纯界面）**：88dp 触控目标、对话框限宽减少视线漂移、不新增多级弹窗。车速信号驱动的强制限制**不在本期**（等 CAN 探测结果）。
4. **系统栏**：保持现有 edge-to-edge 处理，**不做沉浸式隐藏**——加装车机 ROM 的状态栏行为差异太大，隐藏容易反而出问题。列为后续可选。
5. **Manifest**：除 BootReceiver 注册外**不改**（不加 `screenOrientation` 锁定，车机有横有竖；`supports-screens` 在 API 26+ 无实际作用）。

## 6. 手机端零回退（硬约束）

`UiSurface.Phone` 下所有 token 取原值、所有布局分支走 `else` 路径 → 手机端必须与改造前**逐像素一致**。这条不是愿望，由 §7-5 的截图回归把关。

## 7. 验收标准（可测断言）

1. 车机档：uiautomator dump 中**任何可点控件 bounds 短边 ≥88dp**（脚本遍历 clickable 节点）
2. 车机档：`bodyLarge/bodyMedium/bodySmall` 实际字号 ≥19.6/17.3/15.0sp（factor 下限 1.15 生效）
3. 1280x720@160 与 1920x1200@240 两档横屏下，播放页**首屏不滚动即可看到**：播放/暂停、上一个、下一个、收藏、睡眠定时器
4. 车机档：列表页横向无大片死白（grid 列数 ≥2），音频库浏览行可点区 ≥88dp
5. 手机档（411x914dp）：逐页截图与改造前基线一致；`全部/分类/收藏/最近` 与音频库四 Tab 行为不变
6. 方控实测：播放/暂停、上一首、下一首在车机档与手机档均生效（`input keyevent 85/87/88` 或真车按键）
7. 开机自启开启后重启车机：服务存活、通知存在、**未自动播放**
8. 全程 logcat 无 `FATAL EXCEPTION`、无 ANR
9. 界面模式在设置里切换后**不重启**立即重排

## 8. 验证方法

- 沿用本会话已建的 `.qtest/` 采集脚本（`screencap` + `uiautomator dump` + bounds 解析）
- 车机档模拟器：新建 AVD 后用 `adb shell wm size 1280x720` / `wm density 160` 与 `1920x1200` / `240` 两档跑全页；手机档用默认 411x914dp
- 手机档基线截图在动工**之前**先采一轮存 `.qtest/baseline/`，作为 §7-5 的比对基准
- 发布前回归沿用既有《Release 版本发布前验证清单》

## 9. 风险与对策

| 风险 | 对策 |
|---|---|
| `@Composable get()` 存在漏网的非 composable 调用点 | 已静态核实 ui 包外 0 处；编译期必然暴露，逐个改成显式传参 |
| 低分辨率横屏（800x480）rail 高度不够 | **已知限制**：本期按 ≥720dp 高设计；此类机器用「界面模式=手机」兜底，不做第三形态 |
| 车机 density 千奇百怪 | 一律用 `GridCells.Adaptive`，不写死列数 |
| 中文长台名在 2 列网格里截断 | 已有 `maxLines=1 + Ellipsis`；grid 最小宽 300dp 保证可读长度 |
| 放大后对话框超屏 | 对话框已 `verticalScroll`（上轮补的）+ 限宽 720dp |
| BootReceiver 被 ROM 省电策略杀 | 默认关；开启后在设置页显示"依赖车机允许自启"提示文案 |

## 10. 分期（任务边界即建议提交边界）

- **T0** 手机档基线截图采集（无代码）
- **T1** `UiSurface` + `LocalUiSurface` + `uiMode` 偏好 + 主题注入 + 设置页「界面模式」
- **T2** `Dimens` 换 composable getter + 新 token + 字号下限规则（含编译期调用点核验）
- **T3** 两个宿主页面 rail 化 + Home/Category/Favorites/Recent 的 Adaptive grid
- **T4** PlayerScreen 车机分栏 + PlayButtonSize/PlayIconSize
- **T5** 两个迷你条放大 + `seekOperaBy` + ±15 秒按钮
- **T6** 设置页新增三项 + BootReceiver + 音频库各列表行高/字号接线
- **T7** ManageScreen 与三个对话框限宽放大
- **T8** 双档模拟器验证 + bounds 断言 + 手机档回归

## 11. 已确认的决策清单

1. 包形态：**单 APK 自适应**（非独立 flavor），保留将来按 `UiSurface` 接口拆 flavor 的可能
2. 功能范围：**全功能放大版**——音频库/WebDAV/授权码等都上车机档，不做精简
3. 戏曲进度条：**保留可拖动 Slider**（命中区抬高到 64dp）+ **新增 ±15 秒按钮**，不砍既有能力
4. 车机档字号：**下限 1.15（=「大」档），不叠乘**，用户仍可升到 1.3
5. 硬件收音本期不做，等 FYT 探测结果另立 spec

## 12. 后续独立 spec（不在本期）

- **硬件调谐接入**：等 FytHWOneKey / FET 探测结果（`com.syu.radio` 导出组件、MediaBrowserService、厂商 AIDL）→ 决定联动模式 / 直控模式
- **短波（SW）**：USB SDR 外接，独立子系统
- **行车中限制**：接 CAN 车速信号后做强制降级交互
