package com.radio.chinese.ui.audio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.SearchBarRow
import com.radio.chinese.ui.common.OperaMiniPlayerBar
import com.radio.chinese.ui.common.SurfaceTab
import com.radio.chinese.ui.common.SurfaceTabBar
import com.radio.chinese.ui.library.AddSourceDialog
import com.radio.chinese.ui.library.AudioLibraryScreen
import com.radio.chinese.ui.library.AudioLibraryViewModel
import com.radio.chinese.ui.library.SearchResultsList
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface
import com.radio.chinese.ui.theme.canHostRail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioMainScreen(
    playerManager: PlayerManager,
    onOpenPlayer: () -> Unit = {}
) {
    val tabs = listOf(
        SurfaceTab("本地", Icons.Default.Folder),
        SurfaceTab("网络", Icons.Default.Cloud),
        SurfaceTab("收藏", Icons.Default.Star),
        SurfaceTab("最近", Icons.Default.History),
    )
    // 页码与搜索词都用 rememberSaveable：从音频库播一条结果进全屏播放页再返回，
    // 本屏会被重建。普通 remember 把“在第几页、搜了什么”清成初始值，用户就被弹回库主页（用户实测问题 3）。
    var savedPage by rememberSaveable { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(initialPage = savedPage, pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val viewModel: AudioLibraryViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()

    // 播放状态统一读 PlayerManager：网络与本地最终都经由它播出，
    // 此前同时读 ViewModel 和 PlayerManager 两份，切 Tab 时会出现进度不一致。
    val operaFile by playerManager.operaFile.collectAsState()
    val operaPlaying by playerManager.isPlaying.collectAsState()

    var lastBackMs by remember { mutableLongStateOf(0L) }
    val ctx = LocalContext.current
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackMs < 2000L) (ctx as? android.app.Activity)?.finish()
        else {
            lastBackMs = now
            android.widget.Toast.makeText(ctx, "再按一次退出", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 形态判定只有一个源：这里读，往下传布尔给 VM，VM 不自己读 CompositionLocal
    val carSurface = isCarSurface()
    // 跨源搜索只属于「网络」页签；本地/收藏/最近的搜索框继续只做当页过滤（用户实测问题 1）
    val onNetworkTab = pagerState.currentPage == 1
    // 回写页码，供返回时恢复（rememberPagerState 自己不管导航级持久化）
    LaunchedEffect(pagerState.currentPage) { savedPage = pagerState.currentPage }
    LaunchedEffect(searchQuery, carSurface, onNetworkTab) {
        viewModel.updateSearchQuery(searchQuery, carSurface, onNetworkTab)
    }
    // 这里故意没有“进页/加源就自动建索引”的副作用：索引只能由用户在确认框里起。
    // 之前的 LaunchedEffect(sources.size, carSurface) { startIndexingIfNeeded() } 已删除。

    val trackTitle = uiState.currentTrack?.name ?: operaFile?.name ?: ""
    val hasPlayer = trackTitle.isNotEmpty()

    // 用 Scaffold.bottomBar 承载播放条：内容区自动让位，
    // 不再用 Box overlay 压在列表上（那样列表最后一项永远点不到）。
    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        bottomBar = {
            if (hasPlayer) {
                // 迷你条只给曲名与一个播放/暂停；点其余区域进全屏播放页控细项
                OperaMiniPlayerBar(
                    title = trackTitle,
                    isPlaying = operaPlaying,
                    onPlayPause = { playerManager.togglePlayPause() },
                    onClick = onOpenPlayer
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 与电台页共用 SearchBarRow：搜索框同一宽度、同一纵向节奏，
            // 动作按钮固定在右侧同一个 48dp 槽位里（原先是一整行 FilledTonalButton，把 TabRow 顶下去一段）
            SearchBarRow(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = when {
                    onNetworkTab -> "搜索曲目（全部来源）"
                    pagerState.currentPage == 0 -> "搜索本地曲目"
                    else -> "搜索音频"
                },
                actions = {
                    val actionSize = if (isCarSurface()) Modifier.size(Dimens.TouchMin) else Modifier
                    if (pagerState.currentPage == 1 && uiState.showBrowseContent) {
                        IconButton(onClick = { viewModel.refreshBrowse() }, modifier = actionSize) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新目录")
                        }
                    } else {
                        IconButton(onClick = { viewModel.showAddDialog() }, modifier = actionSize) {
                            Icon(Icons.Default.Add, contentDescription = "添加音频库来源")
                        }
                    }
                }
            )

            val onSelectTab: (Int) -> Unit = { scope.launch { pagerState.animateScrollToPage(it) } }
            val headerVisible = pagerState.currentPage == 1 && uiState.showBrowseContent
            val headerName = uiState.browsingSource?.name ?: ""

            // 输入即跨源搜索，结果列表顶掉页签内容区；清空即回到原来的目录浏览。
            // 车机档已与手机档对齐（问题 1）：不再把车机排除在搜索态外。
            val showSearchResults = searchQuery.isNotBlank() && onNetworkTab
            val searchResultsArea: @Composable (Modifier) -> Unit = { m ->
                SearchResultsList(
                    results = uiState.searchResults,
                    query = searchQuery,
                    phase = uiState.searchPhase,
                    indexState = uiState.indexState,
                    breadcrumbOf = viewModel::breadcrumbFor,
                    isFavorited = viewModel::isFavorited,
                    onPlay = viewModel::playSearchResult,
                    onOpenFolder = { hit ->
                        // 先退搜索态再跳：searchQuery 不清掉，跳过去的目录会被这个结果列表盖住，
                        // 看着就像长按了没反应。它是本屏的局部状态，不在 VM 里。
                        searchQuery = ""
                        viewModel.openResultFolder(hit)
                    },
                    onBuildIndex = viewModel::confirmIndexing,
                    onAddSource = viewModel::showAddDialog,
                    onToggleFavorite = { hit ->
                        val sourceName = uiState.sources.firstOrNull { it.id == hit.sourceId }?.name ?: ""
                        viewModel.toggleFavorite(hit.sourceId, hit.path, hit.name, sourceName)
                    },
                    playingPath = uiState.playingPath,
                    modifier = m
                )
            }
            val pagerArea: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    BrowseSourceHeader(visible = headerVisible, name = headerName)
                    AudioPagerHost(
                        pagerState = pagerState,
                        playerManager = playerManager,
                        viewModel = viewModel,
                        searchQuery = searchQuery,
                        // 必须是 weight 不能是 fillMaxSize：同列里还有顶部库名行，
                        // fillMaxSize 会让总高超出父容器，溢出的列表项会盖住下方播放条并抢点击
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            // 内容区：搜索态出结果列表，否则出页签宿主
            val contentArea: @Composable (Modifier) -> Unit = { m ->
                if (showSearchResults) searchResultsArea(m) else pagerArea(m)
            }

            val railNav = canHostRail(tabs.size)
            if (railNav) {
                // 横屏：页签改左侧栏，内容区拿到整屏高度
                Row(modifier = Modifier.weight(1f)) {
                    SurfaceTabBar(
                        tabs = tabs,
                        currentPage = pagerState.currentPage,
                        onSelect = onSelectTab,
                        modifier = Modifier.width(Dimens.RailWidth),
                        useRail = railNav,
                    )
                    contentArea(Modifier.weight(1f))
                }
            } else {
                SurfaceTabBar(
                    tabs = tabs,
                    currentPage = pagerState.currentPage,
                    onSelect = onSelectTab,
                    useRail = railNav,
                )
                contentArea(Modifier.weight(1f))
            }
        }
    }

    // 索引确认框：只能由用户点头才起后台爬取，超时、误操作都不会自己跑
    if (uiState.askIndexConfirm) {
        val webdavCount = uiState.sources.count { it.type == com.radio.chinese.domain.SourceType.WEBDAV }
        // 同一个确认框兼顾两种情形：从未建索引 vs 索引残缺/过期。后者不重建就会搜不到新内容
        val stale = uiState.searchPhase == com.radio.chinese.domain.SearchPhase.ReadyStale
        AlertDialog(
            onDismissRequest = viewModel::declineIndexing,
            title = { Text(if (stale) "索引可能不完整" else "尚未建立索引") },
            text = {
                Text(
                    if (stale) {
                        "当前索引可能已过期或有目录没爬完，所以很多曲目搜不到。\n" +
                            "现在重建这 $webdavCount 个网络源的索引？可能需要 1–3 分钟，期间可以退出本页。"
                    } else {
                        "不建立索引就只能看到当前目录里的曲目。\n" +
                            "现在为 $webdavCount 个网络源建立索引？可能需要 1–3 分钟，期间可以退出本页。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmIndexing) { Text(if (stale) "重建索引" else "建立索引") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::declineIndexing) { Text("以后再说") }
            },
        )
    }

    // 添加来源对话框挂在宿主上：右上角的“+”在四个 Tab 都显示，
    // 以前它只在「网络」Tab 内部合成，其他三个 Tab 按下去什么也不会弹。
    if (uiState.showAddDialog) {
        AddSourceDialog(
            isLoading = uiState.isLoading,
            error = uiState.error,
            onDismiss = { viewModel.hideAddDialog() },
            onConfirm = { name, type, url, username, password ->
                viewModel.addSource(name, type, url, username, password)
            }
        )
    }
}

/** 浏览态下顶部显示当前音频库名（刷新入口在搜索行右侧）。 */
@Composable
private fun BrowseSourceHeader(visible: Boolean, name: String) {
    if (!visible || name.isEmpty()) return
    Text(
        text = name,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(
            horizontal = Dimens.ScreenPadding,
            vertical = Dimens.GapTiny
        )
    )
}

/** 四个页签的宿主，手机档与车机档共用。 */
@Composable
private fun AudioPagerHost(
    pagerState: PagerState,
    playerManager: PlayerManager,
    viewModel: AudioLibraryViewModel,
    searchQuery: String,
    modifier: Modifier = Modifier
) {
    HorizontalPager(state = pagerState, modifier = modifier) { page ->
        when (page) {
            0 -> AudioLocalTab(playerManager = playerManager, searchQuery = searchQuery)
            1 -> AudioLibraryScreen(viewModel = viewModel, searchQuery = searchQuery)
            2 -> AudioFavoritesTab(viewModel = viewModel, searchQuery = searchQuery)
            3 -> AudioRecentTab(viewModel = viewModel, searchQuery = searchQuery)
        }
    }
}
