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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.service.OperaRepeatMode
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.SearchBarRow
import com.radio.chinese.ui.common.OperaMiniPlayerBar
import com.radio.chinese.ui.common.SurfaceTab
import com.radio.chinese.ui.common.SurfaceTabBar
import com.radio.chinese.ui.common.bitrateText
import com.radio.chinese.ui.common.mediaTimeText
import com.radio.chinese.ui.library.AddSourceDialog
import com.radio.chinese.ui.library.AudioLibraryScreen
import com.radio.chinese.ui.library.AudioLibraryViewModel
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioMainScreen(
    playerManager: PlayerManager
) {
    val tabs = listOf(
        SurfaceTab("本地", Icons.Default.Folder),
        SurfaceTab("网络", Icons.Default.Cloud),
        SurfaceTab("收藏", Icons.Default.Star),
        SurfaceTab("最近", Icons.Default.History),
    )
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val viewModel: AudioLibraryViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()

    // 播放状态统一读 PlayerManager：网络与本地最终都经由它播出，
    // 此前同时读 ViewModel 和 PlayerManager 两份，切 Tab 时会出现进度不一致。
    val operaFile by playerManager.operaFile.collectAsState()
    val operaPlaying by playerManager.isPlaying.collectAsState()
    val operaPos by playerManager.operaPosition.collectAsState()
    val operaDur by playerManager.operaDuration.collectAsState()
    val operaBr by playerManager.operaBitrate.collectAsState()
    val operaRepeat by playerManager.operaRepeatMode.collectAsState()

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

    var searchQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) { viewModel.updateSearchQuery(searchQuery) }

    val trackTitle = uiState.currentTrack?.name ?: operaFile?.name ?: ""
    val hasPlayer = trackTitle.isNotEmpty()

    // 用 Scaffold.bottomBar 承载播放条：内容区自动让位，
    // 不再用 Box overlay 压在列表上（那样列表最后一项永远点不到）。
    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        bottomBar = {
            if (hasPlayer) {
                OperaMiniPlayerBar(
                    title = trackTitle,
                    timeText = mediaTimeText(operaPos, operaDur),
                    bitrateText = bitrateText(operaBr),
                    positionMs = operaPos,
                    durationMs = operaDur,
                    isPlaying = operaPlaying,
                    repeatLabel = when (operaRepeat) {
                        OperaRepeatMode.ALL -> "列表循环"
                        OperaRepeatMode.ONE -> "单曲循环"
                        OperaRepeatMode.RANDOM -> "随机播放"
                    },
                    onPlayPause = { playerManager.togglePlayPause() },
                    onPrevious = { playerManager.playOperaPrevious() },
                    onNext = { playerManager.playOperaNext() },
                    onStop = { playerManager.stopOpera() },
                    onCycleRepeat = { playerManager.cycleOperaRepeatMode() },
                    onSeek = { playerManager.seekOperaTo(it) },
                    // 这两个方法早就在 PlayerManager 里，只是从未被任何界面调用
                    onSeekForward = { playerManager.seekOperaForward() },
                    onSeekBackward = { playerManager.seekOperaBackward() }
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
                placeholder = "搜索音频",
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

            if (isCarSurface()) {
                // 横屏：页签改左侧栏，内容区拿到整屏高度
                Row(modifier = Modifier.weight(1f)) {
                    SurfaceTabBar(
                        tabs = tabs,
                        currentPage = pagerState.currentPage,
                        onSelect = onSelectTab,
                        modifier = Modifier.width(Dimens.RailWidth)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        BrowseSourceHeader(visible = headerVisible, name = headerName)
                        AudioPagerHost(
                            pagerState = pagerState,
                            playerManager = playerManager,
                            viewModel = viewModel,
                            searchQuery = searchQuery,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                SurfaceTabBar(
                    tabs = tabs,
                    currentPage = pagerState.currentPage,
                    onSelect = onSelectTab
                )
                BrowseSourceHeader(visible = headerVisible, name = headerName)
                AudioPagerHost(
                    pagerState = pagerState,
                    playerManager = playerManager,
                    viewModel = viewModel,
                    searchQuery = searchQuery,
                    modifier = Modifier.weight(1f)
                )
            }
        }
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
