# WebDAV 全源节目搜索 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让手机档用户在音频库输入关键字即可跨全部目录搜到 WebDAV 曲目，索引由后台在 Wi‑Fi 下渐进建立。

**Architecture:** 新增 `SourceIndexer` 以 BFS + Depth:1 PROPFIND 把每个目录的行灌进现有 `audio_cache` 表（零表结构变更），搜索变成对该表的本地 LIKE 查询；网络能力判定收进 `NetworkGate` seam；UI 仅在 Phone 档把搜索框语义换成"全源搜索"，Car 档渲染与请求量零变化。

**Tech Stack:** Kotlin / Jetpack Compose / Room 2.6.1 / OkHttp（经 `WebDavClient`）/ kotlinx-coroutines / Hilt。

**Spec:** `doc/specs/2026-10-18-webdav-full-source-search-design.md`（实测依据、§4 单例凭证坑、§5 形态边界都以它为准）

## Global Constraints

- `minSdk = 26`，`compileSdk = 35`，`targetSdk = 35`；Java/Kotlin target 17。
- 索引只在 `NET_CAPABILITY_NOT_METERED` 且 transport 为 `WIFI` 或 `ETHERNET` 时发起；车机档**一次 PROPFIND 都不发**。
- 并发目录数 **6**；单请求沿用 `WebDavClient` 的 connect 15s / read 30s；单目录失败重试 **1** 次后计入 skipped，不中断整体。
- 只有 `SourceType.WEBDAV` 参与索引；LOCAL / M3U / HTTP 一律跳过。
- 索引器不得读 `LocalUiSurface`（CompositionLocal 属 UI 层）；形态判定由 UI 传入布尔参数。
- 全部判定逻辑写成纯函数并配 JVM 单测（本项目只有 `junit 4.13.2` + `kotlinx-coroutines-test 1.8.1` + `mockk 1.13.13`，**无 Robolectric、无 androidTest**，Android 框架类在 JVM 下是 stub）。
- 每个任务结束时单独 `git commit`；含中文的多行提交信息写入文件后 `git commit -F <file>`（PowerShell 无 heredoc）。
- `versionCode = git 提交数`，每次提交都会让下次构建版本号 +1，属预期。
- 不新增 Gradle 依赖；`ACCESS_NETWORK_STATE` 权限已在 Manifest。

## Review Focus

单测覆盖不到、但最容易在真机上咬人的输入类别，逐条在对应任务里补断言或列入真机裁决：

1. **SQL LIKE 的通配符注入** —— 用户输入含 `%` 或 `_` 时不能当通配符。转义在纯函数里可钉死，但 `ESCAPE '\'` 子句本身只能在真实 SQLite 上验证 → Task 12 设备裁决。
2. **车机 density 误判成 Phone**（spec §5 已知限制）—— 自动档可能把 1280x720@240dpi 判为手机，于是车机上冒出全源搜索入口。JVM 无从覆盖 → Task 10 强制由 UI 层传入形态、Task 12 用车机档 bounds 回归 + 抓包确认零请求。
3. **同名路径跨源串数据** —— `WebDavClient.listFilesCache` 的 key 只有 path，四个快捷码源共用同一 base URL。索引器若复用单例，A 源目录内容会被当成 B 源 → Task 7 断言每源独立实例。
4. **索引中途进程被杀** —— 恢复后必须按 `cachedAt` 跳过已新鲜目录（等效续爬），不得从头重爬 → Task 3 纯函数钉死 + Task 12 真机复核流量。
5. **手动刷新清空索引** —— 现有 `AudioLibraryViewModel.browseSource()` 与 `refreshBrowse()` 都调用 `repository.refreshSource`，它做 `deleteBySource` 整源清空；一旦与索引共存，用户每进一次目录就抹掉两万行 → Task 8 owns。

---

## 文件结构

**新建**
- `app/src/main/java/com/radio/chinese/domain/model/IndexQuery.kt` —— 纯函数：LIKE 转义、去重归并、面包屑、待爬集合与新鲜度、进度模型
- `app/src/main/java/com/radio/chinese/service/NetworkGate.kt` —— 接口 + `ConnectivityManager` 实现 + 允许判定的纯函数
- `app/src/main/java/com/radio/chinese/service/SourceIndexer.kt` —— BFS 遍历、并发、取消、skipped、进度、`updateSyncTime` 条件
- `app/src/main/java/com/radio/chinese/domain/browser/BrowserFactory.kt` —— 每源独立浏览器（WebDAV 用独立 `WebDavClient` 实例）
- `app/src/main/java/com/radio/chinese/ui/library/SearchResultsList.kt` —— Phone 档全源搜索结果列表 + 索引进度提示条
- `app/src/test/java/com/radio/chinese/IndexQuerySearchTest.kt`（Task 1、2）
- `app/src/test/java/com/radio/chinese/IndexQueryFreshnessTest.kt`（Task 3）
- `app/src/test/java/com/radio/chinese/NetworkGateTest.kt`（Task 4）
- `app/src/test/java/com/radio/chinese/SourceIndexerTest.kt`（Task 5、6）
- `app/src/test/java/com/radio/chinese/BrowserFactoryTest.kt`（Task 7）
- `app/src/test/java/com/radio/chinese/AudioLibrarySearchViewModelTest.kt`（Task 9）

**修改**
- `data/dao/AudioCacheDao.kt` —— 新增 `searchTracks`、`dirOldestCachedAt`、`distinctParentPaths`、`countTracks`
- `data/repository/AudioLibraryRepository.kt` —— `searchTracks` 映射、`refreshSource` 改目录级重爬、`countTracks`
- `ui/library/AudioLibraryViewModel.kt` —— 搜索语义分叉、索引起动与状态收集、结果点击播放
- `ui/audio/AudioMainScreen.kt` —— 结果列表接入与进度条；Car 档分支保持原渲染
- `di/AppModule.kt` —— `NetworkGate` / `SourceIndexer` / `BrowserFactory` 绑定与作用域
- `data/local/RadioPreferences.kt` —— `webdav_index_enabled`

**删除**
- `service/OperaRepository.kt` —— 孤儿搜索实现（Task 11）

---

## Task 1: 搜索 DAO 与 LIKE 转义

**Files:**
- Create: `app/src/main/java/com/radio/chinese/domain/model/IndexQuery.kt`
- Modify: `app/src/main/java/com/radio/chinese/data/dao/AudioCacheDao.kt`（现有 `searchByName` 保留不动，新增查询）
- Modify: `app/src/main/java/com/radio/chinese/data/repository/AudioLibraryRepository.kt`
- Test: `app/src/test/java/com/radio/chinese/IndexQuerySearchTest.kt`

**Interfaces:**
- Consumes: `AudioCacheEntity`（`sourceId/path/parentPath/name/isFolder/size/cachedAt`）
- Produces:
  - `IndexQuery.likePattern(raw: String): String`
  - `AudioCacheDao.searchTracks(sourceId: Long?, pattern: String, limit: Int): List<AudioCacheEntity>`
  - `AudioLibraryRepository.searchTracks(sourceId: Long?, query: String, limit: Int = 200): List<TrackHit>`
  - `data class TrackHit(val sourceId: Long, val name: String, val path: String, val parentPath: String, val size: Long)`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.IndexQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/** LIKE 转义：通配符必须失效，且最终形态是 %…% 包裹的子串匹配。 */
class IndexQuerySearchTest {
    @Test fun `普通中文按子串包裹`() {
        assertEquals("%花木兰%", IndexQuery.likePattern("花木兰"))
    }

    @Test fun `百分号被转义`() {
        assertEquals("%100\\%现场%", IndexQuery.likePattern("100%现场"))
    }

    @Test fun `下划线被转义`() {
        assertEquals("%a\\_b%", IndexQuery.likePattern("a_b"))
    }

    @Test fun `反斜杠自身先转义避免二次转义`() {
        assertEquals("%a\\\\b%", IndexQuery.likePattern("a\\b"))
    }

    @Test fun `空白输入产出空串交由调用方短路`() {
        assertEquals("", IndexQuery.likePattern("   "))
    }

    @Test fun `首尾空格被裁掉`() {
        assertEquals("%贵妃醉酒%", IndexQuery.likePattern("  贵妃醉酒 "))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.IndexQuerySearchTest"`
Expected: 编译失败 / `Unresolved reference: IndexQuery`

- [ ] **Step 3: 写最小实现**

```kotlin
package com.radio.chinese.domain.model

/**
 * 索引与搜索的纯判定集合。SQL 侧只做取行，语义（转义、归并、面包屑、新鲜度）
 * 全部留在这里，便于 JVM 单测钉死——本项目没有 Robolectric，DAO 行为无法在 JVM 验证。
 */
object IndexQuery {

    /**
     * 转成 Room LIKE 参数。转义顺序固定：先反斜杠，再 % 与 _，
     * 否则 `\\` 会把刚补出来的 `\%` 再吃掉一层。
     * 配套 SQL 必须写 `ESCAPE '\'`。
     */
    fun likePattern(raw: String): String {
        val q = raw.trim()
        if (q.isEmpty()) return ""
        val escaped = q.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }
}
```

`AudioCacheDao.kt` 末尾（右花括号前）新增：

```kotlin
    /**
     * 全源/单源曲目搜索。pattern 必须是 [IndexQuery.likePattern] 的产物，
     * 不接受裸用户输入（通配符注入）。目录行不参与搜索。
     */
    @Query(
        """
        SELECT * FROM audio_cache
        WHERE isFolder = 0
          AND (:sourceId IS NULL OR sourceId = :sourceId)
          AND name LIKE :pattern ESCAPE '\'
        ORDER BY name ASC
        LIMIT :limit
        """
    )
    suspend fun searchTracks(sourceId: Long?, pattern: String, limit: Int): List<AudioCacheEntity>
```

`AudioLibraryRepository.kt` 在"浏览"小节后新增（`TrackHit` 定义在同文件顶部）：

```kotlin
data class TrackHit(
    val sourceId: Long,
    val name: String,
    val path: String,
    val parentPath: String,
    val size: Long,
)
```

```kotlin
    // ========== 搜索 ==========

    /**
     * 全源曲目搜索。空/空白查询返回空列表——不把它送进 LIKE，
     * 否则 `%+%` 退化成"匹配全部"，用户清空半个字就刷屏。
     */
    suspend fun searchTracks(sourceId: Long?, query: String, limit: Int = 200): List<TrackHit> {
        val pattern = com.radio.chinese.domain.model.IndexQuery.likePattern(query)
        if (pattern.isEmpty()) return emptyList()
        return cacheDao.searchTracks(sourceId, pattern, limit).map {
            TrackHit(
                sourceId = it.sourceId, name = it.name, path = it.path,
                parentPath = it.parentPath, size = it.size
            )
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.IndexQuerySearchTest"`
Expected: PASS（6 个用例）

- [ ] **Step 5: 编译校验**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（Room 注解生成通过；`ESCAPE '\'` 若在 KSP 阶段报 SQL 语法错，改为 `ESCAPE "\\"` 并保持 Kotlin 常量与 Task 12 的 SQL 检查一致）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/IndexQuery.kt app/src/main/java/com/radio/chinese/data/dao/AudioCacheDao.kt app/src/main/java/com/radio/chinese/data/repository/AudioLibraryRepository.kt app/src/test/java/com/radio/chinese/IndexQuerySearchTest.kt
git commit -m "feat(search): add cross-source track query with LIKE escaping"
```

---

## Task 2: 跨源去重归并与面包屑

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/domain/model/IndexQuery.kt`
- Test: `app/src/test/java/com/radio/chinese/IndexQuerySearchTest.kt`（追加用例）

**Interfaces:**
- Consumes: `TrackHit`（Task 1）
- Produces:
  - `IndexQuery.mergeHits(hits: List<TrackHit>): List<MergedTrack>`
  - `data class MergedTrack(val name: String, val size: Long, val sources: List<TrackHit>)`（`sources.first()` 即主选来源）
  - `IndexQuery.breadcrumb(parentPath: String, sourceName: String, maxSegments: Int = 3): String`

- [ ] **Step 1: 追加失败测试**

```kotlin
    @Test fun `同名同大小跨源归并为一条`() {
        val hits = listOf(
            TrackHit(1, "花木兰 选段", "/豫剧/花木兰.mp3", "/豫剧", 8_000_000),
            TrackHit(2, "花木兰 选段", "/常香玉/花木兰.mp3", "/常香玉", 8_000_000),
        )
        val merged = IndexQuery.mergeHits(hits)
        assertEquals(1, merged.size)
        assertEquals(listOf(1L, 2L), merged[0].sources.map { it.sourceId })
    }

    @Test fun `同名不同大小不合并`() {
        val hits = listOf(
            TrackHit(1, "花木兰", "/a.mp3", "/", 1_000),
            TrackHit(2, "花木兰", "/b.mp3", "/", 9_000),
        )
        assertEquals(2, IndexQuery.mergeHits(hits).size)
    }

    @Test fun `名称差异仅首尾空格仍算同一条`() {
        val hits = listOf(
            TrackHit(1, " 花木兰 ", "/a.mp3", "/", 1_000),
            TrackHit(2, "花木兰", "/b.mp3", "/", 1_000),
        )
        assertEquals(1, IndexQuery.mergeHits(hits).size)
    }

    @Test fun `归并结果按名称稳定排序`() {
        val hits = listOf(
            TrackHit(1, "穆桂英", "/m.mp3", "/", 1),
            TrackHit(1, "花木兰", "/h.mp3", "/", 1),
        )
        assertEquals(listOf("花木兰", "穆桂英"), IndexQuery.mergeHits(hits).map { it.name })
    }

    @Test fun `面包屑去掉源根空段`() {
        assertEquals("豫剧 / 常香玉", IndexQuery.breadcrumb("/豫剧/常香玉", "123云盘·豫剧"))
    }

    @Test fun `面包屑超长时保留末三段并加省略号`() {
        assertEquals("… / 乙 / 丙 / 丁", IndexQuery.breadcrumb("/甲/乙/丙/丁", "源", maxSegments = 3))
    }

    @Test fun `根目录面包屑回退到源名`() {
        assertEquals("123云盘·豫剧", IndexQuery.breadcrumb("/", "123云盘·豫剧"))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.IndexQuerySearchTest"`
Expected: FAIL — `Unresolved reference: mergeHits` / `breadcrumb`

- [ ] **Step 3: 实现**

```kotlin
/** 归并后的搜索结果；`sources.first()` 为主选来源。 */
data class MergedTrack(val name: String, val size: Long, val sources: List<TrackHit>)

/** 跨源重复曲目归并（spec §6 方案 A）。 */
object MergeKeys {
    fun key(name: String, size: Long): Pair<String, Long> = name.trim() to size
}
```

`IndexQuery` 内追加：

```kotlin
    /**
     * 实测两个豫剧源同为 3468 首，是同一批内容的两种分类视图；
     * 不归并则一次搜索出双倍结果。归并键 name+size 有极低概率误合不同内容，
     * UI 侧保留"展开各来源路径"作逃生门。
     */
    fun mergeHits(hits: List<TrackHit>): List<MergedTrack> {
        val order = LinkedHashMap<String, MutableList<TrackHit>>()
        for (h in hits) order.getOrPut(MergeKeys.key(h.name, h.size).toString()) { mutableListOf() }.add(h)
        return order.values.map { group ->
            MergedTrack(
                name = group.first().name.trim(),
                size = group.first().size,
                sources = group.sortedBy { it.sourceId }
            )
        }.sortedBy { it.name }
    }

    /** `/豫剧/常香玉` → `豫剧 / 常香玉`；空路径回退源名。 */
    fun breadcrumb(parentPath: String, sourceName: String, maxSegments: Int = 3): String {
        val segs = parentPath.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return sourceName
        val shown = if (segs.size > maxSegments) listOf("…") + segs.takeLast(maxSegments) else segs
        return shown.joinToString(" / ")
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.IndexQuerySearchTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/IndexQuery.kt app/src/test/java/com/radio/chinese/IndexQuerySearchTest.kt
git commit -m "feat(search): merge cross-source duplicates and format breadcrumbs"
```

---

## Task 3: 待爬目录集合与新鲜度

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/domain/model/IndexQuery.kt`
- Create: `app/src/test/java/com/radio/chinese/IndexQueryFreshnessTest.kt`

**Interfaces:**
- Consumes: 目录→最旧 `cachedAt` 的映射（Task 5 由 DAO 提供）
- Produces:
  - `IndexQuery.pickDirsToCrawl(crawled: Map<String, Long>, discovered: Set<String>, root: String, ttlMs: Long, now: Long): List<String>`
  - `IndexQuery.isFresh(oldestCachedAt: Long, ttlMs: Long, now: Long): Boolean`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.IndexQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 断点续爬的实现方式：不持久化队列，靠"新鲜目录跳过"达到同一效果。
 * 空目录不留行 → 每次都会被重爬（代价 1 个请求，spec §3 明确接受）。
 */
class IndexQueryFreshnessTest {
    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    @Test fun `新鲜目录被跳过`() {
        val out = IndexQuery.pickDirsToCrawl(
            crawled = mapOf("/常香玉" to now - day),
            discovered = setOf("/常香玉"),
            root = "/", ttlMs = day * 7, now = now
        )
        assertEquals(emptyList<String>(), out.filter { it == "/常香玉" })
    }

    @Test fun `过期目录重爬`() {
        val out = IndexQuery.pickDirsToCrawl(
            crawled = mapOf("/常香玉" to now - day * 30),
            discovered = setOf("/常香玉"),
            root = "/", ttlMs = day * 7, now = now
        )
        assertEquals(listOf("/常香玉"), out)
    }

    @Test fun `从未爬过的目录入队`() {
        val out = IndexQuery.pickDirsToCrawl(
            crawled = emptyMap(), discovered = setOf("/新目录"),
            root = "/", ttlMs = day, now = now
        )
        assertEquals(listOf("/新目录"), out)
    }

    @Test fun `根目录始终排在最前且只出现一次`() {
        val out = IndexQuery.pickDirsToCrawl(
            crawled = emptyMap(), discovered = setOf("/b", "/a"),
            root = "/", ttlMs = day, now = now
        )
        assertEquals(listOf("/", "/a", "/b"), out)
    }

    @Test fun `ttl 为零时全量重爬`() {
        val out = IndexQuery.pickDirsToCrawl(
            crawled = mapOf("/a" to now), discovered = setOf("/a"),
            root = "/", ttlMs = 0, now = now
        )
        assertEquals(listOf("/", "/a"), out)
    }

    @Test fun `恰好等于 ttl 的目录算过期`() {
        assertEquals(false, IndexQuery.isFresh(now - day, day, now))
        assertEquals(true, IndexQuery.isFresh(now - day + 1, day, now))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.IndexQueryFreshnessTest"`
Expected: FAIL — `Unresolved reference: pickDirsToCrawl`

- [ ] **Step 3: 实现**

```kotlin
    /** 行级最旧 cachedAt 决定是否重爬；等于 TTL 视为过期，避免边界上"永远差一秒"。 */
    fun isFresh(oldestCachedAt: Long, ttlMs: Long, now: Long): Boolean {
        if (ttlMs <= 0L) return false
        return now - oldestCachedAt < ttlMs
    }

    /**
     * 返回本次要 PROPFIND 的目录清单，根目录恒定第一（它同时是树规模探测器）。
     * `discovered` 是上层 BFS 已知的目录集合；不在 crawled 里的一律要爬。
     */
    fun pickDirsToCrawl(
        crawled: Map<String, Long>,
        discovered: Set<String>,
        root: String,
        ttlMs: Long,
        now: Long
    ): List<String> {
        val need = LinkedHashSet<String>()
        need.add(root)
        discovered.sorted().forEach { dir ->
            val oldest = crawled[dir]
            if (oldest == null || !isFresh(oldest, ttlMs, now)) need.add(dir)
        }
        return need.toList()
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.IndexQueryFreshnessTest"`
Expected: PASS（6 个用例）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/model/IndexQuery.kt app/src/test/java/com/radio/chinese/IndexQueryFreshnessTest.kt
git commit -m "feat(index): add directory freshness selection for resumable crawl"
```

---

## Task 4: NetworkGate（Wi‑Fi 闸门）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/service/NetworkGate.kt`
- Create: `app/src/test/java/com/radio/chinese/NetworkGateTest.kt`

**Interfaces:**
- Consumes: `ConnectivityManager`（`ACCESS_NETWORK_STATE` 已在 Manifest）
- Produces:
  - `interface NetworkGate { fun isIndexingAllowed(): Boolean; suspend fun awaitAllowed() }`
  - `NetworkGateImpl.isAllowed(caps: Set<NetCap>): Boolean`（纯函数，单测目标）
  - `enum class NetCap { WIFI, ETHERNET, CELLULAR, NOT_METERED, METERED, NONE }`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.radio.chinese

import com.radio.chinese.service.NetworkGateImpl
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.EnumSet

/** 判定表：只有"不计费 + (Wi‑Fi 或以太)"放行。蜂窝一律拒绝，即使它显示 not-metered。 */
class NetworkGateTest {
    private fun caps(vararg c: com.radio.chinese.service.NetCap) = EnumSet.of(*c)

    @Test fun `wifi 不计费 放行`() {
        assertEquals(true, NetworkGateImpl.isAllowed(caps(NetCap.WIFI, NetCap.NOT_METERED)))
    }

    @Test fun `以太 不计费 放行（车机可能走 USB 共享或有线）`() {
        assertEquals(true, NetworkGateImpl.isAllowed(caps(NetCap.ETHERNET, NetCap.NOT_METERED)))
    }

    @Test fun `wifi 计费 拒绝`() {
        assertEquals(false, NetworkGateImpl.isAllowed(caps(NetCap.WIFI, NetCap.METERED)))
    }

    @Test fun `蜂窝即使不计费也拒绝`() {
        assertEquals(false, NetworkGateImpl.isAllowed(caps(NetCap.CELLULAR, NetCap.NOT_METERED)))
    }

    @Test fun `无网络 拒绝`() {
        assertEquals(false, NetworkGateImpl.isAllowed(caps(NetCap.NONE)))
    }

    @Test fun `能力集合为空 拒绝`() {
        assertEquals(false, NetworkGateImpl.isAllowed(emptySet()))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.NetworkGateTest"`
Expected: FAIL — 文件不存在 / `Unresolved reference: NetCap`

- [ ] **Step 3: 实现**

```kotlin
package com.radio.chinese.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

enum class NetCap { WIFI, ETHERNET, CELLULAR, NOT_METERED, METERED, NONE }

interface NetworkGate {
    fun isIndexingAllowed(): Boolean

    /** 挂起直到允许索引；不允许期间由调用方转 PausedNoWifi，禁止丢进度。 */
    suspend fun awaitAllowed()
}

@Singleton
class NetworkGateImpl @Inject constructor(
    private val appContext: Context,
) : NetworkGate {

    companion object {
        /**
         * 纯判定表。蜂窝网络即便上报 not-metered（部分车机 USB 共享会这样）
         * 也必须拒绝：索引是 276 次请求、可后台完成的事，不该消耗用户流量。
         */
        fun isAllowed(caps: Set<NetCap>): Boolean =
            NetCap.NOT_METERED in caps &&
                (NetCap.WIFI in caps || NetCap.ETHERNET in caps) &&
                NetCap.CELLULAR !in caps

        fun classify(c: NetworkCapabilities): Set<NetCap> {
            val out = EnumSet.noneOf(NetCap::class.java)
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out.add(NetCap.WIFI)
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) out.add(NetCap.ETHERNET)
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out.add(NetCap.CELLULAR)
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) out.add(NetCap.NOT_METERED)
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) out.add(NetCap.METERED)
            if (out.isEmpty()) out.add(NetCap.NONE)
            return out
        }
    }

    private val cm get() = appContext.getSystemService(ConnectivityManager::class.java)

    override fun isIndexingAllowed(): Boolean {
        val c = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return isAllowed(classify(c))
    }

    override suspend fun awaitAllowed() {
        if (isIndexingAllowed()) return
        callbackFlow<Unit> {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { if (isIndexingAllowed()) trySend(Unit) }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (isAllowed(classify(caps))) trySend(Unit)
                }
                override fun onClose() { trySend(Unit) }   // 唤醒后由调用方重判，避免死等
            }
            val n = cm?.activeNetwork
            if (n != null) cm.registerNetworkCallback(n, cb) else cm.registerDefaultNetworkCallback(cb)
            awaitClose { runCatching { cm.unregisterNetworkCallback(cb) } }
        }.collect { }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.NetworkGateTest"`
Expected: PASS（6 个用例）

- [ ] **Step 5: 编译校验**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/radio/chinese/service/NetworkGate.kt app/src/test/java/com/radio/chinese/NetworkGateTest.kt
git commit -m "feat(index): add network gate allowing indexing only on unmetered wifi or ethernet"
```

---

## Task 5: SourceIndexer 遍历内核

**Files:**
- Create: `app/src/main/java/com/radio/chinese/service/SourceIndexer.kt`
- Create: `app/src/test/java/com/radio/chinese/SourceIndexerTest.kt`

**Interfaces:**
- Consumes: `AudioBrowser.listChildren(path): Result<List<AudioTrack>>`、`AudioCacheDao`（Task 8 补 `dirOldestCachedAt`，本任务先用接口注入）、`NetworkGate`（Task 4）、`IndexQuery.pickDirsToCrawl`（Task 3）
- Produces:
  - `sealed interface IndexState`：`Idle` / `Running(p)` / `PausedNoWifi(p)` / `PartiallyFailed(p)` / `Done(trackCount)`
  - `data class IndexedProgress(val indexedFiles: Int, val pendingDirs: Int, val skippedDirs: Int)`
  - `class IndexCoordinator(browser, cache, sourceSync, gate, concurrency = 6)`，入口 `suspend fun crawl(sourceId: Long, root: String = "/"): IndexState`
  - `interface IndexCacheWriter { suspend fun oldestByDir(sourceId: Long): Map<String, Long>; suspend fun replaceDir(sourceId: Long, path: String, rows: List<IndexRow>); suspend fun countTracks(sourceId: Long): Int }`
  - `data class IndexRow(val path: String, val parentPath: String, val name: String, val size: Long, val isFolder: Boolean)`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.AudioTrack
import com.radio.chinese.service.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * 遍历内核的行为钉死。浏览器与缓存都用假件，虚拟时间推进，
 * 不依赖任何 Android 框架类（JVM 下它们是 stub，mockk 也演不了）。
 */
class SourceIndexerTest {

    private class FakeCache : IndexCacheWriter {
        val dirs = mutableMapOf<Long, MutableList<IndexRow>>()
        val stamps = mutableMapOf<Pair<Long, String>, Long>()
        var replaceCalls = 0
        override suspend fun oldestByDir(sourceId: Long) =
            dirs[sourceId].orEmpty().groupBy { it.parentPath }
                .mapValues { (path, _) -> stamps[sourceId to path] ?: 0L }

        /** 手工预置一个“已爬且新鲜”的目录，供续爬用例使用；不预置时 stamp 缺失当 0 处理。 */
        fun seedFresh(sourceId: Long, path: String, at: Long) {
            dirs.getOrPut(sourceId) { mutableListOf() }
                .add(IndexRow("$path/x.mp3", path, "x.mp3", 1, false))
            stamps[sourceId to path] = at
        }
        override suspend fun replaceDir(sourceId: Long, path: String, rows: List<IndexRow>) {
            replaceCalls++
            dirs.getOrPut(sourceId) { mutableListOf() }.removeAll { it.parentPath == path }
            dirs[sourceId]!!.addAll(rows)
            stamps[sourceId to path] = System.currentTimeMillis()
        }
        override suspend fun countTracks(sourceId: Long) = dirs[sourceId].orEmpty().count { !it.isFolder }
    }

    private class FakeBrowser(
        val tree: Map<String, List<AudioTrack>>,
        val failOnce: MutableSet<String> = mutableSetOf(),
        val alwaysFail: Set<String> = emptySet(),
    ) : AudioBrowser {
        val requested = mutableListOf<String>()
        var inFlight = 0; var maxInFlight = 0
        override suspend fun listChildren(path: String): Result<List<AudioTrack>> {
            requested += path
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            kotlinx.coroutines.delay(10)   // 真挂起点：让同批次协程真的重叠，否则并发测不出来
            inFlight--
            if (path in alwaysFail || (path in failOnce && failOnce.remove(path)))
                return Result.failure(RuntimeException("PROPFIND $path 失败"))
            return Result.success(tree[path] ?: emptyList())
        }
        override fun resolveUri(track: AudioTrack) = track.path
        override suspend fun testConnection() = Result.success(Unit)
    }

    private fun dir(name: String) = AudioTrack(id = name, name = name, path = name, isFolder = true, parentPath = "")
    private fun file(name: String, parent: String) = AudioTrack(id = name, name = name, path = name, parentPath = parent)

    private suspend fun crawl(browser: AudioBrowser, cache: FakeCache, gate: NetworkGate = AllowAllGate) =
        IndexCoordinator(browser = browser, cache = cache, sourceSync = RecordingSync(), gate = gate, concurrency = 6)
            .crawl(sourceId = 1L)

    private class RecordingSync : IndexSourceSync {
        var syncWrites = 0; override suspend fun markSynced(sourceId: Long, at: Long) { syncWrites++ }
    }
    private object AllowAllGate : NetworkGate {
        override fun isIndexingAllowed() = true
        override suspend fun awaitAllowed() {}
    }

    @Test fun `两层树全部目录都被请求`() = runTest {
        val cache = FakeCache()
        val browser = FakeBrowser(mapOf(
            "/" to listOf(dir("/常香玉"), file("花木兰.mp3", "/")),
            "/常香玉" to listOf(file("七仙女.mp3", "/常香玉")),
        ))
        val state = crawl(browser, cache, AllowAllGate)
        assertEquals(IndexState.Done(2), state)
        assertEquals(listOf("/", "/常香玉"), browser.requested)
        assertEquals(2, cache.countTracks(1L))
    }

    @Test fun `并发上限为 6 不越界`() = runTest {
        val tree = LinkedHashMap<String, List<AudioTrack>>()
        tree["/"] = (1..20).map { dir("/d$it") } + file("root.mp3", "/")
        (1..20).forEach { tree["/d$it"] = listOf(file("f$it.mp3", "/d$it")) }
        val browser = FakeBrowser(tree)
        crawl(browser, FakeCache(), AllowAllGate)
        assertTrue("maxInFlight=${browser.maxInFlight}", browser.maxInFlight in 1..6)
    }

    @Test fun `瞬时失败重试一次后成功`() = runTest {
        val cache = FakeCache()
        val browser = FakeBrowser(
            mapOf("/" to listOf(dir("/b")), "/b" to listOf(file("c.mp3", "/b"))),
            failOnce = mutableSetOf("/b"),
        )
        val state = crawl(browser, cache, AllowAllGate)
        assertEquals(2, browser.requested.count { it == "/b" })
        assertEquals(IndexState.Done(1), state)
    }

    @Test fun `永久失败的目录计入 skipped 且不中断`() = runTest {
        val cache = FakeCache()
        val browser = FakeBrowser(
            mapOf("/" to listOf(dir("/坏目录"), file("ok.mp3", "/")), "/坏目录" to emptyList()),
            alwaysFail = setOf("/坏目录"),
        )
        val state = crawl(browser, cache, AllowAllGate)
        assertTrue("state=$state", state is IndexState.PartiallyFailed)
        assertEquals(1, (state as IndexState.PartiallyFailed).p.skippedDirs)
        assertEquals(1, cache.countTracks(1L))
    }

    @Test fun `全部爬完才写源级同步时间戳`() = runTest {
        val cache = FakeCache(); val sync = RecordingSync()
        val browser = FakeBrowser(mapOf("/" to listOf(file("a.mp3", "/"))))
        IndexCoordinator(browser, cache, sync, AllowAllGate, 6).crawl(1L)
        assertEquals(1, sync.syncWrites)
    }

    @Test fun `有跳过目录时不写源级同步时间戳`() = runTest {
        val cache = FakeCache(); val sync = RecordingSync()
        val browser = FakeBrowser(
            mapOf("/" to listOf(dir("/坏"), file("a.mp3", "/"))), alwaysFail = setOf("/坏")
        )
        IndexCoordinator(browser, cache, sync, AllowAllGate, 6).crawl(1L)
        assertEquals(0, sync.syncWrites)
    }

    @Test fun `已新鲜目录不重复请求`() = runTest {
        val cache = FakeCache()
        cache.seedFresh(1L, "/常香玉", System.currentTimeMillis())
        val browser = FakeBrowser(mapOf("/" to listOf(dir("/常香玉"), file("根.mp3", "/"))))
        crawl(browser, cache, AllowAllGate)
        assertEquals("根目录必爬，新鲜子目录应跳过", listOf("/"), browser.requested)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.SourceIndexerTest"`
Expected: FAIL — `Unresolved reference: IndexCoordinator`

- [ ] **Step 3: 实现**

```kotlin
package com.radio.chinese.service

import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.model.IndexQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class IndexRow(
    val path: String, val parentPath: String, val name: String,
    val size: Long, val isFolder: Boolean
)

data class IndexedProgress(val indexedFiles: Int, val pendingDirs: Int, val skippedDirs: Int)

sealed interface IndexState {
    data object Idle : IndexState
    data class Running(val p: IndexedProgress) : IndexState
    data class PausedNoWifi(val p: IndexedProgress) : IndexState
    data class PartiallyFailed(val p: IndexedProgress) : IndexState
    data class Done(val trackCount: Int) : IndexState
}

/** 索引读写口。生产实现走 AudioCacheDao；测试用内存假件（本项目无 Robolectric）。 */
interface IndexCacheWriter {
    suspend fun oldestByDir(sourceId: Long): Map<String, Long>
    suspend fun replaceDir(sourceId: Long, path: String, rows: List<IndexRow>)
    suspend fun countTracks(sourceId: Long): Int
}

interface IndexSourceSync { suspend fun markSynced(sourceId: Long, at: Long) }

/**
 * BFS + Depth:1 分层遍历。不复用 WebDavClient 单例：它的 listFilesCache
 * 的 key 只有 path，四个快捷码源共用同一 base URL，串数据（spec §4 / 终审 I2 同源缺陷）。
 */
class IndexCoordinator(
    private val browser: AudioBrowser,
    private val cache: IndexCacheWriter,
    private val sourceSync: IndexSourceSync,
    private val gate: NetworkGate,
    private val concurrency: Int = 6,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val onState: (IndexState) -> Unit = {},
) {
    companion object {
        const val DEFAULT_TTL_MS = 86_400_000L
        const val RETRY_DELAY_MS = 200L
    }

    private val root = "/"

    suspend fun crawl(sourceId: Long): IndexState = coroutineScope {
        val stamps = cache.oldestByDir(sourceId)
        val pending = ArrayDeque<String>()
        val queued = HashSet<String>()
        val sem = Semaphore(concurrency)
        var files = 0
        var skipped = 0

        // 根目录恒第一：它同时是"整棵树有多大"的探测器
        pending.add(root); queued.add(root)

        while (pending.isNotEmpty()) {
            if (!gate.isIndexingAllowed()) {
                onState(IndexState.PausedNoWifi(IndexedProgress(files, pending.size, skipped)))
                gate.awaitAllowed()
            }
            onState(IndexState.Running(IndexedProgress(files, pending.size, skipped)))

            val batch = mutableListOf<String>()
            while (batch.size < concurrency && pending.isNotEmpty()) batch += pending.removeFirst()

            // 批次内必须并发：串行等于 276 次 RTT 累加，实测会把 75s 拖成四分钟
            val fetched = batch.map { dir -> async { sem.withPermit { fetchWithRetry(dir) } } }.awaitAll()

            val discovered = mutableListOf<String>()
            fetched.forEach { res ->
                if (res == null) {
                    skipped++
                } else {
                    files += res.rows.count { !it.isFolder }
                    cache.replaceDir(sourceId, res.path, res.rows)
                    discovered += res.rows.filter { it.isFolder }.map { it.path }
                }
            }
            // 断点续爬的实现方式：不持久化队列，靠新鲜目录跳过达到同一效果
            discovered.forEach { sub ->
                val oldest = stamps[sub]
                if (oldest != null && IndexQuery.isFresh(oldest, ttlMs, now())) return@forEach
                if (queued.add(sub)) pending.add(sub)
            }
        }

        val p = IndexedProgress(files, 0, skipped)
        if (skipped > 0) {
            onState(IndexState.PartiallyFailed(p))
            IndexState.PartiallyFailed(p)
        } else {
            // 只有完整跑完才写源级时间戳：部分完成写了会让浏览路径误信"缓存已新"
            sourceSync.markSynced(sourceId, now())
            val count = cache.countTracks(sourceId)
            onState(IndexState.Done(count))
            IndexState.Done(count)
        }
    }

    /** 单目录拉取结果；空目录 rows 为空但仍算成功，不会被当成失败重试。 */
    private data class DirFetch(val path: String, val rows: List<IndexRow>)

    private suspend fun fetchWithRetry(path: String): DirFetch? {
        repeat(2) { attempt ->
            val rows = runCatching { browser.listChildren(path) }.getOrNull()?.getOrNull()
            if (rows != null) {
                return DirFetch(path, rows.map {
                    IndexRow(path = it.path, parentPath = path, name = it.name,
                        size = it.size, isFolder = it.isFolder)
                })
            }
            if (attempt == 0) kotlinx.coroutines.delay(RETRY_DELAY_MS)   // 重试一次；仍失败计入 skipped
        }
        return null
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.SourceIndexerTest"`
Expected: PASS（7 个用例）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/service/SourceIndexer.kt app/src/test/java/com/radio/chinese/SourceIndexerTest.kt
git commit -m "feat(index): add resumable BFS index coordinator with retry and skip accounting"
```

---

## Task 6: 每源独立浏览器工厂

**Files:**
- Create: `app/src/main/java/com/radio/chinese/domain/browser/BrowserFactory.kt`
- Create: `app/src/test/java/com/radio/chinese/BrowserFactoryTest.kt`

**Interfaces:**
- Consumes: `AudioSource`、`WebDavBrowser(client, serverUrl, username, password)`、`LocalBrowser(context)`、`M3uBrowser(okHttpClient)`、`HttpBrowser(okHttpClient)`
- Produces:
  - `class BrowserFactory(context: Context, okHttpClient: OkHttpClient)`，`internal var clientFactory: () -> WebDavClient`
  - `BrowserFactory.create(source: AudioSource): AudioBrowser`
  - `BrowserFactory.createForIndexing(source: AudioSource): AudioBrowser?`（非 WEBDAV 返回 null）

- [ ] **Step 1: 写失败测试**

```kotlin
package com.radio.chinese

import android.content.Context
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.SourceType
import com.radio.chinese.domain.browser.BrowserFactory
import com.radio.chinese.domain.browser.WebDavBrowser
import com.radio.chinese.service.WebDavClient
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/**
 * 索引并发的前提：每源一个客户端。WebDavClient.listFilesCache 的 key 只有 path，
 * 四个快捷码源共用同一 base URL，复用单例会跨源串数据（spec §4）。
 */
class BrowserFactoryTest {
    private fun webdav(id: Long, user: String, pass: String) = AudioSource(
        id = id, name = "源$id", type = SourceType.WEBDAV,
        url = "https://webdav.123pan.cn/webdav", username = user, password = pass
    )

    private fun factory() = BrowserFactory(mockk<Context>(relaxed = true), mockk<OkHttpClient>(relaxed = true))

    @Test fun `两个 WebDAV 源得到互不相同的客户端实例`() {
        val made = mutableListOf<WebDavClient>()
        val f = factory()
        f.clientFactory = { WebDavClient().also { made += it } }

        val a = f.create(webdav(1, "u1", "p1"))
        val b = f.create(webdav(2, "u2", "p2"))

        assertIs<WebDavBrowser>(a); assertIs<WebDavBrowser>(b)
        assertEquals(2, made.size)
        assertNotSame(made[0], made[1])
    }

    @Test fun `一次索引任务内客户端被复用而非每目录新建`() {
        var created = 0
        val f = factory()
        f.clientFactory = { WebDavClient().also { created++ } }
        f.createForIndexing(webdav(1, "u", "p"))
        f.createForIndexing(webdav(1, "u", "p"))
        assertEquals("同一源重复取用应当各建一次：由调用方持有实例，不由工厂缓存", 2, created)
    }

    @Test fun `非 WebDAV 源不参与索引`() {
        val f = factory()
        assertNull(f.createForIndexing(AudioSource(1, "本地", SourceType.LOCAL, "")))
        assertNull(f.createForIndexing(AudioSource(2, "歌单", SourceType.M3U, "http://x")))
        assertNull(f.createForIndexing(AudioSource(3, "流", SourceType.HTTP, "http://x")))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.BrowserFactoryTest"`
Expected: FAIL — `Unresolved reference: BrowserFactory`

- [ ] **Step 3: 实现**

```kotlin
package com.radio.chinese.domain.browser

import android.content.Context
import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioSource
import com.radio.chinese.domain.SourceType
import com.radio.chinese.service.WebDavClient
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 浏览器工厂。WebDAV 每次调用都新建 `WebDavClient`：它的凭证是可写字段、
 * 列表缓存 key 只有 path，多源并发共用一个实例必然串。调用方（索引器/VM）
 * 在任务期内持有返回的浏览器即可，工厂不做缓存，避免生命周期与源绑定。
 */
@Singleton
class BrowserFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    internal var clientFactory: () -> WebDavClient = { WebDavClient() }

    fun create(source: AudioSource): AudioBrowser = when (source.type) {
        SourceType.WEBDAV -> WebDavBrowser(clientFactory(), source.url, source.username, source.password)
        SourceType.LOCAL -> LocalBrowser(context)
        SourceType.M3U -> M3uBrowser(okHttpClient)
        SourceType.HTTP -> HttpBrowser(okHttpClient)
    }

    /** 只有目录树语义的来源能索引；LOCAL 走 MediaStore，M3U/HTTP 是扁平列表。 */
    fun createForIndexing(source: AudioSource): AudioBrowser? =
        if (source.type == SourceType.WEBDAV) create(source) else null
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.BrowserFactoryTest"`
Expected: PASS（3 个用例）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/domain/browser/BrowserFactory.kt app/src/test/java/com/radio/chinese/BrowserFactoryTest.kt
git commit -m "feat(index): add per-source browser factory isolating webdav clients"
```

---

## Task 7: SourceIndexer 门面（生命周期、状态、去重触发）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/service/SourceIndexer.kt`
- Modify: `app/src/test/java/com/radio/chinese/SourceIndexerTest.kt`

**Interfaces:**
- Consumes: `IndexCoordinator`（Task 5）、`BrowserFactory`（Task 6）、`NetworkGate`（Task 4）
- Produces:
  - `class SourceIndexer(browserFactory, cacheWriter, sourceSync, gate, scope)`
  - `SourceIndexer.start(source: AudioSource)`、`cancel(sourceId: Long)`
  - `SourceIndexer.states: StateFlow<Map<Long, IndexState>>`

- [ ] **Step 1: 追加失败测试**

```kotlin
    @Test fun `同一源重复触发只跑一个任务`() = runTest {
        val indexer = SourceIndexer(
            browserFactory = FixedFactory(FakeBrowser(mapOf("/" to listOf(file("a.mp3", "/"))))),
            cacheWriter = FakeCache(), sourceSync = RecordingSync(),
            gate = AllowAllGate, scope = this,
        )
        val source = com.radio.chinese.domain.AudioSource(1, "豫剧", com.radio.chinese.domain.SourceType.WEBDAV, "http://x", "u", "p")
        indexer.start(source)
        val first = indexer.states.value[1L]
        indexer.start(source)
        assertEquals("重复 start 必须并入在跑的任务，不能叠两份并发", first, indexer.states.value[1L])
        assertEquals(1, indexer.runningCount())
    }

    @Test fun `非 WebDAV 源直接返回不建任务`() = runTest {
        val indexer = SourceIndexer(FixedFactory(FakeBrowser(emptyMap())), FakeCache(), RecordingSync(), AllowAllGate, this)
        indexer.start(com.radio.chinese.domain.AudioSource(2, "本地", com.radio.chinese.domain.SourceType.LOCAL, ""))
        assertEquals(0, indexer.runningCount())
        assertEquals(emptyMap<Long, IndexState>(), indexer.states.value)
    }

    @Test fun `取消后状态回到 Idle 且已写入的行保留`() = runTest {
        val cache = FakeCache()
        val browser = FakeBrowser(mapOf("/" to listOf(dir("/b")), "/b" to listOf(file("c.mp3", "/b"))))
        val indexer = SourceIndexer(FixedFactory(browser), cache, RecordingSync(), AllowAllGate, this)
        val source = com.radio.chinese.domain.AudioSource(1, "豫剧", com.radio.chinese.domain.SourceType.WEBDAV, "http://x", "u", "p")
        indexer.start(source)
        runCurrent()
        indexer.cancel(1L)
        runCurrent()
        assertEquals(0, indexer.runningCount())
        assertEquals(IndexState.Idle, indexer.states.value[1L])
        // 取消不得清空已写入数据：根目录已落下的行必须还在（假件里以 dirs 代表）
        assertTrue("取消后已索引内容不得被清空",
            cache.dirs[1L].orEmpty().any { it.parentPath == "/" } || browser.requested.isEmpty())
    }
```

假件补充（测试类内）：

```kotlin
    /** 只返回固定浏览器的工厂假件，避免真的去建 WebDavClient。 */
    private class FixedFactory(val browser: AudioBrowser) : BrowserFactoryStub {
        override fun createForIndexing(source: AudioSource): AudioBrowser? = browser
    }
```

**接口约束**：`SourceIndexer` 依赖的是 `interface BrowserFactoryStub { fun createForIndexing(source: AudioSource): AudioBrowser? }`——把该接口声明在 `SourceIndexer.kt` 内，并让 Task 6 的 `BrowserFactory` 实现它（`class BrowserFactory : BrowserFactoryStub`）。这样测试无需 mock Android 依赖。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.SourceIndexerTest"`
Expected: FAIL — `Unresolved reference: SourceIndexer`

- [ ] **Step 3: 实现门面**

```kotlin
interface BrowserFactoryStub {
    fun createForIndexing(source: AudioSource): AudioBrowser?
}

/**
 * 索引生命周期门面：每源一个在跑任务、状态可订阅、取消不清数据。
 * 不读 UiSurface：是否调用 start 由 Phone 档 UI 决定（spec §5 层约束）。
 */
@Singleton
class SourceIndexer @Inject constructor(
    private val browserFactory: BrowserFactoryStub,
    private val cacheWriter: IndexCacheWriter,
    private val sourceSync: IndexSourceSync,
    private val gate: NetworkGate,
    private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<Long, IndexState>>(emptyMap())
    val states: StateFlow<Map<Long, IndexState>> = _states.asStateFlow()

    private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    fun runningCount(): Int = jobs.values.count { it.isActive }

    fun start(source: AudioSource) {
        if (browserFactory.createForIndexing(source) == null) return   // 非 WebDAV：静默跳过
        if (jobs[source.id]?.isActive == true) return                  // 重复触发合并
        val browser = browserFactory.createForIndexing(source) ?: return
        jobs[source.id] = scope.launch {
            _states.update { it + (source.id to IndexState.Running(IndexedProgress(0, 1, 0))) }
            val st = IndexCoordinator(browser, cacheWriter, sourceSync, gate).crawl(source.id)
            _states.update { it + (source.id to st) }
        }
    }

    fun cancel(sourceId: Long) {
        jobs.remove(sourceId)?.cancel()
        _states.update { it + (sourceId to IndexState.Idle) }
    }
}
```

`BrowserFactory` 声明改为 `class BrowserFactory @Inject constructor(...) : BrowserFactoryStub`，并在 `BrowserFactory.kt` 里 `import com.radio.chinese.service.BrowserFactoryStub`。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.SourceIndexerTest" --tests "com.radio.chinese.BrowserFactoryTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/service/SourceIndexer.kt app/src/main/java/com/radio/chinese/domain/browser/BrowserFactory.kt app/src/test/java/com/radio/chinese/SourceIndexerTest.kt
git commit -m "feat(index): add indexer facade with per-source job dedupe"
```

---

## Task 8: Room 适配与手动刷新不再清空索引

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/data/dao/AudioCacheDao.kt`
- Create: `app/src/main/java/com/radio/chinese/data/repository/IndexDaoAdapter.kt`
- Modify: `app/src/main/java/com/radio/chinese/data/repository/AudioLibraryRepository.kt:69-73`
- Modify: `app/src/main/java/com/radio/chinese/ui/library/AudioLibraryViewModel.kt:171,224`
- Create: `app/src/test/java/com/radio/chinese/RefreshKeepsIndexTest.kt`

**Interfaces:**
- Consumes: `AudioCacheDao`、`AudioSourceDao.updateSyncTime`
- Produces:
  - `class IndexDaoAdapter(cacheDao, sourceDao) : IndexCacheWriter, IndexSourceSync`
  - `AudioCacheDao.dirOldestCachedAt(sourceId): List<DirStamp>`、`countTracks(sourceId): Int`
  - `AudioLibraryRepository.refreshDir(source, path, browser)`（不清整源）

- [ ] **Step 1: 写失败测试**

```kotlin
package com.radio.chinese

import com.radio.chinese.data.CredentialManager
import com.radio.chinese.data.dao.*
import com.radio.chinese.data.entity.AudioSourceEntity
import com.radio.chinese.data.repository.AudioLibraryRepository
import com.radio.chinese.domain.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 现状缺陷：browseSource()/refreshBrowse() 走 refreshSource，它先 deleteBySource，
 * 用户每进一次目录就把两万行索引清零。这里钉死“只清当前目录”。
 */
class RefreshKeepsIndexTest {
    @Test fun `目录级刷新不删除其他目录的缓存行`() = runTest {
        val sourceDao = mockk<AudioSourceDao>(relaxed = true)
        val cacheDao = mockk<AudioCacheDao>(relaxed = true)
        val favDao = mockk<AudioFavoriteDao>(relaxed = true)
        val recentDao = mockk<AudioRecentDao>(relaxed = true)
        val creds = mockk<CredentialManager>(relaxed = true)
        every { sourceDao.getById(1L) } returns AudioSourceEntity(id = 1, name = "豫剧", type = "WEBDAV", url = "http://x")
        every { cacheDao.getByParentPath(1L, "/b") } returns emptyList()

        val repo = AudioLibraryRepository(sourceDao, cacheDao, favDao, recentDao, creds)
        val browser = object : AudioBrowser {
            var calls = 0
            override suspend fun listChildren(path: String) = run { calls++; Result.success(emptyList<AudioTrack>()) }
            override fun resolveUri(track: AudioTrack) = ""
            override suspend fun testConnection() = Result.success(Unit)
        }

        repo.refreshDir(source = AudioSource(1, "豫剧", SourceType.WEBDAV, "http://x"), path = "/b", browser = browser)

        verify(exactly = 0) { cacheDao.deleteBySource(any()) }
        verify(exactly = 1) { cacheDao.deleteByParentPath(1L, "/b") }
        assertEquals(1, browser.calls)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.RefreshKeepsIndexTest"`
Expected: FAIL — `Unresolved reference: refreshDir`

- [ ] **Step 3: 实现**

`AudioCacheDao.kt` 追加：

```kotlin
    /** 按目录取最旧 cachedAt，作为新鲜度判定的唯一依据。 */
    @Query("SELECT parentPath, MIN(cachedAt) AS oldest FROM audio_cache WHERE sourceId = :sourceId GROUP BY parentPath")
    suspend fun dirOldestCachedAt(sourceId: Long): List<DirStamp>

    @Query("SELECT COUNT(*) FROM audio_cache WHERE sourceId = :sourceId AND isFolder = 0")
    suspend fun countTracks(sourceId: Long): Int
```

```kotlin
data class DirStamp(val parentPath: String, val oldest: Long)
```

新建 `data/repository/IndexDaoAdapter.kt`：

```kotlin
package com.radio.chinese.data.repository

import com.radio.chinese.data.dao.AudioCacheDao
import com.radio.chinese.data.dao.AudioSourceDao
import com.radio.chinese.data.entity.AudioCacheEntity
import com.radio.chinese.service.IndexCacheWriter
import com.radio.chinese.service.IndexRow
import com.radio.chinese.service.IndexSourceSync
import javax.inject.Inject
import javax.inject.Singleton

/** 索引器的 Room 落地块：写行语义与 AudioLibraryRepository.listChildren 保持一致。 */
@Singleton
class IndexDaoAdapter @Inject constructor(
    private val cacheDao: AudioCacheDao,
    private val sourceDao: AudioSourceDao,
) : IndexCacheWriter, IndexSourceSync {

    override suspend fun oldestByDir(sourceId: Long): Map<String, Long> =
        cacheDao.dirOldestCachedAt(sourceId).associate { it.parentPath to it.oldest }

    override suspend fun replaceDir(sourceId: Long, path: String, rows: List<IndexRow>) {
        cacheDao.deleteByParentPath(sourceId, path)
        if (rows.isEmpty()) return   // 空目录不留行：下次会重爬一次，代价 1 个请求
        cacheDao.insertAll(rows.map {
            AudioCacheEntity(
                sourceId = sourceId, path = it.path, parentPath = it.parentPath,
                name = it.name, isFolder = it.isFolder, size = it.size
            )
        })
    }

    override suspend fun countTracks(sourceId: Long): Int = cacheDao.countTracks(sourceId)

    override suspend fun markSynced(sourceId: Long, at: Long) = sourceDao.updateSyncTime(sourceId, at)
}
```

`AudioLibraryRepository.kt` 把 `refreshSource` 换成按目录版本（保留旧签名供编译过渡的调用点由本任务一并改掉）：

```kotlin
    /**
     * 只重取一个目录：先删该目录旧行再落新行。
     * 绝不 deleteBySource —— 那会抹掉后台索引建立的整棵树。
     */
    suspend fun refreshDir(source: AudioSource, path: String, browser: AudioBrowser): Result<List<AudioTrack>> {
        cacheDao.deleteByParentPath(source.id, path)
        val result = browser.listChildren(path)
        result.onSuccess { items ->
            cacheDao.insertAll(items.map { it.toEntity(source.id) })
        }
        return result
    }
```

`AudioLibraryViewModel.kt`：`browseSource()` 与 `refreshBrowse()` 内的 `repository.refreshSource(...)` 两处调用改为 `repository.refreshDir(source, "", browser)` 与 `repository.refreshDir(source, _state.value.browsePath, browser)`；`refreshSource` 方法本体删除（删除后 `./gradlew :app:compileDebugKotlin` 若报未解析引用，说明还有漏改调用点）。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.RefreshKeepsIndexTest"`
Expected: PASS；随后 `./gradlew :app:testDebugUnitTest` 全量绿

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/data/dao/AudioCacheDao.kt app/src/main/java/com/radio/chinese/data/repository/IndexDaoAdapter.kt app/src/main/java/com/radio/chinese/data/repository/AudioLibraryRepository.kt app/src/main/java/com/radio/chinese/ui/library/AudioLibraryViewModel.kt app/src/test/java/com/radio/chinese/RefreshKeepsIndexTest.kt
git commit -m "fix(library): directory refresh no longer wipes whole-source cache"
```

---

## Task 9: 索引开关与 ViewModel 接线

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/data/local/RadioPreferences.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/library/AudioLibraryViewModel.kt`
- Modify: `app/src/main/java/com/radio/chinese/di/AppModule.kt`
- Create: `app/src/test/java/com/radio/chinese/AudioLibrarySearchViewModelTest.kt`

**Interfaces:**
- Consumes: `SourceIndexer`、`AudioLibraryRepository.searchTracks`、`IndexQuery.mergeHits`、`RadioPreferences.webdavIndexEnabled`
- Produces:
  - `LibraryUiState.searchResults: List<MergedTrack>`、`indexState: IndexState`、`indexingAllowedOnThisSurface: Boolean`
  - `AudioLibraryViewModel.updateSearchQuery(q: String, carSurface: Boolean)`
  - `AudioLibraryViewModel.startIndexingIfNeeded(carSurface: Boolean)`
  - `AudioLibraryViewModel.playSearchResult(track: TrackHit)`
  - `RadioPreferences.webdavIndexEnabled: Flow<Boolean>` / `setWebdavIndexEnabled(Boolean)`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.radio.chinese

import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.repository.AudioLibraryRepository
import com.radio.chinese.data.repository.TrackHit
import com.radio.chinese.service.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AudioLibrarySearchViewModelTest {

    @Test fun `车机档不做全源搜索，只回目录内过滤`() = runTest {
        val repo = mockk<AudioLibraryRepository>(relaxed = true)
        val vm = build(repo)
        coVerify(exactly = 0) { repo.searchTracks(any(), any(), any()) }
        vm.updateSearchQuery("花木兰", carSurface = true)
        coVerify(exactly = 0) { repo.searchTracks(any(), any(), any()) }
        assertEquals("花木兰", vm.uiState.value.searchQuery)
    }

    @Test fun `手机档查询走全源并归并`() = runTest {
        val repo = mockk<AudioLibraryRepository>()
        val hits = listOf(
            TrackHit(1, "花木兰", "/a/1.mp3", "/a", 10),
            TrackHit(2, "花木兰", "/b/1.mp3", "/b", 10),
        )
        coEvery { repo.searchTracks(null, "花木兰", 200) } returns hits
        val vm = build(repo)
        vm.updateSearchQuery("花木兰", carSurface = false)
        assertEquals(1, vm.uiState.value.searchResults.size)
    }

    @Test fun `车机档永不触发索引`() = runTest {
        val indexer = mockk<SourceIndexer>(relaxed = true)
        val vm = build(mockk(relaxed = true), indexer = indexer)
        vm.startIndexingIfNeeded(carSurface = true)
        verify(exactly = 0) { indexer.start(any()) }
    }

    @Test fun `手机档且开关打开才触发索引`() = runTest {
        val indexer = mockk<SourceIndexer>(relaxed = true)
        val vm = build(mockk(relaxed = true), indexer = indexer)
        vm.startIndexingIfNeeded(carSurface = false)
        verify(atLeast = 1) { indexer.start(any()) }
    }

    @Test fun `索引开关关闭时不触发`() = runTest {
        val prefs = mockk<RadioPreferences>()
        every { prefs.webdavIndexEnabled } returns MutableStateFlow(false)
        val indexer = mockk<SourceIndexer>(relaxed = true)
        val vm = build(mockk(relaxed = true), indexer = indexer, prefs = prefs)
        vm.startIndexingIfNeeded(carSurface = false)
        verify(exactly = 0) { indexer.start(any()) }
    }

    private fun build(
        repo: AudioLibraryRepository,
        indexer: SourceIndexer = mockk(relaxed = true),
        prefs: RadioPreferences = mockk(relaxed = true),
    ): AudioLibraryViewModel {
        every { prefs.webdavIndexEnabled } returns MutableStateFlow(true)
        return AudioLibraryViewModel(
            context = mockk(relaxed = true), repository = repo,
            playerManager = mockk(relaxed = true),
            browserFactory = mockk(relaxed = true),
            indexer = indexer, preferences = prefs,
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.AudioLibrarySearchViewModelTest"`
Expected: FAIL — VM 构造函数签名不含 `browserFactory/indexer/preferences`，`Unresolved reference`

- [ ] **Step 3: 实现**

`RadioPreferences.kt`（沿用现有 `booleanPreferencesKey` 模式）：

```kotlin
    val webdavIndexEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_WEBDAV_INDEX_ENABLED] ?: true
    }

    suspend fun setWebdavIndexEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_WEBDAV_INDEX_ENABLED] = enabled }
    }

    private val KEY_WEBDAV_INDEX_ENABLED = booleanPreferencesKey("webdav_index_enabled")
```

`AudioLibraryViewModel` 构造参数改为（删除 `webDavClient`，改注入工厂与索引器；`okHttpClient` 一并去掉，浏览器创建全部走工厂）；同时给 `LibraryUiState` 加字段 `val searchResults: List<MergedTrack> = emptyList()`：

```kotlin
@HiltViewModel
class AudioLibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AudioLibraryRepository,
    private val playerManager: PlayerManager,
    private val browserFactory: BrowserFactory,
    private val indexer: SourceIndexer,
    private val preferences: RadioPreferences,
) : ViewModel() {
```

状态与行为增量：

```kotlin
    private val _searchResults = MutableStateFlow<List<MergedTrack>>(emptyList())
    private var carSurface = true
    val indexStates = indexer.states

    /**
     * 形态由 UI 传进来：ViewModel 属 UI 之下、Compose 之上，不能读 CompositionLocal。
     * 车机档保持既有“当前目录内过滤”，全源搜索只在手机档开启。
     */
    fun updateSearchQuery(q: String, carSurface: Boolean) {
        this.carSurface = carSurface
        _state.update { it.copy(searchQuery = q) }
        if (carSurface) { _searchResults.value = emptyList(); return }
        viewModelScope.launch {
            val hits = repository.searchTracks(sourceId = null, query = q, limit = 200)
            _searchResults.value = if (q.isBlank()) emptyList() else IndexQuery.mergeHits(hits)
        }
    }

    fun startIndexingIfNeeded(carSurface: Boolean) {
        if (carSurface) return
        viewModelScope.launch {
            if (!preferences.webdavIndexEnabled.first()) return@launch
            repository.getSources()
                .filter { it.type == SourceType.WEBDAV }
                .forEach { indexer.start(it) }
        }
    }

    /** 搜索结果点击：索引行没有 browsingSource，必须按行自带的 sourceId 找回来源。 */
    fun playSearchResult(hit: TrackHit) {
        viewModelScope.launch {
            val source = repository.getSource(hit.sourceId) ?: return@launch
            val track = AudioTrack(id = hit.path, name = hit.name, path = hit.path,
                size = hit.size, sourceId = hit.sourceId, parentPath = hit.parentPath)
            playTrackWithSource(track, source)
        }
    }
```

`private fun createBrowser(...)` 整体替换为 `private fun createBrowser(source: AudioSource) = browserFactory.create(source)`，并删除五参数版本；`playTrack` 里的调用点同步改用工厂。`uiState` 增加 `searchResults`：把 `_searchResults` 与 `_state` 用 `combine` 输出，或最小改动地在 `updateSearchQuery` 里一并 `copy(searchResults = …)`（选后者，避免动其他状态流骨架）。

`AppModule.kt` 追加绑定（作用域复用现有 `@Singleton`）：

```kotlin
    @Provides
    @Singleton
    fun provideBrowserFactoryStub(factory: BrowserFactory): com.radio.chinese.service.BrowserFactoryStub = factory

    @Provides
    @Singleton
    fun provideIndexCacheWriter(adapter: com.radio.chinese.data.repository.IndexDaoAdapter): com.radio.chinese.service.IndexCacheWriter = adapter

    @Provides
    @Singleton
    fun provideIndexSourceSync(adapter: com.radio.chinese.data.repository.IndexDaoAdapter): com.radio.chinese.service.IndexSourceSync = adapter

    @Provides
    @Singleton
    @com.radio.chinese.di.ApplicationScope
    fun provideApplicationScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
```

`SourceIndexer` 的 `scope` 参数标注 `@ApplicationScope`；新建 `di/Qualifiers.kt`：

```kotlin
package com.radio.chinese.di

import javax.inject.Qualifier

@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.radio.chinese.AudioLibrarySearchViewModelTest"`
Expected: PASS（5 个用例）；`./gradlew :app:assembleDebug` 编译通过

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese app/src/test/java/com/radio/chinese/AudioLibrarySearchViewModelTest.kt
git commit -m "feat(search): wire cross-source search and surface-gated indexing into view model"
```

---

## Task 10: 搜索结果列表与索引进度提示

**Files:**
- Create: `app/src/main/java/com/radio/chinese/ui/library/SearchResultsList.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/audio/AudioMainScreen.kt:98-99,141-157,244-251`

**Interfaces:**
- Consumes: `LibraryUiState.searchResults`、`MergedTrack`、`IndexQuery.breadcrumb`、`SearchBarRow`、`EmptyState`、`SourceIndexer.states`
- Produces: `SearchResultsList(state, onPlay, onToggleFavorite, isFavorited, modifier)`

- [ ] **Step 1: 实现结果列表**

```kotlin
package com.radio.chinese.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.radio.chinese.data.repository.TrackHit
import com.radio.chinese.domain.model.IndexQuery
import com.radio.chinese.domain.model.MergedTrack
import com.radio.chinese.service.IndexState
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.theme.Dimens

/**
 * 手机档全源搜索结果。车机档不进到这里（保持目录内过滤原样渲染）。
 */
@Composable
fun SearchResultsList(
    results: List<MergedTrack>,
    query: String,
    indexState: IndexState,
    breadcrumbOf: (TrackHit) -> String,
    isFavorited: (String) -> Boolean,
    onPlay: (TrackHit) -> Unit,
    onToggleFavorite: (TrackHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        IndexHint(indexState, resultCount = results.size)
        if (results.isEmpty()) {
            val indexing = indexState is IndexState.Running || indexState is IndexState.PausedNoWifi
            EmptyState(
                icon = Icons.Default.Search,
                // “没搜到”和“还没索引完”是两回事，混用会让用户以为记录丢了
                title = if (query.isBlank()) "输入曲目名开始搜索"
                        else if (indexing) "正在建立索引，请稍后"
                        else "没有匹配「$query」的曲目",
                hint = when {
                    indexState is IndexState.PausedNoWifi -> "索引已暂停，连接 Wi‑Fi 后继续"
                    indexState is IndexState.Idle && query.isNotBlank() ->
                        "连接 Wi‑Fi 后会自动开始建立节目索引"
                    indexing -> null
                    else -> "换个关键字试试"
                }
            )
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)
        ) {
            // key 必须稳定：行内收藏态是 remember 的，无 key 会把星标串到别的行
            items(results, key = { "${it.name}|${it.size}" }) { merged ->
                val primary = merged.sources.first()
                SearchResultRow(
                    name = merged.name,
                    crumb = breadcrumbOf(primary),
                    sourceCount = merged.sources.size,
                    isFav = isFavorited(primary.path),
                    onClick = { onPlay(primary) },
                    onToggleFav = { onToggleFavorite(primary) }
                )
            }
        }
    }
}

/** 索引进行中的软提示：不挡结果，只说明“结果可能不全”。 */
@Composable
private fun IndexHint(indexState: IndexState, resultCount: Int) {
    val text = when (val s = indexState) {
        is IndexState.Running -> "索引进行中，已收录 ${s.p.indexedFiles} 个曲目，结果可能不全"
        is IndexState.PausedNoWifi -> "索引已暂停，已收录 ${s.p.indexedFiles} 个曲目，连 Wi‑Fi 后继续"
        is IndexState.PartiallyFailed -> "有 ${s.p.skippedDirs} 个目录未能收录，下次进入自动重试"
        else -> null
    } ?: return
    if (resultCount == 0 && indexState is IndexState.Idle) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapTiny)
    )
}

@Composable
private fun SearchResultRow(
    name: String,
    crumb: String,
    sourceCount: Int,
    isFav: Boolean,
    onClick: () -> Unit,
    onToggleFav: () -> Unit,
) {
    var fav by remember { mutableStateOf(isFav) }
    LaunchedEffect(isFav) { fav = isFav }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .heightIn(min = Dimens.RowMinHeight).padding(vertical = Dimens.RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Audiotrack, null, modifier = Modifier.size(Dimens.IconLarge),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.GapLarge))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val sub = if (sourceCount > 1) "$crumb · $sourceCount 个来源" else crumb
            Text(sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        IconButton(onClick = { fav = !fav; onToggleFav() }) {
            Icon(if (fav) Icons.Default.Star else Icons.Default.StarOutline,
                contentDescription = if (fav) "取消收藏" else "收藏这一曲",
                tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
```

`IndexQuery.breadcrumb` 的实际拼接由 VM 侧提供（`breadcrumbOf`），UI 不自己拆路径，保持"判定在纯函数"的分层。

- [ ] **Step 2: 接线 AudioMainScreen**

- 顶部把 `searchQuery` 的传递改成同时告知形态：`LaunchedEffect(searchQuery) { viewModel.updateSearchQuery(searchQuery, carSurface = isCarSurface()) }`
- 进入本页时拉起索引：`LaunchedEffect(Unit) { viewModel.startIndexingIfNeeded(carSurface = isCarSurface()) }`
- 第 1 页（网络 Tab）内容与搜索互斥：`searchQuery.isNotBlank() && !isCarSurface()` 时渲染 `SearchResultsList`，否则维持现有 `AudioLibraryScreen(viewModel, searchQuery)` 分支——车机档走的仍是原来那棵树，渲染结果不变
- `indexState` 取 `viewModel.indexStates.collectAsState().value` 中当前浏览源的那一项，无则 `IndexState.Idle`

- [ ] **Step 3: 编译与静态校验**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 手机档设备核对**

Run: `adb -s emulator-5554 install -r app/build/outputs/apk/debug/chinese-online-radio-1.0.<ver>-debug.apk`
再用 `python tools/ui_tap.py` 走一遍：进音频库 → 输入关键字 → 看到进度提示或结果行 → 点一行能起播。
Expected: 搜索框未新增可见控件、`SearchBarRow` 宽度与电台页一致

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/radio/chinese/ui/library/SearchResultsList.kt app/src/main/java/com/radio/chinese/ui/audio/AudioMainScreen.kt
git commit -m "feat(search): add cross-source result list with index progress hint"
```

---

## Task 11: 删除孤儿搜索实现

**Files:**
- Delete: `app/src/main/java/com/radio/chinese/service/OperaRepository.kt`

- [ ] **Step 1: 确认零引用**

Run: `Push-Location app/src/main/java; Select-String -Path (Get-ChildItem -Recurse -Include *.kt) -Pattern 'OperaRepository' -SimpleMatch`
Expected: 仅命中 `OperaRepository.kt` 自身的类声明；若还命中别的文件，**停止本任务**并把它记进交付报告，不得顺手改

- [ ] **Step 2: 删除并验证编译**

```bash
git rm app/src/main/java/com/radio/chinese/service/OperaRepository.kt
```

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`OperaCategory`/`OperaItem` 若随之报未使用，保留不动——它们可能被别的模型引用）

- [ ] **Step 3: 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git commit -m "chore: drop orphaned opera repository search path"
```

---

## Task 12: 验收（设备可做的 / 只能真机的）

**可自动化的部分（有 emulator-5554）：**

- [ ] **Step 1: 全量单测 + Release 构建**

Run: `./gradlew :app:testDebugUnitTest :app:assembleRelease`
Expected: 双绿；R8 开启后须确认新增 `IndexQuery`/`SourceIndexer` 未被混淆吞掉（`data class` 反射不参与，无 keep 规则需求）

- [ ] **Step 2: 车机档 bounds 回归**

Run: `pwsh tools/car_assert.ps1 -Serial emulator-5554`（先把界面模式手动设为“车机”）
Expected: 音频库页节点 bounds 与改造前一致；搜索框仍在同一位置与宽度

- [ ] **Step 3: SQL LIKE 转义的真机验证（唯一无法 JVM 覆盖的一环）**

在设备上把某个 WebDAV 源索引好后，依次搜索：`花木兰`、`100%`、`_test`、`%`。
Expected: 后三个不会返回“全量刷屏”，而是空结果或字面命中；把实际结果写进交付报告

- [ ] **Step 4: 索引真实耗时与不中断播放**

手机连 Wi‑Fi，播放一路 WebDAV 曲目后进入音频库触发索引，记录：开始/结束时间戳、`dumpsys audio` 是否有本包播放器、播放是否中断。
Expected: 四源 ≤3 分钟；播放不间断

**只能上车/上真机、本次不具备条件的项目——必须原样写进报告并标 BLOCKED，不得当作通过：**

- 车机（1280x720 恒横屏）抓包确认零 PROPFIND 外发流量
- 自动档 density 误判时车机是否冒出全源搜索入口（spec §5 已知限制的实际发生情况）
- 移动网络下点刷新的提示文案与实际零请求
- 索引中途杀进程后续爬不重复请求新鲜目录

- [ ] **Step 5: 提交验收记录**

把上述结果写成 `doc/verification_2026-10-18-webdav-search.md`（含每条命令与真实输出），单独提交：

```bash
git add doc/verification_2026-10-18-webdav-search.md
git commit -m "docs(verify): webdav full-source search acceptance record"
```
