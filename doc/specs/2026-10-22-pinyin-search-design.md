# 搜索简称（拼音）匹配设计

> 2026-10-22 · 用户已确认两项决策：**音频库曲目与电台名两处都要做**；汉字转拼音**引第三方库**（不自生成字表）。
> 前置事实：本设计基于对 pinyin4j 的实测探针，不是按文档想象。

## 0. 依赖实测结论（决定后面所有规则）

探针：`PinyinHelper.toHanyuPinyinStringArray(c, fmt)`，`LOWERCASE` + `WITHOUT_TONE` + `WITH_V`。

| 结论 | 证据 | 对设计的影响 |
|---|---|---|
| **多音字候选顺序不是「常用音在前」** | `长`→`zhang,chang`、`重`→`zhong,chong`、`乐`→`le,yue`、`传`→`chuan,zhuan` | **禁止取第一个候选**。「长江」会算成 `zj`、「重庆」算成 `zq`、「音乐之声」算成 `ylzs`，恰好把最常用的读法全弄错。必须存全部变体 |
| 候选数组含重复项 | `王`→2 个元素，去重后只剩 `wang` | 生成前必须 `distinct()`，否则变体数虚增 |
| 非汉字返回**空数组**，不是 null | `A`/`1`/`-`/空格 均返回长度 0 | 判空要用 `isEmpty()`，不能判 null |
| 单读音字占多数 | 12 个样本里 22 个字为 1 读 | 变体数通常很小，上限不必设大 |

结论：**「取主读音」这个省事方案不成立**，多音字必须展开成多值索引。

## 1. 目标与非目标

**目标**

1. `wf` 能搜到「王菲」「汪峰」以外的 `w`+`f` 组合，`wangfei` 能搜到「王菲」「王飞」。
2. 多音字的每个读法都能搜到：`cq` 与 `zq` 都要能命中「重庆」。
3. 音频库曲目搜索与电台列表搜索行为一致，用户不需要记两套规则。
4. 英文与数字混排的曲目名不因加拼音而丢原有匹配能力。

**非目标（本期明确不做）**

- 不做模糊纠错（输入 `wangfie` 这种错序不纠正）。
- 不做声调匹配（`chang2jiang` 之类）。
- 不改「跨源结果去重」与索引新鲜度判定，只在其结果上追加匹配维度。
- 不内置用户自定义拼音词典。

## 2. 拼音索引模型

### 2.1 变体生成

对文本逐字符归一成一个「音节候选列表」，再取笛卡尔积：

- 汉字 → `toHanyuPinyinStringArray` 去重后的全部读音（已去声调、小写）
- ASCII 字母数字 → 原字符小写，作为单元素候选
- 其它字符（空格、`-`、`·`、括号等）→ **跳过，不产生任何符号**

> 跳过标点而非保留，是为了让「黄梅戏-天仙配」与「黄梅戏天仙配」得到同一个索引。

### 2.2 变体数上限

`2^n` 会随多音字个数指数增长。取**每行最多 16 个变体**，超出时按字典序保留前 16 个并停止展开。

依据：12 个真实样本里最多 3 个多音字（`白蛇传` = 1×2×2 = 4 变体）；16 已覆盖到 4 个双读音字，再多的曲目名极少见，且截断只影响生僻读法，不会让常见输入搜不到。

### 2.3 存储形态

两个字段，都是**用 `|` 包裹并以 `|` 分隔的变体集**：

```
py_full     = "|huangmeixitianxianpei|"
py_initials = "|hmxtxp|"
「重庆」     py_full = "|zhongqing|chongqing|"   py_initials = "|zq|cq|"
```

首尾都带 `|` 使跨变体边界的假阳性必须包含 `|`，而 `|` 不会是用户输入的搜索词，因此 `LIKE '%cq%'` 安全。

**无可索引内容时存空串 `""`，而不是 `"||"`**：文本为空、或全是被跳过的标点时，列值就是空串。空串不会被任何非空查询词命中，语义上也比一个空壳变体好解释。

### 2.4 查询分流

设 `q = query.trim().lowercase()`：

- `q` 含任一非 `[a-z0-9]` 字符 → **维持现状**，只匹配原始曲名/台名（中文输入路径零改动，不引入回退风险）
- `q` 全为 `[a-z0-9]` → 匹配 `原名 LIKE %q% OR py_initials LIKE %q% OR py_full LIKE %q%`

> 数字允许通过是为兼容「bt360」这类含型号的曲名；纯数字 query 匹不上拼音列，等价于原行为。

## 3. 电台侧（内存过滤，不动数据库）

`StationFilter.apply()` 本就是内存过滤，直接加一路拼音判定即可，**无 schema 变更、无迁移、无重建成本**。

性能：451 个台 × 平均 6 字，单次全量生成变体的开销可忽略，但每次按键都重算不划算。按 `stationId` 缓存其拼音串（`ConcurrentHashMap`，随台表刷新失效）。

## 4. 音频库侧（要动数据库）

### 4.1 加列

`AudioCacheEntity` 新增 `pyInitials`、`pyFull`，均 `NOT NULL DEFAULT ''`；`AudioLibraryDatabase` version 1 → 2。

### 4.2 迁移（本设计最高风险点）

`audio_library_database` 当前开着 `fallbackToDestructiveMigration()`，而该库同时装着**用户配置的音频库来源（含凭证）、收藏、最近播放**。升版本若不写 Migration，Room 会静默走破坏性回退，把这三样一起清掉。

因此：

1. **必须提供 `Migration(1, 2)`**，内容为两条 `ALTER TABLE audio_cache ADD COLUMN ...`。
2. Migration 内**只清空 `audio_cache`**（它是可重建的缓存，不含用户数据），使老索引行不会以「拼音列为空」的形态残留在库里。
3. 迁移后索引状态自然回到「未索引」，由现有 UI 引导用户重建。搜索框在索引为空时已会提示建立索引，不新增文案分支。

> 不做「保留老行、检测到空拼音再提示」的做法：那会让用户在不知情的情况下搜不到东西，比明确要重建一次更糟。

### 4.3 写入与查询

- `SourceIndexer` 落 `AudioCacheEntity` 时一并算好两个拼音字段。
- `audio_cache` 里**也存目录行**（`isFolder = true`），而目录不参与曲目搜索：目录行两个拼音列写空串，不给它们算变体。
- `AudioCacheDao.searchTracks` 按 §2.4 分流：纯字母数字时三个字段 OR。SQL 仍走 `LIKE`，转义沿用现有 `IndexQuery.likeParam`，不新写一套转义。

## 5. 共用与落点

新增 `domain/PinyinIndex.kt`：纯 Kotlin + pinyin4j，无 Android 依赖，JVM 可直接单测。

```kotlin
object PinyinIndex {
    fun variants(text: String): List<String>   // 全拼变体，已按 §2.2 截断
    fun initialsVariants(text: String): List<String>
    fun column(text: String): String           // "|a|b|" 形态，供直接落库
    fun isAsciiQuery(query: String): Boolean   // §2.4 的分流判据
}
```

两侧共用同一份实现，保证「电台」与「曲目」的简称语义完全一致。

依赖 `com.belerweb:pinyin4j:2.5.1` 已实测可从 Maven Central 拉到（本机 github.com 不通但 Central 直连 200），
项目 `settings.gradle.kts` 已配 `mavenCentral()`，无需加仓库。本设计定稿时**依赖尚未写入
`libs.versions.toml`**：未使用的第三方库不应先进交付包，它应随 `PinyinIndex` 同一个 commit 引入。

## 6. 测试策略

**JVM 纯函数单测（主力）**

- 探针已确认的行为回归：`长`→含 `zhang` 与 `chang` 两种变体；`重庆` 的 `py_initials` 同时含 `zq` 与 `cq`
- 用户点名的例子：`wf` 命中「王菲」「汪峰」，`wangfei` 命中「王菲」「王飞」
- 标点归一：「黄梅戏-天仙配」与「黄梅戏天仙配」索引相同
- 混排：「X光」→ 首字母 `xg`；「ABC123」→ 原样 `abc123`
- 上限截断：构造 5 个双读音字，断言变体数恰为 16 且按字典序取前 16
- `distinct`：「王」不产生重复变体
- 分流判据：`isAsciiQuery("wf")=true`、`isAsciiQuery("王菲")=false`、`isAsciiQuery("王 wf")=false`、空串与纯空格
- 边界：空文本、全标点文本 → `column()` 返回空串 `""`，不抛异常

**Room 迁移测试**

本项目无 Robolectric，Migration 无法在 JVM 单测里跑。改为：真机覆盖安装 v1 包 → 升级到 v2 包，断言**来源/收藏/最近三项仍在**、`audio_cache` 已清空、重建索引后拼音搜索可用。这是本设计唯一必须真机裁决的项。

**真机验收**

- 电台页：输入 `zjjt` 搜到「浙江交通之声」
- 音频库：重建索引后输入 `hmxtxp` 与 `黄梅戏` 都能搜到同一批曲目
- 多音字：`cq` 与 `zq` 都能搜到含「重庆」的曲目

## 7. 风险

1. **迁移写错会丢用户凭证配置** —— 已用「必须提供 Migration + 只清缓存表」约束；真机覆盖安装为强制验收项。
2. **重建索引的代价** —— 用户需重新点一次建立索引，会消耗流量与时间。不可回避，在 §4.2 说明理由。
3. **pinyin4j 是 2011 年风格的库** —— 无 Android 适配问题（纯 Java、无外部依赖），但更新停滞。风险由「接口集中在 `PinyinIndex` 一处」限制，将来可整体替换。
4. **APK 体积** —— pinyin4j jar 约 200KB。已确认接受。
5. **16 变体截断** —— 极端多音字曲目名可能丢生僻读法，只影响少数输入，不影响常用输入。
