# 主题切换功能实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 App 增加 9 套内置主题的单选切换能力（套餐式、只改颜色、入口在设置页），并保证默认主题渲染与改造前逐像素一致。

**Architecture:** 新增 `AppTheme` 枚举承载主题身份，用 DataStore 字符串键 `theme_key` 持久化；配色走「基线 ColorScheme + 分层覆写」，中性/色调面板由 `base→ink` 插值派生，强调层逐主题手给；`ChineseRadioTheme` 是全树唯一判定深浅的地方，并就地驱动系统栏图标明暗。

**Tech Stack:** Kotlin 2.1.0 · Jetpack Compose (compose-bom 2024.12.01 → material3 1.3.1) · Material3 ColorScheme · DataStore Preferences 1.1.1 · JUnit 4.13.2 + MockK 1.13.13 · PowerShell + uiautomator 验收脚本

**Spec:** `doc/specs/2026-10-15-theme-switching-design.md`（本计划逐条实现它，色值表与 tone 表以 spec 为准；写计划时的验算修正已回写到 spec 的 §3/§5.2/§5.3/§8/§11，两份现已一致）

## Global Constraints

每个任务的隐含要求，值一律从 spec 原文抄：

- 主题**只改颜色**。不改 `fontScale`、不改 `UiSurface` 判定、不改 `Dimens` 任何既有取值（只允许新增 `ThemeSwatchSize` 一项）。
- **不新增依赖**。不引 material-color-utilities / MaterialKolor，不接 `dynamicColorScheme`。
- 默认主题（`system`）与 `classic_light` / `classic_dark` 必须返回 `Theme.kt` 里**现有那两个 ColorScheme 实例**，一个色值都不改——这是手机档逐像素回归的前提。
- 所有尺寸走 `Dimens` token，**禁止新的裸 dp 字面量**（现有对话框里的 `12.dp` / `8.dp` 属于要清掉的对象）。
- 深浅判定只有 `AppTheme.isDarkConsidering()` 一处；任何页面不许自己读 `isSystemInDarkTheme()` 再算一遍。
- 代码注释用中文。
- 单测命令：`.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.<类名>"`；全量：`.\gradlew.bat :app:testDebugUnitTest`。编译：`.\gradlew.bat :app:assembleDebug -q`。
- 提交信息含中文多行时，写入 `.qtest/commitmsgN.txt` 再 `git commit -F .qtest/commitmsgN.txt`（PowerShell 无 heredoc）。
- `versionCode = git 提交数`，本计划每个任务一次提交，版本号自然递增。
- 收尾前 debug 与 release（R8）两条构建链路都要绿（见 Task 8）。

## Review Focus

spec 写了但没有单测覆盖、且真人最容易踩的五条。每条都在对应任务里落了检查步骤：

1. **全新安装**（`theme_key` 与 `theme_mode` 都不存在）必须解析成 `system`，且冷启动首帧不许闪一下别的配色——`collectAsState(initial = …)` 的初值选错就会闪。（Task 1 单测 + Task 4 冷启动冒烟）
2. **选了 `system` 时用户去系统设置翻深浅**：状态栏/导航栏图标方向必须跟着翻，切主题也一样。这是 `enableEdgeToEdge()` 与主题各自的结论打架的位置，单测碰不到，靠 Task 4 的装机冒烟与 Task 8 的真机裁决。
3. **DataStore 读取异常**：`dataStore.data` 抛 `IOException` 时 Flow 会终止，主题解析不能崩成空白页。（Task 4 Step 1 的 `.catch` 兜底 + Task 4 Step 6 冷启动观察）
4. **极矮车机视口**（density 上报 240 的 1280x720 → 横屏只有 480dp 高）：9 行主题对话框的末项 `ink_green` 必须能滚进可点区。装不下＝能力回退，这是本项目 `usesRailNavigation()` 栽过的同一个坑。（Task 5 Step 4 的高度算式 + Task 7 脚本的滚到底断言）
5. **R8 混淆后**：`AppTheme.entries` 与 `key` 字符串不能被裁剪/重命名，否则老用户存的 `theme_key` 解析失败静默变 `system`。（Task 8 的 release 专项）

---

## 文件结构

| 文件 | 责任 | 动作 |
|---|---|---|
| `app/src/main/java/com/radio/chinese/ui/theme/AppTheme.kt` | 主题身份、分组、深浅判定、迁移合并 | 新建 |
| `app/src/main/java/com/radio/chinese/ui/theme/PaletteMath.kt` | `tonal` 插值 + WCAG 亮度/对比度（纯函数，JVM 可测） | 新建 |
| `app/src/main/java/com/radio/chinese/ui/theme/Palettes.kt` | 6 套覆写主题的锚点与装配 + `resolveColorScheme` | 新建 |
| `app/src/main/java/com/radio/chinese/ui/theme/Theme.kt` | 唯一主题入口：解析深浅→配色→注入 CompositionLocal→系统栏边效应 | 修改 |
| `app/src/main/java/com/radio/chinese/ui/theme/Dimens.kt` | 新增 `ThemeSwatchSize` | 修改 |
| `app/src/main/java/com/radio/chinese/data/local/RadioPreferences.kt` | `theme: Flow<AppTheme>`（合并读取侧）+ `setThemeKey` | 修改 |
| `app/src/main/java/com/radio/chinese/MainActivity.kt` | 去镜像、去 `onThemeChanged` | 修改 |
| `app/src/main/java/com/radio/chinese/ui/MainScreen.kt`、`ui/radio/RadioScreen.kt` | 删主题参数透传链 | 修改 |
| `app/src/main/java/com/radio/chinese/ui/settings/SettingsScreen.kt` | 对话框重做（分组 + 色块 + 滚动） | 修改 |
| `app/src/main/java/com/radio/chinese/data/local/SourceAvailabilityStore.kt` | `availabilityToColor` → `SourceQuality` | 修改 |
| `app/src/main/java/com/radio/chinese/ui/theme/StatusColors.kt` | 可用性语义色的浅/深两套 + `sourceQualityColor` | 新建 |
| `app/src/main/java/com/radio/chinese/ui/player/PlayerScreen.kt` | 按深浅取可用性色 | 修改 |
| `app/src/test/java/com/radio/chinese/AppThemeTest.kt` | 主题身份与迁移 | 新建 |
| `app/src/test/java/com/radio/chinese/PaletteMathTest.kt` | 插值与对比度算法 | 新建 |
| `app/src/test/java/com/radio/chinese/ThemeContrastTest.kt` | 9 主题 × 深浅 的 WCAG 不变式 | 新建 |
| `app/src/test/java/com/radio/chinese/SourceQualityTest.kt` | 分档与两套语义色对比度 | 新建 |
| `tools/theme_check.ps1` | 9 主题 × 两档的 UI 验收 | 新建 |

任务顺序即依赖顺序：1→2→3 是纯函数与配色（只跑单测，不碰 Compose），4 依赖 3 把入口、持久化与调用链一次打通（这些文件是一根编译链，拆开就无法编译），5 只打磨 4 里那个对话框的呈现，6 依赖 4 的 `LocalIsDarkTheme`，7、8 是验收与收尾。

---

### Task 1: AppTheme 枚举、深浅判定与迁移合并

**Files:**
- Create: `app/src/main/java/com/radio/chinese/ui/theme/AppTheme.kt`
- Test: `app/src/test/java/com/radio/chinese/AppThemeTest.kt`

**Interfaces:**
- Consumes: 无（本任务是最底层）
- Produces:
  - `enum class AppTheme(val key: String, val label: String, val hint: String, val group: AppThemeGroup, val tone: ThemeTone)`
  - `enum class ThemeTone { LIGHT, DARK, SYSTEM }`
  - `enum class AppThemeGroup(val label: String) { CLASSIC, NIGHT, READABLE }`
  - `fun AppTheme.isDarkConsidering(systemDark: Boolean): Boolean`
  - `AppTheme.fromKey(key: String?): AppTheme`、`AppTheme.fromLegacyMode(mode: Int): AppTheme`、`AppTheme.fromStoredValues(storedKey: String?, legacyMode: Int): AppTheme`
  - `AppTheme.groupedForPicker(): List<Pair<AppThemeGroup, List<AppTheme>>>`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/radio/chinese/AppThemeTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.ui.theme.AppTheme
import com.radio.chinese.ui.theme.AppThemeGroup
import com.radio.chinese.ui.theme.ThemeTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {

    @Test
    fun keysAreUniqueAndGroupsAreComplete() {
        val keys = AppTheme.entries.map { it.key }
        assertEquals("key 必须唯一，否则 fromKey 会命中第一个", keys.size, keys.toSet().size)
        // 三组各三项，设置页每组正好一屏一组（spec §2）
        AppTheme.entries.groupBy { it.group }.forEach { (group, list) ->
            assertEquals("${group.label} 应恰好 3 项", 3, list.size)
        }
        assertEquals(9, AppTheme.entries.size)
    }

    @Test
    fun fromKeyFallsBackToSystem() {
        assertEquals(AppTheme.AMBER, AppTheme.fromKey("amber"))
        // 未知键、空串、null 都不能让 App 变成未知配色
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey("bogus"))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey(""))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey(null))
    }

    @Test
    fun legacyModeMapsOneWay() {
        assertEquals(AppTheme.SYSTEM, AppTheme.fromLegacyMode(0))
        assertEquals(AppTheme.CLASSIC_LIGHT, AppTheme.fromLegacyMode(1))
        assertEquals(AppTheme.CLASSIC_DARK, AppTheme.fromLegacyMode(2))
        // 脏数据（旧版本写入以外的值）一律回落跟随系统，不猜
        assertEquals(AppTheme.SYSTEM, AppTheme.fromLegacyMode(7))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromLegacyMode(-1))
    }

    @Test
    fun storedKeyWinsOverLegacyModeButGarbageKeyDoesNotMaskIt() {
        // 全新安装：两个键都没有 → system，且首帧就是这个结论（Review Focus 1）
        assertEquals(AppTheme.SYSTEM, AppTheme.fromStoredValues(null, 0))
        // 只有旧键：升级用户的唯一迁移路径
        assertEquals(AppTheme.CLASSIC_DARK, AppTheme.fromStoredValues(null, 2))
        // 新键有效：旧键此后不再参与
        assertEquals(AppTheme.AMBER, AppTheme.fromStoredValues("amber", 2))
        // 新键是脏值：回落 system，而不是拿旧键猜一个——用户既然存过 theme_key，
        // 旧 theme_mode 就是上一代结论，用它会让用户「切了主题却变成别的主题」
        assertEquals(AppTheme.SYSTEM, AppTheme.fromStoredValues("bogus", 2))
    }

    @Test
    fun isDarkConsideringIsSingleSourceOfTruth() {
        assertTrue(AppTheme.SOFT_DARK.isDarkConsidering(systemDark = false))
        assertTrue(AppTheme.PURE_BLACK.isDarkConsidering(systemDark = false))
        assertTrue(AppTheme.AMBER.isDarkConsidering(systemDark = false))
        assertEquals(false, AppTheme.HIGH_CONTRAST.isDarkConsidering(systemDark = true))
        assertEquals(false, AppTheme.CLASSIC_LIGHT.isDarkConsidering(systemDark = true))
        // 只有 system 一项跟随入参
        assertTrue(AppTheme.SYSTEM.isDarkConsidering(systemDark = true))
        assertEquals(false, AppTheme.SYSTEM.isDarkConsidering(systemDark = false))
    }

    @Test
    fun everyThemeHasUsableCopy() {
        // 面向中老年的名称与场景说明不能空，也不能长到挤掉色块
        AppTheme.entries.forEach { theme ->
            assertTrue("${theme.key} 缺名称", theme.label.isNotBlank())
            assertTrue("${theme.key} 缺场景说明", theme.hint.isNotBlank())
            assertTrue("${theme.key} 名称过长", theme.label.length <= 10)
            assertTrue("${theme.key} 场景说明过长", theme.hint.length <= 12)
        }
    }

    @Test
    fun groupedForPickerKeepsDeclaredOrder() {
        val groups = AppTheme.groupedForPicker()
        assertEquals(
            listOf(AppThemeGroup.CLASSIC, AppThemeGroup.NIGHT, AppThemeGroup.READABLE),
            groups.map { it.first }
        )
        assertEquals("amber", groups[1].second[2].key)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.AppThemeTest"`
Expected: 编译失败，`unresolved reference: AppTheme`（这是本任务的 RED 形态——被测类型还不存在）

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/radio/chinese/ui/theme/AppTheme.kt`：

```kotlin
package com.radio.chinese.ui.theme

/**
 * 主题的明暗属性。套餐式的关键：深浅是主题自带的属性，
 * 不再是和「配色」正交的一个开关——老人不需要理解两个开关的组合。
 */
enum class ThemeTone { LIGHT, DARK, SYSTEM }

/** 设置页对话框的分组标题，每组三项。 */
enum class AppThemeGroup(val label: String) {
    CLASSIC("经典"),
    NIGHT("夜间护眼"),
    READABLE("易读与个性"),
}

/**
 * 内置主题。
 *
 * 持久化用 [key] 字符串而不是枚举序号：以后增删或调整顺序时，
 * 存过旧值的用户会被序号静默换掉主题，用 key 最多多一行 fromKey 兜底。
 * [label] / [hint] 是给中老年看的场景说法，不写 AMOLED、tone 之类的技术名。
 */
enum class AppTheme(
    val key: String,
    val label: String,
    val hint: String,
    val group: AppThemeGroup,
    val tone: ThemeTone,
) {
    SYSTEM("system", "跟随系统", "不想操心", AppThemeGroup.CLASSIC, ThemeTone.SYSTEM),
    CLASSIC_LIGHT("classic_light", "经典蓝·日间", "白天正常使用", AppThemeGroup.CLASSIC, ThemeTone.LIGHT),
    CLASSIC_DARK("classic_dark", "经典蓝·夜间", "习惯旧版夜间", AppThemeGroup.CLASSIC, ThemeTone.DARK),

    SOFT_DARK("soft_dark", "深灰柔和", "夜间长时间收听", AppThemeGroup.NIGHT, ThemeTone.DARK),
    PURE_BLACK("pure_black", "纯黑·夜高对比", "OLED 屏夜间行车", AppThemeGroup.NIGHT, ThemeTone.DARK),
    AMBER("amber", "琥珀暖光", "深夜砍掉蓝光", AppThemeGroup.NIGHT, ThemeTone.DARK),

    HIGH_CONTRAST("high_contrast", "亮白·强对比", "阳光直射屏幕", AppThemeGroup.READABLE, ThemeTone.LIGHT),
    CHINA_RED("china_red", "中国红", "喜欢喜庆配色", AppThemeGroup.READABLE, ThemeTone.LIGHT),
    INK_GREEN("ink_green", "墨绿", "喜欢安静配色", AppThemeGroup.READABLE, ThemeTone.LIGHT),
    ;

    /**
     * 全树唯一的深浅结论。页面里不许再读一次 isSystemInDarkTheme() 自己算，
     * 否则同一棵树会出现两套深浅（配色与系统栏打架就是这么来的）。
     */
    fun isDarkConsidering(systemDark: Boolean): Boolean = when (tone) {
        ThemeTone.LIGHT -> false
        ThemeTone.DARK -> true
        ThemeTone.SYSTEM -> systemDark
    }

    companion object {
        fun fromKey(key: String?): AppTheme =
            entries.firstOrNull { it.key == key } ?: SYSTEM

        /** 旧 theme_mode(Int) 的唯一映射表。除本任务测试外不得出现第二处映射。 */
        fun fromLegacyMode(mode: Int): AppTheme = when (mode) {
            1 -> CLASSIC_LIGHT
            2 -> CLASSIC_DARK
            else -> SYSTEM
        }

        /**
         * 读取侧的一次性合并：有 theme_key 用 theme_key，没有才用旧 theme_mode。
         *
         * 刻意不回写 theme_key：回写需要多一个「冷启动即写盘」的副作用和一个调用点，
         * 而这个纯函数已经把迁移结论定死在一处了。降级到老版本时读不到 theme_key，
         * 老代码走 theme_mode ?: 0 = 跟随系统，不崩也不会静默显示成别的主题。
         */
        fun fromStoredValues(storedKey: String?, legacyMode: Int): AppTheme =
            if (storedKey == null) fromLegacyMode(legacyMode) else fromKey(storedKey)

        /** 设置页分组渲染用，保持枚举声明顺序。 */
        fun groupedForPicker(): List<Pair<AppThemeGroup, List<AppTheme>>> =
            entries.groupBy { it.group }.map { (group, list) -> group to list }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.AppThemeTest"`
Expected: PASS，7 个测试方法全绿

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/theme/AppTheme.kt app/src/test/java/com/radio/chinese/AppThemeTest.kt
git commit -m "feat(theme): AppTheme 枚举、深浅单点判定与 theme_mode 迁移纯函数"
```

---

### Task 2: 配色纯函数（tonal 插值 + WCAG 对比度）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/ui/theme/PaletteMath.kt`
- Test: `app/src/test/java/com/radio/chinese/PaletteMathTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `internal fun tonal(base: Color, ink: Color, tone: Float): Color`
  - `internal fun luminance(c: Color): Double`
  - `internal fun contrast(a: Color, b: Color): Double`
  - 三者都要能在 JVM 单测里直接调用（所以 `tonal` 不用 `androidx.compose.ui.graphics.lerp`，见 spec §5.2 修订）

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/radio/chinese/PaletteMathTest.kt`：

```kotlin
package com.radio.chinese

import androidx.compose.ui.graphics.Color
import com.radio.chinese.ui.theme.contrast
import com.radio.chinese.ui.theme.luminance
import com.radio.chinese.ui.theme.tonal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tonal 的 tone 表把对比度成败压在几个具体值上，所以先把算法本身钉死：
 * 算法错一位，Task 3 的全量不变式会一片红，人就会去改色值而不是改算法。
 */
class PaletteMathTest {

    private fun assertClose(expected: Double, actual: Double, message: String) {
        assertEquals(message, expected, actual, 0.05)
    }

    @Test
    fun tonalInterpolatesPerChannel() {
        val black = Color(0xFF000000)
        val white = Color(0xFFFFFFFF)
        // tone=0 得底色，tone=1 得墨色，不掺中间值
        assertEquals(black, tonal(black, white, 0f))
        assertEquals(white, tonal(black, white, 1f))
        // 0.5 落在中点：三通道都是 0.5，且 alpha 跟底色走
        val mid = tonal(black, white, 0.5f)
        assertEquals(0.5f, mid.red, 0.001f)
        assertEquals(0.5f, mid.green, 0.001f)
        assertEquals(0.5f, mid.blue, 0.001f)
        assertEquals(1f, mid.alpha, 0.001f)
        // 非灰底也要按通道各自插，不能先转灰度
        val brown = Color(0xFF1A1206)
        val amber = Color(0xFFF0C070)
        assertEquals(0x1A + (0xF0 - 0x1A) * 0.42f, tonal(brown, amber, 0.42f).red * 255, 1f)
    }

    @Test
    fun alphaOfInkIsNotCarriedFromInk() {
        // 底色半透明时结果必须保持底色的 alpha：面板色不该继承墨色的 alpha
        val translucent = Color(red = 0f, green = 0f, blue = 0f, alpha = 0.4f)
        assertEquals(0.4f, tonal(translucent, Color.White, 0.5f).alpha, 0.001f)
    }

    @Test
    fun luminanceMatchesWcagReference() {
        assertEquals(0.0, luminance(Color(0xFF000000)), 1e-9)
        assertEquals(1.0, luminance(Color(0xFFFFFFFF)), 1e-9)
        // #808080：WCAG 参考值约 0.2159（只靠这个值就能抓住幂次或临界值写错）
        assertClose(0.2159, luminance(Color(0xFF808080)), "#808080 亮度")
        // #767676 是另一个公认参考点（对白约 4.54:1），放在 contrast 里测
    }

    @Test
    fun contrastMatchesWcagReference() {
        assertEquals(21.0, contrast(Color(0xFF000000), Color(0xFFFFFFFF)), 1e-6)
        assertEquals(1.0, contrast(Color(0xFF777777), Color(0xFF777777)), 1e-6)
        // 顺序无关：反着传同一个值
        assertEquals(contrast(Color(0xFF000000), Color(0xFF767676)),
            contrast(Color(0xFF767676), Color(0xFF000000)), 1e-6)
        // #767676 是 4.5:1 的临界参考色
        assertClose(4.54, contrast(Color(0xFF767676), Color(0xFFFFFFFF)), "#767676/白")
        // 3:1 的临界参考：outline 的达标判断就靠这条口径
        assertClose(3.0, contrast(Color(0xFF949494), Color(0xFFFFFFFF)), "#949494/白")
    }

    @Test
    fun specCriticalToneValuesHold() {
        // 逐条复现 spec §5.2/§5.3 的验算，防止有人事后把 tone 调回去却以为在优化观感
        val black = Color(0xFF000000)
        val white = Color(0xFFFFFFFF)
        assertTrue("outline 0.32 在纯黑上必须不达标（这是提到 0.50 的理由）",
            contrast(tonal(black, white, 0.32f), black) < 3.0)
        assertTrue("outline 0.50 在纯黑上须 ≥3:1",
            contrast(tonal(black, white, 0.50f), black) >= 3.0)
        assertTrue("亮档 outline 0.48 对白底须 ≥3:1",
            contrast(tonal(white, black, 0.48f), white) >= 3.0)

        val softBase = Color(0xFF232326)
        val softInk = Color(0xFFD6D3D9)
        val softPrimary = Color(0xFF86AEDC)
        assertTrue("onPrimary 0.25 必须不达标（这是改用 0.18 的理由）",
            contrast(softPrimary, tonal(softBase, softPrimary, 0.25f)) < 4.5)
        assertTrue("onPrimary 0.18 须 ≥4.5:1",
            contrast(softPrimary, tonal(softBase, softPrimary, 0.18f)) >= 4.5)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.PaletteMathTest"`
Expected: 编译失败，`unresolved reference: tonal` / `contrast` / `luminance`

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/radio/chinese/ui/theme/PaletteMath.kt`：

```kotlin
package com.radio.chinese.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * 中性面板取色：从底色向墨色线性插值。
 *
 * 深浅两档共用同一个函数，差别只在调用方的 tone 表——暗档墨是白所以越高越亮，
 * 亮档墨是黑所以越高越暗（M3 亮档本就靠加深表达层级）。
 *
 * 不用 androidx.compose.ui.graphics.lerp：算法完全相同，但 lerp 属于 ui-graphics
 * 运行时入口，而对比度不变式要在纯 JVM 单测里逐槽位跑（见 ThemeContrastTest）。
 */
internal fun tonal(base: Color, ink: Color, tone: Float): Color = Color(
    red = base.red + (ink.red - base.red) * tone,
    green = base.green + (ink.green - base.green) * tone,
    blue = base.blue + (ink.blue - base.blue) * tone,
    alpha = base.alpha,
)

/** WCAG 2.1 相对亮度。通道值 ≤0.03928 走线性段，否则 2.4 次幂。 */
internal fun luminance(c: Color): Double {
    fun channel(v: Float): Double {
        val s = v.toDouble()
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}

/** WCAG 对比度 (L1+0.05)/(L2+0.05)，与传参顺序无关。 */
internal fun contrast(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    val hi = if (la >= lb) la else lb
    val lo = if (la >= lb) lb else la
    return (hi + 0.05) / (lo + 0.05)
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.PaletteMathTest"`
Expected: PASS。若 `luminanceMatchesWcagReference` 里 `#808080` 差超过 0.05，说明 `channel()` 的临界值或幂次写错，先修算法再进 Task 3。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/theme/PaletteMath.kt app/src/test/java/com/radio/chinese/PaletteMathTest.kt
git commit -m "feat(theme): tonal 插值与 WCAG 亮度/对比度纯函数"
```

---

### Task 3: 六套覆写主题与全主题对比度不变式

**Files:**
- Create: `app/src/main/java/com/radio/chinese/ui/theme/Palettes.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/theme/Theme.kt:11`（`private val LightColorScheme` → `internal val`）、`Theme.kt:30`（`DarkColorScheme` 同）
- Test: `app/src/test/java/com/radio/chinese/ThemeContrastTest.kt`

**Interfaces:**
- Consumes: `AppTheme`（Task 1）、`tonal` / `contrast`（Task 2）、`LightColorScheme` / `DarkColorScheme`（Theme.kt，本任务改为 internal）
- Produces: `internal fun AppTheme.resolveColorScheme(dark: Boolean): ColorScheme`——Task 4 的 `ChineseRadioTheme` 与 Task 6 的对话框色块预览都调它，别处不许自己拼 ColorScheme

- [ ] **Step 1: 打开基线的可见性**

`Theme.kt` 里把两份基线从 `private val` 改成 `internal val`，并加注释说明为什么（回归基线要被单测引用）：

```kotlin
/**
 * 经典蓝基线。internal 而不是 private：
 * ThemeContrastTest 要断言三档经典主题返回的仍是这两个实例本身（引用相等），
 * 派生规则一旦把它们也改了，回归基线就悄悄没了。
 */
internal val LightColorScheme = lightColorScheme(
```

`DarkColorScheme` 同样处理（只改可见性，色值一字不动）。

- [ ] **Step 2: 写失败测试**

创建 `app/src/test/java/com/radio/chinese/ThemeContrastTest.kt`：

```kotlin
package com.radio.chinese

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.radio.chinese.ui.theme.AppTheme
import com.radio.chinese.ui.theme.DarkColorScheme
import com.radio.chinese.ui.theme.LightColorScheme
import com.radio.chinese.ui.theme.resolveColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本功能唯一的安全网：漏改一个槽位导致的「白底白字」只有遍历全配对才抓得住，
 * 人肉核对 9 主题 × 30 槽位是抓不住的。
 */
class ThemeContrastTest {

    private data class Pair2(val name: String, val container: Color, val foreground: Color)

    private fun textPairs(s: ColorScheme) = listOf(
        Pair2("primary/onPrimary", s.primary, s.onPrimary),
        Pair2("primaryContainer/onPrimaryContainer", s.primaryContainer, s.onPrimaryContainer),
        Pair2("secondary/onSecondary", s.secondary, s.onSecondary),
        Pair2("secondaryContainer/onSecondaryContainer", s.secondaryContainer, s.onSecondaryContainer),
        Pair2("tertiary/onTertiary", s.tertiary, s.onTertiary),
        Pair2("tertiaryContainer/onTertiaryContainer", s.tertiaryContainer, s.onTertiaryContainer),
        Pair2("surface/onSurface", s.surface, s.onSurface),
        Pair2("background/onBackground", s.background, s.onBackground),
        Pair2("surfaceVariant/onSurfaceVariant", s.surfaceVariant, s.onSurfaceVariant),
        Pair2("inverseSurface/inverseOnSurface", s.inverseSurface, s.inverseOnSurface),
        Pair2("containerLowest/onSurface", s.surfaceContainerLowest, s.onSurface),
        Pair2("containerLow/onSurface", s.surfaceContainerLow, s.onSurface),
        Pair2("container/onSurface", s.surfaceContainer, s.onSurface),
        Pair2("containerHigh/onSurface", s.surfaceContainerHigh, s.onSurface),
        Pair2("containerHighest/onSurface", s.surfaceContainerHighest, s.onSurface),
    )

    @Test
    fun everyThemeMeetsWcagForTextPairs() {
        AppTheme.entries.forEach { theme ->
            listOf(false, true).forEach { dark ->
                val s = theme.resolveColorScheme(dark)
                textPairs(s).forEach { (name, container, foreground) ->
                    val c = contrast(container, foreground)
                    assertTrue("${theme.key}#$dark $name 只有 ${"%.2f".format(c)}:1（需 ≥4.5）", c >= 4.5)
                }
            }
        }
    }

    @Test
    fun outlineIsVisibleAgainstSurface() {
        AppTheme.entries.forEach { theme ->
            listOf(false, true).forEach { dark ->
                val s = theme.resolveColorScheme(dark)
                val c = contrast(s.outline, s.surface)
                assertTrue("${theme.key}#$dark outline/surface ${"%.2f".format(c)}:1（需 ≥3）", c >= 3.0)
            }
        }
        // 明确不断言 outlineVariant（纯装饰分隔线）与 outline/surfaceContainerHighest：
        // 后者实测亮档只有 2.4:1，M3 自身默认值同样达不到，写进来只会逼人把描边改粗。
    }

    @Test
    fun classicThemesStillReturnBaselineInstances() {
        // 回归基线：三档经典主题必须返回同一个对象，不能是「值相等的重建对象」
        assertSame(LightColorScheme, AppTheme.CLASSIC_LIGHT.resolveColorScheme(dark = false))
        assertSame(DarkColorScheme, AppTheme.CLASSIC_DARK.resolveColorScheme(dark = true))
        assertSame(LightColorScheme, AppTheme.SYSTEM.resolveColorScheme(dark = false))
        assertSame(DarkColorScheme, AppTheme.SYSTEM.resolveColorScheme(dark = true))
    }

    @Test
    fun overridingThemesActuallyOverride() {
        // 只改 background 不算覆写：色调面板必须跟走，否则黑底上会浮出库默认灰块
        val black = AppTheme.PURE_BLACK.resolveColorScheme(dark = true)
        assertEquals(Color(0xFF000000), black.background)
        assertEquals(Color(0xFF000000), black.surface)
        assertTrue("纯黑档的 containerHigh 必须比底色亮一点点但仍很暗",
            contrast(black.surfaceContainerHigh, Color(0xFF000000)) in 1.05..1.6)
        assertTrue("色调面板不能一路亮到接近白字",
            contrast(black.surfaceContainerHighest, black.onSurface) > 4.5)

        val amber = AppTheme.AMBER.resolveColorScheme(dark = true)
        // amber 的验收点：secondaryContainer 必须已转暖，冷蓝灰留在暖底上是一块脏斑
        assertTrue("amber 的 secondaryContainer 残留冷色（b 通道不该高于 r 通道）",
            amber.secondaryContainer.blue <= amber.secondaryContainer.red)
        assertTrue("amber 的 surfaceContainer 系列必须偏暖",
            amber.surfaceContainer.blue <= amber.surfaceContainer.red)
    }

    @Test
    fun everyThemeResolvesForBothDepths() {
        // 穷尽性：新增主题忘了在 when 里给分支时，这个测试与编译一起拦
        AppTheme.entries.forEach { theme ->
            listOf(false, true).forEach { dark ->
                val s = theme.resolveColorScheme(dark)
                assertEquals("${theme.key}#$dark 的 primary 带了透明度", 1f, s.primary.alpha, 0f)
                assertEquals("${theme.key}#$dark 的 onSurface 没铺实", 1f, s.onSurface.alpha, 0f)
                assertEquals("${theme.key}#$dark 的底色没铺实", 1f, s.background.alpha, 0f)
            }
        }
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.ThemeContrastTest"`
Expected: 编译失败，`unresolved reference: resolveColorScheme`

- [ ] **Step 4: 写实现**

创建 `app/src/main/java/com/radio/chinese/ui/theme/Palettes.kt`。锚点值照 spec §5.3 两张表逐行抄，tone 值照 §5.2 表抄：

```kotlin
package com.radio.chinese.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** 中性层三个锚点：底色、正文、次级文字。其余 14 个中性/色调槽位由 tonal 派生。 */
private data class Neutral(val base: Color, val ink: Color, val inkSoft: Color)

/** 强调层必须逐个手给：容器色对上的「降饱和」算法做不出品牌感，红与绿的容器不是一个插值能通用的。 */
private data class Accent(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
)

/** spec §5.2 的 tone 表。改动要先过 ThemeContrastTest，别凭观感调。 */
private data class Tones(
    val containerLowest: Float,
    val containerLow: Float,
    val container: Float,
    val containerHigh: Float,
    val containerHighest: Float,
    val bright: Float,
    val dim: Float,
    val variant: Float,
    val outline: Float,
    val outlineVariant: Float,
    val inverseSurface: Float,
    val inverseOnSurface: Float,
)

private val DarkTones = Tones(0.00f, 0.03f, 0.05f, 0.08f, 0.12f, 0.16f, 0.00f, 0.16f, 0.50f, 0.34f, 0.88f, 0.06f)
private val LightTones = Tones(0.00f, 0.01f, 0.03f, 0.05f, 0.08f, 0.00f, 0.12f, 0.08f, 0.48f, 0.30f, 0.88f, 0.12f)

private fun ColorScheme.withNeutral(n: Neutral, dark: Boolean): ColorScheme {
    val tones = if (dark) DarkTones else LightTones
    fun t(v: Float) = tonal(n.base, n.ink, v)
    return copy(
        background = n.base,
        onBackground = n.ink,
        surface = n.base,
        onSurface = n.ink,
        onSurfaceVariant = n.inkSoft,
        surfaceVariant = t(tones.variant),
        surfaceDim = t(tones.dim),
        surfaceBright = t(tones.bright),
        surfaceContainerLowest = t(tones.containerLowest),
        surfaceContainerLow = t(tones.containerLow),
        surfaceContainer = t(tones.container),
        surfaceContainerHigh = t(tones.containerHigh),
        surfaceContainerHighest = t(tones.containerHighest),
        outline = t(tones.outline),
        outlineVariant = t(tones.outlineVariant),
        inverseSurface = t(tones.inverseSurface),
        inverseOnSurface = t(tones.inverseOnSurface),
    )
}

/**
 * 强调层装配。
 *
 * secondary 主色恒等于 primary：全站没有任何一处直接消费 secondary 主色
 * （chips / FilterChip / ListItem 取的都是 secondaryContainer），
 * 为它另编一套降饱和值是凭空造一个无人消费的维度。要改回来必须先出现真实调用点。
 *
 * 暗档的 on 主色用 0.18 而不是 0.25：0.25 在 soft_dark 上只有 4.1:1，不达正文要求。
 */
private fun ColorScheme.withAccent(a: Accent, base: Color, dark: Boolean): ColorScheme {
    fun onOf(main: Color) = if (dark) tonal(base, main, 0.18f) else Color.White
    return copy(
        primary = a.primary,
        onPrimary = onOf(a.primary),
        primaryContainer = a.primaryContainer,
        onPrimaryContainer = a.onPrimaryContainer,
        secondary = a.primary,
        onSecondary = onOf(a.primary),
        secondaryContainer = a.secondaryContainer,
        onSecondaryContainer = a.onSecondaryContainer,
        tertiary = a.tertiary,
        onTertiary = onOf(a.tertiary),
        tertiaryContainer = a.tertiaryContainer,
        onTertiaryContainer = a.onTertiaryContainer,
        inversePrimary = a.primary,
    )
}

private fun schemeOf(dark: Boolean, n: Neutral, a: Accent): ColorScheme =
    (if (dark) DarkColorScheme else LightColorScheme)
        .withNeutral(n, dark)
        .withAccent(a, n.base, dark)

// error / onError / errorContainer / onErrorContainer 一律不覆写：M3 的浅深两套本身按 tone 配好，本期无诉求。

private val C(n: Neutral) = n

private val SoftDark = schemeOf(
    dark = true,
    n = C(Neutral(Color(0xFF232326), Color(0xFFD6D3D9), Color(0xFFB0B2B8))),
    a = Accent(
        primary = Color(0xFF86AEDC), primaryContainer = Color(0xFF2C4A68), onPrimaryContainer = Color(0xFFD6E7F7),
        secondaryContainer = Color(0xFF3A3F47), onSecondaryContainer = Color(0xFFCFD4DB),
        tertiary = Color(0xFFB79CC4), tertiaryContainer = Color(0xFF453A4E), onTertiaryContainer = Color(0xFFE2D6E9),
    ),
)

private val PureBlack = schemeOf(
    dark = true,
    n = C(Neutral(Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFC9CDD3))),
    a = Accent(
        primary = Color(0xFFA8C8FF), primaryContainer = Color(0xFF143A5E), onPrimaryContainer = Color(0xFFCFE3FF),
        secondaryContainer = Color(0xFF26282E), onSecondaryContainer = Color(0xFFD3D7DD),
        tertiary = Color(0xFFC7A8D6), tertiaryContainer = Color(0xFF3A2F42), onTertiaryContainer = Color(0xFFEBDDF1),
    ),
)

private val Amber = schemeOf(
    dark = true,
    n = C(Neutral(Color(0xFF1A1206), Color(0xFFF0C070), Color(0xFFD2AE74))),
    a = Accent(
        primary = Color(0xFFFFB74D), primaryContainer = Color(0xFF5C3D10), onPrimaryContainer = Color(0xFFFFDCA8),
        secondaryContainer = Color(0xFF3A2C14), onSecondaryContainer = Color(0xFFE8D3A8),
        tertiary = Color(0xFFE0A96D), tertiaryContainer = Color(0xFF4A3315), onTertiaryContainer = Color(0xFFF5DCBE),
    ),
)

private val HighContrast = schemeOf(
    dark = false,
    n = C(Neutral(Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF1A1A1A))),
    a = Accent(
        primary = Color(0xFF0B4EA2), primaryContainer = Color(0xFFD6E6FA), onPrimaryContainer = Color(0xFF001F38),
        secondaryContainer = Color(0xFFE4E4E4), onSecondaryContainer = Color(0xFF1A1A1A),
        tertiary = Color(0xFF8A4B00), tertiaryContainer = Color(0xFFFFE0B8), onTertiaryContainer = Color(0xFF2B1600),
    ),
)

private val ChinaRed = schemeOf(
    dark = false,
    n = C(Neutral(Color(0xFFFFFBF7), Color(0xFF1F1A19), Color(0xFF524341))),
    a = Accent(
        primary = Color(0xFFA31F20), primaryContainer = Color(0xFFFFDAD6), onPrimaryContainer = Color(0xFF410005),
        secondaryContainer = Color(0xFFF5E6C8), onSecondaryContainer = Color(0xFF2E2000),
        tertiary = Color(0xFF7A5C3E), tertiaryContainer = Color(0xFFEEDFC9), onTertiaryContainer = Color(0xFF2E1F0E),
    ),
)

private val InkGreen = schemeOf(
    dark = false,
    n = C(Neutral(Color(0xFFF7FAF6), Color(0xFF171D18), Color(0xFF41483F))),
    a = Accent(
        primary = Color(0xFF14603C), primaryContainer = Color(0xFFCDE8D6), onPrimaryContainer = Color(0xFF00210F),
        secondaryContainer = Color(0xFFD3E4DA), onSecondaryContainer = Color(0xFF1C3226),
        tertiary = Color(0xFF7A5C3E), tertiaryContainer = Color(0xFFEEDFC9), onTertiaryContainer = Color(0xFF2C1E0B),
    ),
)

/**
 * 主题 → 配色。全树唯一的取色出口。
 *
 * 三档经典主题返回 Theme.kt 里的基线实例本身（不是等值重建），
 * 这样「默认主题逐像素不变」这条回归基线是可以被单测证伪的。
 */
internal fun AppTheme.resolveColorScheme(dark: Boolean): ColorScheme = when (this) {
    AppTheme.SYSTEM -> if (dark) DarkColorScheme else LightColorScheme
    AppTheme.CLASSIC_LIGHT -> LightColorScheme
    AppTheme.CLASSIC_DARK -> DarkColorScheme
    AppTheme.SOFT_DARK -> SoftDark
    AppTheme.PURE_BLACK -> PureBlack
    AppTheme.AMBER -> Amber
    AppTheme.HIGH_CONTRAST -> HighContrast
    AppTheme.CHINA_RED -> ChinaRed
    AppTheme.INK_GREEN -> InkGreen
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.ThemeContrastTest"`
Expected: PASS，5 个测试方法全绿

若 `everyThemeMeetsWcagForTextPairs` 报某条不足 4.5:1：**先怀疑抄错的色值**（对照 spec §5.3 表逐位核），不要为了绿灯去改 tone 表或砍断言。`soft_dark` 的 `tertiary/onTertiary`（4.58:1）与 `high_contrast` 的 `outline/surface`（3.7:1）是全部配对里最紧的两条，改动任何锚点色先看它们。

- [ ] **Step 6: 全量单测与编译**

Run: `.\gradlew.bat :app:testDebugUnitTest` 然后 `.\gradlew.bat :app:assembleDebug -q`
Expected: 全部测试绿；编译通过（`Theme.kt` 仍是旧签名，本任务不改它）

- [ ] **Step 7: 提交**

写入 `.qtest/commitmsg1.txt`：

```
feat(theme): 6 套覆写主题配色与全主题 WCAG 不变式

- 色调面板槽位由 base→ink 插值派生，避免覆写型主题露出 material3 库默认灰阶
- 三档经典主题返回基线实例本身，回归基线可被单测证伪
- outline 暗档 0.50 / 亮档 0.48，暗档 on 主色插值系数 0.18（逐条算过对比度）
```

```bash
git add app/src/main/java/com/radio/chinese/ui/theme/Palettes.kt app/src/main/java/com/radio/chinese/ui/theme/Theme.kt app/src/test/java/com/radio/chinese/ThemeContrastTest.kt
git commit -F .qtest/commitmsg1.txt
```

---

### Task 4: 打通主题入口、持久化与调用链

一个任务里同时改 `RadioPreferences` / `Theme.kt` / `MainActivity` / `MainScreen` / `RadioScreen` / `SettingsScreen`，因为它们是一根编译链：删掉 `themeMode` 后任何一处留在原地都编不过。本任务只把对话框改成「能选、9 项、单选」，呈现达标（分组、色块、滚动）留给 Task 5。

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/data/local/RadioPreferences.kt:33-36`（`themeMode`）、`:95-99`（`setThemeMode`）、`:273`（companion 键）
- Modify: `app/src/main/java/com/radio/chinese/ui/theme/Theme.kt:49-79`
- Modify: `app/src/main/java/com/radio/chinese/MainActivity.kt:49-72`
- Modify: `app/src/main/java/com/radio/chinese/ui/MainScreen.kt:41-59`、`:106-111`、`:127-176`
- Modify: `app/src/main/java/com/radio/chinese/ui/radio/RadioScreen.kt:39-44`
- Modify: `app/src/main/java/com/radio/chinese/ui/settings/SettingsScreen.kt:32-101`、`:110`、`:150-168`、`:345-388`

**Interfaces:**
- Consumes: `AppTheme`（Task 1）、`AppTheme.resolveColorScheme(dark)`（Task 3）
- Produces:
  - `RadioPreferences.theme: Flow<AppTheme>`、`RadioPreferences.setThemeKey(key: String)`
  - `ChineseRadioTheme(theme: AppTheme, fontScale: Float, uiMode: Int, content: @Composable () -> Unit)`
  - `val LocalIsDarkTheme`（Task 6 的语义色要用）
  - 删除：`RadioPreferences.themeMode`、`setThemeMode`、`MainScreen(themeMode, onThemeChanged)`、`RadioScreen(themeMode, onThemeChanged)`、`SettingsScreen(onThemeChanged)`

- [ ] **Step 1: 改持久化**

`RadioPreferences.kt`：删除 `val themeMode`（第 33-36 行）与 `suspend fun setThemeMode`（第 95-99 行），在原 `themeMode` 的位置写入：

```kotlin
    /**
     * 当前主题。读取侧一次性合并新旧两键：有 theme_key 用 theme_key，没有才按
     * 旧 theme_mode 映射——这就是升级用户的原样迁移，不需要回写。
     *
     * 不回写 theme_key：回写要额外背一个「冷启动即写盘」的副作用和一个调用点，
     * 而合并结论已经定死在 AppTheme.fromStoredValues 这一处，两处调用也只调它。
     *
     * data 层引 ui.theme.AppTheme 的取舍同本文件末尾的 availabilityToColor：
     * 为 UI 服务的取值逻辑留在这里，多一个类型比多一处判定划算。
     */
    val theme: Flow<AppTheme> = dataStore.data
        .catch { e ->
            // 根 Composable 的取色入口，Flow 一旦终止主题就再也回不来。
            // 读盘 IO 错误降级成「无偏好」= 跟随系统；其他异常照常抛出，不吞。
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            AppTheme.fromStoredValues(prefs[KEY_THEME_KEY], prefs[KEY_THEME_MODE] ?: 0)
        }
```

在 `setUiMode` 之后加：

```kotlin
    suspend fun setThemeKey(key: String) {
        dataStore.edit { prefs -> prefs[KEY_THEME_KEY] = key }
    }
```

companion 里加一行，并给旧键写清「只读不再写」：

```kotlin
        private val KEY_THEME_KEY = stringPreferencesKey("theme_key")
        /** 旧明暗开关（0/1/2）。只作 theme_key 缺失时的迁移来源，不再有新写入。 */
        private val KEY_THEME_MODE = intPreferencesKey("theme_mode")
```

新增 import：`com.radio.chinese.ui.theme.AppTheme`、`java.io.IOException`、`kotlinx.coroutines.flow.catch`、`androidx.datastore.preferences.core.emptyPreferences`。

- [ ] **Step 2: 改主题入口**

`Theme.kt` 整体替换第 49-79 行（两份基线色值保持 Task 3 后的 `internal`）：

```kotlin
/**
 * 当前配色是深色还是浅色。可用性色这类「不属于 ColorScheme、但必须随深浅换明度」的
 * 颜色只有这一个取值入口——页面里再算一次 isSystemInDarkTheme() 就是第二套结论。
 */
val LocalIsDarkTheme = compositionLocalOf { false }

@Composable
fun ChineseRadioTheme(
    theme: AppTheme = AppTheme.SYSTEM,
    fontScale: Float = 1f,
    uiMode: Int = 0,    // 0 = 自动, 1 = 手机, 2 = 车机
    content: @Composable () -> Unit
) {
    val surface = rememberUiSurface(uiMode)
    // 车机档取字号「下限」而不是叠乘：标准档在车机上按「大」渲染，
    // 用户仍可选超大(1.3)，不会出现 1.3×1.15 那种失控放大。
    val effectiveScale = if (surface == UiSurface.Car) maxOf(fontScale, 1.15f) else fontScale

    // 深浅的唯一结论。套餐式的代价在这儿：用户选了强制浅色的主题，
    // 系统深浅就必须让位，否则「经典蓝·日间」在深色系统里会变成夜间。
    val dark = theme.isDarkConsidering(isSystemInDarkTheme())
    val colorScheme = theme.resolveColorScheme(dark)

    // enableEdgeToEdge() 的状态栏/导航栏图标明暗默认跟**系统**深浅走。用户选
    // 「经典蓝·日间」而系统是深色时，图标会变成浅底浅字、看不见。必须在这里纠正：
    // 放到 MainActivity 里算会出现两套深浅（主题一套、Activity 一套）。
    val view = LocalView.current
    LaunchedEffect(dark, view) {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalUiSurface provides surface,
        LocalIsDarkTheme provides dark,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography(effectiveScale),
            shapes = AppShapes,
            content = content
        )
    }
}
```

新增 import：`android.app.Activity`、`androidx.compose.ui.platform.LocalView`、`androidx.core.view.WindowCompat`。删掉不再使用的 `isSystemInDarkTheme`？——不删，本函数仍在用（作为 `isDarkConsidering` 的入参来源）。

- [ ] **Step 3: 删镜像与透传**

`MainActivity.kt` 第 49-72 行的 `setContent` 换成：

```kotlin
        setContent {
            // initial = SYSTEM：冷启动首帧的深浅结论必须和「无偏好」的解析结果一致，
            // 否则首帧会闪一下别的配色再去对齐 Flow 的值。
            val theme by preferences.theme.collectAsState(initial = AppTheme.SYSTEM)
            val fontScaleKey by preferences.fontScaleKey.collectAsState(initial = FontScaleOption.STANDARD.key)
            val uiMode by preferences.uiMode.collectAsState(initial = 0)

            ChineseRadioTheme(
                theme = theme,
                fontScale = FontScaleOption.fromKey(fontScaleKey).factor,
                uiMode = uiMode
            ) {
                MainScreen(playerManager = playerManager)
            }
        }
```

删掉 `var currentThemeMode`、`LaunchedEffect(themeMode)`、`preferences.themeMode` 的收集，加 `import com.radio.chinese.ui.theme.AppTheme`。

`MainScreen.kt`：`RadioNavGraph` 与 `MainScreen` 的签名去掉 `themeMode: Int` 与 `onThemeChanged: (Int) -> Unit` 两个参数，删掉三处向下的实参传递（第 57-58、109、173-174 行），`SettingsScreen(...)` 调用处删 `onThemeChanged = onThemeChanged`，`RadioScreen(...)` 调用处删 `themeMode` / `onThemeChanged` 两行。

`RadioScreen.kt`：签名删掉 `themeMode: Int` 与 `onThemeChanged: (Int) -> Unit`（该页只是转发，自己不消费）。

- [ ] **Step 4: 改设置页 ViewModel 与最小可用对话框**

`SettingsScreen.kt`：

```kotlin
data class SettingsUiState(
    val theme: AppTheme = AppTheme.SYSTEM,
    val fontScaleKey: String = FontScaleOption.STANDARD.key,
    val uiMode: Int = 0,
    val bootAutoStart: Boolean = false
)
```

ViewModel 的 init 里把 `preferences.themeMode.collect { mode -> … themeMode = mode }` 换成：

```kotlin
        viewModelScope.launch {
            preferences.theme.collect { theme ->
                _uiState.value = _uiState.value.copy(theme = theme)
            }
        }
```

`fun setThemeMode(mode: Int)` 换成：

```kotlin
    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            preferences.setThemeKey(theme.key)
        }
    }
```

`SettingsScreen` 签名删掉 `onThemeChanged: (Int) -> Unit,`。第 150-166 行的 ListItem 用 `supportingContent = { Text(uiState.theme.label) }` 换掉那段 `when (uiState.themeMode)`。

第 345-388 行的对话框整块换成（Task 5 再补分组与色块）：

```kotlin
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("选择主题") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    AppTheme.entries.forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Dimens.RowMinHeight)
                                .clickable {
                                    viewModel.setTheme(theme)
                                    showThemeDialog = false
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.theme == theme,
                                onClick = {
                                    viewModel.setTheme(theme)
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(Dimens.GapSmall))
                            Text(theme.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("关闭") }
            }
        )
    }
```

加 `import com.radio.chinese.ui.theme.AppTheme`，并补 `androidx.compose.foundation.layout.heightIn`（该文件已 `layout.*`，无需新增）。

- [ ] **Step 5: 编译与全量单测**

Run: `.\gradlew.bat :app:assembleDebug -q` 然后 `.\gradlew.bat :app:testDebugUnitTest`
Expected: 编译通过、单测全绿（Task 1-3 的测试仍全过）

- [ ] **Step 6: 装机冒烟（三条观察，肉眼判定，别只看不留记录）**

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell pm grant com.radio.chinese android.permission.POST_NOTIFICATIONS
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am force-stop com.radio.chinese
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n com.radio.chinese/.MainActivity
```

1. 冷启动首帧不该闪色（Review Focus 1）。
2. 设置 → 主题 → 选「琥珀暖光」→ 对话框关闭后全站立即变暖，无需返回或重启。
3. 把系统深色打开、App 里选「经典蓝·日间」→ **状态栏图标必须仍可看见**（Review Focus 2，这条是本任务存在的理由）。做不到就停下修，不要带着它进 Task 5。

- [ ] **Step 7: 提交**

写入 `.qtest/commitmsg2.txt`：

```
feat(theme): 主题入口改 AppTheme，接通 theme_key 持久化与调用链

- RadioPreferences.theme 在读取侧合并新旧键，IO 错误降级为跟随系统
- ChineseRadioTheme 就地驱动状态栏/导航栏图标明暗（enableEdgeToEdge 的坑）
- 删除 currentThemeMode 镜像与 onThemeChanged 透传链，主题状态单一真相源
- 深浅单点判定经 LocalIsDarkTheme 暴露给 UI 层
```

```bash
git add app/src/main/java/com/radio/chinese/data/local/RadioPreferences.kt app/src/main/java/com/radio/chinese/ui/theme/Theme.kt app/src/main/java/com/radio/chinese/MainActivity.kt app/src/main/java/com/radio/chinese/ui/MainScreen.kt app/src/main/java/com/radio/chinese/ui/radio/RadioScreen.kt app/src/main/java/com/radio/chinese/ui/settings/SettingsScreen.kt
git commit -F .qtest/commitmsg2.txt
```

---

### Task 5: 主题对话框达标（分组、色块预览、车机可滚到底）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/theme/Dimens.kt`（新增 2 个 token）
- Modify: `app/src/main/java/com/radio/chinese/ui/settings/SettingsScreen.kt`（Task 4 的对话框整块替换 + 新增两个 private Composable）

**Interfaces:**
- Consumes: `AppTheme.groupedForPicker()`、`AppTheme.isDarkConsidering()`、`AppTheme.resolveColorScheme()`、`Dimens.RowMinHeight` / `GapTiny` / `GapSmall` / `ChipGap`
- Produces: `Dimens.ThemeSwatchSize`、`Dimens.SwatchBorderWidth`、`private fun ThemeSwatchRow(...)`、`private fun ThemePickerDialog(...)`

- [ ] **Step 1: 加两个 token**

`Dimens.kt` 末尾（`PlayIconSize` 之后）加：

```kotlin
    // 主题选择对话框的预览色块：手机档 24dp，车机档 32dp——
    // 车机上要给看不清文字的人一个靠颜色选主题的抓手，太小就没意义了
    val ThemeSwatchSize: Dp
        @Composable get() = if (car) 32.dp else 24.dp

    // 色块描边宽度：不参与换档，两个形态都用 1dp 勾出边界
    val SwatchBorderWidth: Dp = 1.dp
```

- [ ] **Step 2: 换掉对话框**

`SettingsScreen.kt` 里把 Task 4 那段 `if (showThemeDialog) { … }` 换成调用：

```kotlin
    if (showThemeDialog) {
        ThemePickerDialog(
            current = uiState.theme,
            onSelect = { theme ->
                viewModel.setTheme(theme)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }
```

同文件底部（`getVersionName()` 之前）新增两个 private Composable：

```kotlin
/**
 * 主题选择对话框。
 *
 * 必须能滚到底：9 行 × 车机档 RowMinHeight(88dp) = 792dp，而 density 上报 240 的
 * 车机横屏只有 480dp 高（与 usesRailNavigation 当年「收藏不可达」是同一个坑）。
 * 这里同时给 verticalScroll 与 heightIn(max)，只给一个都会装不下或撑破窗口。
 */
@Composable
private fun ThemePickerDialog(
    current: AppTheme,
    onSelect: (AppTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    // 上限取「屏高 70%」与 420dp 的较小值：屏幕矮就跟着矮，别让对话框顶到状态栏
    val maxHeight = minOf((LocalConfiguration.current.screenHeightDp * 0.7f).dp, 420.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择主题") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                AppTheme.groupedForPicker().forEach { (group, themes) ->
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Dimens.GapLarge, bottom = Dimens.GapTiny)
                    )
                    themes.forEach { theme ->
                        ThemePickerRow(
                            theme = theme,
                            selected = current == theme,
                            systemDark = systemDark,
                            onSelect = { onSelect(theme) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 一行 = 单选钮 + 名称与场景说明 + 该主题自己的三色预览。整行可点。 */
@Composable
private fun ThemePickerRow(
    theme: AppTheme,
    selected: Boolean,
    systemDark: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 单选钮只负责显示状态，点击统一走整行，避免出现两个命中区
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(Dimens.GapSmall))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = theme.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = theme.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(Dimens.ChipGap))
        ThemeSwatchRow(theme = theme, systemDark = systemDark)
    }
}

/**
 * 三个色块用「该主题解析后的实际颜色」画，不引第二套判色路径：
 * 一律走 resolveColorScheme，system 档按当前系统深浅预览。
 * 给不识字或看不清的老人一个靠颜色选对主题的兜底。
 */
@Composable
private fun ThemeSwatchRow(theme: AppTheme, systemDark: Boolean) {
    val scheme = theme.resolveColorScheme(theme.isDarkConsidering(systemDark))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GapTiny)) {
        listOf(scheme.primary, scheme.surfaceContainerHigh, scheme.onSurface).forEach { c ->
            Box(
                modifier = Modifier
                    .size(Dimens.ThemeSwatchSize)
                    .background(c, MaterialTheme.shapes.extraSmall)
                    .border(
                        width = Dimens.SwatchBorderWidth,
                        color = MaterialTheme.colorScheme.outline,
                        shape = MaterialTheme.shapes.extraSmall
                    )
            )
        }
    }
}
```

新增 import：`androidx.compose.foundation.background`、`androidx.compose.foundation.border`、`androidx.compose.material3.LocalAbsoluteElevation`? 不要——只需要：`androidx.compose.runtime.isSystemInDarkTheme`（在 `androidx.compose.foundation` 包：`import androidx.compose.foundation.isSystemInDarkTheme`）、`androidx.compose.ui.platform.LocalConfiguration`、`androidx.compose.ui.unit.dp`（已有）、`minOf`（kotlin 标准库，无需 import）。

- [ ] **Step 3: 编译**

Run: `.\gradlew.bat :app:assembleDebug -q`
Expected: 通过。若报 `Unresolved: weight`，说明 Row 作用域没导入（`androidx.compose.foundation.layout.Row` 已由 `layout.*` 覆盖，`weight` 是 RowScope 成员，调用点在 Row 内即可）。

- [ ] **Step 4: 手工核对高度算式（写进验证记录）**

车机档 720x1280@240dpi（`screenHeightDp` ≈ 480）：`maxHeight = min(480×0.7=336, 420) = 336dp`，单行最低 88dp → 首屏可见 3 行 + 一个组标题，其余靠滚。确认滚动可达最后一项「墨绿」：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am force-stop com.radio.chinese
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n com.radio.chinese/.MainActivity
```

进设置 → 主题，滚到底，点「墨绿」：必须点得动、点中后全站变墨绿。点不动就是被裁了，回 Step 2 检查 `heightIn` 是否漏了（不要用「先删一行」来蒙过去，那是砍能力）。

- [ ] **Step 5: 单测与提交**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全绿

写入 `.qtest/commitmsg3.txt`：

```
feat(theme): 主题对话框分组渲染 + 配色预览色块 + 车机档可滚到底

- 三组各三项，行高走 Dimens.RowMinHeight，清掉原对话框的 12dp/8dp 裸值
- 预览色块取该主题解析结果，不新开第二条判色路径
- heightIn(max) 与 verticalScroll 双约束，防低 density 车机装不下末项
```

```bash
git add app/src/main/java/com/radio/chinese/ui/theme/Dimens.kt app/src/main/java/com/radio/chinese/ui/settings/SettingsScreen.kt
git commit -F .qtest/commitmsg3.txt
```

---

### Task 6: 可用性语义色随深浅换明度

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/data/local/SourceAvailabilityStore.kt:146-165`
- Create: `app/src/main/java/com/radio/chinese/ui/theme/StatusColors.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/player/PlayerScreen.kt:34`（import）、`:412-418`
- Test: `app/src/test/java/com/radio/chinese/SourceQualityTest.kt`

**Interfaces:**
- Consumes: `LocalIsDarkTheme`（Task 4）、`contrast`（Task 2）
- Produces: `enum class SourceQuality { GOOD, FAIR, BAD }`、`fun availabilityToQuality(score: Float): SourceQuality`、`@Composable fun sourceQualityColor(quality: SourceQuality): Color`；删除 `fun availabilityToColor(score: Float): Long`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/radio/chinese/SourceQualityTest.kt`：

```kotlin
package com.radio.chinese

import androidx.compose.ui.graphics.Color
import com.radio.chinese.data.local.SourceQuality
import com.radio.chinese.data.local.availabilityToQuality
import com.radio.chinese.ui.theme.DarkQualityColors
import com.radio.chinese.ui.theme.LightQualityColors
import com.radio.chinese.ui.theme.contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceQualityTest {

    @Test
    fun scoreMapsToThreeBands() {
        // 边界必须与旧连续算法的语义一致：≥0.5 绝不能判成红
        assertEquals(SourceQuality.GOOD, availabilityToQuality(1.0f))
        assertEquals(SourceQuality.GOOD, availabilityToQuality(0.75f))
        assertEquals(SourceQuality.FAIR, availabilityToQuality(0.74f))
        assertEquals(SourceQuality.FAIR, availabilityToQuality(0.5f))
        assertEquals(SourceQuality.BAD, availabilityToQuality(0.49f))
        assertEquals(SourceQuality.BAD, availabilityToQuality(0.0f))
        // 脏输入不越界（旧函数有 coerceIn，收窄成档位后仍要保住）
        assertEquals(SourceQuality.GOOD, availabilityToQuality(9f))
        assertEquals(SourceQuality.BAD, availabilityToQuality(-3f))
        assertEquals(SourceQuality.BAD, availabilityToQuality(Float.NaN))
    }

    @Test
    fun qualityColorsReadableOnBothExtremes() {
        // 旧值 #4CFF00 对白底 1.4:1、#FF0000 对纯黑 1.5:1，等于看不见。
        // 两套新色必须在「最亮底」和「最暗底」上都够正文对比度。
        SourceQuality.entries.forEach { q ->
            val light = LightQualityColors.getValue(q)
            val dark = DarkQualityColors.getValue(q)
            assertTrue("浅底套 ${q} 对白底只有 ${"%.2f".format(contrast(light, Color.White))}:1",
                contrast(light, Color.White) >= 4.5)
            assertTrue("深底套 ${q} 对纯黑只有 ${"%.2f".format(contrast(dark, Color.Black))}:1",
                contrast(dark, Color.Black) >= 4.5)
        }
    }

    @Test
    fun theTwoBandsStaySeparable() {
        // 黄与红不能到了深底就糊成一块：三档两两之间要有可辨差
        listOf(LightQualityColors, DarkQualityColors).forEach { table ->
            val colors = SourceQuality.entries.map { table.getValue(it) }
            for (i in colors.indices) for (j in i + 1 until colors.size) {
                assertTrue("两档语义色太接近", contrast(colors[i], colors[j]) >= 1.5)
            }
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.SourceQualityTest"`
Expected: 编译失败，`unresolved reference: availabilityToQuality` / `SourceQuality` / `LightQualityColors`

- [ ] **Step 3: 改分档函数**

`SourceAvailabilityStore.kt` 把第 146-165 行整段（含 `availabilityToColor`）替换为：

```kotlin
/** 可用性档位：绿=可用、黄=一般、红=失效。语义跨主题不变。 */
enum class SourceQuality { GOOD, FAIR, BAD }

/**
 * 分数 → 档位。颜色不在这里管：语义色必须按深浅换明度，而 data 层不知道当前深浅。
 *
 * 旧实现是连续插值出 #4CFF00→#FFB200→#FF0000，但那些值在白底上约 1.4:1、
 * 在纯黑底上约 1.5:1，本来就接近看不见；收窄成三档反而更可辨，
 * 持久化的仍然是分数，健康检查逻辑一字不动。
 */
fun availabilityToQuality(score: Float): SourceQuality = when {
    score.isNaN() -> SourceQuality.BAD
    score >= 0.75f -> SourceQuality.GOOD
    score >= 0.5f -> SourceQuality.FAIR
    else -> SourceQuality.BAD
}
```

- [ ] **Step 4: 加主题层取色**

创建 `app/src/main/java/com/radio/chinese/ui/theme/StatusColors.kt`：

```kotlin
package com.radio.chinese.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.radio.chinese.data.local.SourceQuality

/**
 * 可用性语义色的两套明度档。含义不变（绿/黄/红），只是深底上换亮色、浅底上换深色，
 * 由 LocalIsDarkTheme 单点决定——不在页面里各自判深浅。
 *
 * internal 而不是 private：SourceQualityTest 要在 JVM 里逐套断言对比度，
 * Composable 取色函数在没有 Android 运行时的单测里拿不到。
 */
internal val LightQualityColors = mapOf(
    SourceQuality.GOOD to Color(0xFF1B7A3D),
    SourceQuality.FAIR to Color(0xFF9A6800),
    SourceQuality.BAD to Color(0xFFB3261E),
)

internal val DarkQualityColors = mapOf(
    SourceQuality.GOOD to Color(0xFF5CD67C),
    SourceQuality.FAIR to Color(0xFFF2C14E),
    SourceQuality.BAD to Color(0xFFFF6B60),
)

@Composable
fun sourceQualityColor(quality: SourceQuality): Color =
    (if (LocalIsDarkTheme.current) DarkQualityColors else LightQualityColors).getValue(quality)
```

- [ ] **Step 5: 改调用点**

`PlayerScreen.kt`：import 换成 `com.radio.chinese.data.local.availabilityToQuality` 与 `com.radio.chinese.ui.theme.sourceQualityColor`；第 412-420 行那段「取 Long 再拆通道造 Color」的代码换成一行：

```kotlin
        sources.forEach { (source, score) ->
            val isActive = currentSource?.url == source.url
            val color = sourceQualityColor(availabilityToQuality(score))
```

（原来的 `val colorInt = availabilityToColor(score)` 与三行 `Color(((colorInt shr 16) …))` 全删。）

- [ ] **Step 6: 跑测试与编译**

Run: `.\gradlew.bat :app:testDebugUnitTest` 然后 `.\gradlew.bat :app:assembleDebug -q`
Expected: 全绿、编译通过。`grep -rn availabilityToColor app/src` 必须 0 命中（旧函数已删，别留死引用）。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/radio/chinese/data/local/SourceAvailabilityStore.kt app/src/main/java/com/radio/chinese/ui/theme/StatusColors.kt app/src/main/java/com/radio/chinese/ui/player/PlayerScreen.kt app/src/test/java/com/radio/chinese/SourceQualityTest.kt
git commit -m "fix(theme): 可用性语义色按深浅换明度，分数只映射到档位"
```

---

### Task 7: 主题切换的 UI 验收脚本

**Files:**
- Create: `tools/theme_check.ps1`
- Reuse: `tools/cap.ps1`（截图 + dump）、`tools/car_assert.ps1`（命中区断言，带裁切判别）

**Interfaces:**
- Consumes: 已安装并授权的 debug APK、设置页「主题」入口文案、9 个主题的 `label`
- Produces: `.qtest/theme_<idx>_<key>_*.{png,xml,txt}` 与 `PASS`/`FAIL` 结论行；退出码非 0 即有失败项

- [ ] **Step 1: 写脚本**

创建 `tools/theme_check.ps1`。开头必须先清旧产物——本项目栽过「旧产物冒充本次抓取」的假通过：

```powershell
param([string]$pkg = "com.radio.chinese")
# 主题切换验收：逐台选中 9 个主题，验证「对话框可点到底 + 当前项文案同步 + 两档命中区」。
$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root ".qtest"

# 关键：先删掉上一轮的产物。不删的话，某个主题没切成功时会拿旧截图当本轮结果——假通过。
Remove-Item (Join-Path $out "theme_*") -Force -ErrorAction SilentlyContinue

$labels = "跟随系统","经典蓝·日间","经典蓝·夜间","深灰柔和","纯黑·夜高对比","琥珀暖光","亮白·强对比","中国红","墨绿"
$keys   = "system","classic_light","classic_dark","soft_dark","pure_black","amber","high_contrast","china_red","ink_green"

function U { param([string]$s) & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null; & $adb pull /sdcard/ui.xml "$tmp" | Out-Null; [xml](Get-Content $tmp -Raw -Encoding UTF8) }
# 设置入口是个 Icon，只有 content-desc 没有 text（RadioScreen:81 的 contentDescription="设置"），
# 只比 .text 会永远找不到——这是两个属性一起比的原因。
function FindNode { param([xml]$d, [string]$name) $d.SelectNodes("//node") | Where-Object { $_.text -eq $name -or $_.'content-desc' -eq $name } | Select-Object -First 1 }
function CenterX { param([string]$b) $m = [regex]::Match($b, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'); ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2 }
function CenterY { param([string]$b) $m = [regex]::Match($b, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'); ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2 }

$tmp = Join-Path $out "theme_dump.xml"
$fail = 0

# 冷启动就绪轮询（不要 sleep 一把梭：车机冷启动几秒到几十秒都可能）
& $adb shell am force-stop $pkg
& $adb shell am start -n "$pkg/.MainActivity" | Out-Null
$ready = $false
foreach ($i in 1..40) {
  Start-Sleep -Milliseconds 800
  $d = U
  if ((FindNode $d "设置") -or (FindNode $d "电台")) { $ready = $true; break }
}
if (-not $ready) { "FAIL: 冷启动未就绪"; exit 1 }

foreach ($idx in 0..($labels.Count - 1)) {
  $label = $labels[$idx]; $key = $keys[$idx]
  # 每轮都从首页重新进，避免上一主题的状态干扰
  & $adb shell input keyevent KEYCODE_BACK | Out-Null
  $d = U; $n = FindNode $d "设置"
  if (-not $n) { "FAIL [$key] 找不到设置入口"; $fail++; continue }
  & $adb shell input tap (CenterX $n.bounds) (CenterY $n.bounds) | Out-Null
  Start-Sleep -Milliseconds 600

  $d = U; $n = FindNode $d "主题"
  if (-not $n) { "FAIL [$key] 设置页没有主题项"; $fail++; continue }
  & $adb shell input tap (CenterX $n.bounds) (CenterY $n.bounds) | Out-Null
  Start-Sleep -Milliseconds 600

  # 滚到底：断言最后一项可达，这是低 density 车机装不下的照妖镜
  $row = $null
  foreach ($try in 1..6) {
    $d = U; $row = FindNode $d $label
    if ($row) { break }
    & $adb shell input swipe 600 1200 600 500 300 | Out-Null
    Start-Sleep -Milliseconds 400
  }
  if (-not $row) { "FAIL [$key] 对话框里滚到底也找不到「$label」"; $fail++; continue }

  & $adb shell input tap (CenterX $row.bounds) (CenterY $row.bounds) | Out-Null
  Start-Sleep -Milliseconds 800
  & $adb exec-out screencap -p > (Join-Path $out "theme_${idx}_${key}.png")
  $d = U
  Copy-Item $tmp (Join-Path $out "theme_${idx}_${key}.xml")

  # 断言 1：对话框已关；断言 2：设置页「主题」项显示成刚选的名称
  if (FindNode $d "选择主题") { "FAIL [$key] 选后对话框没关"; $fail++ }
  $sup = $d.SelectNodes("//node") | Where-Object { $_.text -eq $label } | Select-Object -First 1
  if (-not $sup) { "FAIL [$key] 设置页没回显当前主题「$label」"; $fail++ }
  "OK   [$key] 已选中并回显"
}

# 命中区：把对话框打开一次交给 car_assert.ps1（它自带裁切判别，别在这里重造）
$d = U; $n = FindNode $d "设置"
if ($n) {
  & $adb shell input tap (CenterX $n.bounds) (CenterY $n.bounds) | Out-Null
  Start-Sleep -Milliseconds 600
  $n2 = (FindNode (U) "主题")
  if ($n2) {
    & $adb shell input tap (CenterX $n2.bounds) (CenterY $n2.bounds) | Out-Null
    Start-Sleep -Milliseconds 600
    & "$PSScriptRoot\car_assert.ps1" -name "theme_dialog"
  }
}

if ($fail -gt 0) { "RESULT: FAIL ($fail 项)"; exit 1 }
"RESULT: PASS"
```

- [ ] **Step 2: 跑脚本**

Run: `pwsh -File tools/theme_check.ps1`
Expected: 9 行 `OK […]` + `RESULT: PASS`，退出码 0

任何一条 `FAIL` 都要当真：尤其「滚到底也找不到」几乎必定是真装不下（对话框 `heightIn` 或 `verticalScroll` 少了一个），不是脚本抖动。

- [ ] **Step 3: 提交**

```bash
git add tools/theme_check.ps1
git commit -m "test(theme): 9 主题切换的 UI 验收脚本（含滚到底可达断言与旧产物清理）"
```

---

### Task 8: 回归基线、release 与真机裁决

不写产品代码。这一步决定能不能发布，任何一条不过都不许提交版本号。

**Files:**
- Create: `doc/theme_verification_2026-10-15.md`（验证记录，逐条写实测值）

- [ ] **Step 1: 手机档逐像素回归**

手机档（模拟器 1080x2340@440dpi，`uiMode` 强制 1）在**旧 APK** 与**新 APK 默认主题**下各采一次首页 + 播放页：

```powershell
pwsh -File tools/cap.ps1 -name "regress_old_home"      # 装 chinese-online-radio-1.0.110-release.apk 采
pwsh -File tools/cap.ps1 -name "regress_new_home"      # 换装新 debug 包重采
```

比对两份 png（肉眼 + `Compare-Object` 逐像素工具均可，项目原有做法是肉眼 + bounds 比对）。
Expected: 默认主题（`system`）下首页/播放页与 1.0.110 **无可见差异**；`Compare-Object` 上关键控件 bounds 一致。有差异 = 派生规则污染了基线，回 Task 3 检查 `resolveColorScheme` 是否返回了重建对象（`assertSame` 那条测试是为此存在）。

- [ ] **Step 2: 全量单测与两条构建**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug -q
.\gradlew.bat :app:assembleRelease -q
```
Expected: 全绿。release 必须成功产出 APK（R8 混淆 + 签名）。

- [ ] **Step 3: R8 专项检查（混淆后主题不能静默变回跟随系统）**

release 包装机，选「琥珀暖光」→ 杀进程 → 冷启动：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am force-stop com.radio.chinese
```
Expected: 冷启动后仍是琥珀（说明 `theme_key` 字符串与 `AppTheme.entries` 没被 R8 裁掉/改值）。若退回跟随系统，**以 mapping 文件为准**查 `AppTheme` 是否被 `allowshrinking`/`renamesourcefileattribute` 影响，不要靠猜加 keep 规则。同时跑一遍 `pwsh -File tools/theme_check.ps1`（release 包）。

- [ ] **Step 4: 车机真机裁决（1280x720 恒横屏，实车或嘟嘟 S1 面板）**

在真机上逐条看，并把结论写进验证记录（真机优先于单测，这是本项目口径）：

1. `pure_black`：AlertDialog / 播放器卡片有没有浮出灰块（Task 3 的色调面板派生是否真起作用）。
2. `amber`：页面里还有没有残留冷蓝（secondary/tertiary 容器、Chip、Slider 轨道）。
3. 状态栏与导航栏图标在「强制浅色主题 + 系统深色」下是否可见（Task 4 Step 6 的第 3 条在真机复核一次）。
4. 对话框 9 项能不能滚到「墨绿」并点中。
5. `high_contrast`：白天在阳光下（真车环境）读起来够不够。
6. 纯黑档的 `outline` 描边（1dp）在车机面板上看不看得见——看不见就报上来改 tone，不要自己调大字号蒙过去。

- [ ] **Step 5: 写验证记录并提交**

`doc/theme_verification_2026-10-15.md` 逐条写实测结论（含失败后怎么修的），不要只写「已验证通过」。

写入 `.qtest/commitmsg4.txt`：

```
test(theme): 主题切换验收记录

- 手机档默认主题与 1.0.110 逐像素一致
- release/R8 后 theme_key 与 AppTheme.entries 仍可正确解析（mapping 核对）
- 车机真机：纯黑无灰块、琥珀无冷蓝残留、对话框末项可达、状态栏图标可见
```

```bash
git add doc/theme_verification_2026-10-15.md doc/plans/2026-10-15-theme-switching.md
git commit -F .qtest/commitmsg4.txt
```

- [ ] **Step 6: 推送前安全核查**

按项目既有规矩走：`git fetch` → `git merge-base --is-ancestor` 确认可快进 → 不设 `GIT_TERMINAL_PROMPT=0`（会让 GCM 认证失败）→ `git show HEAD:app/src/main/java/com/radio/chinese/config/EasterEggConfig.kt` 复核入库版本仍是占位符 → 确认没有 `*.apk` / `keystore.properties` / `local.properties` 混进暂存。**不要盲目 force push。**

---

## 计划的自检（写完时逐条核过）

1. **spec 覆盖**：§2 主题清单 → Task 1；§3 数据模型与持久化 → Task 1 + Task 4；§4 入口与系统栏 → Task 4；§5 分层覆写与 tone → Task 2 + Task 3；§6 语义色 → Task 6；§7 对话框 → Task 5；§8 测试与验收 → Task 1/2/3/6 单测 + Task 7 脚本 + Task 8 回归/release/真机。无遗漏。
2. **占位符**：无 TBD / 「同上」/ 「自行调整」；三处「若…则回退」都是带判据的处置，不是未决项。
3. **类型一致**：`AppTheme.fromStoredValues(String?, Int)`、`isDarkConsidering(Boolean)`、`resolveColorScheme(Boolean)`、`availabilityToQuality(Float): SourceQuality`、`sourceQualityColor(SourceQuality)`、`Dimens.ThemeSwatchSize` / `SwatchBorderWidth` 在定义任务与消费任务里逐字对齐。
4. **Review Focus 落点**：①Task 1 测试 + Task 4 Step 6；②Task 4 Step 6 + Task 8 Step 4；③Task 4 Step 1 `.catch`；④Task 5 Step 4 + Task 7 滚到底断言；⑤Task 8 Step 3。
5. **写计划过程中回头同步进 spec 的两处**（现在两份一致，不再是偏离）：`tonal` 不用 `androidx.compose.ui.graphics.lerp` 而是自写通道插值（JVM 单测可跑）；迁移**不回写** `theme_key`（少一个冷启动写盘副作用与调用点，合并结论仍单点），并因此把 `RadioPreferences.theme` 定成 `Flow<AppTheme>`、连带删掉 `themeMode` Flow。spec §3、§5.2、§8、§11 已同步。
