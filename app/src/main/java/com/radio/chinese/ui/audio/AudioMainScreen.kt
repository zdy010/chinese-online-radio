package com.radio.chinese.ui.audio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.library.AudioLibraryScreen
import com.radio.chinese.ui.library.AudioLibraryViewModel
import com.radio.chinese.ui.library.MiniPlayerBar
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

    // 本地播放状态（绕开 ViewModel 直接读 PlayerManager）
    val operaFile by playerManager.operaFile.collectAsState()
    val operaPlaying by playerManager.isPlaying.collectAsState()
    val operaPos by playerManager.operaPosition.collectAsState()
    val operaDur by playerManager.operaDuration.collectAsState()
    val operaBr by playerManager.operaBitrate.collectAsState()

    var lastBackMs by remember { mutableLongStateOf(0L) }
    val ctx = LocalContext.current
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackMs < 2000L) (ctx as? android.app.Activity)?.finish()
        else { lastBackMs = now; android.widget.Toast.makeText(ctx, "再按一次退出", android.widget.Toast.LENGTH_SHORT).show() }
    }

    // 搜索
    var searchQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) { viewModel.updateSearchQuery(searchQuery) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索栏（缩小）
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text("搜索音频...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.8f).padding(horizontal = 12.dp, vertical = 4.dp),
            trailingIcon = { if (searchQuery.isNotEmpty()) TextButton(onClick = { searchQuery = "" }) { Text("清除") } }
        )

        // Tab 栏
        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title) }
                )
            }
        }

        // 网络 tab 的添加按钮（仅源列表页显示，进入浏览后隐藏）
        if (pagerState.currentPage == 1 && !uiState.showBrowseContent) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { viewModel.showAddDialog() }) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加来源")
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> AudioLocalTab(playerManager = playerManager, searchQuery = searchQuery)
                    1 -> AudioLibraryScreen(viewModel = viewModel, showTopBar = false, showMiniPlayer = false, searchQuery = searchQuery)
                    2 -> AudioFavoritesTab(viewModel = viewModel, searchQuery = searchQuery)
                    3 -> AudioRecentTab(viewModel = viewModel, searchQuery = searchQuery)
                }
            }

            // MiniPlayer：优先用 ViewModel 状态，否则用 PlayerManager 直接状态
            val hasPlayer = uiState.currentTrack != null || operaFile != null
            val isLocalPlayback = uiState.currentTrack == null && operaFile != null
            var localRepeatMode by remember { mutableIntStateOf(0) } // 0=ALL 1=ONE 2=RANDOM
            if (hasPlayer) {
                val track = uiState.currentTrack ?: com.radio.chinese.domain.AudioTrack(
                    id = operaFile?.fileId?.toString() ?: "", name = operaFile?.name ?: "",
                    path = "", parentPath = "", isFolder = false, size = 0
                )
                val repeatDisplay = if (isLocalPlayback) {
                    com.radio.chinese.ui.library.RepeatMode.entries[localRepeatMode % 3]
                } else uiState.repeatMode
                MiniPlayerBar(
                    track = track,
                    isPlaying = if (!isLocalPlayback) uiState.isPlaying else operaPlaying,
                    positionMs = if (!isLocalPlayback) uiState.positionMs else operaPos,
                    durationMs = if (!isLocalPlayback) uiState.durationMs else operaDur,
                    bitrateBps = if (!isLocalPlayback) uiState.bitrateBps else operaBr,
                    onTogglePlayPause = { if (isLocalPlayback) playerManager.togglePlayPause() else viewModel.togglePlayPause() },
                    onStop = { if (isLocalPlayback) playerManager.stopOpera() else viewModel.stopPlayback() },
                    onSeek = { if (isLocalPlayback) playerManager.seekOperaTo(it) else viewModel.seekTo(it) },
                    onNext = { if (isLocalPlayback) playerManager.playOperaNext() else viewModel.playNext() },
                    onPrevious = { if (isLocalPlayback) playerManager.playOperaPrevious() else viewModel.playPrevious() },
                    onCycleRepeat = { if (isLocalPlayback) localRepeatMode = (localRepeatMode + 1) % 3 else viewModel.cycleRepeatMode() },
                    repeatMode = repeatDisplay,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}
