package com.radio.chinese.ui.audio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.radio.chinese.ui.common.bitrateText
import com.radio.chinese.ui.common.mediaTimeText
import com.radio.chinese.ui.library.AddSourceDialog
import com.radio.chinese.ui.library.AudioLibraryScreen
import com.radio.chinese.ui.library.AudioLibraryViewModel
import com.radio.chinese.ui.theme.Dimens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioMainScreen(
    playerManager: PlayerManager
) {
    val tabs = listOf("本地", "网络", "收藏", "最近")
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
                    onSeek = { playerManager.seekOperaTo(it) }
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
                    if (pagerState.currentPage == 1 && uiState.showBrowseContent) {
                        IconButton(onClick = { viewModel.refreshBrowse() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新目录")
                        }
                    } else {
                        IconButton(onClick = { viewModel.showAddDialog() }) {
                            Icon(Icons.Default.Add, contentDescription = "添加音频库来源")
                        }
                    }
                }
            )

            TabRow(selectedTabIndex = pagerState.currentPage) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title) }
                    )
                }
            }

            // 浏览时显示当前库名（刷新入口已上方到搜索行右侧）
            if (pagerState.currentPage == 1 && uiState.showBrowseContent) {
                uiState.browsingSource?.let { src ->
                    Text(
                        text = src.name,
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
            }

            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> AudioLocalTab(playerManager = playerManager, searchQuery = searchQuery)
                    1 -> AudioLibraryScreen(viewModel = viewModel, searchQuery = searchQuery)
                    2 -> AudioFavoritesTab(viewModel = viewModel, searchQuery = searchQuery)
                    3 -> AudioRecentTab(viewModel = viewModel, searchQuery = searchQuery)
                }
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
