package com.radio.chinese.ui.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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

    // 双击退出
    var lastBackMs by remember { mutableLongStateOf(0L) }
    val ctx = LocalContext.current
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackMs < 2000L) {
            (ctx as? android.app.Activity)?.finish()
        } else {
            lastBackMs = now
            android.widget.Toast.makeText(ctx, "再按一次退出", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("音频库") })
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title) }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> AudioLocalTab()
                    1 -> AudioLibraryScreen(viewModel = viewModel, showTopBar = false)
                    2 -> AudioFavoritesTab(viewModel = viewModel)
                    3 -> AudioRecentTab(viewModel = viewModel)
                }
            }
        }
    }
}
