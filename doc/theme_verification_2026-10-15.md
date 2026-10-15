# 主题切换功能验收记录

> 2026-10-15 · 分支 `feat/theme-switching`（BASE `5085cff`）
> 设计：`doc/specs/2026-10-15-theme-switching-design.md` · 计划：`doc/plans/2026-10-15-theme-switching.md`
> 本文只记实测数字与结论；推算与待办单独标明，不混在"已验证"里。

## 1. 单元测试（JVM）

命令：`.\gradlew.bat :app:testDebugUnitTest` → **87/87 通过**（本功能新增 20 条）。

| 测试类 | 条数 | 关键内容 |
|---|---|---|
| `AppThemeTest` | 7 | key 唯一、三组各 3 项、`fromKey` 未知键回落 system、`fromLegacyMode` 0/1/2 与脏值、`fromStoredValues` 新旧键优先级、深浅单点判定、文案长度上限 |
| `PaletteMathTest` | 5 | `tonal` 逐通道插值、alpha 跟底色、WCAG 亮度/对比度用公认参考值（#808080=0.2159、#767676/白=4.54、#949494/白≈3.0）反向校验算法 |
| `ThemeContrastTest` | 5 | 9 主题 × 深浅 的全部 `(容器,前景)` 配对 ≥4.5:1；outline/surface ≥3:1；三档经典主题返回**基线实例本身**（assertSame）；覆写型主题的色调面板确实跟走（纯黑档 containerHigh 对纯黑 lift 在 1.05~1.6 之间）；amber 的 secondaryContainer/surfaceContainer 不得残留冷色 |
| `SourceQualityTest` | 3 | 分数→三档边界（≥0.5 绝不判红、NaN/越界不崩）、两套语义色在对纯白/纯黑时 ≥4.5:1、三档两两 RGB 距离 ≥60 |

RED 阶段逐条先看失败：`Unresolved reference 'AppTheme'` / `'tonal'` / `'resolveColorScheme'` / `'availabilityToQuality'`。
`PaletteMathTest` 另有一次真实失败：断言容差 0.001 撞不上 Compose 的 8 位量化（`tonal(黑,白,0.5)` 实为 128/255），改按量化域断言后通过——不是放宽阈值蒙过去。

## 2. 构建

- `:app:assembleDebug` → 通过
- `:app:assembleRelease`（R8）→ 通过，产物 `chinese-online-radio-1.0.134-release.apk`（6MB），无告警行

## 3. UI 验收（模拟器 1080x2400@420，debug 与 release 各一轮）

命令：`pwsh -File tools/theme_check.ps1`（debug）与 `... -ApkKind release`。

| 轮次 | 结果 |
|---|---|
| debug | 9/9 `OK [主题] 可点中并回显` + `RESULT: PASS` |
| release（R8 后） | 9/9 `OK` + `RESULT: PASS` |

每轮对每个主题都做三件事：从干净状态冷启动 → 在对话框里把该行滚进可视区并点中 → 断言对话框已关且设置页回显成该主题名。release 轮通过即说明 `theme_key` 字符串与 `AppTheme.entries` 在混淆后仍可解析（否则老用户存的键会静默回落成"跟随系统"）。

`mapping.txt` 侧证：`AppTheme.getEntries()`、`Companion.fromKey`、`fromStoredValues`、`fromLegacyMode` 均在映射表中（318 处 `AppTheme` 引用），未被裁剪。

命中区：`car_assert.ps1` 对对话框 dump 报 1 条违规 `[183,1783][897,1789]`（高 2.3dp）。按多帧判别法复查同一列可点 View 的高度集合为 **2.3 / 70.1 / 70.9 / 73.5 / 88 / 89.1 / 102.9 dp**——88dp 正是 `Dimens.RowMinHeight`，2.3dp 那条是被对话框底边裁切的残帧。**判为工具已知失真，非缺陷，未改代码。**

## 4. 车机档矮视口（Task 5）

把「界面模式」手动压成车机（行高 56dp→88dp）后打开对话框：

- 首屏完整可见 **8/9 行**，末项「墨绿」根本不在无障碍树里 → 证明确实需要滚动、且没被静默丢弃
- 滚动一次后「墨绿」bounds `[1933..2006]` 完整落入可视区 → 点中 → 对话框关闭 → 设置页回显「墨绿」
- **冷重启后仍回显「墨绿」** → `theme_key` 持久化与读侧合并生效

## 5. 状态栏图标明暗（本次新发现的必修点）

`enableEdgeToEdge()` 的图标明暗默认跟系统深浅，强制浅色主题 + 系统深色会浅底浅图标。做了两个方向的对照，两种情形恰好都是"跟着系统走就看不见"：

| 系统 | App 主题 | 状态栏带实测 | 判读 |
|---|---|---|---|
| 浅色 | 经典蓝·夜间 | mean=36.2，lightPx=4367，max=255 | 深底 + 浅图标 ✓ |
| 深色 | 亮白·强对比 | mean=247，min=0，darkPx=4102 | 浅底 + 深图标 ✓ |

期间踩到一次判读假失败：用 `亮度<100` 数深色图标得 `darkPct=0`，误报成图标不可见；裁出状态栏条肉眼看，图标是**深灰**（亮度约 100）画在白底上，其实可见。此后改用 mean/min/max/darkPx/lightPx 多指标 + 裁条肉眼复核。

## 6. 回归基线（口径调整，须说明）

计划原定"与 v1.0.110 逐像素比对"。实际设备上只有 1.0.123 的旧包，且 HEAD 与它之间夹着 9 个无关提交，比出来的差异不是主题引入的——因此改用**可证伪的等价对**：

| 对比 | 差异 | 结论 |
|---|---|---|
| 系统浅色下「跟随系统」 vs 「经典蓝·日间」 | 1384/610200 采样点（0.227%） | 差异全部落在 x[148..380] y[348..390] 一处，裁出来看是设置页「主题」行的回显文字本身（`跟随系统` vs `经典蓝·日间`），**其余完全一致** |
| 系统深色下「跟随系统」 vs 「经典蓝·夜间」 | 1432/610200（0.235%） | 同上，只有主题名文字不同 |
| 对照：「经典蓝·日间」 vs 「经典蓝·夜间」 | 610200/610200（100%） | 两套配色确实完全不同，说明上面的"几乎为 0"不是比较失效 |

颜色不变性另有 `ThemeContrastTest.classicThemesStillReturnBaselineInstances` 用 `assertSame` 兜底：三档经典主题返回的就是 `Theme.kt` 里那两个实例本身，派生规则无法悄悄改到它们。

## 7. 待办与已知问题

- **真车机（梁山嘟嘟 S1，10寸 1280x720 恒横屏）未测**：手头只有模拟器。必须在实车面板上眼睛确认四件事——① `pure_black` 下 AlertDialog/Card 不浮灰块；② `amber` 下无残留冷蓝；③ 480dp 高视口下 9 行对话框滚到底可点中（本轮是 420dp 上限下验证的，矮视口数值是**推算不是实测**）；④ `high_contrast` 在阳光直射下的可读性、`pure_black` 的 `outline` 1dp 描边是否看得见。
- **既有问题（非本次引入）**：`res/values/themes.xml` 的 `Theme.ChineseRadio` 继承 `android:Theme.Material.Light`，系统闪屏恒为浅底，深色主题冷启动会先闪一下浅色。要不要补 night 资源另开一轮。
- `tools/theme_check.ps1` 的播放页那步目前只打印亮色像素计数、没有断言（用作取样供肉眼复核），真正的语义色可见性由 `SourceQualityTest` 与真机肉眼兜。
