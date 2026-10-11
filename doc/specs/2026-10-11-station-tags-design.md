# 节目标签功能设计（A-2）

> 2026-10-11 · 承接 A-1（电台清单扩充至 465 台）
> 前置决策已由用户确认：标签挂在电台而非流地址上 / 受控中文词表 + 脚本批量派生 /
> 拆出 country、language 字段且标签只管内容维度 / 分类页加「按标签浏览」入口 /
> 自定义电台可从词表内选标签 / 简介用「模板句 + 官网自述短句」/
> 加 junit 测试依赖 / 手机档是一等验收对象

## 1. 目标与非目标

**目标**

1. 电台可持有多个标签，标签来自一份受控中文词表（唯一来源在 Kotlin 枚举）。
2. 用户能按标签浏览电台，入口在分类页，路径不超过两次点击。
3. 把当前塞在 `frequency` / `description` 里的「国家 · 语言 · 流派」还原成规范字段，
   副标题在三处渲染一致。
4. 全球台补一句真实可用的简介，不留空白行。

**非目标（本期明确不做）**

- 不做标签的运行时自定义、不引入用户自建新词。
- 不做标签的层级/权重/同义词图，词表是扁平的。
- 不改播放链路、不动 `StationSource`（流地址）结构。
- 不做标签的收藏/最近页二次筛选。

## 2. 数据模型

`domain/model/RadioStation.kt` 新增三个**带默认值**的字段：

```kotlin
val tags: List<String> = emptyList()   // 词表 key，不存中文
val country: String = ""                // 中文国家/地区名
val language: String = ""               // 中文语言名
```

兼容性（必须逐条有测试，见 §7）：

- 自定义电台存 DataStore 的 `List<RadioStation>` JSON：新字段有默认值，旧数据反序列化不崩。
- assets JSON 的新字段对**旧版本 App** 是未知字段，由 `StationRepository` 里
  `Json { ignoreUnknownKeys = true }` 与手工 `JsonObject` 取值方式兜住；但
  `loadBuiltInStations()` 是手工解析，**必须同步补这三个字段的读取**，否则新字段在
  运行时永远是空，而单测读 JSON 文件却是有的——那种"测试绿、线上空"的分裂要主动排除。

## 3. 标签词表（唯一来源）

新增 `domain/model/StationTag.kt`，完全照 `StationCategory.kt` 的做法：
`enum class StationTag(val key: String, val label: String, val group: TagGroup)`，
并提供 `labelOf(key)`（未知 key **回落显示原值**，不崩不空）、`orderedByGroup()`、
`isValidKey(key)`。禁止在任何 UI 层再写第二份标签表。

初版 48 个标签，四组。命名上已规避两个真实冲突：**越剧/粤剧拼音相同**（用
`yueju` / `yueopera`）；**乡村音乐标签不能叫 `country`**（与新字段 `country` 撞名，
用 `countrymusic`）。

| 组 | 标签 |
|---|---|
| 戏曲曲艺 (12) | `jingju` 京剧、`yuju` 豫剧、`yueju` 越剧、`yueopera` 粤剧、`huangmei` 黄梅戏、`pingju` 评剧、`kunqu` 昆曲、`qinqiang` 秦腔、`chuanju` 川剧、`hujv` 沪剧、`pingtan` 评弹、`crosstalk` 相声 |
| 内容 (14) | `localnews` 本地新闻、`worldnews` 国际新闻、`military` 国防军事、`health` 健康医药、`edu` 教育、`kids` 少儿、`nighttalk` 夜间清谈、`storytelling` 评书故事、`history` 历史人文、`fincomment` 财经评述、`tech` 科技、`sportslive` 赛事解说、`trafficnow` 实时路况、`weather` 天气 |
| 音乐风格 (16) | `oldies` 怀旧老歌、`hits80` 八十年代、`rock` 摇滚、`folk` 民谣、`jazz` 爵士、`classical` 古典、`lightmusic` 轻音乐、`blues` 蓝调、`countrymusic` 乡村、`electronic` 电子舞曲、`hiphop` 嘻哈、`rnb` 节奏布鲁斯、`metal` 金属、`cpop` 华语流行、`pop` 欧美流行、`instrumental` 纯音乐 |
| 场景 (6) | `drive` 行车、`sleep` 助眠放松、`buddhist` 佛教禅修、`langlearn` 外语学习、`fitness` 运动健身、`chat` 闲聊陪伴 |

**两条纪律**

1. **不与 `StationCategory` 重复**：分类已有新闻/交通/体育/财经/戏曲，标签只打细一层
   （`localnews`/`worldnews`，不是 `news`；`sportslive`，不是 `sports`）。
2. **语言与地区不进词表**：它们已经是字段，由副标题负责显示，避免同一信息两处真源。

**数量约束**：派生上限每台 6 个；详情页最多显示 4 个，顺序按「戏曲曲艺 → 内容 →
音乐风格 → 场景」取，保证信息量高的先出现。

## 4. 派生与迁移：`tools/derive_tags.py`

**输入**：`app/src/main/assets/radio_stations.json`（465 台）+ **`tools/rb_provenance.json`**。

后者是本次新增要求：A-1 的派生依据 `.qtest/rb_pool.json` 在临时目录且不入库，
换机器就无法重跑。因此迁移前先把命中条目的最小依据（`url`、`name`、`tags`、
`countrycode`、`country`、`language`、`homepage`）落成一份**入库的精简溯源文件**，
让"某个台为什么被打上 jazz"这件事长期可复查。

**标签派生优先级**（高 → 低，先命中先定，避免规则互相覆盖）

1. 台名/栏目名关键词（`豫剧`、`评书`、`Classic FM`、`Heart 80s`…）——覆盖率最高，
   262 台大陆与台湾台主要靠这条。
2. `rb_provenance.json` 的英文 tags 映射表（`adult contemporary → lightmusic`、
   `classic hits → oldies`、`hot talk → nighttalk`、`smooth jazz → jazz`…）。
   只覆盖能对上记录的 203 台。
3. 从待拆的 `description`「… · 流派」位与 `frequency`「… · 语言」位取词。
4. **不做 category 反推**（纪律 1）。

**字段拆分**

- `frequency="德国 · 英语"` → `country=德国`、`language=英语`、`frequency=""`。
- `frequency` 是真频率（含 `FM`/`AM`/数字，192 大陆台）→ **原样保留**。
- `description` 以「全球节目源」开头 → 视为占位，按下节重写；否则视为真简介保留。

**简介三层取材**（用户明确否掉了"置空"）

1. 已有真简介 → 保留。
2. 抓官网自述：按 `website` 取 `<title>` / `meta description` / `og:description`，
   清洗 HTML、截断到 40 字，拼成「中文模板句 — 原文短句」。约 350 个首页，
   预计 20–35% 抓不到（站死、超时、境外不可达），这部分退回第 3 层。
3. 兜底模板句：只允许使用 *分类 + 国家/地区 + 语言 + 已派生标签* 四项已核实信息，
   形如「英国的英语爵士音乐电台」「河南的戏曲类广播，主要播豫剧」。

**取材红线**：不写覆盖范围、成立年份、隶属关系、"著名/权威/领先"一类未验证断言；
官网自述含营销语时只取中性描述句，拿不准就退回模板句。**报告必须分开给三个数**：
真简介 N 台、官网自述 N 台、模板句 N 台——不许把模板句计入"已找到介绍"。

**已知局限（写下来免得验收时当成 bug）**：现有词表只有具体剧种、没有“剧种不详的戏曲”
这一层，所以 `河南戏曲广播`、`安徽戏曲广播` 这类只写“戏曲”的台名**拿不到剧种标签**（只会被
分类 `opera` 覆盖）。这是纪律 1 换来的代价；若 dry-run 说明它显著拖低覆盖率，则回到你
这里讨论是否加一个“戏曲合辑”类标签。

**断言（任一不过则不写盘）**

- 每个 tag ∈ `StationTag`；单台 ≤ 6；`tags` 去重且顺序稳定（可重跑结果一致）。
- `country` 非空 ⇒ `language` 非空；`language` ∈ 语言白名单（华语/英语/粤语/闽南语/
  日语/韩语/法语/德语/西班牙语/俄语/…）。
- 原 `frequency` 形如真频率的条目不得被清空。
- 台数不减（465）、id 集合不变、`streamUrl` 与 `sources` 逐条不变（防止简介抓取
  顺手改坏播放链路——A-1 的 `play_failed.json` 机制同样生效）。
- 输出 `0 标签` 的台数与名单，**如实报告，不硬凑**。

**幂等与备份**：可从当前已提交版本反复重跑；备份写
`radio_stations.json.pretag.bak`（不覆盖 A-1 的 `.bak`，那是 349 台基线）。

## 5. UI 改动

### 5.1 副标题统一：新增 `ui/common/StationSubtitle.kt`

规则（纯函数，便于单测）：`frequency` 非空 → 用它；否则 `country`/`language`
非空 → 「国家 · 语言」；否则 → 分类中文名；全空 → 不渲染该行。

改造调用点，三处必须同一结论：`HomeScreen.StationListItem`、`PlayerScreen`
标题下方、`ManageScreen` 电台行（该行现在还额外拼 `· 检测结果`，保留该后缀）。

### 5.2 仓库层与筛选

- `StationRepository`：补三字段解析；新增 `getStationsByTag(key)`、
  `getUsedTagsWithCount()`（只返回真有电台的标签；空标签不进列表——沿用
  `HomeViewModel` 里 `usedCategories` 的既有教训）。
- `HomeViewModel`：`filterStations` 增加 `selectedTag`，与 `category`、`searchQuery`
  正交组合；搜索命中范围加入标签中文名与 `country`/`language`。

### 5.3 新页 `ui/tag/TagScreen.kt`（路由 `Screen.Tags`）

- 第一态：按四组分节的标签 chips，每个 chip 带台数；
- 点标签 → TagScreen **页内第二态**显示该标签的电台列表（仿 `CategoryScreen` 的页内
  二级 + `rememberSaveable`）；TagScreen 自身是一个独立路由，从分类页 push 进来。
- 列表项复用 `StationListItem`，播放行为与分类页一致。
- 入口：`CategoryScreen` 网格首格加「按标签浏览」卡（带标签总数），
  手机 2 列 / 车机 `GridCells.Adaptive(240.dp)` 同形态。

### 5.4 详情页与编辑入口

- `PlayerScreen`：显示最多 4 个标签 chip，点击跳 `TagScreen` 并选中该标签；
  视口高度不足（车机 240dpi 档横屏仅 480dp）时**整块隐藏**，
  绝不允许把播控按钮挤出可视区——按现有 `showDescription` 的降级思路处理。
- `ManageScreen` 添加/编辑电台对话框：标签多选，用 `ExposedDropdownMenuBox`
  内嵌复选项（**对话框内普通 `DropdownMenu` 打不开**，A-1 已踩过）；
  沿用现有 `verticalScroll` 容器，确认按钮不得被顶出窗口。
- 自定义电台的标签同样只可选自词表；写入 DataStore。

## 6. 构建配置变更

`app/build.gradle.kts` 的 `dependencies` 增加 `testImplementation("junit:junit:4.13.2")`，
并新建 `app/src/test/java/...`。理由：该模块此前**没有任何单元测试能力**，
而本次要锁的三类问题（assets 解析、副标题规则、旧数据兼容）都是运行时静默故障。

## 7. 测试与验收

**JVM 单测**（新增）

| 测试 | 钉住的故障 |
|---|---|
| `StationTagTest` | key 唯一、四组非空、`labelOf` 未知 key 回落原值不崩 |
| `RadioStationAssetsTest` | **读真 assets 文件**：465 台全解析；每个 tag 都在词表内；`language` 都在白名单内；原真频率未被清掉 |
| `CustomStationCompatTest` | 旧自定义电台 JSON（缺三字段）不崩、`tags` 空；写后读一致 |
| `SubtitleRuleTest` | 频率 > 国家·语言 > 分类 的优先级与各空值组合 |
| `TagFilterTest` | 标签 × 分类 × 搜索 正交组合，含"标签命中但分类不命中"边界 |

**Python 测试**：`derive_tags.py` 的纯逻辑（映射、模板句拼装、拆分）按 A-1 风格
写 `tools/test_derive_tags.py`，先写测试再写实现。

**设备矩阵**（手机优先，这是用户明确要求的权重）

- 手机档：① 本机 1080x2424@420dpi 竖屏；② 小屏 411x914dp@240dpi；③ 超大字号档。
  断言：标签 chips 命中区 ≥ 48dp、chip 行不溢出、详情页标签不与播控/进度条重叠、
  从 `TagScreen` 返回后 `RadioScreen` 仍停在「分类」页签。
- 车机档：1280x720 恒横屏，**density 160 与 240 两档**都过，命中区 ≥ 88dp。
- 工具：扩 `tools/verify_ui.py` 支持标签页断言，`cap.ps1` + `car_assert.ps1`
  两形态各出截图与几何报告。
- **回归（发布前清单 6 项必跑）**：电台列表加载、播放/暂停/换台、戏曲循环模式、
  字体大小持久化、底部播放条布局、音频库网络 Tab 加载。副标题与详情页都被改动，
  不能只测新功能。
- **抽样实播**：`playcheck` 抽 ≥8 台（含 2 台裸 IP、2 台全球台），
  因为本次会批量重写 `description`，需复验没顺手改坏流地址。

**可量化验收标准**

1. 465 台全部解析通过、JVM 与 Python 测试全绿。
2. 标签覆盖率：**不预设拍脑袋的目标数**。`derive_tags.py` 先跑 dry-run 输出实际覆盖率与
   `0 标签` 名单，拿这个实测值跟你定验收线；本文只约定一条硬底线：**≥ 40%**，
   低于 40% 就停下来讨论要不要给词表加“剧种不详”这类中间层标签，而不是乱贴凑数。
   （A-1 教训：未核实就外推数字比不说更糟。）
3. 全球台简介：真简介 + 官网自述合计 ≥ 60%，三个来源数字分别报告。
4. 三处副标题渲染一致（截图比对）。
5. 手机三档 + 车机两档几何断言全过；发布前 6 项回归全过；抽样实播全 PLAYING。

## 8. 风险与回滚

| 风险 | 处置 |
|---|---|
| 词表 key 入库后难改 | 越剧/粤剧、`countrymusic` 已规避冲突；spec 评审是唯一的改名时机 |
| 官网自述混入未验证断言 | 只取中性描述句 + 抽 20 条人工看；拿不准退回模板句 |
| 派生结果不可重跑（依据在临时目录） | 精简溯源文件 `tools/rb_provenance.json` 入库 |
| Pager 内导航返回丢页签 | 页签保持依赖 `rememberPagerState` 的可保存状态与 NavHost saveable；**这是待证事项不是假定**，设备矩阵实测“分类→标签→返回”仍停在「分类」页签，不过关就改成不加路由 |
| 旧数据/降级安装 | 字段默认值 + `ignoreUnknownKeys` + 兼容单测 |
| APK 体积 | JSON 增加约 30KB，可接受 |
| 回滚 | 代码与数据各一个提交，`git revert` 即可；assets 另有 `.pretag.bak` |

## 9. 实施顺序（细节由 writing-plans 展开）

1. `StationTag.kt` + 模型字段 + 副标题纯函数（先写单测，RED）
2. `tools/rb_provenance.json` 落地 → `derive_tags.py`（测试先行）→ 派生 + 抓取 + 断言 → 写 assets
3. `StationRepository` / `HomeViewModel` 解析与筛选
4. `TagScreen` + 分类页入口 + 详情页 chips + 管理页多选
5. 手机三档、车机两档设备验收 + 发布前 6 项回归 + 抽样实播
6. 提交（代码与数据分开），出 release 包
