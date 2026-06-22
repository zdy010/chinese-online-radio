# 代码审查报告：中文收音机 + 河南地方戏点播 App

**审查范围**：`app/src/main/java/com/radio/chinese/` 全部源码、Gradle 配置、Manifest
**审查日期**：2026-06-21
**项目定位**：面向老年人的网络收音机 + 河南/地方戏曲点播（WebDAV 源）

---

## 一、总体评价

项目整体技术栈现代（Kotlin + Compose + Media3 + Hilt + Room），分层清晰，电台部分的多源可用性打分机制（`SourceAvailabilityStore`）和 WebDAV 路径编码处理（见 `BUGFIX_REPORT.md`）体现了迭代经验。但存在 **6 个 P0 严重问题**，主要集中在安全与播放架构，必须优先修复，否则：

1. 任何人反编译 APK 即可拿到 WebDAV 账号密码；
2. 戏曲功能在老年人最需要的「后台播放 / 通知栏控制」场景下完全失效；
3. Release 构建大概率因缺少 ProGuard 规则而崩溃。

下面按严重等级展开。

---

## 二、P0 严重问题（必须立即修复）

### P0-1. WebDavClient 信任所有 SSL 证书 — 中间人攻击漏洞
**文件**：`service/WebDavClient.kt:69-97`

```kotlin
val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
})
...
.hostnameVerifier { _, _ -> true } // 信任所有主机名
```

**风险**：`checkServerTrusted` 空实现 + 信任所有主机名 = 任意中间人节点都可拦截 HTTPS 流量，窃取 WebDAV 账号密码、戏曲文件路径，甚至注入恶意响应。注释虽写"用于开发测试"，但代码已进入生产路径。

**修复**：删除自定义 TrustManager，使用系统默认证书校验。如自建 WebDAV 用的是自签证书，应通过 `Network Security Configuration`（`res/xml/network_security_config.xml`）只针对该域名信任指定根证书，而非全局信任。

---

### P0-2. WebDAV 凭证与授权码硬编码在源码
**文件**：`service/YunPanConfig.kt`

```kotlin
const val AUTH_CODE = "7554"
const val WEBDAV_SERVER_URL = "https://webdav-1855080734.pd1.123pan.cn"
const val WEBDAV_USERNAME = "13826527554"
const val WEBDAV_PASSWORD = "gw7ym269"
```

**风险**：
- APK 反编译（`jadx` 一键）即可拿到 WebDAV 账号密码，对方可登录云盘删除/篡改全部戏曲资源；
- 所谓"授权码"机制（4 位数字 `7554`）形同虚设：它本身在代码里，且真正的访问凭证（WebDAV 账密）也都在代码里，授权码只是"假门"。

**修复方向**：
1. 凭证不应打包进 APK。改为服务端代理：App 请求你自己的后端，后端持有 WebDAV 凭证并做鉴权转发，App 永远拿不到原始凭证。
2. 若坚持直连 WebDAV，至少在 123pan 后台为该账号单独设置一个**只读 + 仅限指定目录**的子账号，并把凭证存到 `EncryptedSharedPreferences`（Android Keystore 加密），由用户首次使用时通过扫码/分享链接激活，而非硬编码。

---

### P0-3. WebDAV 密码明文存储在 DataStore
**文件**：`data/local/RadioPreferences.kt:185-203`、`ui/opera/OperaViewModel.kt:167-171`

`RadioPreferences.saveWebDavCredentials()` 把 `username` / `password` 直接写入 `DataStore`（明文 XML 文件，位于 `/data/data/<pkg>/files/datastore/`）。在已 root 设备或通过 `adb backup`（见 P0-4）即可导出。

**修复**：使用 Jetpack Security 的 `EncryptedSharedPreferences` 或 Tink 库加密存储；或干脆按 P0-2 走服务端代理，本地不存凭证。

---

### P0-4. 允许应用备份 + 明文流量
**文件**：`AndroidManifest.xml:15, 21`

```xml
android:allowBackup="true"
android:usesCleartextTraffic="true"
```

**风险**：
- `allowBackup="true"`：用户通过 `adb backup` 即可导出包含 WebDAV 凭证的 DataStore 文件；
- `usesCleartextTraffic="true"`：允许全局明文 HTTP，配合 P0-1 的信任所有证书，网络安全防线全面失守。

**修复**：
- `allowBackup="false"`（含敏感凭证时必须关）；
- 删除 `usesCleartextTraffic="true"`，改用 `network_security_config.xml` 仅对必要的明文流（如部分老旧电台 HTTP 流）放行。

---

### P0-5. 戏曲播放绕过 MediaSession，无法后台播放
**文件**：`ui/opera/OperaViewModel.kt:73, 316-360`

戏曲播放由 `OperaViewModel` 内部 `new ExoPlayer.Builder(context)...build()` 直接创建播放器，完全不走 `RadioService`（`MediaSessionService`）。后果：

| 能力 | 收音机（走 PlayerManager+RadioService） | 戏曲（OperaViewModel 自建 ExoPlayer） |
|------|------|------|
| 后台播放 | ✅ | ❌ Activity 销毁即停 |
| 通知栏控制 | ✅ | ❌ 无通知 |
| 锁屏控制 | ✅ | ❌ |
| 耳机线控 | ✅ | ❌ |
| 与收音机互斥 | ✅（playerManager.stop()） | 单向停止收音机，反向无 |

**对老年人的影响最致命**：戏曲是长音频（一整出戏几十分钟到几小时），用户切到微信、关屏，戏曲就停了 —— 这恰恰是老年用户的核心使用场景。

**修复**：戏曲播放也走 `RadioService`，统一通过 `PlayerManager` 控制。`OperaViewModel` 只负责"准备 MediaItem + 交给 PlayerManager"，不持有 ExoPlayer。可给 MediaItem 加 `MediaMetadata` 区分电台/戏曲，UI 根据类型显示不同控制条。

---

### P0-6. Release 构建缺少 ProGuard 规则，极可能崩溃
**文件**：`app/build.gradle.kts:35-41`

```kotlin
release {
    isMinifyEnabled = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"   // 该文件不存在（Glob 查找未找到）
    )
}
```

项目用了 Room、Hilt、kotlinx.serialization、Gson(`@SerializedName`)、Retrofit，这些在 R8 混淆后都需要 keep 规则。缺少 `proguard-rules.pro` 文件会导致：
- Room 生成的 DAO 实现被混淆 → 数据库操作崩溃；
- Gson 反序列化的 data 类字段被重命名 → 全部为 null；
- Hilt 注入失败；
- kotlinx.serialization 反射失败。

**修复**：创建 `app/proguard-rules.pro`，至少包含：

```proguard
# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.Dao { <methods>; }

# Gson
-keepattributes Signature, *Annotation*
-keep class com.radio.chinese.domain.model.** { *; }

# kotlinx.serialization
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class **$$serializer { *; }

# Hilt / Dagger (官方规则已内置，但保险起见)
-keep class dagger.hilt.** { *; }
```

---

## 三、P1 重要问题

### P1-1. PlayerManager 协程作用域泄漏
**文件**：`service/PlayerManager.kt:33`

```kotlin
private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
```

`PlayerManager` 是 `@Singleton`，生命周期 = 进程。`scope` 内启动的协程永远不会随播放停止而取消，`disconnect()` 也未取消 scope。虽然单次影响小，但长期运行会累积。

**修复**：`disconnect()` 中 `scope.cancel()`；或将 scope 与播放会话绑定。

---

### P1-2. 缓存非线程安全
**文件**：`service/WebDavClient.kt:58`、`service/OperaRepository.kt:31`

```kotlin
private val listFilesCache = mutableMapOf<String, List<WebDavFile>>()
private val fileCache = mutableMapOf<String, List<OperaAudioFile>>()
```

两者都在 `Dispatchers.IO` 多协程下被读写，`mutableMapOf` 非线程安全，可能出现 `ConcurrentModificationException` 或数据错乱。

**修复**：改用 `ConcurrentHashMap`，或用 `Mutex` 包裹读写，或改用 `StateFlow<Map<...>>` 不可变方案。

---

### P1-3. 老年人输入授权码门槛过高且无意义
**文件**：`ui/opera/OperaScreen.kt:93`（AuthCodeContent）、`YunPanConfig.AUTH_CODE`

要求老年人手动输入 4 位授权码 `7554` 才能用戏曲。问题：
1. 老年人记不住、看不清、输入困难；
2. 授权码本身在代码里（P0-2），起不到任何保护作用，纯增加使用阻力。

**修复**：删除授权码机制，或改为首次启动时自动激活（扫码 / 服务端下发激活状态）。

---

### P1-4. 错误提示对老年人不友好
**文件**：`ui/opera/OperaViewModel.kt:143, 185, 356` 等多处

```kotlin
"WebDAV连接失败: ${e.message ?: "未知错误"}"
"播放失败(code=$code): $msg"
"HTTP ${response.code}"
```

老年人看到"WebDAV""code=ERROR_CODE_IO_NETWORK"完全无感。应转换为："网络不好，请检查 WiFi""戏曲源暂时无法播放，已为你换下一个"等。

**修复**：建一个 `FriendlyErrorMapper`，把异常类型/错误码映射成老年人能懂的话，并配合重试按钮。

---

### P1-5. 缺少无障碍与适老化设计
全局扫描 UI 代码，未看到：
- 系统字体缩放适配（`fontScale` 支持差，多用 `sp` 但未验证超大字体的布局溢出）；
- 触摸目标普遍偏小（按钮 < 48dp，列表项 < 56dp，老年人推荐 ≥ 56dp）；
- `contentDescription` 在大量纯图标按钮上缺失或为 `null`；
- 高对比度模式支持。

**修复**：审计所有 `IconButton`、`Icon`，补 `contentDescription`；列表项最小高度 ≥ 56dp；主操作按钮 ≥ 56dp；测试开启系统"超大字体"下的布局。

---

### P1-6. 下载不支持取消、断点续传、失败清理
**文件**：`service/OperaRepository.kt:227-290`

```kotlin
suspend fun downloadFile(...): Result<String> = withContext(Dispatchers.IO) {
    ...
    client.newCall(request).execute().use { response ->
        ...
        while (inputStream.read(buffer).also { bytesRead = it } != -1) { ... }
    }
}
```

问题：
- 用户无法中途取消下载（无 `Call` 引用，未支持 `cancel`）；
- 网络中断后从头重下，几十 MB 的戏曲文件对老年人流量不友好；
- 下载失败时 `targetFile` 半成品残留在磁盘，无清理；
- `outputStream.close()` 未放在 `finally` / `use` 中，异常路径下流不关闭。

**修复**：保存 `Call` 引用支持取消；用 `Range` 请求头做断点续传；用 `outputStream.use { }` 确保关闭；失败时 `targetFile.delete()`。

---

### P1-7. `loadDownloaded()` 在 init 中启动永不结束的 collect
**文件**：`ui/opera/OperaViewModel.kt:98, 482-484`

```kotlin
init {
    loadDownloaded()
}
private fun loadDownloaded() {
    viewModelScope.launch { operaRepository.allDownloaded.collect { list -> _uiState.update { ... } } }
}
```

`allDownloaded` 是 Room 的 `Flow`，永不结束。虽然 `viewModelScope` 会在 `onCleared` 取消，但每次进入戏曲页都会新建 ViewModel → 新建一个永久 collect，若用户在导航中反复进出会有多个同时存在。且 `_uiState.update` 写法把整个 `localDownloads` 替换，`downloadProgress` 等其它字段会丢失（被旧值覆盖）。

**修复**：改用 `combine` 把 `allDownloaded` 与其他状态流合并成一个 `uiState`，UI 直接 `collectAsState`，不要在 `init` 里手动 collect 再 update。

---

### P1-8. `searchFiles` 用 `Depth: infinity` 性能隐患
**文件**：`service/WebDavClient.kt:196`

```kotlin
.header("Depth", "infinity")
```

`PROPFIND Depth: infinity` 会递归返回根目录下所有文件，123pan WebDAV 在戏曲文件较多时响应可能很大（数十 MB XML）且超时。很多 WebDAV 服务端默认禁用 infinity。

**修复**：限制为 `Depth: 1` 分层加载，搜索改为客户端遍历分类目录（已有 `fileCache`，可基于缓存搜索，`OperaRepository.searchLocalCache` 已是此思路，应彻底去掉 infinity 路径）。

---

## 四、P2 代码质量问题

### P2-1. 用正则解析 XML，脆弱
**文件**：`service/WebDavClient.kt:243-303` `parsePropfindResponse`

手写正则 `<D:response[^>]*>` + `indexOf` 切片解析 WebDAV PROPFIND XML。问题：
- 不支持 CDATA、自闭合、命名空间前缀变化（如 `d:response` 小写）；
- 嵌套 `<D:response>`（部分服务器嵌套）会错切；
- 性能差（多次全文扫描）。

**修复**：用 `XmlPullParser` 或 `javax.xml.parsers.DocumentBuilder` 解析。Android 自带，无额外依赖。

---

### P2-2. 多处重复创建 OkHttpClient
`WebDavClient`、`OperaRepository`、`StreamChecker` 各 `new OkHttpClient.Builder()`。OkHttp 文档明确推荐单例。

**修复**：在 `AppModule` 里 `@Provides @Singleton fun provideOkHttpClient()`，统一配置超时、拦截器、证书，注入到各处。

---

### P2-3. Room 无迁移策略
**文件**：`di/AppModule.kt:22-28, 37-43`

```kotlin
Room.databaseBuilder(...).build()  // 无 fallbackToDestructiveMigration，无 Migration
```

未来 `DownloadedOpera` 加字段（如下载状态、收藏标记）会直接 `IllegalStateException` 崩溃。

**修复**：加 `.fallbackToDestructiveMigration()`（可接受丢数据时）或定义 `Migration(1,2)`。

---

### P2-4. `fileId` 用 `href.hashCode()`，碰撞风险
**文件**：`WebDavClient.kt:320, 325, 332`、`OperaRepository.kt:65, 81, 93, 118, 136`

```kotlin
OperaCategory(folderId = it.href.hashCode().toLong(), ...)
```

`String.hashCode()` 是 32 位 int，碰撞概率不可忽视；不同文件可能拿到相同 `fileId`，导致下载记录互相覆盖、收藏错乱。`toLong()` 也只是把 int 符号扩展，并未增加信息量。

**修复**：用 `href` 字符串本身作为主键，或用 `href.sha256().take(16).hex` 之类稳定 ID。

---

### P2-5. 日志泄露敏感信息
**文件**：`WebDavClient.kt:91-95, 125-129, 176-180`、`OperaRepository.kt:61`

```kotlin
Log.d(TAG, "--> ${req.method} ${req.url}")   // URL 可能含 token
Log.d(TAG, "verifyConnection body preview: ${bodyString.take(500)}")
files.forEach { Log.d("OperaRepo", "  -> ${it.displayName} ... href=${it.href}") }
```

Release 包若未移除 `Log.d`，logcat 上任何应用都能读到。URL、响应体、文件路径全部泄露。

**修复**：用 `BuildConfig.DEBUG` 包裹日志，或引入 Timber 在 Release 自动禁用。

---

### P2-6. `onTaskRemoved` 逻辑反直觉
**文件**：`service/RadioService.kt:64-70`

```kotlin
override fun onTaskRemoved(rootIntent: Intent?) {
    val player = mediaSession?.player
    if (player != null && !player.playWhenReady) {
        stopSelf()
    }
    super.onTaskRemoved(rootIntent)
}
```

只在 `!playWhenReady`（即暂停状态）时 stopSelf，意味着用户在播放中划掉任务反而不会停 —— 但用户期望"划掉=停止"。建议改为：未在播放则 stopSelf，正在播放则保持（符合 Media3 官方推荐）。

---

### P2-7. `MainScreen` 底部导航缺"设置"入口
`bottomNavItems` 只有首页/戏曲/分类/收藏，设置页只能从首页进。老年人找设置会困难。建议把"设置"也放底栏，或首页显著位置常驻入口。

---

### P2-8. 主题切换逻辑冗余
**文件**：`MainActivity.kt:40-55`

```kotlin
val themeMode by preferences.themeMode.collectAsState(initial = 0)
var currentThemeMode by remember { mutableIntStateOf(themeMode) }
LaunchedEffect(themeMode) { currentThemeMode = themeMode }
```

`themeMode` 已经是可观察的，再复制一份 `currentThemeMode` + `LaunchedEffect` 同步，是多余的。直接用 `themeMode` 即可。

---

### P2-9. 声明了未使用的权限与导出组件
**文件**：`AndroidManifest.xml:11, 46-52`

- `RECEIVE_BOOT_COMPLETED` 声明了但没有对应 Receiver，可删；
- `MediaButtonReceiver` 与 `RadioService` 均 `exported="true"`，Media3 的服务可设 `exported="false"` + 显式 intent，减少攻击面。

---

### P2-10. `versionCode` 永远为 1
**文件**：`app/build.gradle.kts:18`

无法发布增量更新，应用商店会拒绝。建议接入 `versionCode` 自增（如基于 git commit count）。

---

## 五、改进方向（优先级排序）

### 第一阶段：安全加固（1-2 天）
1. 删除 `WebDavClient` 的信任所有证书；
2. 凭证移出 APK：搭一个简易后端代理 WebDAV，或至少改用只读子账号 + `EncryptedSharedPreferences`；
3. `allowBackup=false`，移除 `usesCleartextTraffic=true`，加 `network_security_config.xml`；
4. 创建 `proguard-rules.pro`，跑通 Release 构建。

### 第二阶段：统一播放架构（2-3 天）
1. 戏曲播放改造为走 `RadioService` + `PlayerManager`，删除 `OperaViewModel` 内的 ExoPlayer；
2. MediaItem 用 `MediaMetadata` 区分电台/戏曲，UI 按类型渲染；
3. 实现戏曲的后台播放、通知栏、耳机线控。

### 第三阶段：适老化与稳定性（2-3 天）
1. 移除授权码机制；
2. 错误提示白话化；
3. 触摸目标、字体缩放、contentDescription 审计；
4. 下载支持取消 + 断点续传 + 失败清理；
5. 缓存改 `ConcurrentHashMap`。

### 第四阶段：代码质量（持续）
1. XML 解析换 `XmlPullParser`；
2. `OkHttpClient` 单例化；
3. `fileId` 换稳定算法；
4. Room 加迁移策略；
5. 日志用 Timber，Release 关闭；
6. 引入单元测试（WebDAV 解析、可用性打分算法是很好的测试切入点）。

---

## 六、值得肯定的设计

不是全坏，以下几点做得不错，建议保留：

1. **多源可用性打分**（`SourceAvailabilityStore`）：电台有多源时自动学习哪个最稳，失败降级、成功加分，体验上比"随机选源"好很多。
2. **播放位置记忆**（`RadioPreferences.saveOperaPlayPosition`）：戏曲长音频断点续听，对老年人很实用，思路正确（实现可优化为只存最近 20 条，已是如此，OK）。
3. **WebDAV 路径编码处理**（见 `BUGFIX_REPORT.md`）：保持 href 原始编码形式不 decode，是正确做法，避免了中文路径反复编解码的坑。
4. **技术栈选型**：Compose + Media3 + Hilt 是当前 Android 主流，长期可维护性好。

---

*报告完。建议按"第一阶段 → 第二阶段"顺序推进，前两个阶段完成后应用才具备发布给老年用户使用的基本条件。*
