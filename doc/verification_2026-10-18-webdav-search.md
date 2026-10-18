# WebDAV 全源节目搜索 验收记录

> 2026-10-18 · 分支 feat/webdav-full-source-search（12 个提交，BASE 8a63f78）
> Spec：`doc/specs/2026-10-18-webdav-full-source-search-design.md`
> Plan：`doc/plans/2026-10-18-webdav-full-source-search-plan.md`

## 1. 自动化测试（本会话实跑，输出已读取）

| 命令 | 结果 |
|---|---|
| `gradlew :app:testDebugUnitTest --offline` | BUILD SUCCESSFUL，**184/184 通过，0 failures / 0 errors**（31 个测试类） |
| `gradlew :app:assembleDebug --offline` | BUILD SUCCESSFUL |
| `gradlew :app:assembleRelease --offline`（R8 开启） | BUILD SUCCESSFUL in 3m 34s |
| 基线（改动前）`testDebugUnitTest` | BUILD SUCCESSFUL，23 个测试类 |

新增测试类：`IndexQuerySearchTest`(20)、`IndexQueryFreshnessTest`(7)、`NetworkGateTest`(6)、
`SourceIndexerTest`(10)、`BrowserFactoryTest`(3)、`RefreshKeepsIndexTest`(1)、
`AudioLibrarySearchViewModelTest`(6)。

并发用例特别说明：初版只断言上界（`maxInFlight in 1..6`），串行实现也能过——属假绿，
已补下界改为 `in 2..6`，实测通过，证明批次内确实在并发。

## 2. SQL LIKE 语义（JVM 无法覆盖，用真 SQLite 验证）

`.superpowers/.../sqlcheck.py`：把 `AudioCacheDao.searchTracks` 注解里的 SQL 原样取出，
在 Python 内置 sqlite3（真 SQLite）上跑，8/8 通过：

- `100%` → 只命中字面含 `100%` 的行，**没有退化成"匹配全部"**
- `a_b` → 只命中字面 `a_b`，不把 `_` 当单字符通配符
- 目录行（isFolder=1）不参与搜索
- `ORDER BY name ASC` 为码位序（`常 U+5E38` 排在 `花 U+82B1` 之前），与 Kotlin `sortedBy { name }` 同源
- 空白查询由 `likePattern` 返回空串短路，不进 SQL

## 3. 设备端到端（emulator-5554，Medium_Phone_API_36.0，1080x2400@420）

装 `chinese-online-radio-1.0.170-debug.apk`，通过"添加音频库"填快捷码 `>REMOVED-USER1`
建成真实 WebDAV 源（名称解析为 `123云盘·豫剧·按类型`，连接测试走线上 123pan 成功）。

**索引真的落库**（`adb exec-out run-as … cat databases/audio_library_database` 后用真 SQLite 查）：

| 阶段 | audio_cache 行 | 曲目行 | 目录数 | lastSyncAt |
|---|---|---|---|---|
| 首轮冷启动后 | 3352 | 3302 | 41 | **0**（有目录失败 → PartiallyFailed，按设计不写） |
| 次轮冷启动后 | 3519 | 3469 | **51** | **非零**（整树爬完 → 写时间戳） |

- 曲目总数 3469 与 PC 侧探测的 3468 一致（多出的 1 条是 `copy_by_performer.ps1`，见 §5）
- 次轮只发了 **1 次 PROPFIND**（`logcat | grep PROPFIND` 计数），对比首轮 51 次：
  新鲜目录跳过生效，**断点续爬成立**，且首轮失败的目录在次轮自动补齐
- 设备上对真实库执行 DAO 那条 SQL：`花木兰` 命中 41 条、`穆桂英` 46 条（样例为真实唱段文件名）、
  `100%` 与 `a_b` 命中 0 条（**没有刷屏**）
- 手机档音频库搜索框文案确认为 `搜索曲目（全部来源）`（uiautomator dump 实测 bounds）

## 4. 设备实测发现并已修复的两个缺陷

1. **面包屑乱码**：索引落库的 `parentPath` 是 href 形态（`/webdav/%E7%8E%8B%E9%9C%9E/`），
   直接拼面包屑会显示成百分号乱码。修复：`IndexQuery.breadcrumb` 剔 DAV 挂载前缀段 + 百分号解码，
   并把 `+` 先转义回 `%2B`（URLDecoder 会把 `+` 解成空格）。先写 4 条失败测试再实现（RED 4 失败 → GREEN）。
2. **新增源不会触发索引**：首轮实现里 `LaunchedEffect(carSurface)` 只在进页面时跑一次，
   会话中新增的源要等冷启动才索引。设备证据：保存源后 logcat 43234 行里只有 1 次 PROPFIND
   （即连接测试自身）。修复：效果键改为 `LaunchedEffect(uiState.sources.size, carSurface)`，
   满足 spec §3「新建源成功后立即索引」。

## 5. 已知限制与待办（如实记录，不当作通过）

**未验证（本次条件不足）**

- **车机档零请求 + 搜索框文案不变**：尝试用 `wm size 1280x720 / density 160 / user_rotation 1`
  构造车机视口，但模拟器输入空间仍是 720x1280 且行高 48dp，说明 `resolveUiSurface` 判定为**手机档**，
  因此该次"PROPFIND=0"是空测；且在该形态下未能切换到音频库页（rail 点击与滑动均未生效）。
  **需要真车机或能强制恒横屏的环境重测**。设备参数已还原（`wm size reset`、`wm density reset`、
  `accelerometer_rotation 1`）。
- 移动网络下点刷新的提示文案与零请求：模拟器的 `NOT_METERED` 判定无法稳定切成计费网络，未测。
- 播放中起索引是否中断出声：本次索引在空闲态完成，未与播放并发，未测。
- 真车机（方易通 UIS7862）上四源全量耗时：spec §10 的「≤3 分钟」目前只有 PC 侧 75s 与
  模拟器单源（52 目录 / 3469 首）约 1 分钟两次证据支撑，未覆盖车机网络。

**Minor（决定不在本轮处理）**

- `PartiallyFailed` 只暴露 `skippedDirs` 计数，不含具体目录路径；本次定位"为什么 lastSyncAt=0"
  只能靠对比两轮目录数（41→51）反推。加 `skippedPaths: List<String>` 可显著提升可诊断性。
- 索引写入的 root key 是 `/`，而浏览路径写入的是 `""`，同一份根目录列表可能双份落库；
  搜索按 `name+size` 归并会把重复折叠掉，用户看不到异常，但表体积会偏大。
- 索引收录非音频文件（`copy_by_performer.ps1` 进了曲目表）。目录浏览本来就会列出它，
  不是搜索引入的回退；若要在索引侧过滤需先定口径（无扩展名的流地址是否算曲目）。

## 6. 复核方式

```powershell
# 单测 + 两种构建
.\gradlew.bat :app:testDebugUnitTest --offline
.\gradlew.bat :app:assembleDebug :app:assembleRelease --offline
# SQL 语义
C:\Python314\python.exe .superpowers\sdd\2026-10-18-webdav-full-source-search-plan\sqlcheck.py
# 设备索引落库核对（需先装包并在音频库里加一个 WebDAV 源）
C:\Python314\python.exe .superpowers\sdd\2026-10-18-webdav-full-source-search-plan\dbcheck.py
```
