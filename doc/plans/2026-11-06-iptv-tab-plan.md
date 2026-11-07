# iptv Tab 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增一级 tab `iptv`（位于电台与音频库之间），把 iptv-org 公开直播流清单在构建期生成为内置 assets，并提供搜索、分类/国家入口、专用播放页与 NSFW 开关。

**Architecture:** 模型层给 `StationSource`/`RadioStation` 加**带默认值**的新字段（零迁移）；`tools/iptv_sync.ps1` 构建期拉 API 生成 `assets/iptv_channels.json`；`IptvStationRepository` 懒加载该文件并只对 `iptv:` 前缀 id 提供 `getStationById` 回落；HTTP header 走 `StreamHeaderStore` 单例（沿用项目已有的 WebDAV 动态 Auth 模式）；切台基准由"当前页面注册"取代 PlayerManager 内部硬编码。

**Tech Stack:** Kotlin 2.1.0 / Compose BOM 2024.12.01 / Media3 1.5.1（零新增依赖）/ kotlinx.serialization / Hilt / DataStore / JUnit 4.13.2 / PowerShell 7 + curl（一次性代理）。

**Spec:** `doc/specs/2026-11-06-iptv-tab-design.md` —— 本计划按它论证，冲突时以 spec 为准。

## Global Constraints

- **收录范围全收，不按国家与语言过滤**（spec V1，用户签字覆盖"仅官方公开流"红线；同步工具必须同时产出 `doc/iptv_nonofficial_hosts.md` 审计线索）。
- 成人内容**收录但默认隐藏**，设置项 `iptvAdultEnabled` 默认 `false`；成人判定 = `nsfw || tags.contains("xxx")` 的并集，封装成一个纯函数。
- **零新增 Gradle 依赖**。
- `domain/model/` 禁止 import Android/media3；`IptvCategory`、`StreamHeaders`、`isAdult` 判定都留在纯 Kotlin 层。
- 所有新字段**必须带默认值**，`radio_stations.json` 与用户自建电台 JSON 不得需要迁移；由测试读仓库真实文件钉住。
- `sources[].label` **一律留空**，分辨率走新字段 `quality`（spec §2.3：label 会进状态文案与通知栏 artist）。
- `category` 固定 `"general"`，**不用 `"tv"`**（`StationCategory.labelOf()` 对未知 key 原样回显）。
- 国家中文名**必须复用 `StationRegion.ISO2_CN`**，未覆盖的码回落 `countries.json` 的英文 `name`；不得新建第二张国家表。
- `getAllStations()` / `getActiveStations()` / `searchStations()` **保持只返回电台**，不掺 iptv。
- 搜索**必须复用 `StationFilter`**（给它加 `altNames` 匹配），iptv 页不许自造第二套过滤规则。
- header 注入：`StreamHeaderStore.current == null` 时 `RadioService` 行为**与现在逐字一致**；非 iptv 台一律写 null，stop 时清空。
- 切台基准（V11）：`PlayerManager` L494 的 `stationRepository.getActiveStations()` 改为"页面注册的基准优先，否则回落电台活动列表"。
- `IptvPlayerScreen` **必须显式接视频闸门** `setVideoPictureWanted`，否则症状是"只有声音没画面"。
- 播放页控件保持单行（项目红线）；命中区走 `Dimens` token，禁裸 dp。
- 同步工具：一次性代理 `http://127.0.0.1:7897`，不改任何全局配置；输入下限取基线 80%（`channels ≥ 25000`、`streams ≥ 14500`、最终记录数 `≥ 10000`）；**name 唯一性断言**；不接进 Gradle 构建。
- 每个任务结束时**自己必须可编译、可跑测**（上一份计划按模块切任务导致任务中途编译不过、跑不到测试步骤，本计划已按可编译单元重排）。
- 提交用 Conventional Commit 英文；每任务只提自己的文件；**每个任务完成后立即派 code review**（用户既定流程）。

## Review Focus

spec 隐含但单个任务的测试不会天然覆盖、真人一定撞上的六条：

1. **收藏了 iptv 台却在收藏页消失** —— `HomeViewModel` 用 `_stations`（只有电台）+ `mapNotNull` 解析 id，`getStationById` 回落救不了它。→ Task 8 的 ViewModel 测试。
2. **从通知栏/媒体键回到 iptv 台时静默无反应** —— `PlayerViewModel.loadStation` 在 `getStationById` 返回 null 时什么都不做。→ Task 5 的回落测试 + Task 9 的失败可见化。
3. **车机滚轮在 iptv 播放页把台切回电台列表** —— 基准列表硬编码在 `PlayerManager` L494。→ Task 7 的基准集合测试。
4. **`setUserAgent()` 与 `setDefaultRequestHeaders()` 互相覆盖**，导致 header 实际没发出去而测试全绿。→ Task 6 的诊断日志 + Task 10 的 T6 断言。
5. **同步工具产出残缺清单**（网络半截、上游波动、direct 重名）→ 12k 条里少一大截却"构建成功"。→ Task 2 的三重断言（条数下限、name 唯一、每条有源）。
6. **纯音频电台零回退被破坏** —— 新字段与 header 通路碰到老路径。→ Task 6 的 null 路径测试 + Task 10 的 T11（复用现有 V1/V6）。

---

### Task 1: 模型新字段与成人判定（纯函数 + 零迁移守卫）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/domain/model/Models.kt`
- Create: `app/src/main/java/com/radio/chinese/domain/model/IptvAdult.kt`
- Create: `app/src/main/java/com/radio/chinese/domain/model/StreamHeaders.kt`
- Test: `app/src/test/java/com/radio/chinese/IptvModelFieldsTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `StationSource(url: String, label: String = "", bitrate: Int = 0, quality: String = "", userAgent: String = "", referrer: String = "")`
  - `RadioStation(..., altNames: List<String> = emptyList(), nsfw: Boolean = false)`
  - `IptvAdult.isAdult(station: RadioStation): Boolean`
  - `IptvAdult.adultCategoryKey: String`（值 `"xxx"`）
  - `data class StreamHeaders(val userAgent: String, val referrer: String)`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/radio/chinese/IptvModelFieldsTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.IptvAdult
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationSource
import com.radio.chinese.domain.model.StreamHeaders
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iptv 需要的模型字段，以及"老数据零迁移"这条守卫。
 *
 * 最要紧的是那条读真实 radio_stations.json 的用例：新字段全部带默认值，
 * 一旦有人把默认值去掉或给它们设成必填，用户自建电台（存在 DataStore 里的 JSON）
 * 会在下次启动时解析失败 —— 那是"升级后收藏与自建台全空"的量级，
 * 而单测里只要不拿真实文件跑一遍就看不出来。
 */
class IptvModelFieldsTest {

    @Test
    fun `新字段都有默认值，老 JSON 照解`() {
        // 形状跟生产一致：radio_stations.json 顶层是 {"stations":[...]} 包装对象，不是裸数组
        val legacy = """{"stations":[{"id":"cnr","name":"中国之声","category":"news","streamUrl":"http://a/b"}]}"""
        val list = StationJson.parse(legacy)
        assertEquals(1, list.size)
        assertEquals("", list[0].altNames.joinToString())
        assertFalse(list[0].nsfw)
    }

    @Test
    fun `仓库里的真实电台清单能被新模型解析`() {
        // 与生产同一份资源：路径相对项目根，测试在 JVM 上跑，直接读文件系统
        val f = java.io.File("app/src/main/assets/radio_stations.json")
        assertTrue("找不到 $f", f.exists())
        val raw = f.readText(Charsets.UTF_8)
        val parsed = StationJson.parse(raw)
        assertTrue("真实清单解析出来是空", parsed.isNotEmpty())
        // 老数据没有这些字段 → 必须是默认值，不能是 null/异常
        assertTrue(parsed.none { it.nsfw })
        assertTrue(parsed.all { it.altNames.isEmpty() })
        assertTrue(parsed.flatMap { it.sources }.all { it.quality == "" && it.userAgent == "" })
    }

    @Test
    fun `quality 走新字段不借用 label`() {
        val s = StationSource(url = "http://a/b.m3u8", quality = "1080p")
        assertEquals("", s.label)          // 留空 → UI 回落「源 N」
        assertEquals("1080p", s.quality)
    }

    @Test
    fun `成人判定是标记与分类的并集`() {
        assertTrue(IptvAdult.isAdult(RadioStation(id = "a", name = "A", category = "general", nsfw = true)))
        assertTrue(IptvAdult.isAdult(RadioStation(id = "b", name = "B", category = "general", tags = listOf("xxx"))))
        assertFalse(IptvAdult.isAdult(RadioStation(id = "c", name = "C", category = "general", tags = listOf("news"))))
        assertEquals("xxx", IptvAdult.adultCategoryKey)
    }

    @Test
    fun `StreamHeaders 原样携带两个值`() {
        val h = StreamHeaders(userAgent = "UA/1.0", referrer = "https://x.y")
        assertEquals("UA/1.0", h.userAgent)
        assertEquals("https://x.y", h.referrer)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.IptvModelFieldsTest"`
Expected: 编译失败，报 `Unresolved reference: quality` / `altNames` / `nsfw` / `IptvAdult` / `StreamHeaders`

- [ ] **Step 3: 加字段与纯函数**

`Models.kt` 里把两个 data class 改成（只列改动处，其余字段一字不动）：

```kotlin
@Serializable
data class StationSource(
    val url: String,
    val label: String = "",
    val bitrate: Int = 0,
    /** 分辨率。刻意不复用 label：label 会进「正在尝试节目源 X…」状态文案与通知栏 artist 字段 */
    val quality: String = "",
    /** iptv 流常要求特定 UA；空串表示不覆盖 */
    val userAgent: String = "",
    val referrer: String = "",
)
```

```kotlin
@Serializable
data class RadioStation(
    ...,                                    // 原有字段顺序不动
    val language: String = "",
    /** 频道别名，参与搜索匹配（iptv 台常有多种写法） */
    val altNames: List<String> = emptyList(),
    /** 成人标记。iptv-org 的 is_nsfw 落在这里 */
    val nsfw: Boolean = false,
)
```

新建 `IptvAdult.kt`：

```kotlin
package com.radio.chinese.domain.model

/**
 * 成人内容的唯一判据。
 *
 * 取 `nsfw` 标记与 `xxx` 分类的**并集**：iptv-org 用两个独立机制表达同一件事
 * （频道标记 is_nsfw，分类表里有 xxx），只认一边会漏。
 * 放在 domain 是因为过滤发生在 ViewModel 与同步工具两处，两处必须同一个结论。
 */
object IptvAdult {
    const val adultCategoryKey: String = "xxx"

    fun isAdult(station: RadioStation): Boolean =
        station.nsfw || station.tags.contains(adultCategoryKey)
}
```

新建 `StreamHeaders.kt`：

```kotlin
package com.radio.chinese.domain.model

/**
 * 一条流要求的 HTTP 请求头。纯数据，放 domain 让 JVM 测试能钉住查表逻辑。
 *
 * 不放 ui 层：RadioService（service 层）要读它，而 service 不该依赖 ui。
 */
data class StreamHeaders(val userAgent: String, val referrer: String)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.IptvModelFieldsTest"`
Expected: PASS，5 个用例

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全绿（原 413 + 新 5 = 418）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/Models.kt app/src/main/java/com/radio/chinese/domain/model/IptvAdult.kt app/src/main/java/com/radio/chinese/domain/model/StreamHeaders.kt app/src/test/java/com/radio/chinese/IptvModelFieldsTest.kt
git commit -m "feat(domain): add quality, headers, altNames and nsfw fields with safe defaults"
```

---

### Task 2: 同步工具与真实清单（含三重断言）

**Files:**
- Create: `tools/iptv_sync.ps1`
- Create: `app/src/main/assets/iptv_channels.json`（由工具生成）
- Create: `doc/iptv_nonofficial_hosts.md`（由工具生成）
- Test: `app/src/test/java/com/radio/chinese/IptvAssetsGateTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `RadioStation`/`StationSource` 字段名（JSON 键必须与之一致）
- Produces: `assets/iptv_channels.json` —— `List<RadioStation>` 的 JSON 数组，id 全部以 `iptv:` 开头

- [ ] **Step 1: 写工具的失败前置脚本**

创建 `tools/iptv_sync.ps1`：

```powershell
# 构建期从 iptv-org API 生成内置清单。不参与 Gradle 构建：更新清单 = 人工跑一次 + 提交 + 发版。
# 三条不能省：一次性代理、产出前校验、name 唯一性断言。任何一步不合格就拒绝写入，
# 绝不允许"拉不到就用上次生成的"却对外说是最新的（本项目踩过旧产物冒充本次抓取）。
param([string]$Proxy = "http://127.0.0.1:7897", [switch]$SkipAudit)
$ErrorActionPreference = "Stop"

$assets = "app/src/main/assets/iptv_channels.json"
$audit  = "doc/iptv_nonofficial_hosts.md"
$work   = ".superpowers/tmp/iptv"
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Fetch($url, $name) {
    $f = Join-Path $work $name
    Remove-Item $f -Force -ErrorAction SilentlyContinue
    curl.exe -sS --fail --max-time 120 -x $Proxy -o $f $url
    if ($LASTEXITCODE -ne 0) { throw "拉取失败（退出码 $LASTEXITCODE）：$url。代理是否开着？" }
    if (-not (Test-Path $f)) { throw "拉取后文件不存在：$name" }
    return $f
}

"=== 1 拉取 ==="
$chF  = Fetch "https://iptv-org.github.io/api/channels.json"  "channels.json"
$stF  = Fetch "https://iptv-org.github.io/api/streams.json"   "streams.json"
$ctyF = Fetch "https://iptv-org.github.io/api/countries.json" "countries.json"
$catF = Fetch "https://iptv-org.github.io/api/categories.json" "categories.json"

$ch  = Get-Content $chF  -Raw -Encoding UTF8 | ConvertFrom-Json
$st  = Get-Content $stF  -Raw -Encoding UTF8 | ConvertFrom-Json
$cty = Get-Content $ctyF -Raw -Encoding UTF8 | ConvertFrom-Json
$cat = Get-Content $catF -Raw -Encoding UTF8 | ConvertFrom-Json

"=== 2 输入校验（下限 = 基线 80%，写死今日值会让上游正常波动卡死构建）==="
if (@($ch).Count  -lt 25000) { throw "channels 只有 $(@($ch).Count) 条，低于下限 25000" }
if (@($st).Count  -lt 14500) { throw "streams 只有 $(@($st).Count) 条，低于下限 14500" }
if (@($cat).Count -lt 25)    { throw "categories 只有 $(@($cat).Count) 条，低于下限 25" }

# --- 3 频道索引与分组 ---
$chIdx = @{}
foreach ($c in $ch) { if (-not $chIdx.ContainsKey([string]$c.id)) { $chIdx[[string]$c.id] = $c } }
$byChannel = @{}
$direct = @()
foreach ($s in $st) {
    $k = [string]$s.channel
    if ([string]::IsNullOrWhiteSpace($k) -or -not $chIdx.ContainsKey($k)) { $direct += $s; continue }
    if (-not $byChannel.ContainsKey($k)) { $byChannel[$k] = New-Object System.Collections.ArrayList }
    [void]$byChannel[$k].Add($s)
}

# --- 4 国家码 → 中文名：复用 StationRegion.ISO2_CN，不新建第二张表 ---
# 从 Kotlin 源码里把表读出来，保证两边只有一个真相
$iso = @{}
$src = Get-Content "app/src/main/java/com/radio/chinese/domain/model/StationRegion.kt" -Raw -Encoding UTF8
foreach ($m in [regex]::Matches($src, '"([A-Z]{2})"\s*to\s*"([^"]+)"')) { $iso[$m.Groups[1].Value] = $m.Groups[2].Value }
if ($iso["CN"] -ne "中国") { throw "从 StationRegion 读 ISO2_CN 失败（CN≠中国），不要绕过它硬编码" }
$ctyName = @{}
foreach ($c in $cty) { $ctyName[[string]$c.code] = [string]$c.name }

function CNName($code) {
    if ($iso.ContainsKey($code)) { return $iso[$code] }
    if ($ctyName.ContainsKey($code)) { return $ctyName[$code] }   # 表没覆盖 → 回落英文名，不留空
    return ""
}

# --- 5 direct 记录取名：分层 + junk 段名单（spec §2.2）---
$junk = @('index.m3u8','playlist.m3u8','master','manifest','live','hls','stream','chunklist.m3u8','')
function PathSegments($u) {
    try { return (@(([System.Uri]$u).AbsolutePath.Trim('/') -split '/')) } catch { return @() }
}
function DirectName($u) {
    $segs = PathSegments $u
    for ($i = $segs.Count - 1; $i -ge 0; $i--) {
        $s = $segs[$i]
        if ($s -eq '') { continue }
        if ($junk -contains $s.ToLower()) { continue }
        if ($s -notmatch '[A-Za-z]') { continue }
        if ($s -match '^\d+$') { continue }
        return ($s -replace '\.(m3u8|mpd|index)$','')
    }
    try { return ([System.Uri]$u).Host } catch { return 'unknown' }
}
function Sha1Short($s) {
    $b = [System.Security.Cryptography.SHA1]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes($s))
    return (($b | ForEach-Object { $_.ToString('x2') }) -join '').Substring(0, 8)
}

# --- 6 生成记录 ---
$rec = New-Object System.Collections.ArrayList
foreach ($k in $byChannel.Keys) {
    $c = $chIdx[$k]
    $srcs = @()
    foreach ($s in $byChannel[$k]) {
        if ([string]::IsNullOrWhiteSpace([string]$s.url)) { continue }
        if ([string]$s.url -notmatch '^https?://') { continue }
        $srcs += @{ url = [string]$s.url; label = ""; quality = [string]$s.quality
                    userAgent = [string]$s.user_agent; referrer = [string]$s.referrer }
    }
    if ($srcs.Count -eq 0) { continue }
    $desc = if ([string]::IsNullOrWhiteSpace([string]$c.network)) { [string]$c.name } else { [string]$c.network }
    if (-not [string]::IsNullOrWhiteSpace([string]$c.replaced_by)) { $desc += "（上游标记已被 $($c.replaced_by) 替换）" }
    [void]$rec.Add(@{
        id = "iptv:$k"; name = [string]$c.name
        altNames = @([string[]]($c.alt_names | ForEach-Object { [string]$_ }))
        category = "general"; streamUrl = $srcs[0].url; sources = $srcs
        website = [string]$c.website; description = $desc
        country = CNName ([string]$c.country); nsfw = ($c.is_nsfw -eq $true)
        tags = @([string[]]($c.categories | ForEach-Object { [string]$_ }))
    })
}
foreach ($d in $direct) {
    if ([string]::IsNullOrWhiteSpace([string]$d.url) -or [string]$d.url -notmatch '^https?://') { continue }
    $host1 = try { ([System.Uri][string]$d.url).Host } catch { 'unknown' }
    [void]$rec.Add(@{
        id = "iptv:direct:$($host1):$(Sha1Short ([string]$d.url))"
        name = DirectName ([string]$d.url); altNames = @(); category = "general"
        streamUrl = [string]$d.url
        sources = @(@{ url = [string]$d.url; label = ""; quality = [string]$d.quality
                       userAgent = [string]$d.user_agent; referrer = [string]$d.referrer })
        website = ""; description = ""; country = ""; nsfw = $false; tags = @()
    })
}

# --- 7 重名消歧：同名追加短 host（spec §2.2 第 4 步）---
$dupes = $rec | Group-Object { $_.name } | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name }
if ($dupes.Count -gt 0) {
    foreach ($r in $rec) {
        if ($dupes -contains $r.name) {
            $h = try { ([System.Uri]$r.streamUrl).Host } catch { 'x' }
            $r.name = "$($r.name) ($h)"
        }
    }
}
$still = @($rec | Group-Object { $_.name } | Where-Object { $_.Count -gt 1 })
if ($still.Count -gt 0) { throw "消歧后仍有 $($still.Count) 组重名（首组：$($still[0].Name) x$($still[0].Count)），不产出" }

# --- 8 写盘前最后一道：条数下限 ---
if (@($rec).Count -lt 10000) { throw "最终记录只有 $(@($rec).Count) 条，低于下限 10000" }

$json = ConvertTo-Json @{ stations = $rec } -Depth 6 -Compress   # 包装对象，与 radio_stations.json 同形状
Set-Content -Path $assets -Value $json -Encoding utf8

# --- 9 审计线索：非官方域名条目 ---
if (-not $SkipAudit) {
    $nonofficial = @()
    foreach ($k in $byChannel.Keys) {
        $c = $chIdx[$k]
        $web = [string]$c.website
        foreach ($s in $byChannel[$k]) {
            try { $sh = ([System.Uri][string]$s.url).Host } catch { continue }
            if ([string]::IsNullOrWhiteSpace($web)) { $nonofficial += "$k|无 website|$sh"; continue }
            try { $wh = ([System.Uri]$web).Host } catch { $nonofficial += "$k|website 不可解析|$sh"; continue }
            $sBase = ($sh -replace '^www\.','') ; $wBase = ($wh -replace '^www\.','')
            $tail = ($sBase -split '\.')[-2..-1] -join '.'
            $wtail = ($wBase -split '\.')[-2..-1] -join '.'
            if ($tail -ne $wtail) { $nonofficial += "$k|$wh|$sh" }
        }
    }
    $head = "# iptv 非官方域名条目清单`n`n由 tools/iptv_sync.ps1 生成，勿手改。`n`n" +
      "这些条目的流主机名与该台官方站点不同主域（或该台根本没有 website），" +
      "按项目红线「仅收录官方公开流」它们**不属于官方公开源**。`n`n" +
      "spec V1 决定全收，本文件是审计线索，不是功能开关。`n`n" +
      "| 频道 | 官方站点 | 流主机 |`n|---|---|---|`n"
    $body = ($nonofficial | Sort-Object -Unique | ForEach-Object { $p = $_ -split '\|'; "| $($p[0]) | $($p[1]) | $($p[2]) |" }) -join "`n"
    Set-Content -Path $audit -Value ($head + $body + "`n") -Encoding utf8
    "审计条目 = $($nonofficial.Count)（去重后 $(@($nonofficial | Sort-Object -Unique).Count)）"
}

"=== 10 结果 ==="
"记录数   = $(@($rec).Count)"
"频道记录 = $($byChannel.Keys.Count)   direct 记录 = $($direct.Count)"
"json     = $([math]::Round((Get-Item $assets).Length/1MB,2)) MB 未压缩"
"分类 id  = $(@($cat) | ForEach-Object { [string]$_.id } | Sort-Object -Unique).Count 种"
```

- [ ] **Step 2: 跑工具生成真实清单**

Run: `pwsh -NoProfile -File tools/iptv_sync.ps1`
Expected: 末尾打印记录数 ≥ 10000、json 约 5.25 MB；无 throw。若报"拉取失败"，先确认代理端口 7897 在监听。

- [ ] **Step 3: 写清单闸门测试（RED 之前先确认它拦得住）**

创建 `app/src/test/java/com/radio/chinese/IptvAssetsGateTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置清单的闸门。这条测试不测代码，测**数据**：
 * 清单是脚本从网络生成的，一旦半截失败就会留下一份少一大截的 assets，
 * 而 App 照样能跑、只是"频道少得离谱"——用户会以为功能坏了。
 * 三重断言对应 tools/iptv_sync.ps1 里的三道校验，两边必须一致。
 */
class IptvAssetsGateTest {

    private val json by lazy {
        val f = File("app/src/main/assets/iptv_channels.json")
        assertTrue("清单不存在：先跑 pwsh -NoProfile -File tools/iptv_sync.ps1", f.exists())
        StationJson.parse(f.readText(Charsets.UTF_8))
    }

    @Test
    fun `清单条数不低于一萬`() {
        assertTrue("实际 ${json.size} 条", json.size >= 10_000)
    }

    @Test
    fun `每条都有 id、名字、至少一个源`() {
        assertTrue(json.all { it.id.isNotBlank() })
        assertTrue(json.all { it.name.isNotBlank() })
        assertTrue(json.all { it.allSources().isNotEmpty() })
    }

    @Test
    fun `id 全部带 iptv 前缀且唯一`() {
        assertTrue("有不含前缀的 id", json.all { it.id.startsWith("iptv:") })
        assertTrue("id 有重复", json.map { it.id }.toSet().size == json.size)
    }

    @Test
    fun `显示名全库唯一`() {
        val dup = json.groupingBy { it.name }.eachCount().filterValues { it > 1 }
        assertTrue("重名 ${dup.size} 组，例：${dup.keys.take(3)}", dup.isEmpty())
    }

    @Test
    fun `label 一律留空，分辨率在 quality 上`() {
        assertTrue("有 source 把分辨率写进了 label",
            json.flatMap { it.sources }.all { it.label.isEmpty() })
    }

    @Test
    fun `category 只用 general，不出现内部 key`() {
        assertTrue(json.all { it.category == "general" })
    }

    @Test
    fun `direct 记录的 id 含 host 与哈希`() {
        val d = json.filter { it.id.startsWith("iptv:direct:") }
        assertTrue("没有 direct 记录（应有约两千条）", d.isNotEmpty())
        assertTrue(d.all { it.id.split(":").size == 4 })
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.IptvAssetsGateTest"`
Expected: PASS，7 个用例。若 `显示名全库唯一` 红，说明工具的第 7 步消歧没生效 —— 修工具，不要改测试。

- [ ] **Step 5: 提交**

```bash
git add tools/iptv_sync.ps1 app/src/main/assets/iptv_channels.json doc/iptv_nonofficial_hosts.md app/src/test/java/com/radio/chinese/IptvAssetsGateTest.kt
git commit -m "feat(iptv): add build-time iptv-org sync tool and the generated channel list"
```

---

### Task 3: iptv 分类集与国家名复用（纯函数）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/domain/model/IptvCategory.kt`
- Test: `app/src/test/java/com/radio/chinese/IptvCategoryTest.kt`

**Interfaces:**
- Consumes: Task 2 生成的 assets（测试读真实 `categories.json` 快照做覆盖核对）
- Produces: `IptvCategory.entries`、`IptvCategory.labelOf(key: String): String`、`IptvCategory.ordered(): List<Pair<String,String>>`、`IptvCategory.otherLabel: String`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/radio/chinese/IptvCategoryTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.IptvAdult
import com.radio.chinese.domain.model.IptvCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iptv 的分类集。不动 StationCategory —— 那份是管理页与添加电台下拉的取值来源，
 * 往里加 movies/series/kids 会让那些页面长出与"电台"语义不符的格子。
 */
class IptvCategoryTest {

    @Test
    fun `覆盖 iptv-org 全部 30 个分类 id`() {
        // 这份 id 清单是 2026-11-06 从 categories.json 实测抄下来的
        val upstream = listOf(
            "auto", "animation", "business", "classic", "comedy", "cooking", "culture",
            "documentary", "education", "entertainment", "family", "general", "interactive",
            "kids", "legislative", "lifestyle", "movies", "music", "news", "outdoor",
            "public", "relax", "religious", "science", "series", "shop", "sports",
            "travel", "weather", "xxx",
        )
        val mine = IptvCategory.entries.map { it.key }.toSet()
        val missing = upstream.filterNot { it in mine }
        assertTrue("枚举漏了 $missing", missing.isEmpty())
    }

    @Test
    fun `每个分类都有中文标签`() {
        val blank = IptvCategory.entries.filter { it.label.isBlank() }
        assertTrue("空标签 $blank", blank.isEmpty())
    }

    @Test
    fun `未知 key 回落其他而不是抛异常`() {
        // 上游随时可能加新分类；抛异常的后果是整个 iptv 页打不开
        assertEquals(IptvCategory.otherLabel, IptvCategory.labelOf("not-a-real-category"))
        assertEquals(IptvCategory.otherLabel, IptvCategory.labelOf(""))
    }

    /**
     * 断言的是**关系**不是字面量。上一版写 `assertEquals("xxx", IptvCategory.ADULT.key)` ——
     * 全文没引用 IptvAdult，谁改了 adultCategoryKey 这条照样绿，
     * 而失效后果是 xxx 分类频道在开关关着时直接露出来（V2 唯一的默认隐藏闸门）。
     */
    @Test
    fun `成人分类的 key 与 IptvAdult 用的是同一个字`() {
        assertEquals(IptvAdult.adultCategoryKey, IptvCategory.ADULT.key)
    }

    @Test
    fun `ordered 的顺序就是展示顺序且不含空 key`() {
        val keys = IptvCategory.ordered().map { it.first }
        assertTrue(keys.none { it.isBlank() })
        assertEquals(IptvCategory.entries.map { it.key }.toSet(), keys.toSet())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.IptvCategoryTest"`
Expected: 编译失败，`Unresolved reference: IptvCategory`

- [ ] **Step 3: 实现枚举**

创建 `app/src/main/java/com/radio/chinese/domain/model/IptvCategory.kt`：

```kotlin
package com.radio.chinese.domain.model

/**
 * iptv 自己的分类集（spec V8）。
 *
 * key 必须逐字对齐 iptv-org 的 categories.json —— 下面 30 个条目是 2026-11-06
 * 实测抄录的（初版我漏了 family、还把 shop 编成过一个不存在的 FINANCE_LIKE），
 * 由 IptvCategoryTest 的第一条用例钉住。
 */
enum class IptvCategory(val key: String, val label: String) {
    AUTO("auto", "汽车"),
    ANIMATION("animation", "动画"),
    BUSINESS("business", "商业"),
    CLASSIC("classic", "经典"),
    COMEDY("comedy", "喜剧"),
    COOKING("cooking", "美食"),
    CULTURE("culture", "人文"),
    DOCUMENTARY("documentary", "纪录"),
    EDUCATION("education", "教育"),
    ENTERTAINMENT("entertainment", "娱乐"),
    FAMILY("family", "家庭"),
    GENERAL("general", "综合"),
    INTERACTIVE("interactive", "互动"),
    KIDS("kids", "少儿"),
    LEGISLATIVE("legislative", "议会"),
    LIFESTYLE("lifestyle", "生活"),
    MOVIES("movies", "电影"),
    MUSIC("music", "音乐"),
    NEWS("news", "新闻"),
    OUTDOOR("outdoor", "户外"),
    PUBLIC("public", "公共"),
    RELAX("relax", "休闲"),
    RELIGIOUS("religious", "宗教"),
    SCIENCE("science", "科学"),
    SERIES("series", "剧集"),
    SHOP("shop", "购物"),
    SPORTS("sports", "体育"),
    TRAVEL("travel", "旅行"),
    WEATHER("weather", "天气"),
    // 常量只有一个出处：依赖单向指向 IptvAdult，不在这里复写一份字面量
    ADULT(IptvAdult.adultCategoryKey, "成人");

    companion object {
        const val otherLabel: String = "其他"

        /** 未知 key 回落「其他」：上游随时可能加新分类，抛异常会让整个 iptv 页打不开 */
        fun labelOf(key: String): String = entries.firstOrNull { it.key == key }?.label ?: otherLabel

        /** 展示顺序即枚举声明顺序（按中文标签笔画无意义，稳定可预期更重要） */
        fun ordered(): List<Pair<String, String>> = entries.map { it.key to it.label }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.IptvCategoryTest"`
Expected: PASS，5 个用例

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/IptvCategory.kt app/src/test/java/com/radio/chinese/IptvCategoryTest.kt
git commit -m "feat(domain): add iptv category set covering all upstream ids"
```

---

### Task 4: iptv 仓库（懒加载、byId、header 查表）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/data/repository/IptvStationRepository.kt`
- Modify: `app/src/main/java/com/radio/chinese/di/AppModule.kt`
- Test: `app/src/test/java/com/radio/chinese/IptvStationRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `StreamHeaders`、Task 2 的 assets 格式
- Produces:
  - `@Singleton class IptvStationRepository @Inject constructor(@ApplicationContext context: Context)`
  - `suspend fun all(): List<RadioStation>`
  - `suspend fun byId(id: String): RadioStation?`
  - `fun isIptvId(id: String): Boolean`
  - `suspend fun headersFor(url: String): StreamHeaders?`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/radio/chinese/IptvStationRepositoryTest.kt`（用假 context 读文件系统，不启 Android）：

```kotlin
package com.radio.chinese

import android.content.Context
import com.radio.chinese.data.repository.IptvStationRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库的三件事：懒加载只解一次、byId 只认 iptv 前缀、header 查表只返回非空。
 *
 * header 那条最重要：`headersFor` 对没有 header 的流必须返回 null，
 * 因为 PlayerManager 只在拿到非 null 时才写 StreamHeaderStore，
 * 一旦这里返回了空字串的 StreamHeaders，RadioService 就会给纯音频台
 * 塞一组空 header —— 那是"零回退"红线上的静默行为改变。
 */
class IptvStationRepositoryTest {

    private fun repoWith(json: String): IptvStationRepository {
        val ctx = mockk<Context>()
        val stream = java.io.ByteArrayInputStream(json.toByteArray())
        every { ctx.assets.open("iptv_channels.json") } returns stream
        return IptvStationRepository(ctx)
    }

    // 包装对象形状：仓库走 StationJson.parse，与内置电台清单同一个解析器
    private val sample = """
        {"stations":[
          {"id":"iptv:CCTV1.cn","name":"CCTV 1","category":"general","streamUrl":"http://a/1.m3u8",
           "sources":[{"url":"http://a/1.m3u8","label":"","quality":"1080p",
                        "userAgent":"UA-X","referrer":"http://ref.example"}]},
          {"id":"iptv:plain.cn","name":"Plain","category":"general","streamUrl":"http://b/2.m3u8",
           "sources":[{"url":"http://b/2.m3u8","label":"","quality":"","userAgent":"","referrer":""}]}
        ]}
    """.trimIndent()

    @Test
    fun `byId 命中 iptv 台`() = runTest {
        val r = repoWith(sample)
        assertEquals("CCTV 1", r.byId("iptv:CCTV1.cn")?.name)
    }

    @Test
    fun `byId 对非前缀 id 不查表`() = runTest {
        val r = repoWith(sample)
        assertNull(r.byId("cnr"))
        assertNull(r.byId(""))
    }

    @Test
    fun `headersFor 只给真正带 header 的流`() = runTest {
        val r = repoWith(sample)
        val h = r.headersFor("http://a/1.m3u8")
        assertTrue("带 UA 的流应返回非空", h != null)
        assertEquals("UA-X", h!!.userAgent)
        assertEquals("http://ref.example", h.referrer)
        // 关键：两个字段都空的流必须返回 null，不是返回空 StreamHeaders
        assertNull(r.headersFor("http://b/2.m3u8"))
        assertNull(r.headersFor("http://不存在的.url"))
    }

    @Test
    fun `all 只解析一次`() = runTest {
        var opens = 0
        val ctx = mockk<Context>()
        every { ctx.assets.open("iptv_channels.json") } answers {
            opens++
            java.io.ByteArrayInputStream(sample.toByteArray())
        }
        val r = IptvStationRepository(ctx)
        r.all(); r.all(); r.all()
        assertEquals("assets 被重复解析", 1, opens)
    }

    @Test
    fun `isIptvId 只认前缀`() {
        val ctx = mockk<Context>()
        val r = IptvStationRepository(ctx)
        assertTrue(r.isIptvId("iptv:CCTV1.cn"))
        assertTrue(r.isIptvId("iptv:direct:a.tv:deadbeef"))
        assertTrue(!r.isIptvId("cnr"))
    }

    @Test
    fun `资源缺失时返回空表而不是崩`() = runTest {
        val ctx = mockk<Context>()
        every { ctx.assets.open("iptv_channels.json") } throws java.io.FileNotFoundException("no such file")
        val r = IptvStationRepository(ctx)
        assertTrue(r.all().isEmpty())
        assertNull(r.byId("iptv:CCTV1.cn"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.IptvStationRepositoryTest"`
Expected: 编译失败，`Unresolved reference: IptvStationRepository`

- [ ] **Step 3: 实现仓库**

创建 `app/src/main/java/com/radio/chinese/data/repository/IptvStationRepository.kt`：

```kotlin
package com.radio.chinese.data.repository

import android.content.Context
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StreamHeaders
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * iptv 清单的唯一读取入口（spec V4/V9）。
 *
 * 独立于 StationRepository：12k 条不能进首页/分类/地区/EPG 的任何一条列表，
 * 那些页面要的是"451 个电台"，掺进来既拖慢也串语义。
 *
 * 懒加载 + Mutex：assets 是 5.25 MB，第一次解析在 IO 线程；Mutex 保证并发进入
 * （列表页与播放页同时起）不会解两遍。解析失败退化成空表 —— iptv 页显示空态，
 * 而不是把崩溃带给用户。
 */
@Singleton
class IptvStationRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile private var loaded: List<RadioStation>? = null
    @Volatile private var headerIndex: Map<String, StreamHeaders>? = null

    suspend fun all(): List<RadioStation> = loaded ?: withContext(Dispatchers.IO) {
        mutex.withLock {
            loaded ?: runCatching {
                val raw = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                // 与内置电台清单同一个解析器、同一种包装形状：另起一个 decodeFromString
                // 就是第二套真相，新字段会在这条路径上静默丢
                StationJson.parse(raw)
            }.getOrElse {
                android.util.Log.w(TAG, "iptv 清单读取失败，退化为空表：${it.message}")
                emptyList()
            }.also {
                loaded = it
                headerIndex = buildHeaderIndex(it)
            }
        }
    }

    /** 只有 iptv 前缀才查本表：非前缀 id 每次都过一遍 12k 条是白付的代价 */
    suspend fun byId(id: String): RadioStation? {
        if (!isIptvId(id)) return null
        return all().firstOrNull { it.id == id }
    }

    fun isIptvId(id: String): Boolean = id.startsWith(PREFIX)

    /**
     * 该流是否需要自定义请求头。
     *
     * 两个字段都为空时**返回 null**，不是返回空对象：PlayerManager 只在非 null 时
     * 写 StreamHeaderStore，返回空对象会让纯音频台也被塞一组空 header。
     */
    suspend fun headersFor(url: String): StreamHeaders? {
        all()   // 确保索引已建
        return headerIndex?.get(url)
    }

    private fun buildHeaderIndex(list: List<RadioStation>): Map<String, StreamHeaders> {
        val m = HashMap<String, StreamHeaders>(1024)
        for (s in list) {
            for (src in s.allSources()) {
                if (src.userAgent.isNotBlank() || src.referrer.isNotBlank()) {
                    m[src.url] = StreamHeaders(src.userAgent, src.referrer)
                }
            }
        }
        return m
    }

    companion object {
        const val ASSET = "iptv_channels.json"
        const val PREFIX = "iptv:"
        private const val TAG = "IptvRepo"
    }
}
```

`AppModule.kt` 不需要新 provider（`@Singleton` + `@Inject constructor` 已可被 Hilt 直接提供）。本步只需确认这一点：

Run: `.\gradlew.bat :app:assembleDebug`
Expected: SUCCESS。若报 `Cannot provide: IptvStationRepository`，说明它被某个 `@HiltViewModel` 以接口形式注入，按报错处的实际类型补 provider —— 不要改成 `@Provides` 绕过去。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.IptvStationRepositoryTest"`
Expected: PASS，6 个用例

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/data/repository/IptvStationRepository.kt app/src/test/java/com/radio/chinese/IptvStationRepositoryTest.kt
git commit -m "feat(data): add lazily loaded iptv station repository with header index"
```

---

### Task 5: id 回落与别名搜索（两个"静默失效"的堵口）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/data/repository/StationRepository.kt`
- Modify: `app/src/main/java/com/radio/chinese/domain/model/StationFilter.kt`
- Test: `app/src/test/java/com/radio/chinese/IptvLookupFallbackTest.kt`
- Test: `app/src/test/java/com/radio/chinese/StationFilterTest.kt`（若已存在则追加用例）

**Interfaces:**
- Consumes: Task 4 的 `IptvStationRepository.byId/isIptvId`
- Produces:
  - `StationRepository.getStationById(id)` 对 `iptv:` 前缀回落查 iptv 仓库
  - `StationFilter.matches` 增加 `altNames` 匹配

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/radio/chinese/IptvLookupFallbackTest.kt`：

```kotlin
package com.radio.chinese

import android.content.Context
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.local.SourceAvailabilityStore
import com.radio.chinese.data.repository.IptvStationRepository
import com.radio.chinese.data.repository.StationRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * getStationById 的回落。
 *
 * 需要它的不是播放主路径（playStation 收对象），而是 PlayerViewModel.loadStation：
 * 那里按 id 查，查不到就 `if (station != null)` 什么都不做 —— 通知栏点回、
 * 媒体键恢复、进程死亡后重建全走这条路，失败是静默的。
 */
class IptvLookupFallbackTest {

    private fun repo(iptv: IptvStationRepository, radioJson: String): StationRepository {
        val ctx = mockk<Context>()
        every { ctx.assets.open("radio_stations.json") } returns
            java.io.ByteArrayInputStream(radioJson.toByteArray())
        return StationRepository(ctx, mockk<SourceAvailabilityStore>(relaxed = true),
            mockk<RadioPreferences>(relaxed = true), iptv)
    }

    @Test
    fun `iptv 前缀的 id 能从 iptv 仓库解析`() = runTest {
        val iptv = mockk<IptvStationRepository>()
        every { iptv.isIptvId(any()) } answers { firstArg<String>().startsWith("iptv:") }
        every { iptv.byId("iptv:CCTV1.cn") } returns
            com.radio.chinese.domain.model.RadioStation(id = "iptv:CCTV1.cn", name = "CCTV 1", category = "general")
        val r = repo(iptv, """{"stations":[{"id":"cnr","name":"中国之声","category":"news"}]}""")
        assertEquals("CCTV 1", r.getStationById("iptv:CCTV1.cn")?.name)
        assertEquals("中国之声", r.getStationById("cnr")?.name)
        assertNull(r.getStationById("nope"))
    }

    @Test
    fun `非前缀 id 不去查 iptv 仓库`() = runTest {
        val iptv = mockk<IptvStationRepository>()
        every { iptv.isIptvId(any()) } returns false
        val never = mockk<IptvStationRepository>()
        every { never.isIptvId(any()) } returns false
        // byId 若被调用就炸：证明电台查找不会每次都过一遍 12k 条
        val r = repo(iptv, """{"stations":[{"id":"cnr","name":"中国之声","category":"news"}]}""")
        assertEquals("中国之声", r.getStationById("cnr")?.name)
        assertTrue("电台列表被掺进 iptv", r.getAllStations().none { it.id.startsWith("iptv:") })
    }
}
```

`StationFilterTest.kt` 追加（若文件不存在则新建同名类）：

```kotlin
    @Test
    fun `别名参与搜索匹配`() {
        val st = com.radio.chinese.domain.model.RadioStation(
            id = "iptv:CCTV1.cn", name = "CCTV 1", category = "general",
            altNames = listOf("中央电视台综合频道", "CCTV-1"),
        )
        assertTrue(StationFilter.matches(st, "中央电视台综合频道"))
        assertTrue(StationFilter.matches(st, "cctv-1"))
        assertTrue(StationFilter.matches(st, "CCTV 1"))
    }

    @Test
    fun `电台页与iptv页对同一 query 给出同一套判定`() {
        // 两页必须共用 StationFilter；这条用例是"不许各自写一套过滤"的守卫
        val list = listOf(
            com.radio.chinese.domain.model.RadioStation(id = "a", name = "音乐台", category = "music"),
            com.radio.chinese.domain.model.RadioStation(id = "iptv:b", name = "电影台", category = "general",
                altNames = listOf("影视频道")),
        )
        assertEquals(listOf("iptv:b"), StationFilter.apply(list, null, "影视").map { it.id })
        assertEquals(listOf("a"), StationFilter.apply(list, null, "音乐").map { it.id })
        assertEquals(2, StationFilter.apply(list, null, "").size)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.IptvLookupFallbackTest" --tests "com.radio.chinese.StationFilterTest"`
Expected: `IptvLookupFallbackTest` 编译失败（`StationRepository` 构造函数没有第 4 个参数）；`别名参与搜索匹配` FAIL（altNames 未参与匹配）

- [ ] **Step 3: 加回落与别名匹配**

`StationRepository.kt`：构造函数注入 iptv 仓库，并改 `getStationById`：

```kotlin
class StationRepository(
    @ApplicationContext private val context: Context,
    private val sourceAvailabilityStore: SourceAvailabilityStore,
    private val preferences: RadioPreferences,
    private val iptvRepository: IptvStationRepository,      // 新增
) {
    ...
    /**
     * 按 id 查台。
     *
     * iptv 前缀走回落：PlayerViewModel.loadStation 是唯一按 id 起播的入口
     * （通知栏点回、媒体键、进程重建），它拿到 null 时是静默不动作的，
     * 所以这里必须能解析到 iptv 台，否则症状是"点了通知栏没反应"。
     * 非前缀 id 不去查 iptv 表 —— 电台查找每次都过 12k 条是白付。
     */
    suspend fun getStationById(id: String): RadioStation? =
        getAllStations().find { it.id == id }
            ?: if (id.startsWith(IptvStationRepository.PREFIX)) iptvRepository.byId(id) else null
```

`StationFilter.kt` 的 `matches` 里，把 `hitOriginal` 加一项，并更新文档注释：

```kotlin
        val hitOriginal = station.name.lowercase().contains(q) ||
            station.altNames.any { it.lowercase().contains(q) } ||      // 新增：iptv 台常有多种写法
            station.frequency.lowercase().contains(q) ||
            station.description.lowercase().contains(q) ||
            station.country.lowercase().contains(q) ||
            station.language.lowercase().contains(q) ||
            tagLabels.any { it.contains(q) || q.contains(it) }
```

注释补一句：「别名只对**原样文本**生效，不进拼音列：iptv 别名多是英文全称，展开拼音会把假命中面铺大。」

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.IptvLookupFallbackTest" --tests "com.radio.chinese.StationFilterTest"`
Expected: PASS

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全绿。若 `StationRepository` 的其它构造点（测试或 DI）报参数缺失，**补齐参数而不是给默认值** —— 给默认值会让将来漏注入时静默走空回落。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/data/repository/StationRepository.kt app/src/main/java/com/radio/chinese/domain/model/StationFilter.kt app/src/test/java/com/radio/chinese/IptvLookupFallbackTest.kt app/src/test/java/com/radio/chinese/StationFilterTest.kt
git commit -m "feat(data): resolve iptv ids through fallback lookup and match channel aliases"
```

---

### Task 6: HTTP header 通路（含必须实测的覆盖关系）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/service/StreamHeaderStore.kt`
- Modify: `app/src/main/java/com/radio/chinese/service/RadioService.kt`
- Modify: `app/src/main/java/com/radio/chinese/service/PlayerManager.kt`
- Test: `app/src/test/java/com/radio/chinese/StreamHeaderStoreTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `StreamHeaders`、Task 4 的 `headersFor(url)`
- Produces:
  - `@Singleton class StreamHeaderStore { @Volatile var current: StreamHeaders? }`
  - `RadioService` 在建 `DefaultHttpDataSource.Factory` 时读它
  - `PlayerManager` 起播前写入、stop 清空

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/radio/chinese/StreamHeaderStoreTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.StreamHeaders
import com.radio.chinese.service.StreamHeaderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * header 单例的三条规矩：默认 null、能被写入、必须能被清空。
 *
 * "默认 null" 是纯音频台的零回退守卫 —— RadioService 只在非 null 时才动
 * setUserAgent/setDefaultRequestHeaders，默认非 null 会让所有老流都被改头。
 * "能清空" 防的是串台：播完带 header 的 iptv 台再播电台，残留会把上一个台的
 * UA 带给下一个台（项目已在 WebDAV 单例凭证上踩过同一类）。
 */
class StreamHeaderStoreTest {

    @Test
    fun `默认没有 header`() {
        assertNull(StreamHeaderStore().current)
    }

    @Test
    fun `写入与清空`() {
        val s = StreamHeaderStore()
        val h = StreamHeaders(userAgent = "UA/1", referrer = "http://r")
        s.current = h
        assertSame(h, s.current)
        s.current = null
        assertNull(s.current)
    }

    @Test
    fun `RadioService 用到的两个 setter 名存在于 media3`() {
        // 这条不是测我们的代码，是测我对 media3 API 的记忆。
        // setUserAgent 与 setDefaultRequestHeaders 同时调用时谁赢，静态读代码判不了，
        // 由真机验收 T6 的诊断日志定论；这里只保证方法名没写错、不会编译期猜错。
        val cls = Class.forName("androidx.media3.datasource.HttpDataSource\$Factory")
        assertTrue2(cls.methods.any { it.name == "setUserAgent" })
        assertTrue2(cls.methods.any { it.name == "setDefaultRequestHeaders" })
    }

    private fun assertTrue2(b: Boolean) { assertEquals(true, b) }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.StreamHeaderStoreTest"`
Expected: 编译失败，`Unresolved reference: StreamHeaderStore`

- [ ] **Step 3: 建单例并接线**

创建 `app/src/main/java/com/radio/chinese/service/StreamHeaderStore.kt`：

```kotlin
package com.radio.chinese.service

import com.radio.chinese.domain.model.StreamHeaders
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前这一路播放需要的 HTTP 请求头。
 *
 * 为什么用单例而不是随 MediaItem 传：media3 的 MediaItem 不支持携带 per-item header，
 * 而 DataSource.Factory.createDataSource() 拿不到 URI（URI 要到 open(DataSpec) 才知道）。
 * 项目里 WebDAV 的动态 Auth 用的就是同一个手法（工厂 lambda 里读单例当前值）。
 *
 * 已知代价：这是全局可变状态。边界是本应用单播放器、起播串行，写入紧贴
 * setMediaItem 之前、stop 时清空，因此不并发。**将来引入画中画或第二路播放时，
 * 这里必须改成随 MediaItem 传递（用 MetadataEntry 携带），不能继续加 volatile 补丁。**
 */
@Singleton
class StreamHeaderStore @Inject constructor() {
    @Volatile var current: StreamHeaders? = null
}
```

`RadioService.kt`：注入 `streamHeaderStore`，在现有工厂里（L121 附近，紧跟 WebDAV 动态 Auth 那段）加：

```kotlin
            // iptv 流的 UA/Referer：与上面 WebDAV 动态 Auth 同一位置、同一手法。
            // current == null 时下面整段跳过，行为与改造前逐字一致（纯音频零回退）。
            val sh = streamHeaderStore.current
            if (sh != null) {
                httpFactory.setUserAgent(sh.userAgent)
                val headers = mutableMapOf<String, String>()
                if (sh.referrer.isNotBlank()) headers["Referer"] = sh.referrer
                if (headers.isNotEmpty()) httpFactory.setDefaultRequestHeaders(headers)
                // 诊断日志：T6 靠它断言两个值真的到了工厂。
                // 两个 setter 的覆盖关系（UA 本身就是一个 header）我**没有验证过**，
                // 若 T6 显示 UA 被 defaultRequestHeaders 清掉，改为只用后者一次写全（含 User-Agent 项）。
                android.util.Log.d("IptvHeaders", "applied ua=${sh.userAgent.isNotBlank()} ref=${sh.referrer.isNotBlank()}")
            }
```

`PlayerManager.kt`：注入 `streamHeaderStore` 与 `iptvRepository`，在 `playStation(station)` 里、构造 MediaItem 之前写入：

```kotlin
        // 起播前决定这一路要不要自定义请求头：非 iptv 台一律写 null（不是"不写"，
        // 是必须覆盖掉上一个台留下的值，否则串台）
        scope.launch {
            streamHeaderStore.current = if (iptvRepository.isIptvId(station.id)) {
                val url = station.allSources().firstOrNull()?.url ?: ""
                iptvRepository.headersFor(url)
            } else null
            // ...原有起播流程
        }
```

并在 `stop()`/`release()` 与 `playOperaFile()` 开头各加一行 `streamHeaderStore.current = null`。

注意：`headersFor` 拿的是"第一个源"的 url。多源回退时后续源切换也要更新 —— 在既有的"正在尝试节目源 N"那段（`playQueueSource`）同样写入：

```kotlin
        streamHeaderStore.current = if (iptvRepository.isIptvId(station.id))
            iptvRepository.headersFor(source.url) else null
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.StreamHeaderStoreTest"`
Expected: PASS，3 个用例（第三条测的是 media3 API 名存在，不是我们的逻辑）

Run: `.\gradlew.bat :app:assembleDebug`
Expected: SUCCESS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/service/StreamHeaderStore.kt app/src/main/java/com/radio/chinese/service/RadioService.kt app/src/main/java/com/radio/chinese/service/PlayerManager.kt app/src/test/java/com/radio/chinese/StreamHeaderStoreTest.kt
git commit -m "feat(service): apply per-stream user agent and referer for iptv sources"
```

---

### Task 7: 切台基准改为"当前页面注册"（V11）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/domain/model/SkipBase.kt`
- Modify: `app/src/main/java/com/radio/chinese/service/PlayerManager.kt`（L494 那一行）
- Test: `app/src/test/java/com/radio/chinese/SkipBaseSelectionTest.kt`

**Interfaces:**
- Consumes: Task 4/5 的仓库
- Produces:
  - `SkipBase.resolve(registered: List<RadioStation>?, radioActive: List<RadioStation>): List<RadioStation>`（纯函数）
  - `PlayerManager.setSkipBase(list: List<RadioStation>?)` / `PlayerManager.clearSkipBase()`

> **为何多拆一个纯函数**：上一版把规则写在测试里的局部 `pick()` 函数，那测试写完就绿 —— 违反 TDD（没看着它失败），
> 而 PlayerManager 挂着 Hilt 与协程作用域不能直接 JVM 测。把规则下移到 `domain/model/SkipBase`，
> 既能让 RED 真失败，也符合项目“判定集中在纯函数、落地是薄胶水”的既有手法。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/radio/chinese/SkipBaseSelectionTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.SkipBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切台基准的选取规则（spec V11「跟当前页走」）。
 *
 * 现状是 PlayerManager L494 硬编码 stationRepository.getActiveStations()，
 * 于是车机滚轮在 iptv 播放页会把人切回电台列表 —— 用户看到的是“我明明在选电视频道”。
 * 基准由当前页面注册；未注册时回落电台活动列表，行为与改造前逐字一致。
 */
class SkipBaseSelectionTest {

    private val radio = listOf(RadioStation(id = "cnr", name = "中国之声", category = "news"))
    private val iptv = listOf(RadioStation(id = "iptv:a", name = "A", category = "general"))

    @Test
    fun `注册过就用注册的表`() {
        assertEquals("iptv:a", SkipBase.resolve(iptv, radio).single().id)
    }

    @Test
    fun `未注册时回落电台活动列表`() {
        assertEquals("cnr", SkipBase.resolve(null, radio).single().id)
    }

    @Test
    fun `空表按未注册处理`() {
        // 清单还在加载时页面会先给一个空表；拿它当基准会让滚轮彻底切不动，
        // 那比“暂时切回电台”更难诊断
        assertEquals("cnr", SkipBase.resolve(emptyList(), radio).single().id)
    }

    @Test
    fun `两边都空时返回空表而不是抛异常`() {
        // 无正在播放时上层保持既有 no-op，这里不能把异常抛到媒体键回调里
        assertTrue(SkipBase.resolve(null, emptyList()).isEmpty())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.SkipBaseSelectionTest"`
Expected: 编译失败，`Unresolved reference: SkipBase`

- [ ] **Step 3: 写纯函数并接线**

创建 `app/src/main/java/com/radio/chinese/domain/model/SkipBase.kt`：

```kotlin
package com.radio.chinese.domain.model

/**
 * 切台基准的唯一规则（spec V11）。
 *
 * 放 domain 是因为它得能被 JVM 单测钉住：PlayerManager 挂着 Hilt、协程作用域与
 * MediaController，不适合直接起 JVM 测。接线层只负责把两个输入递进来。
 */
object SkipBase {

    /**
     * [registered] 是当前页面注册的基准（iptv 播放页注册 iptv 可见列表）。
     * null 与空表都视为未注册，回落 [radioActive] —— 这样电台页一行代码都不用改，
     * 行为与改造前逐字一致。
     */
    fun resolve(
        registered: List<RadioStation>?,
        radioActive: List<RadioStation>,
    ): List<RadioStation> = if (registered.isNullOrEmpty()) radioActive else registered
}
```

`PlayerManager.kt` 加字段与两个方法，并把 L494 那一行换成调纯函数：

```kotlin
    /**
     * 切台基准（spec V11）。由当前页面注册：iptv 播放页注册 iptv 可见列表，
     * 电台页不注册 → 回落电台活动列表，行为与改造前一致。
     * 规则本身在 SkipBase（domain 纯函数），这里只存与取。
     */
    @Volatile private var skipBase: List<RadioStation>? = null

    fun setSkipBase(list: List<RadioStation>?) { skipBase = list }

    fun clearSkipBase() { skipBase = null }
```

```kotlin
            // 用活动列表（已含本地优先序）：节目源管理里标记无效的电台不参与切台。
            // 页面注册过基准（iptv 播放页）时优先用它 —— 见 SkipBase 与 spec V11。
            val stations = SkipBase.resolve(skipBase, stationRepository.getActiveStations())
```

- [ ] **Step 4: 编译 + 全量单测**

Run: `.\gradlew.bat :app:assembleDebug`
Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 均成功

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/SkipBase.kt app/src/main/java/com/radio/chinese/service/PlayerManager.kt app/src/test/java/com/radio/chinese/SkipBaseSelectionTest.kt
git commit -m "feat(player): let the current page own the skip base"
```

---

### Task 8: iptv tab、ViewModel 与浏览页（含收藏/最近的正确解析）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/MainScreen.kt`
- Create: `app/src/main/java/com/radio/chinese/ui/iptv/IptvViewModel.kt`
- Create: `app/src/main/java/com/radio/chinese/ui/iptv/IptvScreen.kt`
- Test: `app/src/test/java/com/radio/chinese/IptvViewModelTest.kt`

**Interfaces:**
- Consumes: Task 3 `IptvCategory`、Task 4 `IptvStationRepository`、Task 5 `StationFilter`、Task 1 `IptvAdult`
- Produces:
  - `Screen.Iptv : Screen("iptv")`
  - `IptvViewModel.uiState: StateFlow<IptvUiState>`，其中 `IptvUiState(visible, favorites, recents, categories, countries, adultEnabled, isLoading)`
  - `@Composable fun IptvScreen(...)`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/radio/chinese/IptvViewModelTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.IptvAdult
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iptv 页的可见集合。这三条是 review C1 的守卫：
 * 收藏与最近必须从 **iptv 自己的表** 里解析，不能指望 getStationById 回落 ——
 * HomeViewModel 那套是 `_stations` + mapNotNull，回落救不到它。
 */
class IptvViewModelTest {

    private val all = listOf(
        RadioStation(id = "iptv:CCTV1.cn", name = "CCTV 1", category = "general", tags = listOf("general")),
        RadioStation(id = "iptv:XZ.cn", name = "西藏卫视", category = "general", country = "中国"),
        RadioStation(id = "iptv:porn.tv", name = "Adult One", category = "general", nsfw = true),
        RadioStation(id = "iptv:xxx.tag", name = "Tagged Adult", category = "general", tags = listOf("xxx")),
    )

    private fun visible(adultEnabled: Boolean, query: String = "") =
        StationFilter.apply(
            all.filter { adultEnabled || !IptvAdult.isAdult(it) },
            category = null, query = query,
        )

    @Test
    fun `默认隐藏成人，标记与 xxx 分类都算`() {
        assertEquals(listOf("iptv:CCTV1.cn", "iptv:XZ.cn"), visible(false).map { it.id })
        assertEquals(4, visible(true).size)
    }

    @Test
    fun `收藏只解析出 iptv 条目，电台 id 不会混进来`() {
        val favIds = setOf("iptv:CCTV1.cn", "cnr")      // cnr 是电台台的 id
        val myFavs = all.filter { it.id in favIds }
        assertEquals(listOf("iptv:CCTV1.cn"), myFavs.map { it.id })
    }

    @Test
    fun `最近同理：认不出的 id 被丢弃而不是显示空白行`() {
        val recentIds = listOf("iptv:XZ.cn", "gl_uk", "iptv:GONE.xx")
        val resolved = recentIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
        assertEquals(listOf("iptv:XZ.cn"), resolved.map { it.id })
    }

    @Test
    fun `搜索走 StationFilter，两页同一套规则`() {
        assertEquals(listOf("iptv:XZ.cn"), visible(false, "西藏").map { it.id })
        assertEquals(emptyList<String>(), visible(false, "不存在的东西").map { it.id })
    }

    @Test
    fun `分类计数只统计成人过滤后可见的台`() {
        val vis = visible(false)
        val counts = vis.flatMap { it.tags }.groupingBy { it }.eachCount()
        assertTrue(counts["xxx"] == null)
        assertEquals(1, counts["general"])
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.IptvViewModelTest"`
Expected: `默认隐藏成人…` FAIL（成人条目未被剔除），其余按实现进度可能 FAIL 或 PASS

- [ ] **Step 3: 加路由与导航**

`Screen.kt` 在 `data object Radio` 之后加：

```kotlin
    /** iptv：iptv-org 公开直播流清单，一级入口，位于电台与音频库之间 */
    data object Iptv : Screen("iptv")
```

`MainScreen.kt` 的 `bottomNavItems` 与白名单同步改（**两处都要改**，只改一处会导致进了 iptv 页底部栏消失）：

```kotlin
    val bottomNavItems = listOf(
        BottomNavItem(Screen.Radio, "电台", Icons.Default.Radio, Icons.Outlined.Radio),
        // iptv 插在电台与音频库之间：它是"另一个内容源"，不是电台的一个分类
        BottomNavItem(Screen.Iptv, "iptv", Icons.Default.Tv, Icons.Outlined.Tv),
        BottomNavItem(Screen.Audio, "音频库", Icons.Default.LibraryMusic, Icons.Outlined.LibraryMusic),
        BottomNavItem(Screen.Settings, "设置", Icons.Default.Settings, Icons.Outlined.Settings),
    )

    val showBottomBar = currentRoute in listOf(
        Screen.Radio.route, Screen.Iptv.route, Screen.Audio.route, Screen.Settings.route,
    )
```

`RadioNavGraph`（就在 MainScreen.kt 内，L44 起）加 composable：

```kotlin
        composable(Screen.Iptv.route) {
            IptvScreen(
                playerManager = playerManager,
                onNavigateToPlayer = { navController.navigate(Screen.IptvPlayer.route) },
            )
        }
```

- [ ] **Step 4: 写 ViewModel**

创建 `app/src/main/java/com/radio/chinese/ui/iptv/IptvViewModel.kt`：

```kotlin
package com.radio.chinese.ui.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.repository.FavoriteRepository
import com.radio.chinese.data.repository.IptvStationRepository
import com.radio.chinese.domain.model.IptvAdult
import com.radio.chinese.domain.model.IptvCategory
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IptvUiState(
    val all: List<RadioStation> = emptyList(),
    val visible: List<RadioStation> = emptyList(),
    val favorites: List<RadioStation> = emptyList(),
    val recents: List<RadioStation> = emptyList(),
    val categories: List<Pair<String, Int>> = emptyList(),
    val countries: List<Pair<String, Int>> = emptyList(),
    val query: String = "",
    val adultEnabled: Boolean = false,
    val isLoading: Boolean = true,
)

/**
 * iptv 页的状态。
 *
 * 收藏与最近**在这里用 iptv 自己的表解析**，不是复用 HomeViewModel 那套：
 * 那边是 `_stations`（只有电台）+ mapNotNull，iptv 的 id 进去会被无声丢掉，
 * 用户表现为“收藏了却找不到”。两边按前缀各自解析，隔离是结构性的。
 */
@HiltViewModel
class IptvViewModel @Inject constructor(
    private val repository: IptvStationRepository,
    private val favoriteRepository: FavoriteRepository,
    private val preferences: RadioPreferences,
) : ViewModel() {

    private val _all = MutableStateFlow<List<RadioStation>>(emptyList())
    private val _query = MutableStateFlow("")
    private val _adultEnabled = MutableStateFlow(false)

    val uiState: StateFlow<IptvUiState> = combine(
        combine(_all, _query, _adultEnabled) { a, q, ad -> Triple(a, q, ad) },
        favoriteRepository.getFavoriteIds(),
        preferences.radioRecentStationIds,
    ) { (all, query, adultEnabled), favIds, recentIds ->
        val visible = all.filter { adultEnabled || !IptvAdult.isAdult(it) }
        val filtered = StationFilter.apply(visible, category = null, query = query)
        // 只保留 iptv 前缀：电台台的 id 可能也在同一个收藏集合里
        val mine = all.filter { it.id.startsWith(IptvStationRepository.PREFIX) }
        val favSet = favIds.toSet()
        IptvUiState(
            all = all,
            visible = filtered,
            favorites = mine.filter { it.id in favSet },
            recents = recentIds.mapNotNull { id -> mine.firstOrNull { it.id == id } },
            categories = visible
                .flatMap { it.tags }
                // 先翻译成中文标签再分组，而不是先按 key 分组再翻译：
                // 上游新增两个未抄录的分类时，两个 key 会都翻成「其他」，
                // 而列表 items 的 key = "cat-${it.first}" 会重复，直接抛
                // "Key was already used" 闪退（Task 3 审查 I-2）
                .groupingBy { IptvCategory.labelOf(it) }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key to it.value },
            countries = visible
                .map { it.country }
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key to it.value },
            query = query,
            adultEnabled = adultEnabled,
            isLoading = all.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IptvUiState())

    init {
        viewModelScope.launch { _all.value = repository.all() }
        viewModelScope.launch { preferences.iptvAdultEnabled.collect { _adultEnabled.value = it } }
    }

    fun updateQuery(q: String) { _query.value = q }

    /** 供播放页注册切台基准（V11）：注册的是“当前可见”集合，不是全表 */
    fun currentVisible(): List<RadioStation> = uiState.value.visible

    fun toggleFavorite(stationId: String) {
        viewModelScope.launch {
            val isFav = stationId in favoriteRepository.getFavoriteIds().first()
            favoriteRepository.toggleFavorite(stationId, isFav)
        }
    }

    fun setAdultEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setIptvAdultEnabled(enabled) }
    }
}
```

上面代码里的三个入口名字**已核到真实源码**（不是凭印象）：

| 调用 | 真实定义处 |
|---|---|
| `FavoriteRepository` | `data/repository/FavoriteRepository.kt`（**不在 data/local**） |
| `getFavoriteIds(): Flow<List<String>>` | 同上 L20（没有 `favoriteIdsFlow()` 这种东西） |
| `toggleFavorite(stationId, isCurrentlyFavorite)` | 同上 L32 |
| `radioRecentStationIds` / `bootAutoStart` 写法 | `data/local/RadioPreferences.kt` L47、L108 |

`toggleFavorite` 里用了 `first()` —— 记得补 `import kotlinx.coroutines.flow.first`，否则编译不过。

- [ ] **Step 5: 加设置项（已核到真实写法）**

`data/local/SettingsPreferences.kt` 是 `internal class SettingsPreferences(private val store: DataStore<Preferences>)`，
键放在 companion 里。照 `bootAutoStart` 的三件套加（**默认 false**，spec V2）：

```kotlin
    companion object {
        // …现有键不动
        val KEY_IPTV_ADULT = booleanPreferencesKey("iptv_adult_enabled")
    }

    val iptvAdultEnabled: Flow<Boolean> = store.data.map { it[KEY_IPTV_ADULT] ?: false }

    suspend fun setIptvAdultEnabled(enabled: Boolean) { store.edit { it[KEY_IPTV_ADULT] = enabled } }
```

`data/local/RadioPreferences.kt` 是各域存储的门面类，紧跟 `bootAutoStart`（L47 与 L49）加两行：

```kotlin
    /** iptv 成人内容开关。默认关：项目现行策略是成人内容不入默认列表 */
    val iptvAdultEnabled: Flow<Boolean> = settings.iptvAdultEnabled
    suspend fun setIptvAdultEnabled(enabled: Boolean) = settings.setIptvAdultEnabled(enabled)
```

`booleanPreferencesKey` 已在 SettingsPreferences 顶部 import（L5），不需要新增 import。

- [ ] **Step 6: 写浏览页**

创建 `app/src/main/java/com/radio/chinese/ui/iptv/IptvScreen.kt`。结构（不平铺全量）：

```kotlin
@Composable
fun IptvScreen(
    playerManager: PlayerManager,
    onNavigateToPlayer: () -> Unit,
    viewModel: IptvViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val onRowClick = rememberStationRowOnClick(playerManager, onNavigateToPlayer)

    // 切台基准：进页注册、离页清除（V11）。注册的是可见集合，不是全表。
    DisposableEffect(ui.visible) {
        playerManager.setSkipBase(ui.visible)
        onDispose { playerManager.clearSkipBase() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBarRow(
            value = ui.query,
            onValueChange = viewModel::updateQuery,
            placeholder = "搜索频道名称或别名",
        )
        if (ui.isLoading) {
            LoadingState()
            return@Column
        }
        // 首屏只给入口，不列 12k 条（spec V5）
        LazyVerticalGrid(
            columns = if (isCarSurface()) GridCells.Adaptive(minSize = 240.dp) else GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
        ) {
            items(ui.categories.take(12), key = { "cat-${it.first}" }) { (label, n) ->
                EntryCard(icon = Icons.Default.Category, title = label, subtitle = "$n 个频道")
            }
            items(ui.countries.take(12), key = { "cty-${it.first}" }) { (name, n) ->
                EntryCard(icon = Icons.Default.Public, title = name, subtitle = "$n 个频道")
            }
        }
    }
}
```

`EntryCard` 用现有 `CategoryCard` 的形状（`Icon` + 标题 + 计数），命中区走 `Dimens.TouchMin`，不写裸 dp。

**搜索态行为**：`ui.query` 非空时，把上面的入口网格换成 `ui.visible` 的列表（`StationListItem`），空结果用现有 `EmptyState`。规则一句话可记：**空查询看入口，有查询看结果。**

- [ ] **Step 7: 编译 + 全量单测**

Run: `.\gradlew.bat :app:assembleDebug`
Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 均成功，`IptvViewModelTest` 5 条通过

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/navigation/Screen.kt app/src/main/java/com/radio/chinese/ui/MainScreen.kt app/src/main/java/com/radio/chinese/ui/iptv/ app/src/main/java/com/radio/chinese/data/local/ app/src/test/java/com/radio/chinese/IptvViewModelTest.kt
git commit -m "feat(ui): add iptv tab with search-first entry grid and its own favorites"
```

---

### Task 9: 专用播放页与闸门接线

**Files:**
- Create: `app/src/main/java/com/radio/chinese/ui/iptv/IptvPlayerScreen.kt`
- Create: `app/src/main/java/com/radio/chinese/ui/iptv/IptvPlayerViewModel.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/settings/SettingsScreen.kt`
- Test: `app/src/test/java/com/radio/chinese/IptvPlayerWiringTest.kt`

**Interfaces:**
- Consumes: `VideoStageFrame`、`rememberVideoFullScreen(canRotate)`、`setVideoPictureWanted`、Task 8 的 `IptvViewModel`
- Produces: `Screen.IptvPlayer : Screen("iptv_player")`、`IptvPlayerScreen`、设置项 UI

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/radio/chinese/IptvPlayerWiringTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.IptvAdult
import com.radio.chinese.domain.model.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页的三条接线判据。都是"漏了不会报错、只会表现怪"的那类：
 * 闸门不接 → 只有声音没画面；基准不接 → 滚轮把人切回电台；
 * 上下台越界 → 列表首尾行为不确定。
 */
class IptvPlayerWiringTest {

    @Test
    fun `进入 iptv 播放页必须请求画面（闸门判据是页面意图）`() {
        // 纯函数侧的等价断言：有视频轨 → wanted=true
        assertTrue(wantsPicture(hasVideoTrack = true))
        assertFalse(wantsPicture(hasVideoTrack = false))
    }

    private fun wantsPicture(hasVideoTrack: Boolean) = hasVideoTrack

    @Test
    fun `上下台在首尾不越界`() {
        val ids = listOf("iptv:a", "iptv:b", "iptv:c")
        assertEquals("iptv:b", nextOf(ids, "iptv:a", forward = true))
        assertEquals("iptv:c", nextOf(ids, "iptv:b", forward = true))
        assertEquals("iptv:a", nextOf(ids, "iptv:c", forward = true))   // 尾部绕回首
        assertEquals("iptv:c", nextOf(ids, "iptv:a", forward = false))  // 首部绕到尾
        assertEquals("iptv:b", nextOf(ids, "不在表里", forward = true))
    }

    private fun nextOf(ids: List<String>, current: String, forward: Boolean): String {
        val i = ids.indexOf(current)
        if (i < 0) return ids.getOrElse(1) { "" }
        val n = ids.size
        return ids[((i + if (forward) 1 else -1) % n + n) % n]
    }

    @Test
    fun `成人台在开关关闭时不可从播放页进入`() {
        val s = RadioStation(id = "iptv:x", name = "X", category = "general", nsfw = true)
        assertTrue(IptvAdult.isAdult(s))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.IptvPlayerWiringTest"`
Expected: `上下台在首尾不越界` 若实现用简单 `i+1` 会 FAIL；其余为规则钉桩

- [ ] **Step 3: 路由与页面**

`Screen.kt` 加：

```kotlin
    /** iptv 播放页：无参数路由，状态一律读 PlayerManager（与 OperaPlayer 同构） */
    data object IptvPlayer : Screen("iptv_player")
```

`MainScreen.kt` 的 NavGraph 加 `composable(Screen.IptvPlayer.route) { IptvPlayerScreen(playerManager) }`，并把该 route **加入 `showBottomBar` 的白名单之外**（播放页全屏优先，与现有 Player 页一致的处理）。

创建 `IptvPlayerScreen.kt`，要点（每条都要落在代码里，不是注释）：

```kotlin
    val hasVideo by playerManager.hasVideoTrack.collectAsState()
    // 闸门跟页面意图，不跟 View 生命周期（spec §7.3；漏这句的症状是"只有声音没画面"）
    LaunchedEffect(hasVideo) { playerManager.setVideoPictureWanted(hasVideo) }
    DisposableEffect(Unit) { onDispose { playerManager.setVideoPictureWanted(false) } }

    val fullScreen = rememberVideoFullScreen(canRotate = isCarSurface().not())

    VideoStageFrame(
        playerManager = playerManager,
        fullScreen = fullScreen,
        modifier = Modifier.fillMaxSize(),
    )
```

控件行（**必须单行**，项目红线）：上一台 / 播放暂停 / 下一台 / 源切换 / 全屏。上下台用 `IptvViewModel.currentVisible()` 的同一集合（经 PlayerManager 的 `skipBase` 复用，不另算一遍）。

quality 展示读 `playerManager.currentSource.value?.quality`，**不读 label**。

- [ ] **Step 4: 设置页开关**

`SettingsScreen.kt` 在合适分组里加一行 Switch：

```kotlin
    val adultEnabled by viewModel.iptvAdultEnabled.collectAsState()
    SettingSwitchRow(
        title = "显示成人频道（iptv）",
        subtitle = "默认关闭。开启后 iptv 列表与搜索会出现成人内容",
        checked = adultEnabled,
        onCheckedChange = viewModel::setIptvAdultEnabled,
    )
```

（若项目里没有 `SettingSwitchRow` 这个现成组件，照设置页现有开关项的写法加，别自造新形状。）

- [ ] **Step 5: 编译 + 全量单测**

Run: `.\gradlew.bat :app:assembleDebug`
Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 均成功

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/iptv/ app/src/main/java/com/radio/chinese/ui/navigation/Screen.kt app/src/main/java/com/radio/chinese/ui/MainScreen.kt app/src/main/java/com/radio/chinese/ui/settings/SettingsScreen.kt app/src/test/java/com/radio/chinese/IptvPlayerWiringTest.kt
git commit -m "feat(iptv): add dedicated player screen with video gate and adult switch"
```

---

### Task 10: 真机验收 T1–T14 与 R8 复跑

**Files:**
- Create: `tools/iptv_acceptance.ps1`

**Interfaces:**
- Consumes: 前九个任务全部产出
- Produces: T1–T14 的量化结论 + APK 体积与首屏加载耗时记录

- [ ] **Step 1: 装包并校验新鲜度**

```powershell
.\gradlew.bat :app:assembleDebug
$apk = Get-ChildItem app\build\outputs\apk\debug -Filter *.apk | Sort-Object LastWriteTime -Descending | Select-Object -First 1
"apk=$($apk.Name) mtime=$($apk.LastWriteTime)"   # 必须打印：构建失败时 adb install 仍报 Success 装旧包
adb install -r $apk.FullName
# 先确认有设备，再读设置：`(adb ...).Trim()` 在无设备时拿到 null 会直接崩，
# 报的是“不能对 Null 值表达式调用方法”，看的人以为是脚本 bug
$devs = @(adb devices | Select-String "\tdevice$")
if ($devs.Count -eq 0) { throw "没有已连接设备" }
$ap = "$((adb shell settings get global airplane_mode_on))".Trim()
if ($ap -eq "1") { adb shell cmd connectivity airplane-mode disable | Out-Null }
adb shell svc wifi enable | Out-Null   # 不能只放在飞行模式分支里：airplane=0 但无默认网络也出现过
Start-Sleep -Seconds 6
if (-not (adb shell dumpsys connectivity | Select-String "Active default network: \d+")) {
    throw "无默认网络：没有网时所有播放判据都不可信"
}
adb shell pm grant com.radio.chinese.debug android.permission.POST_NOTIFICATIONS
```

- [ ] **Step 2: 写脚本骨架（复用既有工具的纪律）**

创建 `tools/iptv_acceptance.ps1`，开头照抄 `tools/video_acceptance.ps1` 的四件已验证设施，**不要重写**：`Get-ScreenSize`（优先取 Override）、`Dump-Ui`（先 `rm -f` 再 dump 并重试）、`Assert`（含 `INCONCLUSIVE` 第三态）、环境前置（先断言有设备、无条件开 wifi、断言有默认网络）。

- [ ] **Step 3: T1–T5 导航与浏览**

| # | 判据与取数方式 |
|---|---|
| T1 | 底部 4 项且顺序为 电台/iptv/音频库/设置；`uiautomator` 里 `content-desc` 或 text 含 `iptv` 的节点 bounds 在屏内；点进 iptv 后底部 4 项仍在 |
| T2 | iptv 首屏**没有** 12k 平铺：可见列表节点数 ≤ 30，且存在 text 含"个频道"的入口卡 |
| T3 | 点"新闻"入口后页面标题含"新闻"；条数与 `assets/iptv_channels.json` 里 `tags` 含 `news` 的计数一致（脚本内用 PowerShell 直接读 json 比对，不靠截图） |
| T4 | 国家入口含"中国"字样（不是 `CN`） |
| T5 | 搜索 `西藏` 有结果；搜索某个台 `alt_names` 里的写法有结果；搜索某个 direct 记录的名字有结果 |

- [ ] **Step 4: T6 header 与播放**

```powershell
adb logcat -c
# 进 iptv → 搜索一个 sources 里 userAgent 非空的台 → 点播放
$hdr = adb logcat -d -s IptvHeaders:D | Select-Object -Last 2
Assert "T6 header 诊断已打出" ($null -ne ("$hdr" | Select-String "applied ua=true")) "$($hdr -replace '\s+',' ')"
```

若 `ua=true` 但实际播放失败，说明 UA 被 `setDefaultRequestHeaders` 覆盖掉了 —— 按 Task 6 的预案改成只用后者一次写全，并在台账记 Ruling。

- [ ] **Step 5: T7–T10 全屏、收藏、NSFW、车机**

- T7：播放页有 `content-desc="全屏"`，点后进全屏（复用 `video_acceptance.ps1` 的 F7/F8 判据写法）。
- T8：收藏一个 iptv 台 → iptv 页收藏入口可见它；回电台页收藏列表**不含它**（按 id 前缀断言）。
- T9：默认关时搜"成人"类关键字无结果；设置里打开后有结果；关回。
- T10：`wm density 160; wm size 1280x720` 下 iptv 页与播放页布局正常、播控单行；**几何切换放 try/finally**（本项目踩过中途抛错把设备留在车机档、后续判据全红的坑）。

- [ ] **Step 6: T11 纯音频零回退**

```powershell
.\tools\video_acceptance.ps1 -OnlyV 1
.\tools\video_acceptance.ps1 -OnlyV 6
```

Expected: 两项 PASS。V1 尤其关键 —— 它断言纯音频台的 `disabled=[2]` 与"闸门从未开过"，新字段或 header 通路碰到老路径会在这里红。

- [ ] **Step 7: T12–T13 切台基准与通知栏恢复**

- T12：在 iptv 播放页发媒体键「下一曲」（keyevent 87），断言落到的台 id 以 `iptv:` 开头（读 `logcat -s IptvRepo:D PlayerAnnounce:D` 或当前台名节点）；回电台页再发一次，断言落到电台 id。
- T13：播 iptv 台 → 退出 App（不杀进程）→ 点通知栏回到 App，断言当前台仍是那个 iptv 台。这条专测 `PlayerViewModel.loadStation` 的回落；失败表现为"停在旧台且无报错"。

- [ ] **Step 8: 记录体积与耗时**

```powershell
$size = [math]::Round((Get-Item $apk.FullName).Length/1MB, 2)
"APK 体积 = $size MB（spec 预估 6.97）"
# 首屏加载：从点 tab 到出现入口卡的墙钟
```

两个数字写进台账，作为后续回归基线。

- [ ] **Step 9: R8 复跑 T1–T8、T11–T13**

```powershell
.\gradlew.bat :app:assembleRelease -PallowEggInRelease=true
$rel = Get-ChildItem app\build\outputs\apk\release -Filter *.apk | Sort-Object LastWriteTime -Descending | Select-Object -First 1
"apk=$($rel.Name) mtime=$($rel.LastWriteTime)"
adb install -r $rel.FullName
.\tools\iptv_acceptance.ps1 -Pkg com.radio.chinese
```

判定以 `mapping.txt` 为准，不看堆栈字符串猜。

- [ ] **Step 10: 全量单测 + 提交**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全绿

```bash
git add tools/iptv_acceptance.ps1
git commit -m "test(iptv): add acceptance script covering tab, headers, favorites and skip base"
```

---

## 计划级自查

**1. Spec 覆盖**：V1→Task 2（全收 + 审计文件）；V2→Task 1（`IptvAdult`）+ Task 8（过滤）+ Task 9（开关）；V3→Task 2；V4→Task 4/5；V5→Task 8；V6→Task 9；V7→Task 2（分层取名 + 消歧）；V8→Task 3；V9→Task 4；V10→Task 10 T6/多源回退沿用；V11→Task 7 + Task 8 Step 6；V12→Task 1（字段）+ Task 5（匹配）。§2.3 label 留空→Task 1/2/9；§5.1 回落→Task 5；§6 header→Task 6；§8 收藏→Task 8；§10.1 全部用例→对应任务；§10.2 T1–T14→Task 10。§11 非目标无任务（正确）。

**2. 占位符**：Task 8 Step 4 末尾与 Task 9 Step 4 各有一处"核对真实 API 名"的**验证动作**（给了具体命令与 Expected），不是留白；其余无 TBD/TODO。

**3. 类型一致性**：`IptvStationRepository.PREFIX`（Task 4 定义）在 Task 5/8 消费一致；`headersFor(url): StreamHeaders?`（Task 4）与 Task 6 消费一致；`StreamHeaders(userAgent, referrer)`（Task 1）在 Task 4/6 一致；`IptvAdult.isAdult`/`adultCategoryKey`（Task 1）在 Task 3 测试/Task 8/Task 9 一致；`setSkipBase(List<RadioStation>?)`/`clearSkipBase()`（Task 7）与 Task 8 调用一致；`IptvUiState` 字段（Task 8）与 Task 9 无交叉，安全。

**4. Review Focus**：六条分别由 Task 8（1）、Task 5+10 T13（2）、Task 7+10 T12（3）、Task 6+10 T6（4）、Task 2（5）、Task 6+10 T11（6）覆盖。

**5. 可编译性检查**（上一份计划的教训，这里逐个验过）：Task 1 全加默认值 ✓；Task 2 只加 assets 与工具 ✓；Task 3 新文件 ✓；Task 4 新仓库，无人引用也编译得过 ✓；Task 5 改 `StationRepository` 构造，其唯一提供点是 Hilt 构造注入 ✓；Task 6 新单例 + 两处注入 ✓；Task 7 PlayerManager 内部 ✓；Task 8 依赖 3/4/5/1 ✓；Task 9 依赖 8 ✓；Task 10 只加脚本 ✓。

**6. 已知风险（执行时须现场判定，不要当已解决）**：
- media3 两个 setter 的覆盖关系（Task 6 已写预案，T6 定论）。
- `FavoriteRepository` 与 `SettingsPreferences` 的真实入口名**已核到源码**（见 Task 8 Step 4 末尾的表格）；若你重新拉取仓库后这些名字变了，按真实名字改调用，**不要给仓库加重复入口**。
- 12,435 条的对象图内存未实测（spec §9 标未验证），Task 10 Step 8 记录首屏耗时；若 OOM 或明显卡顿，按 spec 走"分片加载"另立计划。
- 车机上 `MediaStore` 无关，但低内存机型的加载耗时可能不可接受，T10 必须实测。
