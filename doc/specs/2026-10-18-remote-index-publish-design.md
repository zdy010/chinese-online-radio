# 远端索引发布与导入 设计

> 2026-10-18 · 承接「WebDAV 全源节目搜索」（spec/plan 同名日期）。前置决策已由用户确认：
> - **路线 1**：远端索引只在带内置源配置的**特殊 release 版本**里启用；公开包不含此能力，继续自行增量爬取。
> - **下载不鉴权**（公开可读），**上传只允许用户本人**：token 仅存本地构建，且回传入口隐藏——在设置页**连续点击「关于」10 次**才出现。
> - 该特殊版本**只在用户明确要求时构建**；本次实现的代码**不推送到远端**。
> - 车机档沿用既有边界：不开放 WebDAV 搜索，因此既不下载也不上传远端索引。

## 1. 目标与非目标

**目标**

1. 让装了内置戏曲源的设备**首轮就能搜索**：用一次 HTTPS 下载换掉当前实测的 52 次 PROPFIND / 约 1 分钟（PC 并发 8 测得四源 75s、模拟器单源约 1 分钟）。
2. 索引由**持有该特殊构建的人（你）在设备上显式触发上传**，服务端只读分发；普通构建里连写入口都不存在，**任何其他设备都没有写通道**。
3. 远端索引失败必须**等于没有这个功能**：404、schema 不符、条目数不达标、网络超时，一律静默回落到已实现的增量爬，搜索不得因此变空。
4. 有 ETag 时走 `If-None-Match`，索引未变化只花一个 304 的开销（几乎零字节）。
5. 所有可判定逻辑（sourceKey 解析、schema 解析、导入校验、连点计数）为**纯函数**并配 JVM 单测；真实下载导入与隐藏入口走设备裁决。

**非目标（本期不做）**

- 不做自动回传：上传只在隐藏面板里由人显式点击。
- 不为**用户自建源**下载或上传索引（隐私：那是别人的目录清单；也避免污染服务端命名空间）。
- 不做多写者、审计、并发写仲裁；服务端只认一个 token。
- 不做 CDN、多文件分片、断点续传下载。
- 不改音频索引的存储结构与新鲜度判定：复用 `audio_cache` + `IndexCacheWriter` + `IndexQuery.isFresh`。
- 不动车机档渲染与既有「连点 7 次开按键诊断」行为。

## 2. 密钥与配置存放（沿用项目既有保密惯例）

新增 `config/IndexPublishConfig.kt`，**入库版本只含占位符**，真实值靠 `git update-index --skip-worktree` 锁在本地（与 `EasterEggConfig.kt`、`YunPanConfig.kt` 同一套做法）：

```kotlin
// 入库版本（占位符，必须编译得过、跑不出任何真实请求）
object IndexPublishConfig {
    const val BASE_URL = "https://example.invalid/radio-index"      // 占位符
    val ALIAS_BY_CODE: Map<String, String> = mapOf("REPLACE_ME" to "a1")
}
```

- `ALIAS_BY_CODE`：快捷码 → 服务端短 id。**快捷码本身在 `EasterEggConfig.QUICK_CODES` 的 key 里**，运行时先用 `(url, username, password)` 反查命中的快捷码，再由它取短 id。查不到 = 非内置源 = 远端索引关闭。
- **服务端文件名只用短 id（`a1`/`a2`/…），不含 url、用户名、授权码或其哈希**。理由：`sha256(url|username|password)` 虽然单向，但公开端点等于给了离线爆破目标（授权码是 8 位小写字母数字，36⁸ 空间可在普通硬件上枚举验证），等于削弱凭据；短 id 与内容之间没有任何可推算关系。
- token 不放这个文件，放 `local.properties` → `BuildConfig`（见 §5、§7）。
- 推送前必查：`git show HEAD:app/src/main/java/com/radio/chinese/config/IndexPublishConfig.kt` 仍是占位符。

## 3. 索引文件格式（gzip 的 JSON，一源一文件）

```json
{ "schemaVersion": 1,
  "generatedAt": 1792317597579,
  "alias": "a1",
  "dirs": 52, "entries": 3519,
  "rows": [
    { "p": "/webdav/%E5%B8%B8%E9%A6%99%E7%8E%89/",        "pp": "/webdav/",  "n": "常香玉",       "f": 1, "s": 0 },
    { "p": "/webdav/%E5%B8%B8%E9%A6%99%E7%8E%89/x.mp3",   "pp": "/webdav/%E5%B8%B8%E9%A6%99%E7%8E%89/", "n": "花木兰.mp3", "f": 0, "s": 8123456 }
  ] }
```

三条硬规定（都来自本次设备实测）：

1. **路径原样保留 percent 编码**，绝不解码。`resolveUri` 直接用 `path` 拼播放地址，解码后播放即失败；展示层解码已经由 `IndexQuery.breadcrumb` 负责。
2. **目录行（`f:1`）必须包含**。浏览读的是 `getByParentPath`，缺目录行会让用户点进去看到空列表。
3. **`pp` 与 `p` 的形态必须与 `listChildren` 一致**（即 href 形态 `/webdav/<编码名>/`），否则同一目录会被写入两套 key，新鲜度判定与浏览缓存都会分叉。

体积估算：3519 行 ≈ 390KB 原始、**约 80–120KB gz**；四源合计 <500KB，对比现状四源 XML 约 8MB。

## 4. 下载与导入

**时机与顺序（决策点，明确固定）**：进入音频库、`carSurface=false` 且 `NetworkGate.isIndexingAllowed()` 为真时，**先试远端，失败再爬**，不做「先估待爬目录数」的启发式（估算本身要先花一次根目录请求）。成本：远端无索引时多 1 次请求，换来首轮秒得索引。

> **远端下载属于后台索引的一部分，因此与爬取共用同一个 Wi‑Fi 闸门**（用户已定“后台索引只在 Wi‑Fi 连接时进行”）：移动网络下既不下载也不爬取，直接静默跳过。

```
对每个 WEBDAV 源：
  alias = IndexPublishConfig 反查(url,username,password)；无 alias → 跳过远端，直接增量爬
  GET  BASE_URL/<alias>.json.gz   (带 If-None-Match: 本地存的 ETag)
    304            → 本地索引仍新鲜，结束（不爬）
    404 / 超时 / 非2xx → 记 reason，转增量爬
    200            → 校验（下表）；通过则导入，失败记 reason 转增量爬
  导入：按 pp 分组 → IndexCacheWriter.replaceDir(sourceId, pp, rows)
         行级 cachedAt = generatedAt        ← 复用现有 isFresh/shouldCrawl，"能拿到就不爬"自动成立
  导入后 sourceDao.updateSyncTime(sourceId, generatedAt)   ← 视为一次完整同步
```

**导入校验（拒绝即回落，不影响搜索可用）**

| 校验项 | 规则 | 不达标 |
|---|---|---|
| `schemaVersion` | 必须 = 1 | 整体拒绝 |
| `alias` | 必须等于请求的 alias | 整体拒绝（防拿错文件导入，串源） |
| `entries` | 等于 `rows` 数组的实际长度（偏差 >30% 即拒），且 `rows` 非空 | 整体拒绝 |
| `dirs` | ≥ 1 且 ≤ entries | 整体拒绝 |
| `generatedAt` | > 0 且不晚于本地时间 + 24h（容忍时钟偏差） | 整体拒绝 |
| 单行 `p`/`pp`/`n` | `p` 以 `/` 或 `https://` 开头；`n` 非空 | 丢该行；丢弃数 > 10% 则整体拒绝 |

理由（本次真实发生）：首轮设备爬取产出的就是 **41/51 目录、3302 首**的残缺索引，`lastSyncAt=0`。"能拿到就不主动爬"的策略遇到残缺文件会把残缺永久固化，所以校验层比鉴权层更值钱。

**降级原则**：远端任何失败都不弹错、不阻断，只在发布面板里显示原因（寻常用户完全无感）。这与 TTS 播报「失败等于没开这个功能」同源。

## 5. 上传（回传）

**入口可见性——两道独立闸门，缺一不显**

1. **构建闸门**：`local.properties` 的 `indexUploadEnabled=true` → `BuildConfig.ENABLE_INDEX_UPLOAD`。默认为 `false`，因此**任何常规构建里面板这个 composable 都不存在**，反编译也看不到 token 字段值（token 字段仅在 true 时填入真值，否则填 `""`）。
2. **操作闸门**：设置页连续点击「关于」10 次。

连点定义（必须是可判定的规则，不能只写"连续"）：沿用 `SettingsScreen.kt` 现有 `aboutTapCount`（`rememberSaveable`，`>=7` 已用于显出按键诊断入口且**不会弹窗打断**），在同一个计数器上继续累加：`aboutTapCount >= 10` 且 `BuildConfig.ENABLE_INDEX_UPLOAD` 才显出「索引发布」入口。两次点击间隔 > 1500ms 时计数器归零（新增规则，作用于新入口不影响 7 次那条既有行为）。

**上传前置条件（服务端 + 客户端双向把关）**

- 客户端只在 `source.lastSyncAt != 0`（即该源**整树爬完且无跳过目录**）时允许上传；否则按钮旁显示"该源索引不完整（有 N 个目录未收录），请先补齐再上传"。这正是 §4 校验要防的残缺来源。
- 请求：`PUT BASE_URL/admin/<alias>?version=<generatedAt>&entries=<n>&dirs=<d>`，header `Authorization: Bearer <BuildConfig.INDEX_UPLOAD_TOKEN>`，body 为 gzip JSON。
- 服务端实现形式（已定）：**读走 nginx 静态，写走一个最小 Python HTTP 服务**（跑在 systemd 里，只监听 127.0.0.1，由 nginx `location /admin/` 反代）：验 Bearer、校 version 单调、gzip 后写文件并留档。选它的理由：那台 Ubuntu 自带 python3，且版本单调与留档用 shell/lua 在 nginx 里写会变成易错的拼接。
  - `/admin/` 与读路径 `/r/<alias>.json.gz` 分离；`autoindex off`，不做目录列举
  - `version <= 已存 version` → 拒绝（409），除非 `force=1`
  - 覆盖前把旧文件留档为 `<alias>.json.gz.bak.<version>`，保留最近 5 份
  - `client_max_body_size 5m`；对 `/admin/` 加 `limit_req`（burst 5）
  - 记录一行访问日志：时间、alias、version、entries、结果码

**索引导出无需重新爬**：直接从 `audio_cache` 按 `parentPath` 分组导出（复用 `dirOldestCachedAt` 那条查询的分组形态），所以面板只做"读库 → 打包 → PUT"。

## 6. 组件与文件清单

**新增**
- `config/IndexPublishConfig.kt` —— 占位符入库 + skip-worktree 本地真值（`BASE_URL`、`ALIAS_BY_CODE`）
- `domain/model/RemoteIndex.kt` —— 纯函数：`parse`(JSON→rows/dirs/entries)、`validate`(校验表)、`resolveAlias(url,username,password)`、`groupByParentPath`
- `service/RemoteIndexClient.kt` —— GET/PUT seam（OkHttp，`If-None-Match`/`ETag` 存取），接口可注入 fake
- `service/RemoteIndexSync.kt` —— 编排：试远端→校验→导入→失败回落；导出→前置检查→上传
- `ui/settings/IndexPublishPanel.kt` —— 隐藏面板（导出/上传/显示 reason 与当前 version）

**改动**
- `di/AppModule.kt` —— 绑定 `RemoteIndexClient`；把 `okHttpClient` 复用现有单例 provider
- `ui/library/AudioLibraryViewModel.kt` —— `startIndexingIfNeeded` 内在 `SourceIndexer.start` 之前调用 `RemoteIndexSync.tryImport(source)`；车机档仍是最先返回
- `ui/settings/SettingsScreen.kt` —— 加连点间隔重置与 `>=10` 的隐藏入口；**不改** `>=7` 那条
- `app/build.gradle.kts` —— 从 `local.properties` 读 `indexDownloadEnabled` / `indexUploadEnabled` / `indexUploadToken` 三个 BuildConfig 字段（沿用现有 `enableEasterEgg` 的读法）
- `data/local/RadioPreferences.kt` —— 每源存的 ETag（`remote_index_etag:<sourceId>`）与已导入 version
- 服务器侧：nginx 片段与一个 PUT 校验脚本（放 `doc/` 或 `tools/`，不进 APK）

## 7. 构建与分发约定

- 特殊 release 版本**只在用户明确说"构建"时**执行 `assembleRelease`；产物命名沿用现有 `chinese-online-radio-1.0.<ver>-release.apk`。
- 本功能的代码与文档**只提交到本地分支，不 `git push`**（用户明确要求）。
- 发布前检查两条：① `git show HEAD:…/IndexPublishConfig.kt` 是占位符；② 常规构建产物里 `grep` 不到 token（`ENABLE_INDEX_UPLOAD=false` 时 token 字段必须为 `""`）。

## 8. 测试策略（TDD）

**纯函数 JVM 单测（先写失败测试）**
- `resolveAlias`：命中内置源→短 id；url 相同但 username 不同→不同 id；username 相同但授权码不同→不同 id（**四个快捷码源共用 url+username，只有授权码不同，本次实测 51/11/27/14 个目录**，这条是最容易写错的分键）；非内置源→null。
- `parse` + `validate`：schema 不符；`alias` 与请求不符；`entries` 与实际行数差 >30%；`generatedAt` 超前；单行 `n` 为空被丢弃、丢弃 >10% 整体拒绝；空 `rows` 拒绝。
- `groupByParentPath`：目录行与文件行混在同一 `pp` 下正确成组；空目录（只有目录行）不产生文件条目。
- 连点计数：9 次不显、10 次显、间隔 >1500ms 重置、7 次入口不受影响。
- 路径形态回归：导入 `/webdav/%E5%B8%B8/` 形态的 `pp` 后，`IndexQuery.breadcrumb` 仍解出「常香玉」，`resolveUri` 拼出的地址与原样爬取时**逐字节一致**。

**seam 假件**：`RemoteIndexClient` fake 返回 200/304/404/超时/坏 JSON，断言 `RemoteIndexSync` 的降级分支（不发起爬取、或发起爬取、或导入后不调爬取）与 ETag 存取。

**真机/设备裁决**
- 手机端 Wi‑Fi：删掉本地索引 → 冷启动 → 断言只发了 1 次 HTTPS 请求（非 52 次 PROPFIND），搜索「花木兰」立即有结果，点结果能出声。
- 索引已新鲜时再冷启动 → 断言命中 304 且零 PROPFIND。
- 服务端 PUT：`version` 回退请求返回 409；带 `force=1` 才覆盖并生成 `.bak`。
- 隐藏入口：常规包（`indexUploadEnabled=false`）里连点 10 次不出现任何入口；开启的包里 9 次不显、10 次显。
- 车机档（须真车机或手动钉「界面模式=车机」）：零远端请求 + 零 PROPFIND。

## 9. 验收指标

1. 首轮可用时间：从"进页面后约 1 分钟才能搜"降到"一次下载内可搜"；实测记录请求数与耗时。
2. 传输字节：单源 <150KB gz（对比实测单源 XML 约 1.3MB / 全四源 8MB）。
3. 失败不致命：把 `BASE_URL` 指向不存在的主机，搜索仍可用（走增量爬），无崩溃、无错误弹窗。
4. 写权限收口：任何非授权构建与非授权 token 的 PUT 均被拒（403/401），日志留痕。

## 10. 风险与未决

1. **公开可读的合规边界**：索引含第三方云盘分享目录的**完整文件名与结构**，公开分发属于项目"版权红线"的判断范围。用户已同意下载不鉴权；若日后要面向公开包启用，需重新确认此项。本期靠路线 1 把影响面限制在你自己构建的设备。
2. **授权码强度**：8 位小写字母数字属弱口令。本设计不把任何可反推凭证的信息放进 URL，但索引公开意味着**看到文件清单**是公开的；清单本身不含凭证。若日后要收紧，做"下载也要 token"即可（唯一改动点）。
3. **残缺上传**：已用「客户端只允许 `lastSyncAt != 0` 时上传 + 服务端校验 entries/dirs」两道拦；若你手工上传过一份与线上不一致的文件，客户端会因 `alias`/条目数校验拒收，并静默回落自爬。
4. **generatedAt 作为 cachedAt**：远端索引导入后，若真实目录里刚新增了曲目，用户要等该目录 TTL 过期才会重新发现。接受（与浏览缓存 TTL 同源），并保留手动刷新入口。
5. **`ALIAS_BY_CODE` 与 `EasterEggConfig` 的耦合**：快捷码变了要同步改两处映射；实现时在 `RemoteIndex.kt` 的解析里对"查不到 alias"走静默降级，不会崩，只是回到自爬。
6. 服务器可用性：写服务需那台 Ubuntu 上有 python3 与可用的 systemd 单元（现有部署文档已证明有 nginx 与 HTTPS 证书流程）；这属于服务端任务，不阻塞客户端实现。
