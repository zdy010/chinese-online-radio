# 主题切换功能设计（9 套内置主题）

> 2026-10-15 · 前置决策已由用户确认：主题同时服务**夜间护眼 / 个性化配色 / 中老年高对比**三类诉求 /
> **套餐式单选**（主题自带明暗属性，不与「浅色深色」正交）/ **只改颜色**，不动字号与几何 /
> 入口**仅设置页**（方向盘按键循环、播放器页快捷按钮、快捷码、时段自动切换均不在本期）/
> 配色落地走**基线 + 分层覆写**（方案 C），不引入 HCT 取色库、不接 dynamicColor。

## 1. 目标与非目标

**目标**

1. 用户在设置页一次点选即可切换到 9 套内置主题之一，全站即时生效、无需重启。
2. 夜间行车有三档可选（深灰柔和 / 纯黑高对比 / 琥珀暖光），把当前唯一深色方案的 15:1 刺眼白字降下来。
3. 强光环境有可用的亮白强对比档，弱视用户不必靠「字体大小」凑可读性。
4. 老用户在升级后看到的主题与升级前**完全一致**（迁移零感知）。
5. 默认主题在手机档的渲染结果与改造前逐像素一致，作为回归基线。

**非目标（本期明确不做）**

- 不做自定义取色器、主题导入导出、单主题色值编辑。
- 不接 `dynamicColorScheme`（与 `Theme.kt` 既有裁定一致：系统取色会覆盖品牌色，导致真机与市场截图不一致）。
- 不改字号档位、不改 `Dimens` 任何几何取值、不改布局形态判定。
- 不做按日出日落 / 点火状态的自动主题切换。
- 不做主题的分发捕获、按键绑定、Service 侧读写（入口只有设置页，主题状态不必离开 UI 层）。

## 2. 主题清单

设置页按三组渲染，每组三项，正好一屏一组。`hint` 是给中老年看的场景说明，不是技术名。

| 组 | key | 名称 | 适合 | tone | 中性层 | 强调层 |
|---|---|---|---|---|---|---|
| 经典 | `system` | 跟随系统 | 不想操心 | SYSTEM | 现基线原值 | 现基线原值 |
| 经典 | `classic_light` | 经典蓝·日间 | 白天正常使用 | LIGHT | 现基线原值 | 现基线原值（**回归基线**） |
| 经典 | `classic_dark` | 经典蓝·夜间 | 习惯旧版夜间 | DARK | 现基线原值 | 现基线原值 |
| 夜间护眼 | `soft_dark` | 深灰柔和 | 夜间长时间收听 | DARK | 覆写 | 降饱和覆写 |
| 夜间护眼 | `pure_black` | 纯黑·夜高对比 | OLED 屏夜间行车 | DARK | 覆写 | 覆写 |
| 夜间护眼 | `amber` | 琥珀暖光 | 深夜，砍蓝光 | DARK | 覆写 | 全暖色覆写 |
| 易读·个性 | `high_contrast` | 亮白·强对比 | 阳光直射屏幕 | LIGHT | 覆写 | 加深覆写 |
| 易读·个性 | `china_red` | 中国红 | 喜欢喜庆配色 | LIGHT | 微暖覆写 | 朱红金覆写 |
| 易读·个性 | `ink_green` | 墨绿 | 喜欢安静配色 | LIGHT | 微绿覆写 | 松绿覆写 |

## 3. 数据模型与持久化

新增 `ui/theme/AppTheme.kt`，照 `Typography.kt` 里 `FontScaleOption` 的既有惯例（字符串 `key` + `label` + `fromKey` 兜底）：

```kotlin
enum class ThemeTone { LIGHT, DARK, SYSTEM }

enum class AppThemeGroup(val label: String) { CLASSIC("经典"), NIGHT("夜间护眼"), READABLE("易读与个性") }

enum class AppTheme(
    val key: String,
    val label: String,
    val hint: String,
    val group: AppThemeGroup,
    val tone: ThemeTone,
) {
    SYSTEM("system", "跟随系统", "不想操心", AppThemeGroup.CLASSIC, ThemeTone.SYSTEM),
    CLASSIC_LIGHT("classic_light", "经典蓝·日间", "白天正常使用", AppThemeGroup.CLASSIC, ThemeTone.LIGHT),
    // 其余 7 项同构：key / label / hint / group / tone 一律照 §2 表的三列逐行抄入，不自行编写

    companion object {
        /** 未知 key 回落 SYSTEM：升级/降级/数据被清都不能让 App 变成未知配色。 */
        fun fromKey(key: String): AppTheme = entries.firstOrNull { it.key == key } ?: SYSTEM

        /** 旧 theme_mode(Int) 一次性迁移的唯一映射表，除测试外不得有第二处映射。 */
        fun fromLegacyMode(mode: Int): AppTheme = when (mode) {
            1 -> CLASSIC_LIGHT
            2 -> CLASSIC_DARK
            else -> SYSTEM
        }
    }
}

/** 有效深浅：全树唯一判定源，`ChineseRadioTheme` 之外不许再算第二次。 */
fun AppTheme.isDarkConsidering(systemDark: Boolean): Boolean = when (tone) {
    ThemeTone.LIGHT -> false
    ThemeTone.DARK -> true
    ThemeTone.SYSTEM -> systemDark
}
```

**持久化**（`data/local/RadioPreferences.kt`）：

- 新增 `private val KEY_THEME_KEY = stringPreferencesKey("theme_key")`。
- **不复用旧键名 `theme_mode`**：DataStore Preferences 里同名不同类型的键会撞车，读侧拿到非预期类型会静默失败。换名是最便宜的规避。
- 暴露 `val theme: Flow<AppTheme>`（已含新旧键合并）与 `suspend fun setThemeKey(key: String)`；`setThemeMode(Int)` 与 `themeMode: Flow<Int>` 都删除（已无调用点，留着就是两个真相源），`KEY_THEME_MODE` 仅作迁移读取源。`RadioPreferences` 因此引用 `ui.theme.AppTheme`，与本目录已有的 `availabilityToColor`（一个为 UI 服务的取值函数）同档取舍：多一个类型比多一处判定划算。
- 迁移在读取侧一次合并，单向且**不回写**：有 `theme_key` 就用；无则 `AppTheme.fromLegacyMode(themeMode)`。合并在 `RadioPreferences.theme` 这一个 Flow 里做完，全树只有这一处。不回写的理由：DataStore 的 `Flow.map` 里写不了盘，要回写就得多一个冷启动写盘的副作用和一个调用点，而结论已经定死在 `AppTheme.fromStoredValues` 这个纯函数里，回写买不到任何东西。
- 降级到老版本时读不到 `theme_key`，走 `theme_mode ?: 0` = 跟随系统，不崩、也不会静默显示成别的主题。

## 4. 解析入口与系统栏

`ui/theme/Theme.kt` 的新签名（`fontScale` / `uiMode` 两条既有逻辑一字不改）：

```kotlin
@Composable
fun ChineseRadioTheme(
    theme: AppTheme = AppTheme.SYSTEM,
    fontScale: Float = 1f,
    uiMode: Int = 0,
    content: @Composable () -> Unit,
)
```

内部流程固定为四步：`surface = rememberUiSurface(uiMode)` → `dark = theme.isDarkConsidering(isSystemInDarkTheme())` → `colorScheme = theme.resolveColorScheme(dark)` → `CompositionLocalProvider(LocalUiSurface, LocalIsDarkTheme)`。

新增 `val LocalIsDarkTheme = compositionLocalOf { false }`：可用性等「按深浅微调但不属于 ColorScheme」的颜色只有这一个取值的入口。

**系统栏（本轮新发现的必修点）**：`MainActivity` 走 `enableEdgeToEdge()`，状态栏/导航栏图标明暗默认跟**系统**深浅。用户选「经典蓝·日间」而系统是深色时，图标变成浅底浅字，看不见。修法放在主题层内部，保持判定源唯一：

```kotlin
// ChineseRadioTheme 内，MaterialTheme 之前
val activity = LocalContext.current as? Activity
LaunchedEffect(activity, dark) {
    activity?.window?.let {
        WindowCompat.getInsetsController(it, it.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
```

不放在 `MainActivity` 里算，否则同一棵树上会出现「主题层一套深浅、Activity 另一套深浅」。

**顺手清理（属于本改动路径，不是无关重构）**：删除 `MainActivity` 的 `currentThemeMode` 本地镜像与 `mutableIntStateOf` / `LaunchedEffect` 同步，删除 `onThemeChanged` 沿 `MainScreen → RadioNavGraph → RadioScreen → SettingsScreen` 的整条透传。`preferences.themeKey` 本身是 Flow，写 DataStore 即触发重组，两个真相源是这次功能的隐患而非资产。`SettingsUiState.themeMode: Int` 改为 `theme: AppTheme`。

## 5. 配色架构：基线 + 分层覆写

### 5.1 槽位分三组，不是两组

全站实际消费的槽位（`Select-String` 统计）比 §2 轮次我口头说的「中性 6 + 强调 10」更多：`outlineVariant`、`errorContainer`、`onErrorContainer`、`onTertiaryContainer`、`surfaceContainer*` 都在被直接消费或被 M3 组件（Card / AlertDialog / Switch / Menu / SnackBar）间接消费。而项目用的 compose-bom 2024.12.01 对应 material3 1.3.1，`ColorScheme` 有约 30 个槽位，其中 `surfaceContainerLowest/Low/Container/High/Highest`、`surfaceBright`、`surfaceDim`、`outline`、`outlineVariant`、`inverseSurface`、`inverseOnSurface`、`inversePrimary`、`scrim` 在 `lightColorScheme()` / `darkColorScheme()` 里都有**库自带的固定灰阶**。

结论：**覆写型主题不能只改 6 个中性槽位**。只改 `background` / `surface` 的话，AlertDialog / Card / 下拉菜单仍会取库默认灰阶，纯黑主题上会浮出灰块、琥珀主题上会露出冷灰——恰好在最需要"成套"的三个主题上破功。

因此槽位分三组：

| 组 | 槽位 | 覆写方式 |
|---|---|---|
| 中性锚点 | `background` `onBackground` `surface` `onSurface` `onSurfaceVariant` | 每主题手给锚点值 |
| 色调面板 | `surfaceContainerLowest/Low/Container/High/Highest` `surfaceBright` `surfaceDim` `surfaceVariant` `outline` `outlineVariant` `inverseSurface` `inverseOnSurface` | **由派生规则算出**，不手抄 |
| 强调层 | `primary` `onPrimary` `primaryContainer` `onPrimaryContainer` `secondary` `onSecondary` `secondaryContainer` `onSecondaryContainer` `tertiary` `onTertiary` `tertiaryContainer` `onTertiaryContainer` | 每主题手给值 |

`inversePrimary` 直接取本主题 `primary`；`scrim` 保持库默认（两套都是黑，无需派生）。`error` / `onError` / `errorContainer` / `onErrorContainer` 保持库默认，不覆写（M3 的浅深两套本身已按 tone 配好，本期无诉求）。

### 5.2 色调面板派生规则

底色 `base` 向墨色 `ink` 线性插值，替代库默认灰阶。插值自己写通道算式而不是调 `androidx.compose.ui.graphics.lerp`：两者算法完全相同，但 `lerp` 属于 ui-graphics 的运行时入口，而 §8 的对比度不变式要在 **JVM 单测**里逐槽位跑（无 Android 运行时），自己算能把“单测跑不了”的风险归零。

```kotlin
/**
 * 中性面板取色：从底色向墨色插值。
 * 深浅两档共用同一个函数——差别只在 tone 表，暗档插向白所以越高越亮，
 * 亮档插向黑所以越高越暗（M3 亮档本就靠加深表达层级）。
 */
internal fun tonal(base: Color, ink: Color, tone: Float): Color = Color(
    red = base.red + (ink.red - base.red) * tone,
    green = base.green + (ink.green - base.green) * tone,
    blue = base.blue + (ink.blue - base.blue) * tone,
    alpha = base.alpha,
)
```

tone 取值固定成两张表，实现时照抄，不许自行调整：

| 槽位 | 暗档 tone | 亮档 tone |
|---|---|---|
| surfaceContainerLowest | 0.00 | 0.00 |
| surfaceContainerLow | 0.03 | 0.01 |
| surfaceContainer | 0.05 | 0.03 |
| surfaceContainerHigh | 0.08 | 0.05 |
| surfaceContainerHighest | 0.12 | 0.08 |
| surfaceBright | 0.16 | 0.00 |
| surfaceDim | 0.00 | 0.12 |
| surfaceVariant | 0.16 | 0.08 |
| outline | 0.50 | 0.48 |
| outlineVariant | 0.34 | 0.30 |
| inverseSurface | 0.88 | 0.88 |
| inverseOnSurface | 0.06 | 0.12 |

几个 tone 是算过才定的，不是抄的，改动前请先看 §8 的断言：

- **outline 暗档 = 0.50 / 亮档 = 0.48**：`outline` 承担 Card/OutlinedButton 描边，属非文本元素，WCAG 要求 ≥3:1。实测：0.32 时纯黑档得 `#525252`，对 `#000000` 只有 2.7:1；0.42 时 amber 得 2.9:1、soft_dark 得 3.02:1（一个不达标一个贴边）。暗档提到 0.50 后 amber 3.3:1、soft_dark 3.7:1、纯黑 5.3:1；亮档 0.48 对 `high_contrast` 白底 3.7:1、对两份暖底 3.3:1。
- **inverseOnSurface 亮档 = 0.12**（不是 0.92）：`inverseSurface` 在亮档是深色面板（tone 0.88），它的 on 色必须是浅字；取 0.92 会得到深字压深底。暗档反过来取 0.06。这一对是 §8 断言里的 `inverseSurface / inverseOnSurface` 配对。
- `outlineVariant` 只做分隔线，纯装饰，**不参与对比度断言**（取 0.34/0.30 是为了在黑底上看得见一条线，不是为了达标）。

派生规则不适用于 `system` / `classic_light` / `classic_dark`——这三项继续原样返回 `Theme.kt` 里现有的两份 `ColorScheme`，一个字节都不改。

### 5.3 覆写型主题的锚点值

暗档 `onPrimary` / `onSecondary` / `onTertiary` 一律 = `lerp(base, 对应主色, 0.18)`；亮档一律 = `#FFFFFF`。所以表里只给需要人判断的列。

> 0.18 而非 0.25：取 0.25 时 `soft_dark` 的 primary/onPrimary 对只有 **4.1:1**，不达正文 4.5:1；改 0.18 后得 4.9:1，而 amber（7.1:1）与纯黑（9.2:1）本来就宽裕。tertiary 同理：`soft_dark` 的 onTertiary 在 0.18 下得 4.58:1，是全部配对里最紧的一条，改动 tone 时先看它。

| 主题 | base | ink | inkSoft（=onSurfaceVariant / onSecondaryContainer 之外的次级文字） |
|---|---|---|---|
| soft_dark | `#232326` | `#D6D3D9` | `#B0B2B8` |
| pure_black | `#000000` | `#FFFFFF` | `#C9CDD3` |
| amber | `#1A1206` | `#F0C070` | `#D2AE74` |
| high_contrast | `#FFFFFF` | `#000000` | `#1A1A1A` |
| china_red | `#FFFBF7` | `#1F1A19` | `#524341` |
| ink_green | `#F7FAF6` | `#171D18` | `#41483F` |

`onBackground` = `ink`，`surface` = `base`（面板分离交给 5.2 的色调系列，不再靠 surface 与 background 手动留差）。

| 主题 | primary | primaryContainer | onPrimaryContainer | secondaryContainer | onSecondaryContainer | tertiaryContainer | onTertiaryContainer |
|---|---|---|---|---|---|---|---|
| soft_dark | `#86AEDC` | `#2C4A68` | `#D6E7F7` | `#3A3F47` | `#CFD4DB` | `#453A4E` | `#E2D6E9` |
| pure_black | `#A8C8FF` | `#143A5E` | `#CFE3FF` | `#26282E` | `#D3D7DD` | `#3A2F42` | `#EBDDF1` |
| amber | `#FFB74D` | `#5C3D10` | `#FFDCA8` | `#3A2C14` | `#E8D3A8` | `#4A3315` | `#F5DCBE` |
| high_contrast | `#0B4EA2` | `#D6E6FA` | `#001F38` | `#E4E4E4` | `#1A1A1A` | `#FFE0B8` | `#2B1600` |
| china_red | `#A31F20` | `#FFDAD6` | `#410005` | `#F5E6C8` | `#2E2000` | `#EEDFC9` | `#2E1F0E` |
| ink_green | `#14603C` | `#CDE8D6` | `#00210F` | `#D3E4DA` | `#1C3226` | `#EEDFC9` | `#2C1E0B` |

tertiary 主色：amber `#E0A96D`、high_contrast `#8A4B00`、china_red `#7A5C3E`、ink_green `#7A5C3E`、soft_dark `#B79CC4`、pure_black `#C7A8D6`。

`secondary` 主色**直接等于该主题的 `primary`**，不另找「降一档饱和」的版本。理由：全站没有一处直接消费 `secondary` 主色（chips / FilterChip / ListItem 取的都是 `secondaryContainer`），为它设计一套降饱和色值等于凭空造一个无人消费的维度，还会让 §8 的断言多出两条无意义的配对。要改回来必须先出现真实调用点。

amber 与 high_contrast 的两条特意要求：amber 的 secondary/tertiary 容器必须转暖（旧基线冷蓝灰留在暖底上是一块明显的脏斑）；high_contrast 的 `onSurfaceVariant` 不许退成中灰，取 `#1A1A1A`，否则「强对比」名不副实。

## 6. 可用性语义色（顺手修掉的既有缺陷）

`data/local/SourceAvailabilityStore.kt` 的 `availabilityToColor(score)` 算出 `score=1.0 → #4CFF00`、`0.5 → #FFB200`、`0.0 → #FF0000`。`#4CFF00` 在白底上约 1.4:1、`#FF0000` 在纯黑底上约 1.5:1——**现在的浅色主题下就已经接近看不见**，只是没人拿放大镜量。它只有一个调用点（`PlayerScreen` 的节目源卡片描边与百分比文字）。

改法：函数职责收窄为「分数 → 三档枚举 `SourceQuality`（GOOD / FAIR / BAD）」，颜色由 UI 层按 `LocalIsDarkTheme` 从两套里取。语义（绿=可用 / 黄=一般 / 红=失效）跨主题不变，明度随深浅走：

| 档 | 浅底主题 | 深底主题 |
|---|---|---|
| GOOD | `#1B7A3D` | `#5CD67C` |
| FAIR | `#9A6800` | `#F2C14E` |
| BAD | `#B3261E` | `#FF6B60` |

持久化的仍是分数，不动存储格式、不动健康检查逻辑。

## 7. 设置页对话框

替换 `SettingsScreen.kt` 现有那个 3 项 `AlertDialog`：

- 行结构：`RadioButton` +（`label` 一行 + `hint` 一行 `bodySmall`）+ 行尾**三个用该主题自己配色画的色块**（primary / surfaceContainerHigh / ink，圆角走 `MaterialTheme.shapes.extraSmall`，尺寸 `Dimens.ThemeSwatchSize`）。色块是给不识字或看不清的老人兜底的选法。
- 预览取色直接调 `theme.resolveColorScheme(dark)`，**不新开第二条判色路径**；`AppTheme.SYSTEM` 的预览用 `isSystemInDarkTheme()` 的结果。
- 按 `AppThemeGroup` 分三段，段标题用 `labelLarge` + `onSurfaceVariant`。
- **必须能滚到底**：9 行 × 车机档 `Dimens.RowMinHeight`(88dp) = 792dp，而 density 上报 240 的车机横屏只有 480dp 高——与 `usesRailNavigation()` 当年「四个条目装不下导致收藏不可达」是同一个坑。内容列 `verticalScroll` 与 `heightIn(max = min(420.dp, screenHeightDp * 0.7f))` 两个约束都要有，行高仍走 `Dimens`，不保留现有 `12.dp` / `8.dp` 裸值。
- 整行可点（现有代码 radio 和 row 各挂一次同样的 lambda，保留整行点击，去掉重复实现）。
- 对话框不显式注入 `LocalUiSurface`（既有结论：能穿透，车机档取值会正常生效）。

`ui/theme/Dimens.kt` 只加一项 `ThemeSwatchSize`（手机 24dp / 车机 32dp），其余复用现有 token。

## 8. 测试与验收

**JVM 单测**（`app/src/test/` 已存在，junit 4.13.2 + mockk 1.13.13 + coroutines-test 已在 `app/build.gradle.kts` 依赖里）：

1. `AppTheme.fromKey("没见过的键")` → SYSTEM；9 个 `key` 互不重复。
2. `fromLegacyMode`：0→SYSTEM、1→CLASSIC_LIGHT、2→CLASSIC_DARK、任意其他值→SYSTEM。
3. `isDarkConsidering`：LIGHT 恒 false、DARK 恒 true、SYSTEM 跟随入参。
4. **对比度不变式（本设计的安全网）**：遍历 9 主题 × 有效深浅，对每对 `(容器, 前景)` 断言 WCAG 对比度——正文类配对 ≥4.5:1（primary/onPrimary、primaryContainer/onPrimaryContainer、secondary 系、tertiary 系、surface/onSurface、background/onBackground、surfaceVariant/onSurfaceVariant、inverseSurface/inverseOnSurface、surfaceContainer* 各档与 onSurface），非文本配对 ≥3:1（**仅** outline/surface）。`outlineVariant`、`scrim`、纯装饰的 `hint` 文字、以及「outline 对 surfaceContainerHighest」不在断言范围内——最后那条我自己算过：亮档 outline 对 containerHighest 只有 2.4:1，M3 自身默认值同样达不到，而分层本来由色调面板承担，写进断言只会逼人把描边改粗。这条能自动抓住唯一一类真实事故：漏改一个槽位导致白底白字，也让 §5.2 的派生 tone 表在被改动时立刻暴露。
5. `resolveColorScheme` 对 `system` / `classic_light` / `classic_dark` 返回值与改造前的 `LightColorScheme` / `DarkColorScheme` **引用相等**，防止回归基线被派生规则污染。
6. 迁移读侧：`fromStoredValues(null, 2)` 解析出 CLASSIC_DARK、`fromStoredValues(null, 0)` 与全空解析出 SYSTEM、`fromStoredValues("amber", 2)` 不受旧键影响、`fromStoredValues("垃圾值", 2)` 回落 SYSTEM。合并逻辑是纯函数，因此不需要造 DataStore 也能把迁移钉死（Flow 接线部分由编译与 Task 5/6 的装机冒烟兼顾）。

**UI 自动化**（沿用 `.qtest` 的 PowerShell + uiautomator 风格，含冷启动就绪轮询与权限预授权）：

- 9 主题 × 手机/车机两档：每档 9 组「首页 + 播放页 + 主题对话框」截图与 dump。
- 断言对话框末项 `ink_green` 的 bounds 在滚动后可落入视口（吸取 rail 教训）。
- 断言 radio 行命中高度不低于 `Dimens.TouchMin`；bounds 用跨帧取 max 的多帧判别法，区分裁切失真与真实缺陷。
- 中文输出走无管道读取，避免代码页乱码导致的假失败判读。

**回归基线**：默认 `system` 主题下，手机档首页/播放页截图与 v1.0.110 逐像素比对一致。

**真机裁决**（车机 1280x720 恒横屏）：至少实测 `pure_black` 与 `amber` 两档，用眼睛和截图确认三件事——状态栏图标可见（§4 边效应，不可靠推断）、AlertDialog 与 Card 不浮出灰块（§5.1 派生是否真的生效）、amber 下无残留冷蓝。真机结论优先于单测。

**R8 release**：debug 与 release 全脚本绿，且 release APK 装真机手跑一遍主题切换后才提交推送。

## 9. 改动文件清单

| 文件 | 动作 |
|---|---|
| `ui/theme/AppTheme.kt` | 新增：枚举、`ThemeTone`、`AppThemeGroup`、`isDarkConsidering`、`fromKey`、`fromLegacyMode` |
| `ui/theme/Palettes.kt` | 新增：6 个覆写型主题的锚点值 + `resolveColorScheme` + `lerp` 派生 |
| `ui/theme/Theme.kt` | 改：`ChineseRadioTheme` 签名与解析流程，新增 `LocalIsDarkTheme`、系统栏 `SideEffect` |
| `ui/theme/Dimens.kt` | 改：加 `ThemeSwatchSize` |
| `data/local/RadioPreferences.kt` | 改：`theme_key` + 读侧迁移，删 `setThemeMode` |
| `MainActivity.kt` | 改：收集 `themeKey`，删镜像与 `onThemeChanged` 透传 |
| `ui/MainScreen.kt`、`ui/radio/RadioScreen.kt` | 改：删掉两个主题参数的透传链 |
| `ui/settings/SettingsScreen.kt` | 改：`SettingsUiState.theme`、对话框重做成分组 + 色块 + 滚动 |
| `data/local/SourceAvailabilityStore.kt`、`ui/player/PlayerScreen.kt` | 改：分数→档位，取色移到 UI 层按深浅 |
| `app/src/test/…/AppThemeTest.kt`、`ContrastInvariantTest.kt` | 新增 |

## 10. 风险登记

| 风险 | 对策 |
|---|---|
| 覆写型主题只改 6 个中性槽位，卡片/对话框露出库默认灰阶 | §5.1 明确三组槽位；§5.2 派生色调面板；真机专项确认 |
| 强制日间主题 + 系统深色 → 状态栏图标隐形 | §4 `SideEffect` 驱动 `isAppearanceLightStatusBars`；真机必测 |
| 同名不同键类型冲突 | 用新键名 `theme_key`，不复用 `theme_mode` |
| 9 行对话框在低 density 车机上装不下 | 双约束滚动 + 末项可达断言 |
| 枚举顺序变动使老用户静默换主题 | 持久化字符串 `key`，不存 ordinal |
| 派生规则改坏 `classic_*` 回归基线 | 单测断言三档经典主题返回同一实例 |
| amber 下仍有冷蓝残留 | 强调层容器全部转暖 + 真机眼睛复核 |
| 假设 material3 1.3.1 的 `ColorScheme` 可 `copy()`（它是 data class，但本设计完全建立在这个前提上） | 编译不过则退路：每主题用 `lightColorScheme(...)` / `darkColorScheme(...)` 全参数构造，锚点与派生值不变，只换写法；不得为迁就写法而砍掉色调面板那组槽位 |

## 11. 设计修订记录

1. **§2 轮次曾说「`error` / `inverse` / `outline` 一律不覆写，交给 M3 默认值兜底」——此结论作废并已修正**。核实 material3 1.3.1 的 `surfaceContainer*` 等槽位有库自带固定灰阶，覆写型主题不接管就会在黑底上浮出灰块，故新增「色调面板」这一组与派生规则。
2. **§2 轮次曾说「纯黑主题必须让 `surface` 与 `background` 留 1% 差，否则卡片边界消失」——此结论作废**。面板分离由 §5.2 的色调系列负责，`surface` 直接等于 `base`，不需要手动留差。
3. 第 1 节批准的「删除 `currentThemeMode` 镜像与 `onThemeChanged` 透传」「旧 `theme_mode` 单向迁移不回滚」维持不变。
4. **spec 自审阶段的三处修正**（写完后拿 §8 的算法逐条复核发现的，不是实现期才会遇到）：`outline` 派生 tone 由 0.32 提到 0.42（纯黑档 2.7:1 不达标）；亮档 `inverseOnSurface` 由 0.92 改为 0.12（原值会得到深字压深底）；`secondary` 主色由「降一档饱和、值待定」改为恒等于 `primary`（无调用点，原表述是未决项）。
5. **写实施计划时的逐值验算修正**：上一轮把 `outline` 定成 0.42 仍不够——amber 只有 2.9:1、soft_dark 3.02:1，所以暗档提到 **0.50**、亮档定 **0.48**；`outlineVariant` 随之从 0.22 提到 0.34/0.30（纯为看得见）；§5.3 的暗档 on 主色插值系数由 0.25 改为 **0.18**（0.25 下 `soft_dark` 的 primary/onPrimary 只有 4.1:1）；§8 取消「outline 对 surfaceContainerHighest ≥3:1」这条断言（亮档实际 2.4:1，且 M3 默认值同样达不到，写进去只会误导实现者改粗描边）。另：§5.2 的插值改为自写通道算式而不是 `androidx.compose.ui.graphics.lerp`，因为 §8 的不变式要在纯 JVM 单测里跑。
