# 线控播报 + 定位本地优先 设计

> 2026-10-16 · 两个独立新功能，同一 spec 归档（都作用于「收听切换」体验）。前置决策已由用户确认：
> - 功能A：语音播报**仅在线控/方控/系统媒体控件切台时**触发（UI 点台不播）；时序为**先播报再开播**；文案 `即将播放：<名称>`；**要求挑好听自然的声音**。
> - 功能B：本地粒度到**省/市一级**；设备侧走 **GPS 经纬度 + 离线最近城市 + 手动兜底**；电台地区**运行时推断**、不手改 451 条 JSON；本地优先**同时影响首页列表与线控切台顺序**；开关默认**开**。

## 1. 目标与非目标

**目标**

1. 用线控/方向盘切台时，先出声播报"即将播放：<台名/曲目名>"，播完再起新流，接近 iPod 语音提示体验。
2. 播报依赖设备已装的中文 TTS，能自动选到**相对自然**的音色；用户可在设置里试听、改选音色；无引擎/无中文 voice/引擎异常时**静默降级**，绝不卡住切台。
3. 按用户当前所在省/市，把"本地"电台在列表与切台顺序里排在前面；同市 > 同省 > 同国家 > 其余，组内保持原相对顺序。
4. 拿不到定位或被拒时，用设置里手动选择的省/市兜底；两者都无则退化为"不加分"，列表等于现状。
5. 所有可判定逻辑（地区推断、最近城市、排序、下一台解析、音色评分）都是**纯函数**，JVM 单测可钉死；出声与 GPS 上车机真机裁决。

**非目标（本期不做）**

- 不做把任意动态台名预生成音频（预录 clips）的方案——台名不可枚举，仍走运行时 TTS。
- 不做联网地理编码 API（高德/百度/腾讯），避免厂商 key、联网与隐私依赖。
- 不做省/市以外更细粒度（区县）匹配；不做"根据定位切换语言/自动换台"。
- 播报只覆盖**切台**，不覆盖播放/暂停、定时、收藏等操作。
- 不改 `Dimens` 几何、不动主题系统、不动 `HeadsetHookDetector` 单/双击判定逻辑。

## 2. 功能A：线控播报

### 2.1 唯一入口

现有 `PlayerManager.handleMediaSkip(forward)` 是线控/方控媒体键、系统媒体控件、`MainActivity` 按键分发的共同收敛点（见 `RadioService.buildSessionCallback` 与 `MainActivity`），UI 点台走独立的 `playStation`。改造集中于此即天然满足"仅线控切台播报"，无需新增判定分支。

### 2.2 时序（先播报再开播）

```
handleMediaSkip(forward):
  若戏曲模式:  target = nextOperaFile(forward)   名称 = target?.name
  否则:        stations = getActiveStations()（已含功能B的本地优先序）
              target   = nextStation(stations, currentId, forward)   名称 = target?.name
  若 target 为空:  return（保持现状，不动作）
  若 播报开 且 TtsAnnouncer 可用:
      controller.pause()                       // 先停/让出音频焦点
      TtsAnnouncer.speak("即将播放：" + 名称)   // 等 onDone / onError / 超时(3.5s)
  playStation(target) / playOpera(target)      // 播完再开播新内容
```

- `nextStation(stations, currentId, forward): RadioStation?`：从 `playNext/playPrevious` 里抽出的纯函数，返回"将要切到的那个台"，不产生副作用。`handleMediaSkip` 用它先拿到名称、再落地播放。
- 单条直播流/逐段起播本就是"换内容"，pause 旧流不影响体验；不做旧流恢复。

### 2.3 降级矩阵

| 情况 | 行为 |
|---|---|
| 设备无 TTS 引擎 | `speak` 直接返回失败 → 跳过播报、照常切台 |
| 引擎无 zh voice | `isChineseVoiceAvailable=false` → 跳过播报 |
| 引擎初始化超时/崩溃 | `withTimeout` + `onInit status!=SUCCESS` → 静默切台 |
| 播报中途引擎报错/卡死 | `onError` 或 3.5s 超时兜底 → 立即放行开播 |

原则：播报是增益，失败必须"等于没开这个功能"，不能吞掉按键或延迟出声到不可接受。

## 3. 音色选取（"要好听自然"）

设备可能装了**不止一个**中文 TTS 引擎（系统自带、厂商、第三方各一），质量与音色差别很大（尤其方易通 FYT 车机）。因此既要有默认选优，也要把**全部引擎×音色**摊开让用户自己挑：

1. **枚举全部引擎与音色**：新增 `VoiceCatalog`（seam，可注入 fake）。用 `PackageManager.queryIntentActivities(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)`（配合 Manifest `<queries>`）列出设备上**每一个** TTS 引擎的包名与显示名，再对每个引擎 `TextToSpeech(context, this, enginePackage)` 取其 `voices`，汇总成 `List<TtsOption>`（`enginePackage` / `engineLabel` / `voiceName` / `voiceFeatures` / locale / 是否联网 / 是否 premium）。`VoiceMeta` 增加 `enginePackage`、`engineLabel` 两字段。
2. **默认选优**：纯函数 `VoiceScoring`（打分：premium +2 / 非低质 +1 / 联网 -1）在**跨所有引擎**的中文 voice 里挑一个作为初始默认；用户没动过时生效。未选定时持久化为空，运行时用选优结果。
3. **用户显式选择（引擎 + 音色）+ 试听**：设置页「播报音色」列出**所有引擎×中文音色**的完整清单（每项显示 `引擎名 · 音色名`，标注「联网」等），点任一项即选定并**试听** `即将播放：中国之声 交通广播`；中老年用户靠逐个试听耳挑最自然的。选定二元组持久化（`preferred_tts_engine`=包名、`preferred_voice`=音色名），`TtsAnnouncer` 用该包名构造引擎并 `setVoice`。
4. **兜底**：无任何引擎或无任何 zh voice → 关闭播报并给出提示；不勉强用英文 voice 念中文台名。

语速 `speechRate≈1.1`、音调默认，均可在设置里微调（本期至少留参数，UI 视篇幅取舍）。

## 4. 功能B：定位 + 本地优先排序

### 4.1 数据侧：地区运行时推断

- 新增离线资产 `assets/region_gazetteer.json`：省份 + 该省主要城市名清单（关键词表，用于匹配台名/简介）。
- 纯函数 `RegionResolver.infer(name, description, tags, country): Region`：命中城市名→该城市+所属省；只命中省名→省（city 空）；`country` 非中国且无中文地区词→`Region(province="", city="")`（不参与本地加分）。
  - 例：`河南交通广播`→省=河南；`绍兴之声`→市=绍兴/省=浙江；`中国之声`、`CCTV-1综合伴音`→空。
- 不改 `RadioStation` 领域模型字段、不改 `radio_stations.json`：地区由推断得到，避免污染已体检的源数据。

### 4.2 设备侧：GPS → 最近城市

- 新增 `LocationSource`（seam）：`LocationManager`（GPS/network，不依赖 Google）取一次坐标——优先 `getLastKnownLocation`，无则在权限已授予时发起带 **5s 超时**的单次 `requestSingleUpdate`；拿不到返回 `null`。
- 新增离线资产 `assets/city_coordinates.json`：`[{name, province, lat, lng}]`（全国地级市 + 省会中心坐标）。
- 纯函数 `NearestCityFinder.find(lat, lng, cities, maxKm=120): Region?`：haversine 取最近，超阈值（人在国外/无表内城市）返回 `null`。
- 当前 region 缓存进 `RadioPreferences.cached_region`；列表排序读缓存值，**不在每次取列表时实时定位**。

### 4.3 排序接入

- 纯函数 `StationSorter.localPriority(stations, userRegion): List<RadioStation>`：对每台取 `RegionResolver.infer(...)`，算 tier——同市=3 / 同省=2 / 同国家(中国)=1 / 其余=0；**按 tier 降序稳定排序**（`sortedByDescending{tier}` 保持组内原序）。
- 接入点：`StationRepository.getActiveStations()`——`local_first_enabled` 且 `userRegion` 非空时套用 `localPriority`。首页列表（`HomeViewModel` 已用它）与 `handleMediaSkip`（同样用它）因此都跟随本地优先序，行为一致。

### 4.4 手动兜底

- 设置"本地地区"：显示自动定位结果；提供省/市下拉手动覆盖（`manual_region`）；"重新定位"按钮触发 `LocationSource` 刷新缓存。优先级：`manual_region` > `cached_region`（自动）> 无（不加分）。

## 5. 数据 / 持久化 / 权限 / 设置

**`RadioPreferences` 新增键**（沿用现有 `booleanPreferencesKey`/`stringPreferencesKey` 模式）：

| key | 类型 | 默认 | 用途 |
|---|---|---|---|
| `voice_announce_enabled` | Boolean | true | 功能A 总开关 |
| `preferred_voice` | String? | null | 选定的 TTS 音色标识 |
| `local_first_enabled` | Boolean | true | 功能B 总开关 |
| `cached_region` | String? | null | 自动定位缓存 `省|市` |
| `manual_region` | String? | null | 用户手动地区 `省|市` |

**Manifest**：`ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`；`<queries>` 内加 `TEXT_TO_SPEECH` intent（安卓 11+ TTS 引擎可见性必需）。运行时按现有 `notificationPermissionLauncher` 模式申请定位权限，被拒即走手动兜底、不强弹。

**`SettingsScreen`**：新增分区「收听增强」——线控播报开关 + 音色(试听)；本地优先开关 + 地区(显示/重新定位/手动选)。遵循车机 UI 几何与字号既有规范，不新增自定义尺寸。

## 6. 组件与文件清单

**新增（domain，纯）**：`Region.kt`、`RegionResolver.kt`、`NearestCityFinder.kt`、`StationSorter.kt`、`VoiceScoring.kt(rankVoice)`
**新增（seam/impl）**：`service/TtsAnnouncer.kt`、`service/VoiceCatalog.kt`、`service/LocationSource.kt`、`data/RegionRepository.kt`
**新增（assets）**：`region_gazetteer.json`、`city_coordinates.json`
**改动**：`PlayerManager.kt`(handleMediaSkip/nextStation)、`StationRepository.kt`(getActiveStations 接入排序)、`RadioPreferences.kt`(键)、`SettingsScreen.kt`(+VM)、`AndroidManifest.xml`(权限/queries)、`DI`（新增单例绑定）

## 7. 测试策略（TDD）

- **纯函数单测（JVM）**：
  - `RegionResolver.infer`：城市/省份/仅国家级/无地区/歧义（如"东方卫视"→上海）各例。
  - `NearestCityFinder.find`：已知坐标→期望城市；超阈值→null；边界。
  - `StationSorter.localPriority`：tier 分层 + **稳定性**（同 tier 保持入参顺序）+ 空 region 原样返回。
  - `nextStation`：中间/末位回绕/空列表。
  - `rankVoice`：PREMIUM/网络/低质 voice 排序符合打分表。
  - gazetteer & city_coordinates 合法性校验（仿现有 `StationJson` 测试）。
- **seam 假件**：`TtsEngine`/`LocationSource` 接口 + fake，验证 `handleMediaSkip` "先播后报" 时序与降级分支（不依赖真引擎）。
- **真机裁决**：车机上确认①实际出声、自然度与"播完再起流"无卡顿；②GPS/无 Google 环境定位可用性与手动兜底；③本地电台在列表与线控切台里都排前。按项目"真机防误报"规范，未出声/排序未变即判失败。

## 8. 风险与未决

1. **方易通车机是否装有中文 TTS 引擎**：若无良好 voice，功能A 自动降级为不播报；届时评估是否引入可控的第三方 TTS（不在本期承诺）。
2. **GPS 冷启动耗时**：单次定位 5s 超时兜底 + 手动选，避免首次进首页等待。
3. **451 台地区覆盖有限**：无中文地区词的台不参与加分（可接受，不强行猜测）。
4. **播报与音频焦点**：`controller.pause()` 后 MediaSession 让出焦点，TTS 走 `CONTENT_TYPE_SPEECH`；真机验证不被 ExoPlayer 残留焦点吞声。
