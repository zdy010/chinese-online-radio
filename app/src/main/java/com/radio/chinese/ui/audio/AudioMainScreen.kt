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
    val tabs = listOf("本地", "网络")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val viewModel: AudioLibraryViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()

    var lastBackMs by remember { mutableLongStateOf(0L) }
    val ctx = LocalContext.current
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackMs < 2000L) (ctx as? android.app.Activity)?.finish()
        else { lastBackMs = now; android.widget.Toast.makeText(ctx, "再按一次退出", android.widget.Toast.LENGTH_SHORT).show() }
    }

    // 搜索
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索栏
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                placeholder = { Text("搜索音频...") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                trailingIcon = { if (searchQuery.isNotEmpty()) TextButton(onClick = { searchQuery = "" }) { Text("清除") } }
            )
            if (pagerState.currentPage == 1) {
                IconButton(onClick = { viewModel.showAddDialog() }) {
                    Icon(Icons.Default.Add, "添加")
                }
            }
        }

        // Tab 栏（紧凑）
        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> AudioLocalTab(playerManager = playerManager)
                    1 -> AudioLibraryScreen(viewModel = viewModel, showTopBar = false)
                }
            }

            // MiniPlayer
            if (uiState.currentTrack != null) {
                MiniPlayerBar(
                    track = uiState.currentTrack!!,
                    isPlaying = uiState.isPlaying,
                    positionMs = uiState.positionMs,
                    durationMs = uiState.durationMs,
                    bitrateBps = uiState.bitrateBps,
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onStop = { viewModel.stopPlayback() },
                    onSeek = { viewModel.seekTo(it) },
                    onNext = { viewModel.playNext() },
                    onPrevious = { viewModel.playPrevious() },
                    onCycleRepeat = { viewModel.cycleRepeatMode() },
                    repeatMode = uiState.repeatMode,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}
