# 我的音频库 — 重构设计方案

> 2026-06-24 · 取代现有"戏曲"栏目
> 2026-06-25 · Review 修正：凭证安全 / 本地存储 / M3U 区分 / 缓存失效 / ExoPlayer 协议 / 老年用户优化

---

## 1. 目标

把「戏曲」（单源 WebDAV + 授权码）重构为「我的音频库」：

- 用户自主添加多种来源的音频库
- 来源一次配置、自动持久化
- 进入后直接展示已添加的库，逐级展开浏览
- **不再需要授权码**，每个音频库自带认证信息

---

## 2. 支持的音频来源

### 2.1 首期支持（4 种）

| 来源 | 典型场景 | 复杂度 |
|------|----------|--------|
| **本地存储** | 手机内存/外置 SD 卡里的 mp3/flac 等 | ⭐⭐ 中（需同时支持 MediaStore + SAF） |
| **WebDAV** | 123云盘/NAS/自建网盘（现有戏曲功能的来源） | ⭐⭐ 中（已有 WebDavClient） |
| **M3U 播放列表** | 网络电台合集、播客列表（点播，可解析浏览） | ⭐⭐ 中 |
| **HTTP(S) 直链** | 单个音频文件、Icecast 流、HLS 直播 (.m3u8) | ⭐ 低 |

> **M3U8 (HLS)** 不作为独立来源类型，而是 HTTP 直链的一种——当 URL 以 `.m3u8` 结尾时，ExoPlayer 自动走 HLS 自适应码率播放，无需解析子项。

### 2.2 ExoPlayer 原生支持的流媒体协议

ExoPlayer (Media3) 已内置以下协议支持，只需添加对应可选依赖：

| 协议 | Gradle 依赖 | 说明 |
|------|-------------|------|
| HLS | `media3-exoplayer-hls` | `.m3u8` 自适应流，自动码率切换 |
| DASH | `media3-exoplayer-dash` | `.mpd` 自适应流 |
| SmoothStreaming | `media3-exoplayer-smoothstreaming` | `.ism` 微软流媒体 |

**首期只需加 `media3-exoplayer-hls`**，即可覆盖 HLS 直播/点播场景。

### 2.3 后续扩展

| 来源 | 说明 |
|------|------|
| **SMB/CIFS** | NAS 共享文件夹（Samba），Android 上需要 jcifs 库 |
| **FTP(S)** | 古老的协议，但大量 NAS 和服务器还支持 |
| **DLNA/UPnP** | 局域网媒体服务器，Cling 库即可 |
| **Subsonic / Navidrome** | 自建音乐服务器，API 非常简单 |
| **Jellyfin / Emby** | 家庭媒体中心，有完整 REST API |
| **百度/阿里云盘** | 国内常用网盘，用官方 SDK |
| **Google Drive / OneDrive** | 网盘音频，用官方 SDK |

---

## 3. 数据模型

### 3.1 Room 实体

```kotlin
// 音频库来源
@Entity(tableName = "audio_sources")
data class AudioSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                    // 用户自定义名称，如"123云盘豫剧"
    val type: SourceType,                // LOCAL / WEBDAV / M3U / HTTP / M3U8
    val url: String,                     // 根地址 / 播放列表 URL / M3U8 地址
    val username: String = "",           // 认证用户名
    val encryptedPassword: String = "",  // 认证密码（加密存储，见 §3.3）
    val iconUri: String = "",            // 可选图标
    val sortOrder: Int = 0,              // 排序
    val createdAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long = 0,            // 最后缓存同步时间
    val cacheTtlMs: Long = 86_400_000    // 缓存有效期，默认 24 小时
)

enum class SourceType { LOCAL, WEBDAV, M3U, HTTP, SMB, FTP, SUBSONIC }

// 缓存的目录/文件项（浏览器展开后就缓存，加速二次访问）
@Entity(
    tableName = "audio_cache",
    indices = [Index("sourceId"), Index("parentPath")],
    foreignKeys = [ForeignKey(
        entity = AudioSourceEntity::class,
        parentColumns = ["id"],
        childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class AudioCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val path: String,                    // 在源中的完整路径
    val parentPath: String,              // 父目录路径（用于树展开）
    val name: String,                    // 显示名称
    val isFolder: Boolean = false,
    val size: Long = 0,
    val mimeType: String = "",           // audio/mpeg 等
    val cachedAt: Long = System.currentTimeMillis()
)

// 收藏标记（老年用户友好）
@Entity(
    tableName = "audio_favorites",
    indices = [Index("trackPath", unique = true)]
)
data class AudioFavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val trackPath: String,               // 文件在源中的路径
    val trackName: String,               // 显示名称（缓存冗余）
    val sourceName: String,              // 来源名称（缓存冗余）
    val favoritedAt: Long = System.currentTimeMillis()
)

// 最近播放记录
@Entity(
    tableName = "audio_recent",
    indices = [Index("trackPath", unique = true)]
)
data class AudioRecentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val trackPath: String,
    val trackName: String,
    val sourceName: String,
    val playedAt: Long = System.currentTimeMillis(),
    val positionMs: Long = 0             // 断点续听位置
)
```

### 3.2 领域模型

```kotlin
data class AudioSource(
    val id: Long,
    val name: String,
    val type: SourceType,
    val url: String,
    val auth: AuthInfo? = null
)

data class AuthInfo(val username: String, val password: String)

// 与现有 OperaAudioFile 类似但更通用
data class AudioTrack(
    val id: String,                      // path 的 hash
    val name: String,
    val path: String,
    val size: Long,
    val sourceId: Long,
    val isFolder: Boolean,
    val parentPath: String,
    val isHlsStream: Boolean = false     // HLS 流标记，ExoPlayer 特殊处理
)
```

### 3.3 凭证安全（Review 修正）

**问题**：`password` 明文存 Room 有风险，root 过的设备可被直接读取。

**方案**：使用 `EncryptedSharedPreferences`（AndroidX Security 库）加密存储密码，Room 只存密文。

```kotlin
// build.gradle.kts
implementation("androidx.security:security-crypto:1.1.0-alpha06")

// CredentialManager.kt
@Singleton
class CredentialManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context, "audio_credentials", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun savePassword(sourceId: Long, password: String) {
        prefs.edit().putString("pwd_$sourceId", password).apply()
    }

    fun getPassword(sourceId: Long): String? = prefs.getString("pwd_$sourceId", null)

    fun deletePassword(sourceId: Long) {
        prefs.edit().remove("pwd_$sourceId").apply()
    }
}
```

`AudioSourceEntity` 中的 `encryptedPassword` 字段实际存的是"是否已设置密码"的标记（`"1"` 或 `""`），真正的密码通过 `CredentialManager` 读取。

---

## 4. 架构设计

```
┌─────────────────────────────────────────────────────┐
│  UI Layer                                           │
│  AudioLibraryScreen  ───  AudioLibraryViewModel     │
│  AddSourceDialog     ───  BrowseTreeView            │
│  FavoritesBar        ───  RecentBar                 │
│  MiniPlayer (复用现有)                              │
├─────────────────────────────────────────────────────┤
│  Domain Layer                                       │
│  AudioLibraryRepository                             │
│  ┌──────────┬──────────┬──────────┬──────────┐     │
│  │  Local   │ WebDAV   │  M3U     │  HTTP    │     │
│  │ Browser  │ Browser  │ Browser  │ Browser  │     │
│  └──────────┴──────────┴──────────┴──────────┘     │
│      每个 Browser 实现 AudioBrowser 接口             │
├─────────────────────────────────────────────────────┤
│  Data Layer                                         │
│  Room DB │ EncryptedSharedPreferences │ MediaStore  │
│  WebDavClient(现有) │ SAF DocumentFile              │
└─────────────────────────────────────────────────────┘
```

### 4.1 AudioBrowser 接口

```kotlin
interface AudioBrowser {
    /** 列出指定路径下的子项 */
    suspend fun listChildren(path: String): Result<List<AudioTrack>>

    /** 获取文件的播放 URI（含鉴权） */
    fun resolveUri(track: AudioTrack): String

    /** 测试连接 */
    suspend fun testConnection(): Result<Unit>

    /** 是否为流媒体（HLS/DASH/HLS 等，ExoPlayer 直接处理，不解析子项） */
    fun isStreamSource(): Boolean = false
}
```

### 4.2 各 Browser 实现

| Browser | 实现要点 |
|---------|---------|
| `LocalBrowser` | **双通道**：① `MediaStore.Audio` 扫描系统索引音频；② `DocumentFile` (SAF) 支持用户自选目录，持久化 URI 权限 |
| `WebDavBrowser` | 封装现有 `WebDavClient`，去掉授权码限制 |
| `M3uBrowser` | `OkHttp` 拉回播放列表文本，逐行解析 `#EXTINF`，**仅解析 `.m3u`**；`.m3u8` 不在此处理 |
| `HttpBrowser` | 单文件：直接返回；`.m3u8` 结尾：`isStreamSource()=true`，直接传 ExoPlayer 走 HLS |

### 4.3 缓存失效机制（Review 修正）

```kotlin
class AudioLibraryRepository @Inject constructor(
    private val audioCacheDao: AudioCacheDao,
    private val sourceDao: AudioSourceDao,
    // ...
) {
    suspend fun listChildren(source: AudioSource, path: String): Result<List<AudioTrack>> {
        // 1. 检查缓存是否在有效期内
        val sourceEntity = sourceDao.getById(source.id)
        val now = System.currentTimeMillis()
        val isCacheValid = sourceEntity != null &&
            now - sourceEntity.lastSyncAt < sourceEntity.cacheTtlMs

        if (isCacheValid) {
            val cached = audioCacheDao.getByParentPath(source.id, path)
            if (cached.isNotEmpty()) {
                return Result.success(cached.map { it.toDomain() })
            }
        }

        // 2. 缓存过期或为空，从 Browser 重新加载
        val browser = browserFactory.create(source)
        val result = browser.listChildren(path)
        result.onSuccess { items ->
            // 3. 更新缓存
            audioCacheDao.deleteByParentPath(source.id, path)
            audioCacheDao.insertAll(items.map { it.toEntity(source.id) })
            sourceDao.updateSyncTime(source.id, now)
        }
        return result
    }

    /** 手动刷新（下拉刷新触发） */
    suspend fun refreshSource(source: AudioSource, path: String): Result<List<AudioTrack>> {
        audioCacheDao.deleteBySource(source.id)
        sourceDao.updateSyncTime(source.id, 0) // 强制过期
        return listChildren(source, path)
    }
}
```

---

## 5. UI 设计

### 5.1 主页面

```
┌──────────────────────────┐
│  ← 返回    我的音频库  │＋│  ← 顶部栏带添加按钮
├──────────────────────────┤
│  ⭐ 收藏 (3)              │  ← 收藏快捷入口
│  🕐 最近播放 (5)          │  ← 最近播放快捷入口
├──────────────────────────┤
│  📁 123云盘·豫剧          │
│  webdav://.....123pan.cn │  ← 已添加的音频库列表
├──────────────────────────┤
│  📁 NAS·无损音乐          │
│  smb://192.168.1.100/    │
├──────────────────────────┤
│  📄 经典电台合集          │
│  m3u://http://.../list   │
├──────────────────────────┤
│  📱 手机本地音乐          │
│  本地存储                 │
├──────────────────────────┤
│                          │
│   （空状态提示）          │
│   点击右上角 ＋ 添加      │
│                          │
└──────────────────────────┘
```

### 5.2 添加音频库弹窗

```
┌──────────────────────────┐
│     添加音频库           │
├──────────────────────────┤
│  来源类型: [下拉选择]     │
│  ○ 本地存储              │
│  ○ WebDAV               │
│  ○ M3U 播放列表          │
│  ○ HTTP(S) 直链         │
├──────────────────────────┤
│  名称: [输入框]          │
│  URL/地址: [输入框]      │
│  (WebDAV 显示) │
│  用户名: [输入框]        │
│  密码:   [输入框]        │
│  (仅 WebDAV 显示)        │
├──────────────────────────┤
│  [本地存储点此选择文件夹] │
│  (仅 LOCAL 类型显示)      │
├──────────────────────────┤
│      [测试连接] [保存]   │
└──────────────────────────┘
```

### 5.3 本地存储选择（SAF）

本地存储类型点击后触发系统文件选择器：

```kotlin
// 选择文件夹
val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
startActivityForResult(intent, REQUEST_SAF_FOLDER)

// 持久化 URI 权限
contentResolver.takePersistableUriPermission(
    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
)
```

用户选择的文件夹 URI 存入 `AudioSourceEntity.url`，后续通过 `DocumentFile.fromTreeUri()` 遍历子文件。

### 5.4 浏览视图（逐级展开）

```
┌──────────────────────────┐
│  ←  123云盘·豫剧     ↻   │  ← 右上角刷新按钮
├──────────────────────────┤
│  📁 豫剧                  │
│  📁 曲剧                  │
│  📁 越调                  │
│  📁 二夹弦                │
│  ...                     │
├──────────────────────────┤
│  (展开后又二级目录)       │
│  📁 豫剧 >               │
│    📁 花木兰              │
│    📁 朝阳沟              │
│  📁 曲剧 >               │
│    📁 卷席筒              │
├──────────────────────────┤
│    (最终展开到文件)       │
│  🎵 花木兰-第一场.mp3  ⭐ │  ← 长按收藏
│  🎵 花木兰-第二场.mp3     │  ← 点击即播放
│  ...                     │
└──────────────────────────┘
```

### 5.5 状态转换

```
进入Tab ──→ [空状态:"暂无音频库, 点＋添加"]
         ──→ [已有库: 显示列表 + 收藏/最近快捷入口]
           ──→ 点＋ → 添加弹窗 → 测试连接 → 保存 → 刷新列表
           ──→ 点库 → BrowseScreen(逐级展开, 带刷新) → 点文件 → 播放
           ──→ 长按文件 → 收藏/取消收藏
           ──→ 点收藏 → 收藏列表 → 点文件 → 播放
           ──→ 点最近 → 最近列表 → 点文件 → 续播
```

---

## 6. 与现有代码的关系

### 6.1 保留/复用

| 模块 | 处理方式 |
|------|----------|
| `PlayerManager` | ✅ 完全复用，已有 `playOperaFile` → 改为 `playAudioTrack` |
| `RadioService` | ✅ 不变 |
| `WebDavClient` | ✅ 封装进 `WebDavBrowser` |
| `OperaRepository` | 🔄 重构成 `AudioLibraryRepository` |
| `YunPanConfig` | ❌ 废弃（不再需要全局配置） |
| `AUTH_CODE` 逻辑 | ❌ 删除 |

### 6.2 文件变更清单

```
新增:
  app/src/main/java/com/radio/chinese/data/entity/AudioSourceEntity.kt
  app/src/main/java/com/radio/chinese/data/entity/AudioCacheEntity.kt
  app/src/main/java/com/radio/chinese/data/entity/AudioFavoriteEntity.kt
  app/src/main/java/com/radio/chinese/data/entity/AudioRecentEntity.kt
  app/src/main/java/com/radio/chinese/data/dao/AudioSourceDao.kt
  app/src/main/java/com/radio/chinese/data/dao/AudioCacheDao.kt
  app/src/main/java/com/radio/chinese/data/dao/AudioFavoriteDao.kt
  app/src/main/java/com/radio/chinese/data/dao/AudioRecentDao.kt
  app/src/main/java/com/radio/chinese/data/repository/AudioLibraryRepository.kt
  app/src/main/java/com/radio/chinese/data/CredentialManager.kt
  app/src/main/java/com/radio/chinese/domain/AudioBrowser.kt         (接口)
  app/src/main/java/com/radio/chinese/domain/browser/WebDavBrowser.kt
  app/src/main/java/com/radio/chinese/domain/browser/LocalBrowser.kt
  app/src/main/java/com/radio/chinese/domain/browser/M3uBrowser.kt
  app/src/main/java/com/radio/chinese/domain/browser/HttpBrowser.kt
  app/src/main/java/com/radio/chinese/domain/BrowserFactory.kt
  app/src/main/java/com/radio/chinese/ui/library/AudioLibraryScreen.kt
  app/src/main/java/com/radio/chinese/ui/library/AudioLibraryViewModel.kt
  app/src/main/java/com/radio/chinese/ui/library/BrowseScreen.kt
  app/src/main/java/com/radio/chinese/ui/library/AddSourceDialog.kt
  app/src/main/java/com/radio/chinese/ui/library/FavoritesScreen.kt
  app/src/main/java/com/radio/chinese/ui/library/RecentScreen.kt
  app/src/main/java/com/radio/chinese/data/AppDatabase.kt           (加表)

修改:
  app/src/main/java/com/radio/chinese/service/PlayerManager.kt       (playOperaFile → playAudioTrack)
  app/src/main/java/com/radio/chinese/ui/MainScreen.kt               (戏曲→音频库 tab)
  app/src/main/java/com/radio/chinese/di/AppModule.kt                (新 DI 绑定)
  app/build.gradle.kts                                              (加 security-crypto, media3-exoplayer-hls)

删除:
  app/src/main/java/com/radio/chinese/service/OperaRepository.kt
  app/src/main/java/com/radio/chinese/service/YunPanConfig.kt
  app/src/main/java/com/radio/chinese/ui/opera/OperaScreen.kt
  app/src/main/java/com/radio/chinese/ui/opera/OperaViewModel.kt
```

### 6.3 Room 数据库迁移

旧表 `downloaded_opera` 重命名为 `audio_downloads`，字段通用化：

```kotlin
@Entity(tableName = "audio_downloads")
data class AudioDownloadEntity(
    @PrimaryKey val fileId: Long,
    val sourceId: Long,                  // 来源音频库 ID
    val fileName: String,
    val fileSize: Long,
    val sourceName: String,              // 原 categoryName → sourceName
    val parentPath: String,              // 原 operaName → parentPath
    val localPath: String,
    val downloadedAt: Long = System.currentTimeMillis()
)

@Database(
    version = 2,
    entities = [
        AudioSourceEntity::class,
        AudioCacheEntity::class,
        AudioFavoriteEntity::class,
        AudioRecentEntity::class,
        AudioDownloadEntity::class
    ]
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun audioSourceDao(): AudioSourceDao
    abstract fun audioCacheDao(): AudioCacheDao
    abstract fun audioFavoriteDao(): AudioFavoriteDao
    abstract fun audioRecentDao(): AudioRecentDao
    abstract fun audioDownloadDao(): AudioDownloadDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 重命名旧表
                db.execSQL("ALTER TABLE downloaded_opera RENAME TO audio_downloads")
                // 加新表
                db.execSQL("CREATE TABLE audio_sources (...)")
                db.execSQL("CREATE TABLE audio_cache (...)")
                db.execSQL("CREATE TABLE audio_favorites (...)")
                db.execSQL("CREATE TABLE audio_recent (...)")
            }
        }
    }
}
```

---

## 7. 实施计划

### Phase 1：核心框架（2-3 天）
1. 创建 Room 实体 + DAO + Database migration (v1→v2)
2. 实现 `AudioBrowser` 接口 + `BrowserFactory`
3. 实现 `AudioLibraryRepository`（含缓存失效逻辑）
4. 实现 `CredentialManager`（EncryptedSharedPreferences）
5. 添加 Gradle 依赖：`security-crypto`、`media3-exoplayer-hls`

### Phase 2：WebDAV 适配（1 天）
1. 把现有 `WebDavClient` 包装成 `WebDavBrowser`
2. 实现 `listChildren` / `resolveUri` / `testConnection`

### Phase 3：替换 UI（2-3 天）
1. `MainScreen` 把戏曲 tab 改为音频库 tab
2. 实现 `AudioLibraryScreen` + `AddSourceDialog`
3. 实现逐级展开的 `BrowseScreen`（含下拉刷新）
4. 实现 `FavoritesScreen` + `RecentScreen`

### Phase 4：新增来源（每类 0.5-1 天）
1. 本地存储（MediaStore + SAF 双通道）
2. M3U 播放列表解析（仅 `.m3u`）
3. HTTP 直链（含 `.m3u8` 自动走 HLS）

### Phase 5：清理 + 测试（1 天）
1. 删除 OperaRepository / OperaViewModel / OperaScreen
2. 删除 YunPanConfig
3. 真机完整测试

---

## 8. M3U vs M3U8 处理策略（Review 修正）

| 格式 | 扩展名 | 处理方式 | 示例 |
|------|--------|----------|------|
| **M3U** | `.m3u` | `M3uBrowser` 解析为 `AudioTrack` 列表，可浏览点播 | 电台合集、播客列表 |
| **M3U8 (HLS)** | `.m3u8` | 不解析，`HttpBrowser` 返回单个 `AudioTrack(isHlsStream=true)`，ExoPlayer 原生走 HLS 自适应码率 | 直播流、自适应流 |

```kotlin
class HttpBrowser : AudioBrowser {
    override fun isStreamSource() = true

    override fun resolveUri(track: AudioTrack): String = track.path

    override suspend fun listChildren(path: String): Result<List<AudioTrack>> {
        // .m3u8 直接作为单个流返回
        if (path.endsWith(".m3u8", ignoreCase = true)) {
            return Result.success(listOf(
                AudioTrack(
                    id = path.hashCode().toString(),
                    name = path.substringAfterLast("/"),
                    path = path,
                    size = 0,
                    sourceId = 0,
                    isFolder = false,
                    parentPath = "",
                    isHlsStream = true
                )
            ))
        }
        // 其他 HTTP 直链同理
        return Result.success(listOf(AudioTrack(...)))
    }
}
```

---

## 9. 老年用户优化（Review 补充）

目标用户是老年人，以下是关键体验优化：

| 功能 | 说明 | 优先级 |
|------|------|--------|
| **收藏** | 长按文件收藏，首页快捷入口，避免每次逐级翻目录 | ⭐ 高 |
| **最近播放** | 自动记录，支持断点续听，首页快捷入口 | ⭐ 高 |
| **大字体** | 文件列表字号 ≥ 16sp，按钮点击区域 ≥ 48dp | ⭐ 高 |
| **简化操作** | 点文件即播放，不弹"选择播放方式"对话框 | ⭐ 中 |
| **记忆位置** | 自动保存播放进度，下次从上次位置继续 | ⭐ 中 |
| **搜索** | 跨来源搜索缓存中的文件名 | ⭐ 低（Phase 2 后加） |

---

## 总结

这个重构把"戏曲"从小众场景升级为通用的"音频库"框架，核心变更：

| 旧 | 新 |
|----|----|
| 单一 WebDAV 来源 | 4 种来源可扩展（本地/WebDAV/M3U/HTTP） |
| 硬编码授权码 | 无需授权码，用户自管凭据 |
| 明文密码 | EncryptedSharedPreferences 加密存储 |
| 无缓存刷新 | 24h TTL + 手动下拉刷新 |
| 无收藏 | 收藏 + 最近播放 + 断点续听 |

可以先做 WebDAV + 本地 + M3U，上架后用用户反馈决定下一步加哪些源。
