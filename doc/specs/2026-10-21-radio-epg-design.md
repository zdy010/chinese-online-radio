# 广播节目单（EPG）功能设计

> 2026-10-21 · 前置决策已由用户确认：
> 形态为**全天节目单**（不是只显示"正在播放"）/ 数据源改用**云听官方公开接口**
> （`cnr.cn` 的节目表页面已停更 6 年，废弃）/ **运行时拉取 + 本地缓存**（不内置快照）/
> 播放页内嵌 + 独立节目单页 + 节目搜索三处都要 / 覆盖范围按 **A → B → C 分阶段**推进。

## 0. 数据源调研结论（写在这里防止以后重蹈覆辙）

| 候选源 | 结论 | 依据 |
|---|---|---|
| `cnr.cn/jmb/erji/*.html` | **废弃** | 中国之声页面仍写《千里共良宵》00:00–02:00，该节目 2020-07 已撤出电波、2022-09 停播；文艺之声页面含"笑动2013"。至少 6 年未更新 |
| `cnr.cn/advertising/gtjmsjb/` | **废弃** | 上海/福建/吉林节目表全是 2011 年的 xls/doc/jpg |
| 51zmt、CCSH/IPTV 等 | **不适用** | 只覆盖电视频道，无广播 |
| 维基百科 | 仅作校对 | 志愿者维护，滞后且不全 |
| **云听 `ytmsout.radio.cn`** | **采用** | 央广官方，网页端公开调用，签名算法公开在 `api.js`，无需登录 |

云听实测数据（2026-10-21）：

- `GET /web/appBroadcast/list?provinceCode=<code>`：32 省共 **896 个台**
- `GET /web/appProgram/listByDate?broadcastId=<id>&date=<yyyy-MM-dd>`：按日返回全天节目单
- **307 个台有 EPG**；其中 **56 个与现有 451 台库精确匹配**，251 个不在台库
- 未来 3 天可查（中国之声今天 23 条、明天 23 条、后天 21 条），节目覆盖 00:00–23:59
- 返回字段：`programName`、`startTime`/`endTime`（毫秒时间戳）、`broadcastId`、`columnId` 等

签名规则（照抄 `pc-portal/js/api.js`，不自行发明）：

```
sign = MD5( 参数按 key 字典序拼成 "k=v&k=v" + "&timestamp=" + 毫秒时间戳 + "&key=" + KEY ).toUpperCase()
Header: timestamp=<同一毫秒值>, sign=<上述值>, equipmentId=0000, platformCode=WEB
KEY = f0fc4c668392f9f9a447e48584c214ee   // 公开在前端 JS 里的常量，不是密钥
```

**合规边界（硬约束，实现时必须遵守）**

1. 只在用户打开播放页 / 节目单页时按需请求，**单台、单日**。
2. 不做后台批量抓取、不做定时轮询、不把接口返回原样写进日志。
3. 不做节目单的二次分发、不导出。
4. 接口失败必须**等于这个功能不存在**：不弹错、不阻断播放。

## 1. 目标与非目标

**目标**

1. 播放页能直接看到该台当前在播什么、下一档是什么，不必离开播放页。
2. 独立节目单页能一眼扫到所有有 EPG 的台此刻各在播什么，可切今天/明天/后天。
3. 能按节目名搜索，结果落到具体电台并能直接起播。
4. 没有 EPG 的台**静默降级**：不出现空占位、不出现"暂无节目单"。
5. 手机档、车机横屏、系统大字号三种形态都不截断、不挤掉播控。

**非目标（本期明确不做）**

- 不做内置周模板：云听数据是逐日快照，周模板会与真实排播脱节。
- 不做节目回听 / 点播 / 订阅提醒。
- 不改播放链路、不改 `RadioStation` 与 `StationSource` 结构。
- 不做 B/C 阶段（省台补流、251 台全接）——本期只交付 A。
- 不做主持人、嘉宾、节目简介等扩展字段。

## 2. 数据模型

```kotlin
// domain/model/EpgProgram.kt
data class EpgProgram(
    val stationId: String,
    val date: String,     // yyyy-MM-dd（本地时区）
    val startMin: Int,    // 当天 00:00 起算的分钟数，0..1440
    val endMin: Int,      // 跨天时截断到 1440
    val title: String,
)
```

时间换算一律在解析层完成，UI 与查询只碰 `Int` 分钟，避免各处重复处理时区：

- `startMin = 毫秒时间戳 → 本地时分 → 小时*60+分钟`
- `endMin`：若结束时间落在次日（`endTime` 的日期 > `startTime` 的日期），截断为 `1440`
- 丢弃 `endMin <= startMin` 的坏行（数据清洗，不崩）

**映射表** `assets/epg_stations.json`（云听 id ↔ 本项目 stationId）：

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-10-21",
  "stations": [
    { "stationId": "cnr1", "broadcastId": "639", "name": "中国之声" }
  ]
}
```

- 唯一来源，**禁止在 Kotlin 里再写第二份映射**。
- 构建期校验：每个 `stationId` 必须能在 `radio_stations.json` 里查到，否则 JVM 测试失败。
- 台名比对必须先规范化（去首尾空格、内部连续空格压成一个），云听返回里实测有 `" 经济之声"` 这种带前导空格的台名。

## 3. 缓存与刷新

**存储**：新增独立 Room 库 `epg_database`（不复用 `radio_database`，避免升 version 触发 `fallbackToDestructiveMigration` 清掉用户收藏）。

```kotlin
@Entity(tableName = "epg_program", primaryKeys = ["stationId", "date", "startMin"])
data class EpgProgramEntity(...)

@Query("DELETE FROM epg_program WHERE date < :before")
suspend fun deleteOlderThan(before: String)
```

**刷新规则**

| 场景 | 行为 |
|---|---|
| 进入播放页，缓存无当天数据 | 请求当天 |
| 进入播放页，缓存有当天数据且 `fetchedAt` 在 6 小时内 | 直接用缓存 |
| 进入独立节目单页 | 对列表中缺当天数据的台批量补拉，并发上限 3 |
| 同台 30 秒内重复进入 | 不重复请求（内存节流表） |
| 日期切换 | 切到哪天就请求哪天，缓存命中则不请求 |
| 请求失败 | 保留旧缓存；UI 显示"数据截至 MM-dd HH:mm"；无旧缓存则整块隐藏 |
| 跨天 | 查询以本地当前日期为 key，昨天数据不参与"正在播出"计算 |

清理：每次启动或首次请求时删除 `date < 今天 - 1` 的行。

## 4. UI

### 4.1 播放页内嵌（A）

位置：`StationInfoBlock` 之后、`PlaybackStatusIndicator` 之前。

```
┌────────────────────────────────────────┐
│ 正在播出   06:00–06:30 · 国防时空        │
│ 接下来     06:30 新闻和报纸摘要          │
└────────────────────────────────────────┘
```

- 每 60 秒按本地时间重算一次（`produceState` + `delay`，离开页面即取消）。
- 点击整块 → `ModalBottomSheet` 全天节目单：当前时段用 `primary` 底色高亮，打开时自动滚到当前项可见。
- 车机档：行高 ≥ `Dimens.TouchMin`，字号跟随 `MaterialTheme.typography`，不另设固定 sp。
- 无数据：**整个 composable 不渲染**，不留空白、不显示占位文案。

### 4.2 独立节目单页（B）

- 路由：`Screen.Epg`，`route = "epg"`。
- 入口：`RadioScreen` 的 `SearchBarRow` 右侧动作区，与设置按钮同排，图标用 `Icons.Default.CalendarView`（或 `Schedule`）。
- 结构：`TopAppBar("节目单") + 日期切换行（今天/明天/后天）+ 搜索框 + LazyColumn`
- 列表行：`台名` / `当前节目 · 时段` / `接下来 节目名`，点击 → `playerManager.playStation(station)` 后跳播放页。
- 排序：有当前节目的台优先，其余按省份/台名。
- 车机档：列表行 `minHeight = Dimens.RowMinHeight`（88dp），左右 `ScreenPadding`（32dp）。

### 4.3 节目搜索（C）

- `RadioScreen` 搜索框占位符改为「搜索电台、频率或节目」。
- `HomeViewModel` 在现有 `StationFilter.apply` 之外增加一路 `EpgQuery.search(query, cachedPrograms)`，结果分两组渲染：
  - `电台`：现有列表，逻辑一字不改
  - `节目`：仅在节目结果非空时出现，行内显示 `节目名` / `电台名 · 时段`
- 搜索范围**只限本地已缓存节目单**（当天）。原因：全量 307 个台实时搜索会瞬间打 300+ 请求，违反 §0 合规边界。
- 点击节目结果 → 起播该台 + 跳播放页。

## 5. 分阶段实施

| 阶段 | 范围 | 交付物 | 前置条件 |
|---|---|---|---|
| **A（本期）** | 56 个已匹配台 + 央广 11 个可验静态流的台补入台库（合计 ~67 台） | 完整 EPG 功能（播放页/独立页/搜索） | 央广静态流已验证可播 |
| B | 省台逐个验流后入库（预计 +30–80 台） | 仅扩充 `epg_stations.json` 与台库，代码不动 | 每个台实测出声 |
| C | 251 个有 EPG 的台全接，播放地址改为运行时向云听拉取并刷新 | 新增动态流刷新通道 | 稳定性与合规再评估 |

A 阶段补台的 11 个央广台（static 流已实测 200）：

音乐之声 `yyzs`、文艺之声 `wyzs`、神州之声 `szzs`、香港之声 `xgzs`、民族之声 `mzzs`、
维吾尔语广播 `wygb`、都市/经典音乐 `dszs`、台海/中华之声 `zhzs`、华夏/大湾区 `hxzs`、
经济之声 `jjzs`、中国之声 `zgzs` —— 统一走 `https://ngcdn001.cnr.cn/live/<code>/index.m3u8`。

**404 不可用**：老年之声 `lnpl`、西藏频率 `xzpl`、娱乐广播 `ylgb`、乡村之声 `xwzs/ncgb`。
其中西藏藏语已有 `satellitepull` 流在库，其余不补。

## 6. 组件与文件清单

**新增**

- `domain/model/EpgProgram.kt` —— 数据类
- `domain/epg/EpgQuery.kt` —— 纯函数：`currentAt(programs, minute)`、`nextAfter`、`search`、`scheduleFor(day)`、`isStale(fetchedAt)`
- `data/remote/YuntingEpgClient.kt` —— OkHttp 签名请求；接口可注入 fake
- `data/local/EpgDatabase.kt`、`EpgProgramEntity.kt`、`EpgProgramDao.kt`
- `data/repository/EpgRepository.kt` —— 编排：查缓存 → 按需拉取 → 清洗 → 落库
- `assets/epg_stations.json` —— 映射表（A 阶段 ~67 条）
- `ui/player/NowOnAirCard.kt` + `EpgDaySheet.kt`
- `ui/epg/EpgScreen.kt` + `EpgViewModel.kt`
- `tools/fetch_epg.py` —— 生成/更新映射表、校验台名与台库一致性、输出补台候选

**改动**

- `di/AppModule.kt` —— 提供 `EpgDatabase`、`EpgProgramDao`
- `ui/player/PlayerScreen.kt` / `PlayerViewModel.kt` —— 插入卡片与状态
- `ui/radio/RadioScreen.kt` —— 节目单入口按钮、搜索占位符
- `ui/navigation/Screen.kt` / `MainScreen.kt` —— 新增 `epg` 路由
- `ui/home/HomeViewModel.kt` / `HomeScreen.kt` —— 节目搜索结果分组
- `assets/radio_stations.json` —— 补 11 个央广台（A 阶段）

## 7. 测试策略（TDD）

**JVM 纯函数单测**

- 签名：固定参数 + 固定 timestamp，断言 MD5 与手工计算值一致（与 JS 算法的黄金样例对照一次）。
- 解析：用真实响应的裁剪样本，断言 `programName`、`startMin/endMin`、跨天截断、空标题丢弃。
- `currentAt`：00:00 边界、正好等于 `startMin`、正好等于 `endMin`、跨午夜节目、空列表。
- `search`：中文子串、空格、无结果、大小写英文字母。
- 缓存过期：5 小时不拉、7 小时拉、跨天必拉。
- 映射表闸门：`epg_stations.json` 每个 `stationId` 必须存在于 `radio_stations.json`；`broadcastId` 非空且唯一。

**seam 假件**：`YuntingEpgClient` fake 返回 200 / 空数组 / 超时 / 坏 JSON，断言 `EpgRepository` 的降级分支（用缓存 / 隐藏 / 不崩）。

**真机 / 车机裁决**

- 手机端 Wi-Fi：进播放页 → 显示当日真实节目；等 1 分钟跨过某档边界 → 文案自动切换。
- 飞行模式冷启动：有缓存则显示并标注"数据截至"，无缓存则整块不显示。
- 车机横屏 1280×720：卡片不把播控顶出首屏；列表行 ≥88dp。
- 系统大字号：卡片两行不截断；节目单页行高自适应。

## 8. 风险与未决

1. **接口非文档化**：云听随时可能改签名或加鉴权。缓解：所有失败静默降级，功能消失但不影响播放；接口细节集中在 `YuntingEpgClient` 一处，便于替换。
2. **合规**：这是官方网页端的公开调用路径，不是破解；但无书面授权。缓解：严格限制为单台单日按需请求、不批量、不分发。
3. **台名匹配误差**：云听台名存在前导空格、别名（台海/中华、大湾区/华夏）。缓解：规范化 + 人工核对 + 构建期闸门；宁可少接不可错接。
4. **补台质量**：新增台必须实测出声，禁止只凭接口返回就入库。
5. **数据新鲜度**：跨天首次打开必须重新拉取；无网时显示的数据可能是昨天的，UI 必须标注时间。
6. **`ProgramInfo` 死代码**：`domain/model/Models.kt` 里现有的 `ProgramInfo` 与本期结构不兼容且从未被使用，本期内**删除**，避免两套节目模型并存。
