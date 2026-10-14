# 音频库独立播放页（OperaPlayerScreen）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把音频库底部播放条的全部播控能力独立成全屏播放页，并附加同名 `.lrc` 歌词与专辑封面（左右滑动切换），播放条保留原样仅加跳转入口。

**Architecture:** 新导航路由 `opera_player` 的页面只读 `PlayerManager` 的 opera 状态流（单一状态源）；歌词/封面经新增的 `SidecarMediaSource` 抽象走各 `AudioBrowser` 的附属文件读取；`OperaPlayContext(sourceId, trackPath)` 让播放页找回当前源。

**Tech Stack:** Kotlin + Jetpack Compose (Material3, HorizontalPager)、Hilt、Media3 (ExoPlayer/MediaSession)、OkHttp、Room、JUnit4 + kotlinx-coroutines-test + mockk、PowerShell + uiautomator（tools/ 既有脚本体系）。

**Spec:** `docs/specs/2026-10-13-opera-player-page-design.md`（本计划实现它；两份文件同行）

## Global Constraints

- 播放状态单一来源：`OperaPlayerScreen` 只 collect `PlayerManager` 的状态流，不得新建第二份播控状态。
- 列表页零新增网络请求：sidecar（歌词/封面）读取只在全屏页触发，`OperaMiniPlayerBar` 不发任何附属文件请求。
- 播放条九项能力不缩水：可拖动滑条、后退 10 秒、上一段、播放/暂停、前进 10 秒、下一段、循环模式、停止八个控件 + 标题/时间码率信息行，一个不减。
- 尺寸只用 `ui/theme/Dimens.kt` token（只能在 composable 上下文取值）；车机档可点控件 ≥ `Dimens.TouchMin`(88dp)，手机档基线 48dp。
- 页面 Scaffold 一律 `contentWindowInsets = NestedWindowInsets`，顶栏 `windowInsets = NestedWindowInsets`（与 PlayerScreen 相同）。
- TDD：每个任务先写失败测试（RED），实现跑通（GREEN），再提交；纯函数任务无测试不开工。
- 提交规范：`keystore.properties`、`*.jks`、`local.properties` 绝不入库；多行中文提交信息写入临时文件后 `git commit -F <file>`，核实用不带管道的 `git log -1`。
- 测试命令统一走 `.\gradlew.bat`（Windows PowerShell 7，工作区根目录）。

## Review Focus

spec 隐含、但各任务常规测试不自然覆盖、且真实用户一定会撞上的失效模式（每条在对应任务里有钉住的测试）：

1. **换段后旧歌词/旧封面挂在新一段上**——行车中按下一段是高频动作，歌词必须随 fileId 即刻失效（Task 5 `flatMapLatest` 丢弃迟到响应测试）。
2. **老年歌手的 .lrc 是 GBK 编码**——123 云盘分享目录里的歌词多为老工具产物，按 UTF-8 读会得到乱码/替换符（Task 1 `decodeTextBytes` 测试）。
3. **URL 编码与鉴权参数**——123 云盘 WebDAV 的 href 含百分号编码、M3U 条目带 `?token=`，扩展名替换必须保留这些原样（Task 1 `sidecarPath` 测试）。
4. **空内容直接打开播放页**——从最近任务恢复/通知栏点击等路径可能无播放上下文，页面不得崩溃且要给出路（Task 7 空态守卫 + Task 8 脚本断言）。
5. **播放条新点击吞掉既有控件**——加了 `onClick` 后，点暂停/停止必须仍只触发原动作、不跳页（Task 8 脚本"点暂停不跳页"断言）。

---

## 文件结构总览

| 文件 | 动作 | 责任 |
|---|---|---|
| `app/src/main/java/com/radio/chinese/domain/sidecar/SidecarPath.kt` | Create | 附属文件路径推导 + 字节解码（纯函数） |
| `app/src/main/java/com/radio/chinese/domain/sidecar/LrcParser.kt` | Create | LRC 解析与当前行索引（纯函数） |
| `app/src/main/java/com/radio/chinese/domain/sidecar/SidecarMediaSource.kt` | Create | 歌词/封面读取抽象接口 |
| `app/src/main/java/com/radio/chinese/domain/browser/BrowserSidecarSource.kt` | Create | 接口的浏览器实现（含 LRU 缓存、MediaStore 路径解析） |
| `app/src/main/java/com/radio/chinese/domain/AudioBrowser.kt` | Modify | 新增 `readSidecar` 默认方法 |
| `app/src/main/java/com/radio/chinese/domain/browser/{WebDav,Local,M3u,Http}Browser.kt` | Modify | 实现 `readSidecar` |
| `app/src/main/java/com/radio/chinese/service/WebDavClient.kt` | Modify | 新增 GET 字节能力 |
| `app/src/main/java/com/radio/chinese/service/PlayerManager.kt` | Modify | `OperaPlayContext`、`operaContext`、`operaMediaMetadata`、`playOperaFile` 加 context 参数 |
| `app/src/main/java/com/radio/chinese/domain/model/YunPanOperaModels.kt` | Modify | `OperaAudioFile.sourcePath` |
| `app/src/main/java/com/radio/chinese/ui/library/AudioLibraryViewModel.kt` | Modify | 点播写入 context、构造点填 sourcePath |
| `app/src/main/java/com/radio/chinese/ui/audio/AudioLocalTab.kt` | Modify | 本地点播写入 `OperaPlayContext(0L, path)` |
| `app/src/main/java/com/radio/chinese/ui/player/OperaPlayerViewModel.kt` | Create | 歌词/封面加载状态机 |
| `app/src/main/java/com/radio/chinese/ui/player/OperaPlayerScreen.kt` | Create | 全屏播放页 UI |
| `app/src/main/java/com/radio/chinese/ui/common/MiniPlayerBar.kt` | Modify | 标题行可选 `onClick` + 展开图标 + 滑条 contentDescription |
| `app/src/main/java/com/radio/chinese/ui/audio/AudioMainScreen.kt` | Modify | 透传 `onOpenPlayer` |
| `app/src/main/java/com/radio/chinese/ui/navigation/Screen.kt` | Modify | `OperaPlayer` 路由 |
| `app/src/main/java/com/radio/chinese/ui/MainScreen.kt` | Modify | 注册路由 + 注入跳转 |
| `app/src/main/java/com/radio/chinese/ui/theme/Dimens.kt` | Modify | 新增 `PlayerSideWidth` token |
| `app/build.gradle.kts` + `gradle/libs.versions.toml` | Modify | 测试依赖（版本对齐见 Task 5） |
| `app/src/test/java/com/radio/chinese/SidecarPathTest.kt` | Create | Task 1 测试 |
| `app/src/test/java/com/radio/chinese/LrcParserTest.kt` | Create | Task 2 测试 |
| `app/src/test/java/com/radio/chinese/NextContextTest.kt` | Create | Task 4 测试 |
| `app/src/test/java/com/radio/chinese/OperaPlayerViewModelTest.kt` | Create | Task 5 测试 |
| `tools/opera_player_check.ps1` | Create | Task 8 UI 自动化 |

> spec §3.4 写的是 `readTextFile`/`readBinaryFile` 两个接口方法；本计划合并为一个
> `readSidecar(path): Result<ByteArray>` + 独立纯函数 `decodeTextBytes`（编码策略本就一致，
> 少一个接口方法）。行为与 spec 等价，Task 1/5 的测试覆盖不减少。

---

### Task 1: sidecarPath 与 GBK 容错解码（纯函数）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/domain/sidecar/SidecarPath.kt`
- Test: `app/src/test/java/com/radio/chinese/SidecarPathTest.kt`

**Interfaces:**
- Consumes: 无（纯 JDK）
- Produces: `fun sidecarPath(base: String, ext: String): String`；`fun decodeTextBytes(bytes: ByteArray): String`（package `com.radio.chinese.domain.sidecar`，Task 5/8 使用）

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/radio/chinese/SidecarPathTest.kt`:

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.sidecar.decodeTextBytes
import com.radio.chinese.domain.sidecar.sidecarPath
import org.junit.Assert.assertEquals
import org.junit.Test

class SidecarPathTest {
    @Test fun replacesAudioExtension() {
        assertEquals("/music/henan/a.lrc", sidecarPath("/music/henan/a.mp3", "lrc"))
    }
    @Test fun keepsPercentEncodingAndQuery() {
        assertEquals("https://wd/dir/%E8%B1%AB.lrc?t=1",
            sidecarPath("https://wd/dir/%E8%B1%AB.mp3?t=1", "lrc"))
    }
    @Test fun appendsWhenNoExtension() {
        assertEquals("/d/song.lrc", sidecarPath("/d/song", "lrc"))
    }
    @Test fun replacesCaseInsensitivePosition() {
        // 原扩展名多大写都整段替换为调用方给的 ext
        assertEquals("/d/a.lrc", sidecarPath("/d/a.MP3", "lrc"))
        assertEquals("/d/a.LRC", sidecarPath("/d/a.mp3", "LRC"))
    }
    @Test fun emptyInputsReturnBase() {
        assertEquals("/d/a.mp3", sidecarPath("/d/a.mp3", ""))
        assertEquals("", sidecarPath("", "lrc"))
    }

    @Test fun decodesUtf8Normally() {
        val bytes = "第一行".toByteArray(charsets = Charsets.UTF_8)
        assertEquals("第一行", decodeTextBytes(bytes))
    }
    @Test fun stripsUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "豫剧".toByteArray(Charsets.UTF_8)
        assertEquals("豫剧", decodeTextBytes(bytes))
    }
    @Test fun fallsBackToGb18030WhenNotUtf8() {
        // 老工具产出的 GBK 歌词：按 UTF-8 解会得到替换符，必须回退 GB18030
        val gbk = "河南豫剧院".toByteArray(Charset.forName("GB18030"))
        assertEquals("河南豫剧院", decodeTextBytes(gbk))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.SidecarPathTest"`
Expected: 编译失败（unresolved reference: sidecarPath）

- [ ] **Step 3: 最小实现**

`app/src/main/java/com/radio/chinese/domain/sidecar/SidecarPath.kt`:

```kotlin
package com.radio.chinese.domain.sidecar

import java.nio.charset.Charset

/**
 * 由音频路径/URL 推导同名附属文件路径：只替换最后一段的尾扩展名，
 * 查询参数与百分号编码原样保留（123 云盘 href 带 %E8%B1%AB、M3U 条目带 ?token=）。
 * ext 不含点；base 或 ext 为空时原样返回，调用方无需再做防御。
 */
fun sidecarPath(base: String, ext: String): String {
    if (base.isEmpty() || ext.isEmpty()) return base
    val q = base.indexOf('?')
    val pathPart = if (q >= 0) base.substring(0, q) else base
    val tail = if (q >= 0) base.substring(q) else ""
    val seg = pathPart.substringAfterLast('/')
    val dot = seg.lastIndexOf('.')
    val newPath = if (dot > 0) {
        pathPart.dropLast(seg.length - dot) + "." + ext
    } else {
        "$pathPart.$ext"
    }
    return newPath + tail
}

/**
 * 歌词字节解码：UTF-8（含去 BOM）优先，出现替换符说明多半是老工具产出的 GBK 文件，
 * 回退 GB18030（Android 与 JVM 都带该 charset）。
 */
fun decodeTextBytes(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    val utf8 = String(bytes, Charsets.UTF_8)
    return if (utf8.contains('\uFFFD')) String(bytes, Charset.forName("GB18030")) else utf8
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.SidecarPathTest"`
Expected: 8 个测试 PASS

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/radio/chinese/domain/sidecar/SidecarPath.kt app/src/test/java/com/radio/chinese/SidecarPathTest.kt
git commit -m "feat(library): sidecarPath pure derivation + GB18030-tolerant lyric decoder, TDD"
```

---

### Task 2: LrcParser（纯函数）

**Files:**
- Create: `app/src/main/java/com/radio/chinese/domain/sidecar/LrcParser.kt`
- Test: `app/src/test/java/com/radio/chinese/LrcParserTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `data class LrcLine(val timeMs: Long, val text: String)`；`object LrcParser { fun parse(text: String): List<LrcLine>; fun indexAt(lines: List<LrcLine>, posMs: Long): Int }`（Task 5/7 使用）

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/radio/chinese/LrcParserTest.kt`:

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.sidecar.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test fun parsesSimpleLines() {
        val r = LrcParser.parse("[00:01.00]第一句\n[00:05.50]第二句")
        assertEquals(2, r.size)
        assertEquals(1_000L, r[0].timeMs)
        assertEquals(5_500L, r[1].timeMs)
        assertEquals("第一句", r[0].text)
    }
    @Test fun multipleTimestampsShareOneLine() {
        val r = LrcParser.parse("[00:10.00][01:30.00]副歌")
        assertEquals(2, r.size)
        assertEquals(90_000L, r[1].timeMs)
        assertEquals("副歌", r[1].text)
    }
    @Test fun fractionDigitPrecision() {
        // 1 位=十分之一秒、2 位=百分之一、3 位=毫秒；[mm:ss] 无小数按 0
        assertEquals(1_200L, LrcParser.parse("[00:01.2]x")[0].timeMs)
        assertEquals(1_250L, LrcParser.parse("[00:01.25]x")[0].timeMs)
        assertEquals(1_256L, LrcParser.parse("[00:01.256]x")[0].timeMs)
        assertEquals(61_000L, LrcParser.parse("[01:01]x")[0].timeMs)
    }
    @Test fun dotSeparatorAccepted() {
        assertEquals(65_000L, LrcParser.parse("[01:05.00]x")[0].timeMs)
        assertEquals(65_000L, LrcParser.parse("[01:05:00]x")[0].timeMs)
    }
    @Test fun metadataAndGarbageSkippedWithoutAbort() {
        val r = LrcParser.parse(
            "[ti:豫剧 花木兰]\n[ar:河南豫剧院]\n纯文本行没有时间戳\n[00:03.00]有效句\n[abc]\n[99:99.99]越界也算合法时间"
        )
        assertEquals(2, r.size)
        assertEquals(listOf("有效句", "越界也算合法时间"), r.map { it.text })
    }
    @Test fun emptyTextTimestampsDropped() {
        assertEquals(0, LrcParser.parse("[00:01.00]   \n[00:02.00]").size)
    }
    @Test fun sortedByTimeAcrossLines() {
        val r = LrcParser.parse("[00:30.00]后\n[00:10.00]先")
        assertEquals(listOf("先", "后"), r.map { it.text })
    }
    @Test fun indexAtFollowsPosition() {
        val lines = LrcParser.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c")
        assertEquals(-1, LrcParser.indexAt(lines, 500))
        assertEquals(0, LrcParser.indexAt(lines, 1_000))
        assertEquals(1, LrcParser.indexAt(lines, 6_000))
        assertEquals(2, LrcParser.indexAt(lines, 60_000))
        assertEquals(-1, LrcParser.indexAt(emptyList(), 1_000))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.LrcParserTest"`
Expected: 编译失败（unresolved reference: LrcParser）

- [ ] **Step 3: 最小实现**

`app/src/main/java/com/radio/chinese/domain/sidecar/LrcParser.kt`:

```kotlin
package com.radio.chinese.domain.sidecar

data class LrcLine(val timeMs: Long, val text: String)

/**
 * 标准 LRC 解析。只认 [mm:ss(.frac)] 时间标签：一行可挂多个时间戳；
 * [ti:] 之类元数据标签、无时间戳纯文本、坏行一律跳过（不中断整文件）；
 * 结果按时间升序。`[99:99.99]` 这类越界值照收——它只是永远到不了的一句词，无害。
 */
object LrcParser {
    private val TIME = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val LINE = Regex("""^((?:\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?\])+)\s*(.*)$""")

    fun parse(text: String): List<LrcLine> {
        val out = mutableListOf<LrcLine>()
        for (raw in text.lineSequence()) {
            val m = LINE.matchEntire(raw.trim()) ?: continue
            val body = m.groupValues[2].trim()
            if (body.isEmpty()) continue
            for (tm in TIME.findAll(m.groupValues[1])) {
                val min = tm.groupValues[1].toLongOrNull() ?: continue
                val sec = tm.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = tm.groupValues[3]
                val fracMs = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                out += LrcLine(min * 60_000 + sec * 1_000 + fracMs, body)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** 播放位置对应的行号；未到第一行或空列表返回 -1。lines 必须已按时间排序。 */
    fun indexAt(lines: List<LrcLine>, posMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) idx = i else break
        }
        return idx
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.LrcParserTest"`
Expected: 9 个测试 PASS

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/radio/chinese/domain/sidecar/LrcParser.kt app/src/test/java/com/radio/chinese/LrcParserTest.kt
git commit -m "feat(library): LrcParser with multi-timestamp, precision and garbage-line tolerance, TDD"
```

---

### Task 3: AudioBrowser.readSidecar + 四浏览器实现 + WebDavClient GET

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/domain/AudioBrowser.kt`（接口，11 行）
- Modify: `app/src/main/java/com/radio/chinese/service/WebDavClient.kt`（`client`/`authHeader()`/`normalizeUrl()` 已有，约 184 行 getFileUrl 附近加方法）
- Modify: `app/src/main/java/com/radio/chinese/domain/browser/WebDavBrowser.kt`（48 行）
- Modify: `app/src/main/java/com/radio/chinese/domain/browser/LocalBrowser.kt`（131 行）
- Modify: `app/src/main/java/com/radio/chinese/domain/browser/M3uBrowser.kt`（66 行）
- Modify: `app/src/main/java/com/radio/chinese/domain/browser/HttpBrowser.kt`（39 行）

**Interfaces:**
- Consumes: `WebDavClient.getBytes(path): Result<ByteArray>`（本任务产出）
- Produces: `AudioBrowser.readSidecar(path: String): Result<ByteArray>`（Task 5 的 BrowserSidecarSource 调用）

- [ ] **Step 1: WebDavClient 加 GET 字节**

在 `getFileUrl` 方法后追加（`client` 是该类既有的 lazy OkHttpClient 属性；404 与异常都进 `Result.failure`，**不抛**）：

```kotlin
/** GET 指定路径的附属文件字节（歌词/封面）。404、鉴权失败、网络异常统一走 Result.failure。 */
suspend fun getBytes(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url(normalizeUrl(path))
            .header("Authorization", authHeader())
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body
            if (!resp.isSuccessful || body == null) Result.failure(Exception("HTTP ${resp.code}"))
            else Result.success(body.bytes())
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

- [ ] **Step 2: 接口默认方法**

`AudioBrowser.kt` 改为：

```kotlin
interface AudioBrowser {
    suspend fun listChildren(path: String): Result<List<AudioTrack>>
    fun resolveUri(track: AudioTrack): String
    suspend fun testConnection(): Result<Unit>
    fun isStreamSource(): Boolean = false

    /**
     * 读取任意附属文件字节（同名 .lrc/.jpg 等），path 为源内路径或完整 URL。
     * 默认不支持：新增源类型不实现也编译得过，歌词/封面自动降级为"没有"。
     */
    suspend fun readSidecar(path: String): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("该来源不支持附属文件读取"))
}
```

- [ ] **Step 3: 四个浏览器实现**

`WebDavBrowser.kt`（复用 applyCredentials）:

```kotlin
override suspend fun readSidecar(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
    applyCredentials()
    webDavClient.getBytes(path)
}
```

`LocalBrowser.kt`（`path` 可能是文件系统路径或 content:// URI——MediaStore 曲目只给内容 URI，
附属文件不在媒体索引里，必须先回查 DATA 列换成文件系统路径再直读。canRead 失败即 failure，
作用域存储在 Android 11+ 可能拒绝，本地源的 sidecar 支持以真机裁决为准）:

```kotlin
override suspend fun readSidecar(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
    try {
        val real = if (path.startsWith("content://")) {
            queryDataColumn(android.net.Uri.parse(path))
                ?: return@withContext Result.failure(Exception("MediaStore 无文件路径"))
        } else path
        val file = java.io.File(real)
        if (!file.canRead()) Result.failure(Exception("不可读: ${file.name}"))
        else Result.success(file.readBytes())
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private fun queryDataColumn(uri: android.net.Uri): String? =
    context.contentResolver.query(
        uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null
    )?.use { c ->
        if (c.moveToFirst()) {
            val i = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
            if (i >= 0) c.getString(i) else null
        } else null
    }
```

`M3uBrowser.kt` 与 `HttpBrowser.kt` 各加同一段（`okHttpClient` 两家都已持有）：

```kotlin
override suspend fun readSidecar(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
    try {
        okHttpClient.newCall(Request.Builder().url(path).build()).execute().use { r ->
            val b = r.body
            if (r.isSuccessful && b != null) Result.success(b.bytes())
            else Result.failure(Exception("HTTP ${r.code}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

- [ ] **Step 4: 编译 + 全量单测回归**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL；既有 6 个测试类 + 本计划新增测试全部 PASS

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/radio/chinese/domain/AudioBrowser.kt app/src/main/java/com/radio/chinese/service/WebDavClient.kt app/src/main/java/com/radio/chinese/domain/browser/
git commit -m "feat(browser): AudioBrowser.readSidecar default + WebDAV/Local/M3U/HTTP implementations"
```

---

### Task 4: PlayerManager 播放上下文（operaContext / operaMediaMetadata / sourcePath）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/service/PlayerManager.kt`（流声明区 ~L80-104、playOperaFile L394-426、setupListeners L131-136、stopOpera L570-578）
- Modify: `app/src/main/java/com/radio/chinese/domain/model/YunPanOperaModels.kt`（OperaAudioFile L69-78）
- Modify: `app/src/main/java/com/radio/chinese/ui/library/AudioLibraryViewModel.kt`（playTrack L236-268、playTrackWithSource L337-350）
- Modify: `app/src/main/java/com/radio/chinese/ui/audio/AudioLocalTab.kt`（播放分支 L116-128）
- Modify: `app/src/main/java/com/radio/chinese/service/WebDavClient.kt`（toAudioFiles L287 构造点）
- Test: `app/src/test/java/com/radio/chinese/NextContextTest.kt`

**Interfaces:**
- Consumes: Task 无依赖
- Produces:
  - `data class OperaPlayContext(val sourceId: Long, val trackPath: String)`（`com.radio.chinese.service`，与 `OperaRepeatMode` 同文件并列）
  - `PlayerManager.operaContext: StateFlow<OperaPlayContext?>`、`PlayerManager.operaMediaMetadata: StateFlow<MediaMetadata?>`
  - `playOperaFile(file, playlist, localPath = null, context: OperaPlayContext? = null)`
  - `internal fun nextOperaContext(old: OperaPlayContext?, file: OperaAudioFile, explicit: OperaPlayContext?): OperaPlayContext?`
  - `OperaAudioFile.sourcePath: String? = null`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/radio/chinese/NextContextTest.kt`：

```kotlin
package com.radio.chinese

import com.radio.chinese.domain.model.OperaAudioFile
import com.radio.chinese.service.OperaPlayContext
import com.radio.chinese.service.nextOperaContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextContextTest {
    private fun file(path: String?, url: String?) = OperaAudioFile(
        fileId = 1L, name = "n", size = 0, categoryName = "c", operaName = "",
        downloadUrl = url, sourcePath = path
    )

    @Test fun explicitContextWins() {
        val old = OperaPlayContext(7L, "/a/old.mp3")
        val ctx = OperaPlayContext(9L, "/a/new.mp3")
        assertEquals(ctx, nextOperaContext(old, file("/x.mp3", null), ctx))
    }
    @Test fun nextTrackKeepsSourceIdAndSwapsPath() {
        val old = OperaPlayContext(7L, "/dav/a1.mp3")
        val next = file("/dav/a2.mp3", "https://wd/dav/a2.mp3")
        assertEquals(OperaPlayContext(7L, "/dav/a2.mp3"), nextOperaContext(old, next, null))
    }
    @Test fun fallsBackToDownloadUrlWhenNoSourcePath() {
        val old = OperaPlayContext(7L, "/dav/a1.mp3")
        val next = file(null, "https://wd/dav/a2.mp3?t=1")
        assertEquals(OperaPlayContext(7L, "https://wd/dav/a2.mp3?t=1"), nextOperaContext(old, next, null))
    }
    @Test fun nullWhenNoOldAndNoExplicit() {
        assertNull(nextOperaContext(null, file("/a.mp3", "u"), null))
    }
    @Test fun keepsOldWhenFileCarriesNoPathAtAll() {
        val old = OperaPlayContext(7L, "/dav/a1.mp3")
        assertEquals(old, nextOperaContext(old, file(null, null), null))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.NextContextTest"`
Expected: 编译失败（unresolved reference: OperaPlayContext / nextOperaContext / sourcePath）

- [ ] **Step 3: 实现**

`YunPanOperaModels.kt`——`OperaAudioFile` 增加尾字段（所有既有构造点用命名参数，加默认值不破坏编译）：

```kotlin
data class OperaAudioFile(
    val fileId: Long,
    val name: String,
    val size: Long,
    val categoryName: String,
    val operaName: String,
    val downloadUrl: String? = null,
    val etag: String? = null,
    val s3KeyFlag: String? = null,
    /** 源内原始路径（WebDAV href / 本地路径 / M3U 条目 URL），歌词封面等附属文件按它推导 */
    val sourcePath: String? = null
)
```

`PlayerManager.kt`——文件底部与 `OperaRepeatMode` 并列处加类型与纯函数；属性区（`_operaRepeatMode` 附近）加两个流：

```kotlin
/** 戏曲播放上下文：歌词/封面等附属文件要靠"哪个源的哪个路径"找回归属。sourceId=0 表示本地直连（MediaStore/文件路径）。 */
data class OperaPlayContext(val sourceId: Long, val trackPath: String)

/** 纯函数便于单测：显式 context 优先；内部切段保留 sourceId 只换 trackPath；无路可推时保持旧值。 */
internal fun nextOperaContext(
    old: OperaPlayContext?,
    file: OperaAudioFile,
    explicit: OperaPlayContext?
): OperaPlayContext? = explicit ?: old?.let { o ->
    (file.sourcePath ?: file.downloadUrl)?.let { o.copy(trackPath = it) } ?: o
}
```

```kotlin
private val _operaContext = MutableStateFlow<OperaPlayContext?>(null)
val operaContext = _operaContext.asStateFlow()

private val _operaMediaMetadata = MutableStateFlow<MediaMetadata?>(null)
val operaMediaMetadata: StateFlow<MediaMetadata?> = _operaMediaMetadata
```

`playOperaFile` 签名加 `context: OperaPlayContext? = null`，在 `_operaFile.value = file` 之后插入：

```kotlin
_operaContext.value = nextOperaContext(_operaContext.value, file, context)
android.util.Log.d("PlayerManager", "OperaContext sourceId=${_operaContext.value?.sourceId} path=${_operaContext.value?.trackPath}")
```

`setupListeners()` 的 `Player.Listener` 里加（内嵌封面取料；电台模式不写，避免旧 metadata 串进戏曲页）：

```kotlin
override fun onMediaMetadataChanged(md: MediaMetadata) {
    if (_isOperaMode.value) _operaMediaMetadata.value = md
}
```

`stopOpera()` 里 `_operaIndex.value = -1` 之后加：

```kotlin
_operaContext.value = null
_operaMediaMetadata.value = null
```

- [ ] **Step 4: 各构造点与调用点接线**

`AudioLibraryViewModel.playTrack`：两处 `OperaAudioFile(...)` 各加 `sourcePath = item.path` / `sourcePath = track.path`；`playOperaFile(file, playlist)` 改为 `playOperaFile(file, playlist, context = OperaPlayContext(source.id, track.path))`。
`AudioLibraryViewModel.playTrackWithSource`：构造加 `sourcePath = track.path`，调用加 `context = OperaPlayContext(source.id, track.path)`。
`AudioLocalTab` 播放分支：playlist 构造加 `sourcePath = ai.path`，`playOperaFile(playlist[index], playlist)` 改为 `playOperaFile(playlist[index], playlist, context = com.radio.chinese.service.OperaPlayContext(0L, item.path))`。
`WebDavClient.toAudioFiles`：构造加 `sourcePath = it.href`。

- [ ] **Step 5: 跑测试 + 编译**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.NextContextTest" :app:assembleDebug`
Expected: 5 个测试 PASS + BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/com/radio/chinese/service/PlayerManager.kt app/src/main/java/com/radio/chinese/domain/model/YunPanOperaModels.kt app/src/main/java/com/radio/chinese/ui/library/AudioLibraryViewModel.kt app/src/main/java/com/radio/chinese/ui/audio/AudioLocalTab.kt app/src/main/java/com/radio/chinese/service/WebDavClient.kt app/src/test/java/com/radio/chinese/NextContextTest.kt
git commit -m "feat(player): OperaPlayContext + operaMediaMetadata flows, sourcePath wiring on all play paths"
```


---

### Task 5: SidecarMediaSource + OperaPlayerViewModel（状态机 TDD）

**Files:**
- Modify: `gradle/libs.versions.toml`（读入既有 coroutines 版本）
- Modify: `app/build.gradle.kts`（测试依赖）
- Create: `app/src/main/java/com/radio/chinese/domain/sidecar/SidecarMediaSource.kt`
- Create: `app/src/main/java/com/radio/chinese/domain/browser/BrowserSidecarSource.kt`（含 Hilt @Binds）
- Modify: `app/src/main/java/com/radio/chinese/domain/browser/LocalBrowser.kt`（Task 3 的 `queryDataColumn` 改名 `internal fun resolveDataPath(path: String): String?`，入参收 content:// 也收文件系统路径，文件系统路径原样返回）
- Create: `app/src/main/java/com/radio/chinese/ui/player/OperaPlayerViewModel.kt`
- Test: `app/src/test/java/com/radio/chinese/OperaPlayerViewModelTest.kt`

**Interfaces:**
- Consumes: `sidecarPath`/`decodeTextBytes`（Task 1）、`LrcParser`（Task 2）、`AudioBrowser.readSidecar`（Task 3）、`OperaPlayContext`/`operaMediaMetadata`（Task 4）
- Produces:
  - `interface SidecarMediaSource { suspend fun readLyrics(ctx: OperaPlayContext): Result<String>; suspend fun readImage(ctx: OperaPlayContext): Result<ByteArray> }`
  - `OperaPlayerViewModel.uiState: StateFlow<OperaPlayerUiState>`，其中 `OperaPlayerUiState(lyricsStatus: LyricsStatus, lines: List<LrcLine>, currentIndex: Int, coverBytes: ByteArray?, coverSource: CoverSource)`；`enum LyricsStatus { NONE, LOADING, READY, MISSING }`；`enum CoverSource { NONE, EMBEDDED, SIDECAR }`；`fun seekToLine(index: Int)`（Task 7 的 Screen 使用）

- [ ] **Step 1: 测试依赖**

打开 `gradle/libs.versions.toml`，找到既有 `kotlinx-coroutines-android`（或同类）的版本号 X.Y.Z；在 `app/build.gradle.kts` 的 `testImplementation("junit:junit:4.13.2")` 下一行加（**版本抄 libs.versions.toml 里的实际值，保持一致**）：

```kotlin
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:<抄 libs.versions.toml 的 coroutines 版本>")
testImplementation("io.mockk:mockk:1.13.13")
```

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 依赖解析成功、既有测试照旧全绿

- [ ] **Step 2: 写失败测试**

`app/src/test/java/com/radio/chinese/OperaPlayerViewModelTest.kt`:

```kotlin
package com.radio.chinese

import androidx.media3.common.MediaMetadata
import com.radio.chinese.domain.sidecar.SidecarMediaSource
import com.radio.chinese.domain.model.OperaAudioFile
import com.radio.chinese.service.OperaPlayContext
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.player.CoverSource
import com.radio.chinese.ui.player.LyricsStatus
import com.radio.chinese.ui.player.OperaPlayerViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.runs.Runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private class FakeSidecar(
    var lyrics: suspend () -> Result<String> = { Result.failure(Exception("NOT_FOUND")) },
    var image: suspend () -> Result<ByteArray> = { Result.failure(Exception("NOT_FOUND")) }
) : SidecarMediaSource {
    var imageCalls = 0
    override suspend fun readLyrics(ctx: OperaPlayContext) = lyrics()
    override suspend fun readImage(ctx: OperaPlayContext) = image.also { imageCalls++ }()
}

class OperaPlayerViewModelTest {
    private val fileFlow = MutableStateFlow<OperaAudioFile?>(null)
    private val ctxFlow = MutableStateFlow<OperaPlayContext?>(null)
    private val posFlow = MutableStateFlow(0L)
    private val metaFlow = MutableStateFlow<MediaMetadata?>(null)
    private lateinit var pm: PlayerManager

    @Before fun setup() {
        pm = mockk()
        every { pm.operaFile } returns fileFlow
        every { pm.operaContext } returns ctxFlow
        every { pm.operaPosition } returns posFlow
        every { pm.operaMediaMetadata } returns metaFlow
        every { pm.seekOperaTo(any()) } just Runs
    }

    @After fun teardown() { Dispatchers.resetMain() }

    private fun opera(id: Long) = OperaAudioFile(
        fileId = id, name = "唱段$id", size = 0, categoryName = "河南豫剧", operaName = ""
    )
    private val ctx1 = OperaPlayContext(7L, "/dav/a1.mp3")
    private val twoLines = "[00:01.00]第一句\n[00:05.00]第二句"

    @Test fun lyricsReadyAndIndexFollowsPosition() = runTest {
        val main = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val vm = OperaPlayerViewModel(pm, FakeSidecar(lyrics = { Result.success(twoLines) }))
        fileFlow.value = opera(1L); ctxFlow.value = ctx1
        runCurrent()
        assertEquals(LyricsStatus.READY, vm.uiState.value.lyricsStatus)
        assertEquals(2, vm.uiState.value.lines.size)
        assertEquals(-1, vm.uiState.value.currentIndex)
        posFlow.value = 6_000L
        runCurrent()
        assertEquals(1, vm.uiState.value.currentIndex)
        vm.seekToLine(0) // 只验证不越界；seek 动作打在 mock pm 上
    }

    @Test fun missingLyricsDegradeToMissing() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = OperaPlayerViewModel(pm, FakeSidecar())
        fileFlow.value = opera(1L); ctxFlow.value = ctx1
        runCurrent()
        assertEquals(LyricsStatus.MISSING, vm.uiState.value.lyricsStatus)
        assertEquals(0, vm.uiState.value.lines.size)
    }

    @Test fun timeoutTreatedAsMissing() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val vm = OperaPlayerViewModel(pm, FakeSidecar(lyrics = { delay(60_000); Result.success(twoLines) }))
        fileFlow.value = opera(1L); ctxFlow.value = ctx1
        main.scheduler.advanceUntilIdle() // 触发内部 8s withTimeout
        assertEquals(LyricsStatus.MISSING, vm.uiState.value.lyricsStatus)
    }

    @Test fun switchingTrackDropsLateResponseOfPrevious() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val slow1 = { delay(9_000); Result.success("[00:01.00]旧段歌词") }
        var calls = 0
        val fast2 = { calls++; Result.success(twoLines) }
        val vm = OperaPlayerViewModel(pm, FakeSidecar(lyrics = { if (calls == 0 && ctxFlow.value == ctx1) slow1() else fast2() }))
        fileFlow.value = opera(1L); ctxFlow.value = ctx1
        main.scheduler.advanceTimeBy(1_000); runCurrent()
        // 换段：ctx1 的慢响应 8 秒后才回来，必须被 flatMapLatest 丢弃
        val ctx2 = OperaPlayContext(7L, "/dav/a2.mp3")
        fileFlow.value = opera(2L); ctxFlow.value = ctx2
        main.scheduler.advanceUntilIdle()
        assertEquals(LyricsStatus.READY, vm.uiState.value.lyricsStatus)
        assertEquals("第一句", vm.uiState.value.lines[0].text) // 是第二段的词，不是"旧段歌词"
    }

    @Test fun embeddedArtworkWinsAndSidecarImageNotFetched() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val md = mockk<MediaMetadata>()
        every { md.artworkData } returns byteArrayOf(1, 2, 3)
        metaFlow.value = md
        val sidecar = FakeSidecar(
            lyrics = { Result.failure(Exception("NOT_FOUND")) },
            image = { Result.success(byteArrayOf(9)) }
        )
        val vm = OperaPlayerViewModel(pm, sidecar)
        fileFlow.value = opera(1L); ctxFlow.value = ctx1
        runCurrent()
        assertEquals(CoverSource.EMBEDDED, vm.uiState.value.coverSource)
        assertEquals(0, sidecar.imageCalls)
    }

    @Test fun noContextClearsEverything() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = OperaPlayerViewModel(pm, FakeSidecar(lyrics = { Result.success(twoLines) }))
        fileFlow.value = opera(1L); ctxFlow.value = ctx1
        runCurrent()
        fileFlow.value = null; ctxFlow.value = null
        runCurrent()
        assertEquals(LyricsStatus.NONE, vm.uiState.value.lyricsStatus)
        assertEquals(0, vm.uiState.value.lines.size)
        assertEquals(CoverSource.NONE, vm.uiState.value.coverSource)
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.OperaPlayerViewModelTest"`
Expected: 编译失败（unresolved reference: OperaPlayerViewModel）

- [ ] **Step 4: 实现接口与浏览器侧数据源**

`domain/sidecar/SidecarMediaSource.kt`:

```kotlin
package com.radio.chinese.domain.sidecar

import com.radio.chinese.service.OperaPlayContext

/** 播放页附属内容（歌词/封面）的取料抽象，VM 只依赖它，单测用 Fake 顶替。 */
interface SidecarMediaSource {
    suspend fun readLyrics(ctx: OperaPlayContext): Result<String>
    suspend fun readImage(ctx: OperaPlayContext): Result<ByteArray>
}
```

`domain/browser/BrowserSidecarSource.kt`:

```kotlin
package com.radio.chinese.domain.browser

import android.content.Context
import com.radio.chinese.data.repository.AudioLibraryRepository
import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.SourceType
import com.radio.chinese.domain.sidecar.SidecarMediaSource
import com.radio.chinese.domain.sidecar.decodeTextBytes
import com.radio.chinese.domain.sidecar.sidecarPath
import com.radio.chinese.service.OperaPlayContext
import com.radio.chinese.service.WebDavClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 走各源 AudioBrowser.readSidecar 取附属文件：按候选扩展名逐个试，
 * 命中即返回（先 .lrc 后 .LRC；图片 jpg/png 含大写）。字节按派生路径做小容量 LRU 缓存，
 * 换段回来不重复下载。sourceId=0 是本地直连约定：先经 MediaStore 把
 * content:// 解析成文件系统路径，再在文件系统上找同名附属文件。
 */
@Singleton
class BrowserSidecarSource @Inject constructor(
    private val repository: AudioLibraryRepository,
    private val webDavClient: WebDavClient,
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val appContext: Context
) : SidecarMediaSource {

    private val byteCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Result<ByteArray>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result<ByteArray>>?) = size > 64
        }
    )

    override suspend fun readLyrics(ctx: OperaPlayContext): Result<String> =
        readWithExtensions(ctx, listOf("lrc", "LRC")) { Result.success(decodeTextBytes(it)) }

    override suspend fun readImage(ctx: OperaPlayContext): Result<ByteArray> =
        readWithExtensions(ctx, listOf("jpg", "JPG", "png", "PNG")) { Result.success(it) }

    private suspend fun <T> readWithExtensions(
        ctx: OperaPlayContext,
        exts: List<String>,
        map: (ByteArray) -> Result<T>
    ): Result<T> {
        val browser = browserFor(ctx)
        val base = if (ctx.sourceId == 0L) LocalBrowser(appContext).resolveDataPath(ctx.trackPath) else ctx.trackPath
        for (ext in exts) {
            val p = sidecarPath(base, ext)
            val r = byteCache[p] ?: browser.readSidecar(p).also { byteCache[p] = it }
            r.getOrNull()?.let { return map(it) }
        }
        return Result.failure(Exception("NOT_FOUND"))
    }

    private suspend fun browserFor(ctx: OperaPlayContext): AudioBrowser {
        val source = if (ctx.sourceId == 0L) null else repository.getSource(ctx.sourceId)
        return when (source?.type) {
            SourceType.WEBDAV -> WebDavBrowser(webDavClient, source.url, source.username, source.password)
            SourceType.M3U -> M3uBrowser(okHttpClient)
            SourceType.HTTP -> HttpBrowser(okHttpClient)
            else -> LocalBrowser(appContext)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SidecarBinder {
    @Binds
    abstract fun bindSidecarMediaSource(impl: BrowserSidecarSource): SidecarMediaSource
}
```

`LocalBrowser.kt`：把 Task 3 的 `private fun queryDataColumn(uri)` 调整为：

```kotlin
/** content:// → 文件系统路径；已是文件系统路径则原样返回。解析不出返回 null。 */
internal fun resolveDataPath(path: String): String =
    if (path.startsWith("content://")) queryDataColumn(android.net.Uri.parse(path)) ?: path else path

private fun queryDataColumn(uri: android.net.Uri): String? = // 保持 Task 3 的实现，仅改 private 签名衔接
```

- [ ] **Step 5: 实现 ViewModel**

`ui/player/OperaPlayerViewModel.kt`:

```kotlin
package com.radio.chinese.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.domain.sidecar.LrcParser
import com.radio.chinese.domain.sidecar.LrcLine
import com.radio.chinese.domain.sidecar.SidecarMediaSource
import com.radio.chinese.service.OperaPlayContext
import com.radio.chinese.service.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

enum class LyricsStatus { NONE, LOADING, READY, MISSING }
enum class CoverSource { NONE, EMBEDDED, SIDECAR }

data class OperaPlayerUiState(
    val lyricsStatus: LyricsStatus = LyricsStatus.NONE,
    val lines: List<LrcLine> = emptyList(),
    val currentIndex: Int = -1,
    val coverBytes: ByteArray? = null,
    val coverSource: CoverSource = CoverSource.NONE
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest：换段丢弃迟到响应的核心机制
class OperaPlayerViewModel @Inject constructor(
    private val playerManager: PlayerManager,
    private val sidecar: SidecarMediaSource
) : ViewModel() {

    private data class SidecarEvent(
        val clear: Boolean = false,
        val lyricsStatus: LyricsStatus? = null,
        val lines: List<LrcLine> = emptyList(),
        val cover: ByteArray? = null
    )

    private val _uiState = MutableStateFlow(OperaPlayerUiState())
    val uiState: StateFlow<OperaPlayerUiState> = _uiState.asStateFlow()

    /** (fileId, context) 一个键同时决定取料目标与旧响应失效——换段即刻清场 */
    private val keys = combine(playerManager.operaFile, playerManager.operaContext) { file, ctx ->
        (file?.fileId ?: -1L) to ctx
    }.distinctUntilChanged()

    init {
        viewModelScope.launch {
            keys.flatMapLatest { (fileId, ctx) ->
                if (fileId == -1L || ctx == null) flowOf(SidecarEvent(clear = true))
                else loadSidecars(ctx)
            }.collect { e ->
                if (e.clear) {
                    _uiState.value = OperaPlayerUiState()
                    return@collect
                }
                if (e.lyricsStatus != null) _uiState.update {
                    it.copy(
                        lyricsStatus = e.lyricsStatus,
                        lines = if (e.lyricsStatus == LyricsStatus.LOADING) it.lines else e.lines,
                        currentIndex = if (e.lyricsStatus == LyricsStatus.LOADING) it.currentIndex else -1
                    )
                }
                if (e.cover != null) _uiState.update {
                    if (it.coverSource != CoverSource.EMBEDDED) it.copy(coverBytes = e.cover, coverSource = CoverSource.SIDECAR)
                    else it
                }
            }
        }
        // 内嵌封面优先：artworkData 一到就落位，sidecar 图不再覆盖它
        viewModelScope.launch {
            combine(keys, playerManager.operaMediaMetadata) { k, md -> k.first to md?.artworkData }
                .collect { (fileId, art) ->
                    if (fileId == -1L) _uiState.update { it.copy(coverBytes = null, coverSource = CoverSource.NONE) }
                    else if (art != null && art.isNotEmpty()) _uiState.update { it.copy(coverBytes = art, coverSource = CoverSource.EMBEDDED) }
                }
        }
        viewModelScope.launch {
            combine(
                playerManager.operaPosition,
                _uiState.map { it.lines }.distinctUntilChanged()
            ) { pos, lines -> LrcParser.indexAt(lines, pos) }
                .collect { idx -> _uiState.update { it.copy(currentIndex = idx) } }
        }
    }

    private fun loadSidecars(ctx: OperaPlayContext) = flow {
        emit(SidecarEvent(clear = true))
        emit(SidecarEvent(lyricsStatus = LyricsStatus.LOADING))
        val text = runCatching { withTimeout(8_000) { sidecar.readLyrics(ctx) } }.getOrNull()
        val parsed = text?.let { LrcParser.parse(it) }
        emit(SidecarEvent(
            lyricsStatus = if (!parsed.isNullOrEmpty()) LyricsStatus.READY else LyricsStatus.MISSING,
            lines = parsed ?: emptyList()
        ))
        if (_uiState.value.coverSource != CoverSource.EMBEDDED) {
            val img = runCatching { withTimeout(8_000) { sidecar.readImage(ctx) } }.getOrNull()
            if (img != null && img.isNotEmpty()) emit(SidecarEvent(cover = img))
        }
    }

    fun seekToLine(index: Int) {
        val line = _uiState.value.lines.getOrNull(index) ?: return
        playerManager.seekOperaTo(line.timeMs)
        _uiState.update { it.copy(currentIndex = index) }
    }
}
```

注意：上面 import 列表已保持最小集（hiltViewModel 属于 Screen 不属于 VM，不要加进来）。

- [ ] **Step 6: 跑测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.radio.chinese.OperaPlayerViewModelTest"`
Expected: 6 个测试 PASS。若 `switchingTrackDropsLateResponseOfPrevious` 里 FakeSidecar 分支判断别扭，可改按 `ctx.trackPath.endsWith("a2.mp3")` 分流——不许改断言本身。

- [ ] **Step 7: 提交**

```powershell
git add app/build.gradle.kts app/src/main/java/com/radio/chinese/domain/sidecar/ app/src/main/java/com/radio/chinese/domain/browser/ app/src/main/java/com/radio/chinese/ui/player/OperaPlayerViewModel.kt app/src/test/java/com/radio/chinese/OperaPlayerViewModelTest.kt
git commit -m "feat(player): sidecar media source + OperaPlayerViewModel state machine (timeout, stale-drop, embedded-art priority)"
```

---

### Task 6: 路由注册 + 播放条入口（onClick / 展开图标 / 滑条语义）

**Files:**
- Modify: `app/src/main/java/com/radio/chinese/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/MainScreen.kt`（Audio 路由 L61-63、NavHost 内注册）
- Modify: `app/src/main/java/com/radio/chinese/ui/audio/AudioMainScreen.kt`（签名 + OperaMiniPlayerBar 调用点 L109-131）
- Modify: `app/src/main/java/com/radio/chinese/ui/common/MiniPlayerBar.kt`（`OperaMiniPlayerBar`）

**Interfaces:**
- Consumes: Task 4 完成后的 play 链路（不动它）
- Produces: `Screen.OperaPlayer.route == "opera_player"`；`OperaMiniPlayerBar(..., onClick: (() -> Unit)? = null)`；`AudioMainScreen(playerManager, onOpenPlayer)`（Task 7 的 Screen 注册进行为完整闭环）

- [ ] **Step 1: Screen 路由**

`Screen.kt` 的 `data object Player` 之后加：

```kotlin
/** 音频库（戏曲）全屏播放页：无参数，状态一律读 PlayerManager */
data object OperaPlayer : Screen("opera_player")
```

- [ ] **Step 2: OperaMiniPlayerBar 加入口**

`MiniPlayerBar.kt` 修改点（`RadioMiniPlayerBar` 不动）：

签名尾部加参数（`modifier` 之前）：

```kotlin
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
```

标题信息行整段替换为（原 L210-252 的 Row；图标+文字列包进一个可点的内层 Row，
新增可选展开按钮，停止按钮保持在最后不动）：

```kotlin
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding)
                .height(Dimens.RowMinHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = if (onClick != null)
                    Modifier.weight(1f).clickable(onClick = onClick)
                else Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(Dimens.CoverTiny)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.IconMedium),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(Dimens.GapMedium))
                Column {
                    Text(text = title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = if (bitrateText != null) "$timeText · $bitrateText" else timeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // 展开入口：中老年用户需要可见 affordance，不能只靠"猜标题行可点"
            if (onClick != null) {
                IconButton(
                    onClick = onClick,
                    modifier = if (isCarSurface()) Modifier.size(Dimens.TouchMin) else Modifier
                ) {
                    Icon(Icons.Default.OpenInFull, contentDescription = "展开全屏播放", modifier = Modifier.size(Dimens.IconMedium))
                }
            }
            IconButton(onClick = onStop) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "停止播放")
            }
        }
```

文件头补 import：`androidx.compose.foundation.clickable`、`androidx.compose.material.icons.filled.OpenInFull`。

滑条加语义（回归基线断言要用它定位；纯附加，无障碍播报一并受益）——在 Slider 的 modifier 链尾接：

```kotlin
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.SliderTouchHeight)
                    .padding(horizontal = Dimens.GapMedium)
                    .semantics { contentDescription = "播放进度" },
```

补 import：`androidx.compose.ui.semantics.semantics`、`androidx.compose.ui.semantics.contentDescription`。

- [ ] **Step 3: AudioMainScreen 透传**

签名：`fun AudioMainScreen(playerManager: PlayerManager, onOpenPlayer: () -> Unit)`；
`OperaMiniPlayerBar(...)` 调用尾部加一行 `onClick = onOpenPlayer,`。

- [ ] **Step 4: MainScreen 接线（本步只改 Audio 调用）**

`RadioNavGraph` 里把 Audio 路由改为：

```kotlin
composable(Screen.Audio.route) {
    AudioMainScreen(
        playerManager = playerManager,
        onOpenPlayer = { navController.navigate(Screen.OperaPlayer.route) }
    )
}
```

新页自身的 `composable(Screen.OperaPlayer.route)` 要等 `OperaPlayerScreen` 存在后才能编译，
放在 Task 7 Step 3 注册——两步分开提交、各自可编译，合在一起导航才闭环。

- [ ] **Step 5: 编译 + 全量单测**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: 全绿。手动快速确认（可选装包）：播放条与之前唯一的可视差异是多了一个展开图标。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/com/radio/chinese/ui/navigation/Screen.kt app/src/main/java/com/radio/chinese/ui/common/MiniPlayerBar.kt app/src/main/java/com/radio/chinese/ui/audio/AudioMainScreen.kt app/src/main/java/com/radio/chinese/ui/MainScreen.kt
git commit -m "feat(ui): opera player route + mini bar entry (title tap, OpenInFull icon, slider semantics)"
```

---

### Task 7: OperaPlayerScreen 全屏页

**Files:**
- Create: `app/src/main/java/com/radio/chinese/ui/player/OperaPlayerScreen.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/theme/Dimens.kt`
- Modify: `app/src/main/java/com/radio/chinese/ui/MainScreen.kt`（注册 composable）

**Interfaces:**
- Consumes: `OperaPlayerViewModel.uiState/seekToLine`（Task 5）、`Screen.OperaPlayer`/`onOpenPlayer`（Task 6）、`PlayerManager` 各 opera 流与播控方法（现状）、`mediaTimeText`/`bitrateText`（`ui.common.TimeText`）、`EmptyState`（`ui.common.StateViews`）、`NestedWindowInsets`、`isCarSurface`/`usesRailNavigation`（`ui.theme`）
- Produces: `@Composable fun OperaPlayerScreen(playerManager: PlayerManager, onNavigateBack: () -> Unit, viewModel: OperaPlayerViewModel = hiltViewModel())`

- [ ] **Step 1: Dimens 加 token**

`Dimens.kt` 的 `CarContentMaxWidth` 之后加：

```kotlin
    // 全屏播放页横屏车机档右侧控件栏宽度（手机档单列不用）
    val PlayerSideWidth: Dp
        @Composable get() = if (car) 420.dp else 0.dp
```

- [ ] **Step 2: 页面实现**

`ui/player/OperaPlayerScreen.kt`（完整文件）：

```kotlin
package com.radio.chinese.ui.player

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.OperaAudioFile
import com.radio.chinese.service.OperaRepeatMode
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.bitrateText
import com.radio.chinese.ui.common.mediaTimeText
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface
import com.radio.chinese.ui.theme.usesRailNavigation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperaPlayerScreen(
    playerManager: PlayerManager,
    onNavigateBack: () -> Unit,
    viewModel: OperaPlayerViewModel = hiltViewModel()
) {
    val file by playerManager.operaFile.collectAsState()
    val playing by playerManager.isPlaying.collectAsState()
    val pos by playerManager.operaPosition.collectAsState()
    val dur by playerManager.operaDuration.collectAsState()
    val br by playerManager.operaBitrate.collectAsState()
    val repeat by playerManager.operaRepeatMode.collectAsState()
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
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
    ) { padding ->
        val f = file
        if (f == null) {
            // 无播放上下文直接落到本页：不崩、给出路（Review Focus 4）
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Default.LibraryMusic,
                    title = "未播放任何唱段",
                    hint = "回到音频库列表，点一首唱段再进来",
                    actionLabel = "返回",
                    onAction = onNavigateBack
                )
            }
            return@Scaffold
        }

        val stopAndExit = {
            playerManager.stopOpera()
            onNavigateBack()
        }

        val info: @Composable () -> Unit = {
            Column(Modifier.padding(horizontal = Dimens.ScreenPadding)) {
                Text(f.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOf(f.categoryName, f.operaName).filter { it.isNotEmpty() }.joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val bt = bitrateText(br)
                Text(
                    text = if (bt != null) "${mediaTimeText(pos, dur)} · $bt" else mediaTimeText(pos, dur),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Dimens.GapSmall))
            }
        }
        val slider: @Composable () -> Unit = {
            PlayerSlider(pos = pos, dur = dur, onSeek = { playerManager.seekOperaTo(it) })
        }
        val controls: @Composable () -> Unit = {
            ControlRow(
                playing = playing,
                repeat = repeat,
                canSeek = dur > 0L,
                onPlayPause = { playerManager.togglePlayPause() },
                onPrevious = { playerManager.playOperaPrevious() },
                onNext = { playerManager.playOperaNext() },
                onStop = stopAndExit,
                onCycleRepeat = { playerManager.cycleOperaRepeatMode() },
                onForward = { playerManager.seekOperaForward(10) },
                onBackward = { playerManager.seekOperaBackward(10) }
            )
        }
        val contentArea: @Composable (Modifier) -> Unit = { m ->
            OperaContentArea(m, state, f.name, onLineClick = { viewModel.seekToLine(it) })
        }

        if (usesRailNavigation()) {
            // 车机横屏：左信息+内容，右进度+大控件；±10 秒贴主播放键两侧在 ControlRow 内
            Row(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.weight(1f)) { info; contentArea(Modifier.weight(1f)) }
                Column(
                    Modifier
                        .width(Dimens.PlayerSideWidth)
                        .padding(end = Dimens.ScreenPadding, bottom = Dimens.GapLarge)
                ) { slider; controls }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                info
                contentArea(Modifier.weight(1f))
                slider
                controls
            }
        }
    }
}

@Composable
private fun PlayerSlider(pos: Long, dur: Long, onSeek: (Long) -> Unit) {
    if (dur <= 0L) return // 时长未知时不给可拖 0 的滑条（与迷你条同一防御）
    var isDragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableLongStateOf(0L) }
    Slider(
        value = (if (isDragging) dragMs else pos).toFloat(),
        onValueChange = { dragMs = it.toLong(); isDragging = true },
        onValueChangeFinished = { isDragging = false; onSeek(dragMs) },
        valueRange = 0f..dur.toFloat(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.SliderTouchHeight)
            .padding(horizontal = Dimens.GapMedium)
            .semantics { contentDescription = "播放进度" },
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

@Composable
private fun OperaContentArea(
    modifier: Modifier,
    state: OperaPlayerUiState,
    name: String,
    onLineClick: (Int) -> Unit
) {
    val hasCover = state.coverBytes != null
    val hasLyrics = state.lyricsStatus == LyricsStatus.READY && state.lines.isNotEmpty()
    val car = isCarSurface()
    when {
        hasCover && hasLyrics -> {
            val pagerState = rememberPagerState(pageCount = { 2 })
            Column(modifier = modifier.fillMaxWidth()) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                    if (page == 0) CoverPage(state.coverBytes!!, name)
                    else LyricPage(state, car, onLineClick)
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Dimens.GapSmall),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(2) { i ->
                        Box(
                            Modifier
                                .size(if (car) 10.dp else 6.dp)
                                .background(
                                    if (pagerState.currentPage == i) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape
                                )
                        )
                        if (i == 0) Spacer(Modifier.width(Dimens.GapSmall))
                    }
                }
            }
        }
        hasCover -> CoverPage(state.coverBytes!!, name)
        hasLyrics -> LyricPage(state, car, onLineClick)
        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(Dimens.CoverSmall).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, null, Modifier.size(Dimens.IconLarge),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(Dimens.GapMedium))
                Text(
                    text = if (state.lyricsStatus == LyricsStatus.LOADING) "正在加载歌词…"
                        else "该唱段暂无歌词\n在音频同目录放同名 .lrc 即可显示歌词",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun CoverPage(bytes: ByteArray, name: String) {
    val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (bmp != null) {
                Image(
                    bmp,
                    contentDescription = "专辑封面",
                    modifier = Modifier.size(Dimens.CoverLargeMax).clip(RoundedCornerShape(Dimens.GapLarge))
                )
            }
        }
    }
}

@Composable
private fun LyricPage(state: OperaPlayerUiState, car: Boolean, onLineClick: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentIndex) {
        if (state.currentIndex in state.lines.indices) {
            listState.animateScrollToItem(state.currentIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapSmall)
    ) {
        itemsIndexed(state.lines) { i, line ->
            Text(
                text = line.text,
                style = if (i == state.currentIndex) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                color = if (i == state.currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (car) it.heightIn(min = Dimens.TouchMin) else it }
                    .clickable { onLineClick(i) }
                    .padding(vertical = Dimens.GapTiny)
            )
        }
    }
}

@Composable
private fun ControlRow(
    playing: Boolean,
    repeat: OperaRepeatMode,
    canSeek: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onCycleRepeat: () -> Unit,
    onForward: () -> Unit,
    onBackward: () -> Unit
) {
    val car = isCarSurface()
    val btnMod = if (car) Modifier.size(Dimens.TouchMin) else Modifier
    val repeatLabel = when (repeat) {
        OperaRepeatMode.ALL -> "列表循环"
        OperaRepeatMode.ONE -> "单曲循环"
        OperaRepeatMode.RANDOM -> "随机播放"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onCycleRepeat) {
            Text(repeatLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        if (canSeek) {
            IconButton(onClick = onBackward, modifier = btnMod) {
                Icon(Icons.Default.Replay10, "后退 10 秒", modifier = if (car) Modifier.size(Dimens.IconLarge) else Modifier)
            }
        }
        IconButton(onClick = onPrevious, modifier = btnMod) {
            Icon(Icons.Default.SkipPrevious, "上一段", modifier = Modifier.size(Dimens.IconMedium))
        }
        IconButton(onClick = onPlayPause, modifier = Modifier.size(Dimens.PlayButtonSize)) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "暂停播放" else "继续播放",
                modifier = Modifier.size(Dimens.PlayIconSize)
            )
        }
        IconButton(onClick = onNext, modifier = btnMod) {
            Icon(Icons.Default.SkipNext, "下一段", modifier = Modifier.size(Dimens.IconMedium))
        }
        if (canSeek) {
            IconButton(onClick = onForward, modifier = btnMod) {
                Icon(Icons.Default.Forward10, "前进 10 秒", modifier = if (car) Modifier.size(Dimens.IconLarge) else Modifier)
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onStop, modifier = btnMod) {
            Icon(Icons.Default.Close, "停止播放")
        }
    }
}
```

- [ ] **Step 3: MainScreen 注册新页**

`RadioNavGraph` 中 `composable(Screen.Audio.route)` 之后加：

```kotlin
composable(Screen.OperaPlayer.route) {
    OperaPlayerScreen(
        playerManager = playerManager,
        onNavigateBack = { navController.popBackStack() }
    )
}
```

import `com.radio.chinese.ui.player.OperaPlayerScreen`。

- [ ] **Step 4: 编译 + 全量单测回归**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/radio/chinese/ui/player/OperaPlayerScreen.kt app/src/main/java/com/radio/chinese/ui/theme/Dimens.kt app/src/main/java/com/radio/chinese/ui/MainScreen.kt
git commit -m "feat(player): fullscreen opera player page with cover/lyrics pager and parity controls"
```

---

### Task 8: UI 自动化验收脚本 opera_player_check.ps1

**Files:**
- Create: `tools/opera_player_check.ps1`
- 测试夹具（设备侧手工铺一次，脚本自检存在性）：`/sdcard/Music/opera_test/demo.mp3`、`demo.lrc`、可选 `demo.jpg`

`.lrc` 夹具内容（PC 侧新建后随 mp3 一起 push）：

```
[ti:测试唱段]
[00:03.00]第一句 测试歌词
[00:08.00]第二句 点我应该跳转
[00:13.00]第三句 收尾
```

**Interfaces:**
- Consumes: Task 6/7 的全部 content-desc 文案（文案改动必须同步此脚本）、`tools/car_env.ps1`、`tools/car_assert.ps1`、`tools/cap.ps1`
- Produces: 退出码 0 = 全部断言通过；`.qtest/opera_*.xml` 为当次抓取产物

- [ ] **Step 1: 写脚本**

`tools/opera_player_check.ps1`:

```powershell
param([switch]$SkipCar)
# 音频库播放页验收：回归基线（九控件不缩水）+ 入口跳转 + 新页控件 + 歌词 + 车机档 88dp。
# 前提：设备在线；/sdcard/Music/opera_test/ 里有 demo.mp3(+demo.lrc,demo.jpg)。
$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.radio.chinese"
$out = Join-Path (Split-Path $PSScriptRoot -Parent) ".qtest"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$fails = 0
function Check($cond, [string]$what) { if ($cond) { "PASS: $what" } else { "FAIL: $what"; $script:fails++ } }
function Dump([string]$name) {
  & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
  & $adb pull /sdcard/ui.xml "$out\opera_$name.xml" | Out-Null
  [xml](Get-Content "$out\opera_$name.xml" -Raw -Encoding UTF8)
}
function FindNode($doc, [string]$label) {
  @($doc.SelectNodes("//*")) | Where-Object {
    $_.GetAttribute("content-desc") -eq $label -or $_.GetAttribute("text") -eq $label
  } | Select-Object -First 1
}
function Has($doc, [string]$label) { [bool](FindNode $doc $label) }
function Tap($doc, [string]$label) {
  $n = FindNode $doc $label
  if (-not $n) { "  (找不到 $label)"; return $false }
  $m = [regex]::Match($n.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
  & $adb shell input tap (([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2) (([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
  Start-Sleep -Milliseconds 900
  return $true
}
# --- 冷启动 + 就绪轮询（本项目自动化规范：不许拿旧产物当本次结果）---
Remove-Item "$out\opera_*.xml" -ErrorAction SilentlyContinue
& $adb shell pm grant $pkg android.permission.READ_MEDIA_AUDIO 2>$null | Out-Null
& $adb shell am force-stop $pkg
& $adb shell am start -n "$pkg/.MainActivity" | Out-Null
$ready = $false
1..25 | ForEach-Object { if (-not $ready) { Start-Sleep -Seconds 1; if ((Has (Dump "boot") "音频库")) { $ready = $true } } }
if (-not $ready) { "FAIL: 冷启动 25 秒未就绪"; exit 2 }

# --- 用本地夹具起播 ---
if (-not ( & $adb shell "ls /sdcard/Music/opera_test/demo.mp3" 2>$null )) { "FAIL: 缺夹具 /sdcard/Music/opera_test/demo.mp3"; exit 3 }
& $adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/opera_test/demo.mp3" | Out-Null
Tap (Dump "home") "音频库" | Out-Null
Tap (Dump "audio") "本地" | Out-Null
$d = Dump "beforeplay"
Check (-not (Has $d "停止播放")) "起播前无播放条"
if (-not (Tap $d "demo.mp3")) { "FAIL: 本地列表没找到 demo.mp3（媒体库未索引到？换真机裁决）"; exit 3 }

# --- 回归基线：迷你条九控件 ---
Start-Sleep -Seconds 2
$d = Dump "minibar"
Check (Has $d "播放进度") "迷你条: 滑条存在"
Check (Has $d "上一段") "迷你条: 上一段"
Check (Has $d "下一段") "迷你条: 下一段"
Check ((Has $d "暂停播放") -or (Has $d "继续播放")) "迷你条: 播放/暂停"
Check (Has $d "停止播放") "迷你条: 停止"
Check ((Has $d "列表循环") -or (Has $d "单曲循环") -or (Has $d "随机播放")) "迷你条: 循环模式"
Check (Has $d "demo.mp3") "迷你条: 标题信息行"
# 点暂停不许跳页（点击吞并回归，Review Focus 5）
$before = (Dump "pause_before").SelectNodes("//*") | Where-Object { $_.GetAttribute("content-desc") -eq "暂停播放" -or $_.GetAttribute("content-desc") -eq "继续播放" } | Select-Object -First 1
Tap $d $before.GetAttribute("content-desc") | Out-Null
$d2 = Dump "pause_after"
Check ((Has $d2 "停止播放") -and -not (Has $d2 "正在播放")) "点暂停仍留在音频库页（新 onClick 未吞按钮）"

# --- 进入全屏页 ---
Tap $d2 "展开全屏播放" | Out-Null
$d = Dump "player"
Check (Has $d "正在播放") "新页: 顶栏标题"
Check (Has $d "播放进度") "新页: 滑条"
Check (Has $d "后退 10 秒") "新页: 后退10秒（有时长）"
Check (Has $d "前进 10 秒") "新页: 前进10秒"
Check (Has $d "上一段") "新页: 上一段"; Check (Has $d "下一段") "新页: 下一段"
Check (Has $d "停止播放") "新页: 停止"
Check ((Has $d "专辑封面") -or (Has $d "第一句 测试歌词")) "新页: 内容区有封面或歌词"
if ((Has $d "专辑封面") -and (Has $d "第一句 测试歌词")) {
  Check (Has $d "第一句 测试歌词") "新页: 封面+歌词双页态（首页=封面）"
  # 左右滑动切页：从内容区中部横滑
  $scr = (& $adb shell wm size) -join " "; $m = [regex]::Match($scr, '(\d+)x(\d+)')
  $w = [int]$m.Groups[1].Value; $h = [int]$m.Groups[2].Value
  & $adb shell input swipe ([int]($w*0.7)) ([int]($h*0.4)) ([int]($w*0.3)) ([int]($h*0.4)) 400
  Start-Sleep -Milliseconds 900
  Check (Has (Dump "swiped") "第一句 测试歌词") "新页: 滑动后到歌词页"
}
# 点歌词行跳转进度（Review Focus：位置文本变化）
$d = Dump "lyrictap"
if (Tap $d "第二句 点我应该跳转") {
  $t = (@($d.SelectNodes("//*")) | Where-Object { $_.GetAttribute("text") -match '^\d+:\d\d' } | Select-Object -First 1).GetAttribute("text")
  Check ($null -ne $t) "新页: 时间文本可读（$t）"
}
# 返回不中断播放
Tap $d "返回" | Out-Null
$d = Dump "back"
Check (Has $d "停止播放") "返回后迷你条仍在（播放未中断）"
# 停止 = 回列表并清条：重新进全屏页再点停止
Tap (Dump "relaunch") "展开全屏播放" | Out-Null
Tap (Dump "player2") "停止播放" | Out-Null
Start-Sleep -Seconds 1
$d = Dump "stopped"
Check (-not (Has $d "停止播放")) "新页停止：自动返回列表且播放条消失"

# --- 车机档：横屏 160/240 两档 88dp ---
if (-not $SkipCar) {
  foreach ($dpi in 160, 240) {
    & (Join-Path $PSScriptRoot "car_env.ps1") -dpi $dpi | Out-Null
    & $adb shell am force-stop $pkg; & $adb shell am start -n "$pkg/.MainActivity" | Out-Null
    Start-Sleep -Seconds 3
    Tap (Dump "carhome") "音频库" | Out-Null
    Tap (Dump "caraudio") "本地" | Out-Null
    Tap (Dump "carlist") "demo.mp3" | Out-Null
    Start-Sleep -Seconds 2
    if (-not (Tap (Dump "carminibar") "展开全屏播放")) { "FAIL: 车机档($dpi) 无展开入口"; $fails++; continue }
    $a = & (Join-Path $PSScriptRoot "car_assert.ps1") -name ("carplayer_" + $dpi) -density ($dpi / 160.0) -minDp 88.0
    $a | ForEach-Object { "  [car$dpi] $_" }
    Check ($a -notmatch '违规') "车机档($dpi): 播放页可点控件 >= 88dp"
    & $adb shell wm density reset | Out-Null; & $adb shell settings put system user_rotation 0 | Out-Null
  }
}
if ($fails -gt 0) { "RESULT: $fails 项失败"; exit 1 } else { "RESULT: ALL PASS"; exit 0 }
```

注意两处脚本纪律（本项目旧坑）：其一，开头 `Remove-Item opera_*.xml` 防旧产物冒充本次抓取；其二，`Dump` 每次实时 `uiautomator dump`，不许复用文件判断两个时刻的状态。

- [ ] **Step 2: 装包并跑脚本**

```powershell
.\gradlew.bat :app:installDebug
pwsh -File tools\opera_player_check.ps1
```
Expected: `RESULT: ALL PASS`，退出码 0。任何 FAIL 回到实现修，修完重跑整个脚本（不许只重跑失败段）。

- [ ] **Step 3: 提交**

```powershell
git add tools/opera_player_check.ps1
git commit -m "test(ui): opera player acceptance script (mini bar regression, pager, lyrics seek, car 88dp)"
```

---

### Task 9: MediaSession 元数据裁决 + 真机端到端（人工参与）

**Files:**
- 无代码改动，除非 Step 1 裁决失败才回到 `PlayerManager/RadioService` 加显式 artwork 注入

- [ ] **Step 1: dumpsys 元数据核对**

播放 demo.mp3（含内嵌封面更佳）时执行：

```powershell
adb shell dumpsys media_session | Select-String -Pattern 'PlaybackStack dump' -Context 0,40
```

逐项记录：会话归属包名、`metadata:` 段的 title/artist/album 是否为戏曲段信息、artwork 是否存在。**结论二选一**：artwork 已透出 → 嘟嘟桌面卡片的数据前提成立；未透出 → 追加小任务（把 artworkData 设进 `MediaMetadata.Builder`），记入 spec §3.5 修订。

- [ ] **Step 2: FYT 真机端到端（用户配合）**

1. 真机装本次 release 包；123 云盘目录放一对 `xxx.mp3`/`xxx.lrc`（用户已确认自备）。
2. 播放该唱段 → 进全屏页：歌词逐行高亮、点行跳转、±10 秒、切段后歌词换段（不许挂旧词）。
3. 切回电台 Tab 再切回：迷你条进度与页内一致（单一状态源的实证点）。
4. 嘟嘟桌面桌面观察通用音乐卡片是否显示标题/封面/可播控 → 结论写入 spec §3.5 作为后续 spike 立项依据。

- [ ] **Step 3: 裁决记录**

真机结论（含照片/截图路径）记入 `docs/specs/2026-10-13-opera-player-page-design.md` 末尾"真机裁决记录"小节并单独提交：

```powershell
git add docs/specs/2026-10-13-opera-player-page-design.md
git commit -m "docs(spec): device verdict records for opera player page"
```

---

## 计划完成后待办（不属于任务）

- CodeReview 子智能体全分支评审 → 人工回查（项目规范：评审结论须人工回查）
- Release 前验证清单（既有规范：R8 混淆后验证、APK 文件名规范）
- 文档归档 Obsidian + 工作区清理（用户规范）
