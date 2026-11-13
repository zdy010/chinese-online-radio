package com.radio.chinese.ui.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.data.repository.IptvStationRepository
import com.radio.chinese.domain.model.IptvCategory
import com.radio.chinese.domain.model.IptvChinaOrder
import com.radio.chinese.domain.model.IptvLanguage
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.common.NowPlayingState
import com.radio.chinese.ui.common.RadioMiniPlayerBar
import com.radio.chinese.ui.common.SearchBarRow
import com.radio.chinese.ui.common.SurfaceTab
import com.radio.chinese.ui.common.TvNineKeySearchDialog
import com.radio.chinese.ui.common.TvSearchEntry
import com.radio.chinese.ui.common.focusRing
import com.radio.chinese.ui.theme.isTvSurface
import com.radio.chinese.ui.common.SurfaceTabBar
import com.radio.chinese.ui.common.rememberNowPlaying
import com.radio.chinese.ui.home.StationListItem
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.canHostRail
import com.radio.chinese.ui.theme.isCarSurface
import kotlinx.coroutines.launch

/** 页签顺序：国家(0) / 省份(1) / 中国(2) / 分类(3) / 收藏(4) / 最近(5)，默认停在中国 */
private const val DEFAULT_TAB_PAGE = 2

/**
 * iptv 一级 tab：与电台同构的「搜索栏 + 页签 + 左右滑动」，**不平铺 12k 条**。
 *
 * 页签顺序 国家 / 省份 / 中国 / 分类 / 收藏 / 最近，默认停在第 2 页「中国」直接列国内频道。
 * 分类与省份是两级入口，点进去才按标签 / 中文省名列频道；没有分类标签的记录
 * 只能靠搜索、国家、最近或收藏到达，这是数据形状决定的，不硬造一个巨大的「其他」格子。
 * 有查询时页签让位给搜索结果，二级列表用 [BrowseTopBar] 带返回。
 */
@Composable
fun IptvScreen(
    playerManager: PlayerManager,
    onNavigateToPlayer: (String) -> Unit,
    viewModel: IptvViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    // 输入框绑同步 query，不绑 uiState（后者在 Default 线程重算，会回滚输入）
    val queryText by viewModel.query.collectAsState()
    // 迷你条要的是"这台还在不在播"，与列表的 isPlaying 标记分开取，避免搜索过滤掉当前台时看不到
    val current by playerManager.currentStation.collectAsState()
    val playing by playerManager.isPlaying.collectAsState()
    val playbackError by playerManager.error.collectAsState()
    // iptv 是视频：点台就该立刻看到画面，不能沿用电台"先留在列表、再点才进播放页"的语义。
    // 当前台正在播时只导航，不重复起播（重复 playStation 会重置轮转，打断正在播的流）。
    val onRowClick: (RadioStation) -> Unit = { station ->
        if (playerManager.currentStation.value?.id != station.id) {
            playerManager.playStation(station)
        }
        onNavigateToPlayer(station.id)
    }
    val nowPlaying = rememberNowPlaying(playerManager)
    // "" = 首屏四入口；categories/countries/recent/favorite = 二级入口页；
    // "cat:<label>" / "cty:<name>" = 具体频道列表。用 String 是为了 rememberSaveable 直接可存。
    var mode by rememberSaveable { mutableStateOf("") }
    // 记住进二级目录前停在哪一页，返回时 Pager 恢复到那一页而不是弹回「中国」
    // key 带版本：页签顺序变过，旧版保存的 index 0 是「中国」，新版 0 已变成「国家」，
    // 直接恢复会把用户丢到错误的一页。换 key 后升级一律回到默认「中国」。
    var lastTabPage by rememberSaveable(key = "iptv_tab_v2") {
        mutableIntStateOf(DEFAULT_TAB_PAGE)
    }
    var showTvSearch by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    // 系统返回键先退"搜索态"、再退"二级列表"，不要一步退出 iptv tab。
    // 不拦这一步时，在分类/国家列表按返回会直接回到电台页，看起来像"点进去就回不来了"。
    BackHandler(enabled = ui.query.isNotEmpty()) { viewModel.updateQuery("") }
    BackHandler(enabled = ui.query.isBlank() && mode.isNotEmpty()) { mode = parentOf(mode) }

    // 切台基准（V11）：始终注册一份列表，**不能清空**。
    // 首屏已改成页签式，默认「中国」页本身就是频道列表；清空会让播放页显示
    // 「第 0 / 0 频道」、上下台按钮失效 —— 从播放页返回再点迷你条回去时尤其明显，
    // 看起来像播放状态被重置。清理集中在 MainScreen 按路由做（切到别的 tab 才清）。
    LaunchedEffect(ui.visible, mode, ui.query) {
        // 冷启 / 切回 tab 的首帧 visible 可能是空表：别把已有基准覆盖成空，
        // 否则播放页显示「第 0 / 0 频道」、上下台与方控滚轮全 no-op（QA 审查 P1）。
        if (ui.isLoading || ui.visible.isEmpty()) return@LaunchedEffect
        val base = when {
            ui.query.isNotBlank() || mode.isNotEmpty() -> ui.visible
            // 与「中国」页同一份顺序，否则播放页频道号和列表对不上、上下台会跳到意外位置
            else -> IptvChinaOrder.sorted(ui.visible.filter { it.country == "中国" })
        }
        playerManager.setSkipBase(base)
        // 诊断：0/0 频道这类问题要靠这行确认「到底注册了没有、注册了多少条」
        android.util.Log.d("SkipBase", "registered size=${base.size} mode=$mode query=${ui.query}")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 电视没有键盘，换成九键拼音首字母入口
        if (isTvSurface()) {
            TvSearchEntry(onOpen = { showTvSearch = true })
        } else {
            SearchBarRow(
                value = queryText,
                onValueChange = { q ->
                    viewModel.updateQuery(q)
                    if (q.isNotEmpty()) mode = ""
                },
                placeholder = "搜索频道名称、别名或国家",
            )
        }

        if (showTvSearch) {
            TvNineKeySearchDialog(
                stations = ui.visible,
                onDismiss = { showTvSearch = false },
                onSelect = { station ->
                    showTvSearch = false
                    onRowClick(station)
                },
            )
        }

        // 语言多选常驻在搜索栏下：过滤在 ViewModel 层，对搜索、页签、分类目录同时生效，
        // 不是只在「分类」页才有效，免得换个页签筛选就悄悄失效。
        LanguageFilterRow(
            selected = ui.selectedLanguages,
            onOpen = { showLanguageDialog = true },
        )
        if (showLanguageDialog) {
            LanguageFilterDialog(
                selected = ui.selectedLanguages,
                counts = ui.languages,
                onToggle = viewModel::toggleLanguage,
                onClear = { viewModel.setLanguages(emptySet()) },
                onDismiss = { showLanguageDialog = false },
            )
        }

        if (ui.isLoading) {
            LoadingState("正在读取内置频道清单…", modifier = Modifier.weight(1f))
            return@Column
        }
        if (ui.loadFailed) {
            EmptyState(
                icon = Icons.Default.Tv,
                title = "频道清单不可用",
                hint = "内置清单缺失或解析失败，请重新安装应用",
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        val searching = ui.query.isNotBlank()
        if (!searching && mode.isNotEmpty()) {
            BrowseTopBar(title = modeTitle(mode, ui), onBack = { mode = parentOf(mode) })
            if (mode == "categories") {
                Text(
                    text = "按上游标签聚合；没有分类标签的频道请用搜索或国家入口查找",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapSmall),
                )
            }
        }

        val favoriteIds = ui.favorites.map { it.id }.toSet()

        when {
            searching -> {
                if (ui.visible.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Tv,
                        title = "没有匹配的频道",
                        hint = "试试频道别名、国家名或路径名",
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    StationGrid(
                        stations = ui.visible,
                        nowPlaying = nowPlaying,
                        favoriteIds = favoriteIds,
                        onRowClick = onRowClick,
                        onFavoriteClick = viewModel::toggleFavorite,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 与电台一致的排版：搜索栏 + 页签 + 左右滑动。默认停在第 0 页「中国」。
            mode.isEmpty() -> IptvTabsHost(
                ui = ui,
                nowPlaying = nowPlaying,
                favoriteIds = favoriteIds,
                onRowClick = onRowClick,
                onFavoriteClick = viewModel::toggleFavorite,
                onOpenMode = { mode = it },
                initialPage = lastTabPage,
                onPageChanged = { lastTabPage = it },
                modifier = Modifier.weight(1f),
            )

            mode == "categories" -> LabelGrid(
                entries = ui.categories,
                icon = Icons.Default.Category,
                onOpen = { mode = "cat:$it" },
                modifier = Modifier.weight(1f),
            )

            mode == "countries" -> LabelGrid(
                entries = ui.countries,
                icon = Icons.Default.Public,
                onOpen = { mode = "cty:$it" },
                modifier = Modifier.weight(1f),
            )

            mode == "regions" -> LabelGrid(
                entries = ui.regions,
                icon = Icons.Default.Place,
                onOpen = { mode = "rgn:$it" },
                modifier = Modifier.weight(1f),
            )

            else -> {
                val list = listFor(ui, mode)
                if (list.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Tv,
                        title = "这里还没有频道",
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    StationGrid(
                        stations = list,
                        nowPlaying = nowPlaying,
                        favoriteIds = favoriteIds,
                        onRowClick = onRowClick,
                        onFavoriteClick = viewModel::toggleFavorite,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 迷你条：从播放页返回后还能看到"正在播什么"，点它回专用播放页。
        // 只有 iptv 台才显示：电台台有自己的迷你条，不该在 iptv 页出现。
        val iptvNow = current?.takeIf { it.id.startsWith(IptvStationRepository.PREFIX) }
        if (iptvNow != null) {
            RadioMiniPlayerBar(
                title = com.radio.chinese.domain.model.StationDisplayName.of(iptvNow),
                subtitle = playbackError ?: if (playing) "正在播放" else "已暂停",
                coverUrl = iptvNow.logoUrl,
                isPlaying = playing,
                onPlayPause = { playerManager.togglePlayPause() },
                onClick = { onNavigateToPlayer(iptvNow.id) },
            )
        }
    }
}

/**
 * 二级列表标题。带上条目数：既让用户知道这一页有多大，也让验收能直接读到
 * "点进新闻后有 1003 个频道"，不必靠数列表项。
 */
private fun modeTitle(mode: String, ui: IptvUiState): String = when {
    mode == "categories" -> "全部分类 · ${ui.categories.size} 类"
    mode == "countries" -> "全部国家/地区 · ${ui.countries.size} 个"
    mode == "regions" -> "省份/区域 · ${ui.regions.size} 个"
    mode.startsWith("rgn:") -> {
        val name = mode.removePrefix("rgn:")
        "$name · ${ui.visible.count { it.region == name }} 个频道"
    }
    mode == "recent" -> "最近播放 · ${ui.recents.size} 个频道"
    mode == "favorite" -> "我的收藏 · ${ui.favorites.size} 个频道"
    mode.startsWith("cat:") -> {
        val label = mode.removePrefix("cat:")
        val n = ui.visible.count { st -> st.tags.any { IptvCategory.labelOf(it) == label } }
        "$label · $n 个频道"
    }
    mode.startsWith("cty:") -> {
        val name = mode.removePrefix("cty:")
        "$name · ${ui.visible.count { it.country == name }} 个频道"
    }
    else -> "频道列表"
}

/**
 * 二级频道列表返回时直接回页签首页（Pager 会记住来时的那一页）。
 *
 * 不再回 "categories"/"regions" 这类独立目录页：新排版下目录已经是页签里的一页，
 * 再退回一份没有页签栏的旧目录，用户会看到「返回后顶部页签不见了」。
 */
private fun parentOf(mode: String): String = when {
    mode.startsWith("cat:") || mode.startsWith("cty:") || mode.startsWith("rgn:") -> ""
    else -> ""
}

private fun listFor(ui: IptvUiState, mode: String): List<RadioStation> = when {
    mode == "recent" -> ui.recents
    mode == "favorite" -> ui.favorites
    mode.startsWith("cat:") -> {
        val label = mode.removePrefix("cat:")
        // 与 IptvViewLogic 的计数同一套翻译：先翻中文标签再比，别用原始 key
        ui.visible.filter { st -> st.tags.any { IptvCategory.labelOf(it) == label } }
    }
    mode.startsWith("cty:") -> ui.visible.filter { it.country == mode.removePrefix("cty:") }
    mode.startsWith("rgn:") -> ui.visible.filter { it.region == mode.removePrefix("rgn:") }
    else -> emptyList()
}

/** 常驻的语言筛选入口：一行文字 + 已选语言，点开多选对话框。 */
@Composable
private fun LanguageFilterRow(
    selected: Set<String>,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Language,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(Dimens.GapSmall))
        val label = if (selected.isEmpty()) {
            // 不能写「全部」：空选集时走的是默认的只中英过滤，西语/日语等并不在列表里
            "语言：默认（只显示中文与英语）"
        } else {
            "语言：" + selected.joinToString("、") { IptvLanguage.labelOf(it) }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "多选",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 语言多选对话框。
 *
 * 不用 DropdownMenu：项目实测在对话框里点不开（ManageScreen 的多选标签也踩过）。
 * 不用 FlowRow 是为了少一个 @OptIn(ExperimentalLayoutApi)：两列网格足够放 13 个选项，
 * 手动 chunked(2) 还能顺手把奇数行补空格，最后一个 chip 不会拉满整行。
 */
@Composable
private fun LanguageFilterDialog(
    selected: Set<String>,
    counts: List<Pair<String, Int>>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val countMap = counts.toMap()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按语言筛选") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                IptvLanguage.OPTIONS.chunked(2).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { (code, label) ->
                            val n = countMap[code] ?: 0
                            FilterChip(
                                selected = code in selected,
                                onClick = { onToggle(code) },
                                label = { Text(if (n > 0) "$label $n" else label) },
                                // 不按 n>0 禁用：计数是「当前可见」的台数，需 VPN 开关关闭时
                                // 只存在于需 VPN 台的语言会显示 0，禁用会让那个语言永远选不了。
                                // 允许选中，选中后列表为空时由列表页的空态文案兜底。
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = Dimens.GapSmall, bottom = Dimens.GapSmall),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text(
                    text = "不选 = 保持默认（只显示中文与英语）；可多选，命中任意一个即显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = onClear) { Text("清空") } },
    )
}

@Composable
private fun BrowseTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(Dimens.TouchSecondary)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
        }
        Spacer(Modifier.width(Dimens.GapSmall))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 与电台首页同构的页签容器：手机档顶部 TabRow / 车机档左侧 rail + HorizontalPager。
 *
 * 默认停在第 0 页「中国」——进 iptv 先看国内频道，不用在 12k 条里找；
 * 向左滑依次是地区 / 分类 / 收藏 / 最近，点顶栏页签等价。
 */
@Composable
private fun IptvTabsHost(
    ui: IptvUiState,
    nowPlaying: NowPlayingState,
    favoriteIds: Set<String>,
    onRowClick: (RadioStation) -> Unit,
    onFavoriteClick: (String) -> Unit,
    onOpenMode: (String) -> Unit,
    initialPage: Int,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 用户指定顺序：国家 → 省份 → 中国 → 分类 → 收藏 → 最近。
    // 默认停在中国（index 2），让国家/省份在左、分类/收藏/最近在右，两边一滑就到。
    val tabs = listOf(
        SurfaceTab("国家", Icons.Default.Public),
        SurfaceTab("省份", Icons.Default.Place),
        SurfaceTab("中国", Icons.Default.Flag),
        SurfaceTab("分类", Icons.Default.Category),
        SurfaceTab("收藏", Icons.Default.Favorite),
        SurfaceTab("最近", Icons.Default.History),
    )
    val tv = isTvSurface()
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { tabs.size })
    // 电视不用 HorizontalPager：列表项获得焦点时的 bringIntoView 会把 Pager 推到
    // 非整页横向偏移并停住（左列空白、右列被裁），每次按方向键又推一次，
    // 表现为「节目列表刷新停不下来」。电视改成页签点击直接换页，没有横向滚动容器。
    var tvPage by rememberSaveable(key = "iptv_tv_page_v1") { mutableIntStateOf(initialPage) }
    val currentPage = if (tv) tvPage else pagerState.currentPage
    // 进二级目录前记录当前页；返回时用它当 initialPage
    LaunchedEffect(currentPage) { onPageChanged(currentPage) }
    val scope = rememberCoroutineScope()
    val onSelect: (Int) -> Unit = if (tv) {
        { tvPage = it }
    } else {
        { scope.launch { pagerState.animateScrollToPage(it) } }
    }
    val railNav = canHostRail(tabs.size)

    val pager: @Composable (Modifier) -> Unit = { m ->
        IptvPager(
            pagerState = pagerState,
            staticPage = if (tv) tvPage else null,
            ui = ui,
            nowPlaying = nowPlaying,
            favoriteIds = favoriteIds,
            onRowClick = onRowClick,
            onFavoriteClick = onFavoriteClick,
            onOpenMode = onOpenMode,
            modifier = m,
        )
    }

    if (railNav) {
        Row(modifier = modifier) {
            SurfaceTabBar(
                tabs = tabs,
                currentPage = currentPage,
                onSelect = onSelect,
                modifier = Modifier.width(Dimens.RailWidth),
                useRail = true,
            )
            pager(Modifier.weight(1f))
        }
    } else {
        Column(modifier = modifier) {
            SurfaceTabBar(
                tabs = tabs,
                currentPage = currentPage,
                onSelect = onSelect,
                useRail = false,
            )
            pager(Modifier.weight(1f))
        }
    }
}

@Composable
private fun IptvPager(
    pagerState: PagerState,
    /** 非空 = 电视：直接渲染这一页，不创建 HorizontalPager */
    staticPage: Int?,
    ui: IptvUiState,
    nowPlaying: NowPlayingState,
    favoriteIds: Set<String>,
    onRowClick: (RadioStation) -> Unit,
    onFavoriteClick: (String) -> Unit,
    onOpenMode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // CCTV 打头的国内列表。页签 0/1/3 是目录网格，2/4/5 是频道列表
    val china = IptvChinaOrder.sorted(ui.visible.filter { it.country == "中国" })
    if (staticPage != null) {
        IptvPage(
            page = staticPage,
            china = china,
            ui = ui,
            nowPlaying = nowPlaying,
            favoriteIds = favoriteIds,
            onRowClick = onRowClick,
            onFavoriteClick = onFavoriteClick,
            onOpenMode = onOpenMode,
            modifier = modifier,
        )
    } else {
        HorizontalPager(state = pagerState, modifier = modifier) { page ->
            IptvPage(
                page = page,
                china = china,
                ui = ui,
                nowPlaying = nowPlaying,
                favoriteIds = favoriteIds,
                onRowClick = onRowClick,
                onFavoriteClick = onFavoriteClick,
                onOpenMode = onOpenMode,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun IptvPage(
    page: Int,
    china: List<RadioStation>,
    ui: IptvUiState,
    nowPlaying: NowPlayingState,
    favoriteIds: Set<String>,
    onRowClick: (RadioStation) -> Unit,
    onFavoriteClick: (String) -> Unit,
    onOpenMode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (page) {
        2, 4, 5 -> {
            val list = when (page) {
                2 -> china
                4 -> ui.favorites
                else -> ui.recents
            }
            if (list.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Tv,
                    title = if (page == 2) "没有中国频道" else "这里还是空的",
                    hint = if (page == 4) "在频道列表点收藏即可加入" else "播放过的频道会出现在这里",
                    modifier = modifier,
                )
            } else {
                StationGrid(
                    stations = list,
                    nowPlaying = nowPlaying,
                    favoriteIds = favoriteIds,
                    onRowClick = onRowClick,
                    onFavoriteClick = onFavoriteClick,
                    modifier = modifier,
                )
            }
        }
        0 -> LabelGrid(
            entries = ui.countries,
            icon = Icons.Default.Public,
            onOpen = { onOpenMode("cty:$it") },
            modifier = modifier,
        )
        1 -> LabelGrid(
            entries = ui.regions,
            icon = Icons.Default.Place,
            onOpen = { onOpenMode("rgn:$it") },
            modifier = modifier,
        )
        3 -> LabelGrid(
            entries = ui.categories,
            icon = Icons.Default.Category,
            onOpen = { onOpenMode("cat:$it") },
            modifier = modifier,
        )
    }
}

@Composable
private fun LabelGrid(
    entries: List<Pair<String, Int>>,
    icon: ImageVector,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = if (isCarSurface()) GridCells.Adaptive(300.dp) else GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
    ) {
        // label 已按中文聚合过，全表唯一：上游新增分类都落到「其他」也只会有一个格子
        items(entries, key = { it.first }) { (label, count) ->
            EntryCard(icon, label, "$count 个频道") { onOpen(label) }
        }
    }
}

@Composable
private fun StationGrid(
    stations: List<RadioStation>,
    nowPlaying: NowPlayingState,
    favoriteIds: Set<String>,
    onRowClick: (RadioStation) -> Unit,
    onFavoriteClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = if (isCarSurface()) GridCells.Adaptive(300.dp) else GridCells.Fixed(1),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapMedium),
    ) {
        items(stations, key = { it.id }) { station ->
            StationListItem(
                station = station,
                isPlaying = nowPlaying.matches(station),
                isFavorite = station.id in favoriteIds,
                onClick = { onRowClick(station) },
                onFavoriteClick = { onFavoriteClick(station.id) },
                // iptv 不接 EPG：不挂"正在播出"假标记
            )
        }
    }
}

@Composable
private fun EntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .focusRing(MaterialTheme.shapes.large)
            .fillMaxWidth()
            .heightIn(min = Dimens.TouchMin)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(Dimens.CardPadding).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconLarge),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(Dimens.GapMedium))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
