# 12 项问题解决计划

> 范围：P0-5、P0-6、P1-1、P1-2、P1-6、P1-7、P1-8、P2-1、P2-2、P2-3、P2-6、P2-10
> 不含安全问题。按"先低风险独立项 → 再架构改造"顺序推进，每项给出**现状 / 改法 / 风险 / 完成标准 / 估时**。

---

## 依赖与执行顺序

```
独立小改（可并行，互不影响）:
  P0-6 ProGuard 补全        30 min
  P2-3 Room 迁移策略         15 min
  P2-6 onTaskRemoved         10 min
  P2-10 versionCode 自增     15 min
  P1-1 PlayerManager scope   20 min
  P1-2 缓存线程安全          30 min
  P1-8 删 Depth: infinity    30 min
  P2-2 OkHttp 单例化         45 min  ← 改完再做 P2-1 / P1-6

中等改造（依赖 P2-2 完成）:
  P2-1 XML 用 XmlPullParser   2 h
  P1-7 loadDownloaded 重构    1.5 h

大改造（高风险）:
  P1-6 下载断点续传+取消       3 h
  P0-5 戏曲统一走 RadioService 1-2 天
```

**推荐分两批走**：
- 第 1 批（半天）：P0-6 / P2-3 / P2-6 / P2-10 / P1-1 / P1-2 / P1-8 / P2-2 —— 全部独立、低风险，做完即可发布一个 patch 版本。
- 第 2 批（2-3 天）：P2-1 / P1-7 / P1-6 / P0-5 —— 涉及核心路径改造，需充分测试。

---

## 独立小改（第 1 批）

### P0-6. ProGuard 规则补全

**现状**：`app/proguard-rules.pro` 已存在，但只 keep 了 Media3 / Hilt / Room Database 基类。缺：
- `@Entity` data 类（`DownloadedOpera`、`FavoriteEntity`）—— Room 反射字段名被混淆导致读写错位
- Gson 反序列化的 model（`com.radio.chinese.domain.model.**` + 任何带 `@SerializedName` 的类）—— Retrofit Gson converter 拿到全 null
- kotlinx.serialization 的 `$$serializer` —— `SourceAvailabilityStore` 的 JSON 解析失败
- Room DAO 方法 —— 部分情况下生成的实现被混淆

**改法**：在 `app/proguard-rules.pro` 末尾追加：

```proguard
# ===== Room 实体与 DAO =====
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.Dao { <methods>; }
-keep class * extends androidx.room.RoomDatabase { *; }

# ===== Gson / Retrofit 反序列化的 model =====
-keepattributes Signature, *Annotation*
-keep class com.radio.chinese.domain.model.** { *; }
-keep class com.radio.chinese.data.remote.** { *; }   # 若有 DTO
-keepclassmembers, allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ===== kotlinx.serialization =====
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# ===== DataStore / 通用 =====
-keep class androidx.datastore.** { *; }
```

**验证**：`./gradlew :app:assembleRelease` 后安装到真机，跑一遍：进戏曲 → 加载分类 → 播放 → 下载 → 收藏 → 重启读收藏。任一环节崩或数据为 null 即未通过。

**风险**：低。纯增量 keep，不影响 Debug 包。
**完成标准**：Release 包完整跑通主流程，logcat 无 `IllegalStateException` / `NullPointerException` 来自反射。
**估时**：30 min（写规则）+ 1 h（真机回归）。

---

### P2-3. Room 迁移策略

**现状**：`AppModule.kt:22-28, 37-43` 两个 `Room.databaseBuilder(...).build()` 均无任何迁移策略。下次给 `DownloadedOpera` 加字段（如 `downloadStatus`、`isFavorite`）会 `IllegalStateException: A migration from X to Y was required but not found`。

**改法**：`AppModule.kt` 两个 builder 都加：

```kotlin
@Provides @Singleton
fun provideOperaDatabase(@ApplicationContext context: Context): OperaDatabase {
    return Room.databaseBuilder(context, OperaDatabase::class.java, "opera_database")
        .fallbackToDestructiveMigration()   // 可接受丢数据（仅下载记录，可重下）
        .build()
}
```

`RadioDatabase` 同理（收藏数据用户感知大，但当前只有 1 张表且 schema 简单，破坏性迁移可接受；若未来重要，再换成 `addMigrations(Migration(1,2){...})`）。

**风险**：低。`fallbackToDestructiveMigration` 仅在 schema 版本号变化时触发，当前 version=1 不会触发任何行为。
**完成标准**：未来把 `@Entity` 加字段、`version` 改 2 后，应用不崩（数据清空但能用）。
**估时**：15 min。

---

### P2-6. RadioService.onTaskRemoved 逻辑修正

**现状**：`RadioService.kt:64-70`

```kotlin
if (player != null && !player.playWhenReady) {
    stopSelf()
}
```

只在"未在播放"时 stopSelf —— 用户在播放中划掉任务反而不会停，反直觉。

**改法**：按 Media3 官方推荐，"未在播放则停"：

```kotlin
override fun onTaskRemoved(rootIntent: Intent?) {
    val player = mediaSession?.player
    if (player != null && !player.playWhenReady) {
        stopSelf()
    }
    // 正在播放则保持服务存活（用户期望后台继续放）
    super.onTaskRemoved(rootIntent)
}
```

等等——这其实就是现状。真正的反直觉在于：用户从最近任务**划掉**通常期望"停"，而代码逻辑是"播放中不停"。Media3 官方样例确实是这个写法（保留播放），所以**这其实不是 bug，是设计选择**。

**修正方案**：保留现状，但在用户设置里加一个开关"划掉任务即停止播放"，默认关（符合 Media3 习惯）。短期可不动，标记为"已确认非 bug"。

**风险**：无。
**完成标准**：在 `MEMORY.md` 记一笔"onTaskRemoved 是有意保留播放，非 bug"。
**估时**：0 min（仅记录结论）。

> ⚠️ **修正**：复核后此条不必改代码。如果产品决定"划掉即停"，把 `if` 改成无条件 `stopSelf()` 即可，10 min 完成。建议先和用户确认产品预期。

---

### P2-10. versionCode 自增

**现状**：`app/build.gradle.kts:18` `versionCode = 1`，每次发版手动改易漏，应用商店会拒重复包。

**改法**：基于 git commit count 自动生成。`app/build.gradle.kts` 顶部加：

```kotlin
import org.gradle.api.plugins.ExtraPropertiesExtension

fun gitCommitCount(): Int {
    return try {
        val out = providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.get().trim()
        out.toIntOrNull() ?: 1
    } catch (_: Exception) { 1 }
}
```

`defaultConfig` 内：

```kotlin
versionCode = gitCommitCount()
versionName = "1.0.${gitCommitCount()}"
```

**风险**：低。CI 环境如果没装 git 会 fallback 到 1，需确保本地/CI 都有 git。
**完成标准**：每次 commit 后 `./gradlew :app:assembleDebug` 出来的 APK `versionCode` 自动递增。
**估时**：15 min。

---

### P1-1. PlayerManager 协程作用域泄漏

**现状**：`PlayerManager.kt:33`

```kotlin
private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
```

`PlayerManager` 是 `@Singleton`，`scope` 永不取消。`disconnect()` 也没 `scope.cancel()`。`scope.launch { preferences.setLastPlayedStationId(...) }` 这类协程在用户停止播放后仍可能挂着。

**改法**：

```kotlin
fun disconnect() {
    controllerFuture?.let { MediaController.releaseFuture(it) }
    controller = null
    _isConnected.value = false
    scope.cancel()   // ← 新增：取消所有挂起的协程
    // 注意：scope 取消后不可复用，下次 connect() 前需重建
}

// 改为可重建
private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

fun connect() {
    if (!scope.isActive) scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // ... 原逻辑
}
```

**更优方案**（推荐）：`PlayerManager` 是单例，scope 跟单例同寿其实没问题。真正的问题是"`disconnect()` 后协程仍在跑"。所以只需在 `disconnect()` 里 cancel，并在 `connect()` 里重建：

```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

fun disconnect() {
    scope.coroutineContext[Job]?.cancelChildren()   // 只取消子协程，scope 可复用
    controllerFuture?.let { MediaController.releaseFuture(it) }
    controller = null
    _isConnected.value = false
}
```

`cancelChildren()` 比 `scope.cancel()` 更稳——scope 本身不死，下次 `launch` 仍可挂上去。

**风险**：低。`cancelChildren` 不影响后续使用。
**完成标准**：`disconnect()` 后立即断网，没有残留协程在 logcat 里报"Job was cancelled"。
**估时**：20 min。

---

### P1-2. 缓存非线程安全

**现状**：两处 mutable Map 在 `Dispatchers.IO` 多协程下被并发读写：
- `WebDavClient.kt:58` `private val listFilesCache = mutableMapOf<String, List<WebDavFile>>()`
- `OperaRepository.kt:31` `private val fileCache = mutableMapOf<String, List<OperaAudioFile>>()`

**改法**：换成 `ConcurrentHashMap`，保留 `clear()` / `[]=` 语义：

```kotlin
// WebDavClient.kt
private val listFilesCache = java.util.concurrent.ConcurrentHashMap<String, List<WebDavFile>>()

// OperaRepository.kt
private val fileCache = java.util.concurrent.ConcurrentHashMap<String, List<OperaAudioFile>>()
```

`clear()`、`[]`、`get`、`put` 全部兼容，无需改调用点。

**风险**：极低。`ConcurrentHashMap` API 与 `MutableMap` 几乎一致。
**完成标准**：编译通过，戏曲页快速反复点分类/搜索无 `ConcurrentModificationException`。
**估时**：30 min。

---

### P1-8. 删除 searchFiles 的 Depth: infinity

**现状**：`WebDavClient.kt:188-216` `searchFiles` 用 `Depth: infinity` 一次性 PROPFIND 全树。123pan 在文件多时响应巨大或超时，且很多 WebDAV 服务端禁用 infinity。

**改法**：`OperaRepository.searchFiles` 已经优先走 `searchLocalCache`，只有 `cacheInitialized == false` 时才 fallback 到 `searchFilesRealtime`。所以：
1. **删除 `WebDavClient.searchFiles` 整个方法**（不再用 infinity）。
2. `OperaRepository.searchFilesRealtime` 改为"遍历分类目录 + Depth:1"：

```kotlin
private suspend fun searchFilesRealtime(query: String): List<OperaAudioFile> {
    val results = mutableListOf<OperaAudioFile>()
    val categories = getCategories()
    for (cat in categories) {
        try {
            val files = loadAllFilesInCategory(cat)   // 已用 Depth:1 分层
            files.forEach { f ->
                if (f.name.contains(query, ignoreCase = true) ||
                    f.operaName.contains(query, ignoreCase = true)) {
                    results.add(f)
                }
            }
        } catch (e: Exception) {
            Log.w("OperaRepo", "search skip ${cat.name}: ${e.message}")
        }
    }
    // 顺手填充缓存，下次就走 searchLocalCache 了
    categories.forEach { cat ->
        if (!fileCache.containsKey(cat.name)) {
            fileCache[cat.name] = loadAllFilesInCategory(cat)
        }
    }
    cacheInitialized = true
    return results
}
```

3. `WebDavClient.cancelSearch()` 和 `currentSearchCall` 字段一并删除（不再需要，分层加载无法整体取消；改为 `searchJob?.cancel()` 在 ViewModel 层取消协程即可，`OperaViewModel.cancelSearch()` 已有此调用）。

**风险**：中。首次搜索会变慢（多次 HTTP），但能完成且不超时。可在 UI 上提示"首次搜索较慢"。
**完成标准**：删除 infinity 后，搜索仍能返回结果；123pan 不会因 413/507/超时失败。
**估时**：30 min。

---

### P2-2. OkHttpClient 单例化

**现状**：3 处独立 `new OkHttpClient.Builder()`：
- `WebDavClient.kt:69-97`（含信任所有证书，本次不动安全）
- `OperaRepository.kt:24-28`
- `StreamChecker.kt:28-33`

每个 client 各自维护连接池、缓存，浪费资源且配置不一致。

**改法**：在 `di/AppModule.kt` 加一个 provider：

```kotlin
@Provides @Singleton
fun provideOkHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}
```

然后 3 个类改成 `@Inject constructor(private val baseClient: OkHttpClient)`：

- `StreamChecker`：直接用 `baseClient`，删除自己的 builder。
- `OperaRepository`：直接用 `baseClient`，删除自己的 builder。
- `WebDavClient`：保留自定义 SSL（**安全问题不在本批处理范围**，但下次修 SSL 时也只改这一处）。把 `client by lazy` 改为基于注入的 `baseClient.newBuilder().addInterceptor(logInterceptor).sslSocketFactory(...).build()`——`newBuilder()` 共享连接池。

**注意**：`WebDavClient` 当前无 `@Inject constructor()` 参数，改为 `@Inject constructor(private val baseClient: OkHttpClient)` 后，所有 `OperaRepository` 注入 `WebDavClient` 的地方仍正常（Hilt 自动注入链）。

**风险**：低。仅是依赖注入改造，行为不变。
**完成标准**：3 个类的 HTTP 行为与之前一致；连接池只有一个。
**估时**：45 min。

---

## 中等改造（第 2 批，依赖 P2-2 完成）

### P2-1. WebDAV XML 解析换 XmlPullParser

**现状**：`WebDavClient.kt:243-303` `parsePropfindResponse` 用正则 `<D:response[^>]*>` + `indexOf` 切片。脆弱点：
- 不支持 CDATA、自闭合标签
- 命名空间前缀变化（`d:response` 小写、`D:response` 大写、`lp1:response` 等）会失败
- 嵌套 response 错切
- 多次全文扫描性能差

历史教训：第四轮 Bug 就是 `<D:response xmlns:D="DAV:">` 没匹配上，只解析出 5 个分类。正则方案已经打过一次补丁，但根因未除。

**改法**：用 Android 自带的 `XmlPullParser`，无视命名空间前缀：

```kotlin
private fun parsePropfindResponse(xml: String, basePath: String): List<WebDavFile> {
    val files = mutableListOf<WebDavFile>()
    val parser = Xml.newPullParser()
    parser.setInput(xml.reader())
    var event = parser.eventType

    var currentHref: String? = null
    var currentName: String? = null
    var isFolder = false
    var contentLength = 0L

    val baseRef = runCatching { URI(basePath).path.trimEnd('/') }.getOrDefault(basePath.trimEnd('/'))

    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                val tag = parser.name?.lowercase()   // 忽略大小写
                val ns = parser.namespace?.lowercase()
                val isDav = ns == "dav:" || ns.isNullOrBlank()
                when {
                    tag == "response" && isDav -> {
                        currentHref = null; currentName = null; isFolder = false; contentLength = 0L
                    }
                    tag == "href" && isDav && currentHref == null -> {
                        currentHref = parser.nextText()
                    }
                    tag == "displayname" && isDav -> {
                        currentName = parser.nextText()
                    }
                    tag == "collection" && isDav -> {
                        isFolder = true
                    }
                    tag == "getcontentlength" && isDav -> {
                        contentLength = parser.nextText().trim().toLongOrNull() ?: 0L
                    }
                }
            }
            XmlPullParser.END_TAG -> {
                val tag = parser.name?.lowercase()
                if (tag == "response") {
                    val href = currentHref
                    if (href != null) {
                        val hrefPath = runCatching { URI(href).path.trimEnd('/') }
                            .getOrDefault(href.trimEnd('/'))
                        if (hrefPath != baseRef) {
                            val name = currentName ?: runCatching { URLDecoder.decode(href, "UTF-8") }
                                .getOrDefault(href)
                                .trimEnd('/').substringAfterLast('/')
                            files.add(WebDavFile(href, name, isFolder, contentLength))
                        }
                    }
                }
            }
        }
        event = parser.next()
    }
    return files
}
```

要点：
- `parser.name` 不带前缀，只看 local name（`response` 而非 `D:response`）
- `parser.namespace` 判断是否 `DAV:` 命名空间，避免误吃非 DAV 的同名标签
- `nextText()` 自动处理 CDATA 和实体转义

**风险**：中。XML 解析逻辑变了，必须回归测试：分类列表、剧目列表、文件列表、搜索。
**完成标准**：在 123pan 真机上跑通"戏曲 → 分类 → 剧目 → 文件 → 播放"全链路，且分类数 = 服务端实际数。
**估时**：2 h（含测试）。

---

### P1-7. loadDownloaded 重构，消除永久 collect

**现状**：`OperaViewModel.kt:97-108, 482-484`

```kotlin
init {
    loadDownloaded()
}
private fun loadDownloaded() {
    viewModelScope.launch { operaRepository.allDownloaded.collect { list -> _uiState.update { it.copy(localDownloads = list) } } }
}
```

每次进戏曲页新建 ViewModel → 新建永久 collect。`allDownloaded` 是 Room Flow 永不结束。`_uiState.update` 里只 copy 了 `localDownloads`，但 `update` 的 lambda 闭包了旧 `it`，如果其它字段在 collect 期间被别的 update 改了，这里会回写旧值——存在数据竞争。

**改法**：把 `uiState` 从"手搓 MutableStateFlow + 多处 update"改为"组合 Flow"：

```kotlin
private val _baseState = MutableStateFlow(OperaUiState())
private val _downloadedFlow = operaRepository.allDownloaded
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

val uiState: StateFlow<OperaUiState> = combine(_baseState, _downloadedFlow) { base, downloaded ->
    base.copy(
        localDownloads = downloaded,
        downloadedIds = downloaded.map { it.fileId }.toSet()
    )
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OperaUiState())
```

`init` 里删掉 `loadDownloaded()` 调用。其它 `_uiState.update { ... }` 改为 `_baseState.update { ... }`。

**注意**：`combine` 会自动在最后一个订阅取消时停 collect，`WhileSubscribed(5000)` 给 5 秒缓冲，符合 UI 生命周期。ViewModel `onCleared` 时 `viewModelScope` 取消，所有 stateIn 自动收尾。

**风险**：中。改的是状态管理骨架，需要回归：下载完成 → 列表刷新；删除下载 → 列表刷新；切 tab → 状态保留。
**完成标准**：反复进出戏曲页 10 次，内存不增长（Profiler 看 `OperaViewModel` 实例数 = 0 当不在戏曲页）。
**估时**：1.5 h。

---

## 大改造（第 2 批后半段）

### P1-6. 下载支持取消、断点续传、失败清理

**现状**：`OperaRepository.kt:227-290`

```kotlin
suspend fun downloadFile(audioFile, saveDir, onProgress): Result<String> = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(downloadUrl)...
    client.newCall(request).execute().use { response ->
        val outputStream = FileOutputStream(targetFile)   // ← 未 use，异常不关
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(...)
            onProgress(pct)
        }
        outputStream.flush(); outputStream.close(); inputStream.close()
    }
    dao.insert(...)
}
```

问题清单：
1. **无法取消**：没保存 `Call` 引用，UI 没有"取消下载"按钮
2. **无断点续传**：网络中断后从头重下
3. **异常路径流不关**：`outputStream` 不在 `use` 里，写过程中抛异常 → 文件句柄泄漏 + 半成品残留
4. **失败不清理**：`catch` 块不删 `targetFile`，磁盘累积损坏文件

**改法**：

```kotlin
// 1. 暴露取消能力
private val activeDownloads = java.util.concurrent.ConcurrentHashMap<Long, Call>()

fun cancelDownload(fileId: Long) {
    activeDownloads[fileId]?.cancel()
    activeDownloads.remove(fileId)
}

// 2. 断点续传 + 资源安全
suspend fun downloadFile(
    audioFile: OperaAudioFile,
    saveDir: File,
    onProgress: (Int) -> Unit = {}
): Result<String> = withContext(Dispatchers.IO) {
    val downloadUrl = audioFile.downloadUrl ?: return@withContext Result.failure(Exception("获取下载地址失败"))
    val safeName = audioFile.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
    val targetFile = File(saveDir, safeName)
    val tmpFile = File(saveDir, "$safeName.part")   // 临时文件，完成后重命名

    try {
        val existing = if (tmpFile.exists()) tmpFile.length() else 0L
        val requestBuilder = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .header("Authorization", webDavClient.authHeader())
        if (existing > 0) {
            requestBuilder.header("Range", "bytes=$existing-")   // 断点续传
        }
        val call = client.newCall(requestBuilder.build())
        activeDownloads[audioFile.fileId] = call

        call.execute().use { response ->
            // 206 = 支持 Range 续传；200 = 服务端不支持，需重头下
            val supported = existing > 0 && response.code == 206
            val appending = supported
            if (!response.isSuccessful && response.code != 206) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }
            val body = response.body ?: return@withContext Result.failure(Exception("响应为空"))
            val totalBytes = if (supported) existing + (body.contentLength().coerceAtLeast(0))
                             else body.contentLength()

            body.byteStream().use { input ->
                java.io.FileOutputStream(tmpFile, appending).use { output ->   // ← use 确保关闭
                    val buffer = ByteArray(8192)
                    var read: Int
                    var total = existing
                    var lastPct = if (total > 0 && totalBytes > 0) ((total * 100) / totalBytes).toInt() else -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        total += read
                        if (totalBytes > 0) {
                            val pct = ((total * 100) / totalBytes).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                    output.flush()
                }
            }
        }

        // 完成：原子重命名
        if (!tmpFile.renameTo(targetFile)) {
            tmpFile.copyTo(targetFile, overwrite = true); tmpFile.delete()
        }

        dao.insert(DownloadedOpera(
            fileId = audioFile.fileId,
            categoryName = audioFile.categoryName,
            operaName = audioFile.operaName,
            fileName = audioFile.name,
            localPath = targetFile.absolutePath,
            fileSize = targetFile.length(),
            downloadTime = System.currentTimeMillis()
        ))
        Result.success(targetFile.absolutePath)

    } catch (e: Exception) {
        // 失败清理：仅删除 .part，保留已下载部分供下次续传
        if (e is kotlinx.coroutines.CancellationException) {
            // 协程取消 → 保留 .part
        } else if (e is java.io.IOException && activeDownloads[audioFile.fileId]?.isCanceled == true) {
            // 用户主动取消 → 保留 .part
        } else {
            // 真错误 → 删除 .part
            tmpFile.delete()
        }
        Result.failure(e)
    } finally {
        activeDownloads.remove(audioFile.fileId)
    }
}
```

**ViewModel 配套**：`OperaViewModel` 加 `cancelDownload(fileId)`：

```kotlin
fun cancelDownload(fileId: Long) {
    operaRepository.cancelDownload(fileId)
    _uiState.update { it.copy(downloadProgress = it.downloadProgress - fileId) }
}
```

**UI 配套**：下载进度条旁加"取消"按钮（`IconButton(Icons.Default.Close)`）。

**风险**：中高。断点续传依赖 123pan 支持 `Range`，需真机验证。若不支持，逻辑会 fallback 到全量重下（`appending=false`），不会出错但失去续传能力。
**完成标准**：
1. 下载中点取消 → 立即停，磁盘留 `.part` 文件
2. 再次点下载 → 从断点继续（logcat 看到 `Range: bytes=xxx-`）
3. 下载完成后 → `.part` 消失，正式文件存在
4. 网络断开重连 → 不报错，继续下载
**估时**：3 h（含真机测试）。

---

### P0-5. 戏曲统一走 RadioService（后台播放）

**现状**：`OperaViewModel.kt:73, 316-360` 自建 `ExoPlayer`，完全绕过 `RadioService`/`MediaSession`。后果：戏曲切后台/锁屏即停，无通知栏控制，无耳机线控——老年人核心场景缺失。

**根因**：`PlayerManager` 当前只服务电台，API 围绕 `RadioStation` 设计（`playStation`、`sourceQueue`、多源 failover）。戏曲是单源 + 播放列表 + 断点续听，模型不同。

**改造方案**：扩展现有 `PlayerManager`，让它支持"两类媒体"：

#### 步骤 1：统一 MediaItem 标记
给 `MediaItem` 加 `MediaMetadata` 自定义字段区分类型：

```kotlin
// 电台
MediaItem.Builder()
    .setUri(source.url)
    .setMediaId("radio:${station.id}:${source.url}")
    .setMediaMetadata(MediaMetadata.Builder()
        .setTitle(station.name)
        .setArtist(source.label)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .setExtras(bundleOf(
            "type" to "radio",
            "stationId" to station.id
        ))
        .build())
    .build()

// 戏曲
MediaItem.Builder()
    .setUri(uri)   // http(s) 走带 Auth 的 DataSource；file:// 走本地
    .setMediaId("opera:${file.fileId}")
    .setMediaMetadata(MediaMetadata.Builder()
        .setTitle(file.name)
        .setArtist(file.operaName.ifEmpty { file.categoryName })
        .setAlbumTitle(file.categoryName)
        .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)   // 戏曲更像 podcast
        .setExtras(bundleOf(
            "type" to "opera",
            "fileId" to file.fileId,
            "categoryName" to file.categoryName,
            "operaName" to file.operaName
        ))
        .build())
    .build()
```

#### 步骤 2：PlayerManager 加戏曲播放 API

```kotlin
// PlayerManager.kt 新增
private val _operaPlaylist = MutableStateFlow<List<OperaAudioFile>>(emptyList())
val operaPlaylist: StateFlow<List<OperaAudioFile>> = _operaPlaylist

private val _operaCurrentIndex = MutableStateFlow(-1)
val operaCurrentIndex: StateFlow<Int> = _operaCurrentIndex

private val _operaPosition = MutableStateFlow(0L)
val operaPosition: StateFlow<Long> = _operaPosition

private val _operaDuration = MutableStateFlow(0L)
val operaDuration: StateFlow<Long> = _operaDuration

fun playOperaFile(file: OperaAudioFile, playlist: List<OperaAudioFile>, localPath: String?) {
    // 互斥：先停电台
    _currentStation.value = null
    _operaPlaylist.value = playlist
    val index = playlist.indexOfFirst { it.fileId == file.fileId }
    _operaCurrentIndex.value = index

    scope.launch {
        val ctrl = ensureController()
        val uri = if (localPath != null) Uri.fromFile(File(localPath))
                  else file.downloadUrl?.let { Uri.parse(it) } ?: return@launch

        // 设置带 Auth 的 DataSource（戏曲远程文件需要）
        // 注：RadioService 初始化 ExoPlayer 时已注入 DefaultDataSource.Factory，
        //     但 Auth header 需动态更新——见下方步骤 3

        ctrl.stop()
        ctrl.clearMediaItems()
        ctrl.setMediaItem(buildOperaMediaItem(file, uri))
        // 断点续听
        val saved = preferences.getOperaPlayPosition(file.fileId)
        if (saved > 0) ctrl.seekTo(saved)
        ctrl.prepare()
        ctrl.play()
    }
}

fun playOperaNext() { /* 基于 _operaCurrentIndex 切下一个 */ }
fun playOperaPrevious() { /* ... */ }
```

#### 步骤 3：动态 Auth header
`RadioService.initializePlayer()` 当前建 ExoPlayer 时 `DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Authorization" to ...))`，但 WebDAV 凭证是用户输入授权码后才有的——服务启动时凭证可能为空。

**改法**：让 `PlayerManager` 持有 `WebDavClient`，凭证变化时**重建 ExoPlayer**（或用 `MediaSourceFactory` 动态切换）。最简单：凭证变化时调 `RadioService` 的接口重建 player：

```kotlin
// RadioService.kt 加方法
fun rebuildPlayer(authHeader: String) {
    mediaSession?.player?.release()
    initializePlayer(authHeader)   // 把 authHeader 传进来
}
```

`PlayerManager.autoConnect` 成功后调 `controller.sendCustomCommand(...)` 通知 service 重建——但 MediaController 不支持直接调 service 方法。

**更简单方案**：把 Auth header 作为请求属性写在 MediaItem 上，让 ExoPlayer 每次请求时带：

```kotlin
// MediaItem 支持 per-request header (Media3 1.2+)
MediaItem.Builder()
    .setUri(uri)
    .setCustomCacheKey(...)   // 不行，这不是 header
// 正确做法：用 LocalConfiguration
```

实际上 Media3 的 `DefaultHttpDataSource.Factory` 是全局的，per-MediaItem header 需要用 `HttpDataSource.Factory` 的子类或 `setUserAgent`/拦截器。**最干净的做法**：把 `WebDavClient.authHeader()` 注入一个 OkHttp 拦截器（见 P2-2 单例化后），拦截器对所有 `*.123pan.cn` 请求加 Auth，ExoPlayer 走 OkHttp DataSource 即可。

但这又要引入 `media3-datasource-okhttp` 依赖。**短期最小改动方案**：

- 凭证变化时 `RadioService` 整体重启（`stopService` + `startService`），ExoPlayer 用新凭证重建。
- `PlayerManager.connect()` 时把当前 `WebDavClient.authHeader()` 通过 `MediaController` 的 `setMediaItems` 隐式带上——不行，header 不在 MediaItem 里。

**结论**：这一步是本批最复杂的点。推荐分两小步：
1. 先让 `RadioService` 启动时读取已保存凭证（`RadioPreferences.webDavPassword.first()`），建带 Auth 的 ExoPlayer；
2. 凭证变化时 `stopService + startService` 重建。够用。

#### 步骤 4：UI 改造

`OperaViewModel`：
- 删除 `exoPlayer` 字段、`initPlayer()`、`positionUpdater`、`onCleared` 里的 player 释放
- `playFile` 改为调 `playerManager.playOperaFile(...)`
- `playNext/playPrevious` 改为调 `playerManager.playOperaNext()`
- `positionMs/durationMs/isPlaying` 从 `playerManager` 的 StateFlow 派生，不再自己维护
- `currentFile` 由 `playerManager.operaPlaylist + operaCurrentIndex` 推导

`MiniOperaPlayer`（戏曲底部播放条）：状态来自 `playerManager`，逻辑不变。

**通知栏**：`RadioService` 已有 `MediaSession`，戏曲播放时自动显示通知（Media3 默认行为），无需额外代码。可在 `MediaSession.Builder` 加 `setSessionActivity` 让点击通知回到戏曲页。

#### 步骤 5：测试矩阵

| 场景 | 期望 |
|------|------|
| 戏曲播放中按 Home | 继续播放 |
| 戏曲播放中锁屏 | 继续播放，锁屏控件可见 |
| 通知栏点暂停 | 戏曲暂停 |
| 戏曲播放中切到电台页点播放 | 戏曲停，电台起 |
| 电台播放中切到戏曲页点播放 | 电台停，戏曲起 |
| 戏曲播放中拔耳机 | 暂停（`setHandleAudioBecomingNoisy(true)` 已配） |
| 戏曲播放完一首 | 自动下一首（`STATE_ENDED` → `playOperaNext`） |
| 戏曲播放中杀进程 | 重启后能恢复（保存 last opera fileId） |

**风险**：高。涉及播放架构核心，改不好电台和戏曲都会坏。建议：
1. 先在新分支开发；
2. 电台功能作为回归基线，每改一步都验证电台不受影响；
3. 完成后灰度给少数用户。
**完成标准**：上述测试矩阵全过；老年用户在微信/锁屏场景下戏曲不停。
**估时**：1-2 天（含调试与测试）。

---

## 汇总

| 问题 | 批次 | 估时 | 风险 | 备注 |
|------|------|------|------|------|
| P0-6 ProGuard | 1 | 30 min | 低 | 补 keep 规则 |
| P2-3 Room 迁移 | 1 | 15 min | 低 | 加 fallback |
| P2-6 onTaskRemoved | 1 | 0-10 min | 无 | 已确认非 bug，待产品决策 |
| P2-10 versionCode | 1 | 15 min | 低 | git count |
| P1-1 scope 泄漏 | 1 | 20 min | 低 | cancelChildren |
| P1-2 缓存线程安全 | 1 | 30 min | 极低 | ConcurrentHashMap |
| P1-8 删 infinity | 1 | 30 min | 中 | 改走分层加载 |
| P2-2 OkHttp 单例 | 1 | 45 min | 低 | DI provider |
| P2-1 XML 解析 | 2 | 2 h | 中 | XmlPullParser |
| P1-7 collect 重构 | 2 | 1.5 h | 中 | combine + stateIn |
| P1-6 下载断点续传 | 2 | 3 h | 中高 | Range + .part + 取消 |
| P0-5 戏曲统一 Service | 2 | 1-2 天 | 高 | 架构改造 |

**第 1 批合计**：约 3 小时（含测试），完成后可发 patch 版本。
**第 2 批合计**：约 2-3 天，完成后戏曲具备后台播放能力。

---

## 执行建议

1. **先做第 1 批**：8 项独立、低风险，半天搞定。建议在当前分支直接提交，发一个 `1.0.1` 版本。
2. **第 2 批开新分支** `feature/opera-background-play`：P0-5 改动大，单独分支便于回滚。
3. **每项完成后更新本文档**：在对应小节末尾加 `状态：已完成 / commit: xxxx`。
4. **测试重点**：P0-5 必须在真机（非模拟器）上测后台播放和通知栏，模拟器行为可能不一致。
