package com.radio.chinese.ui.radio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.category.CategoryScreen
import com.radio.chinese.ui.favorites.FavoritesScreen
import com.radio.chinese.ui.home.HomeScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(
    playerManager: PlayerManager,
    onNavigateToPlayer: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    themeMode: Int,
    onThemeChanged: (Int) -> Unit
) {
    val tabs = listOf("分类", "地区", "收藏", "最近")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

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
            TopAppBar(
                title = { Text("电台") },
                actions = {
                    if (pagerState.currentPage == 0) {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, "设置")
                        }
                    }
                }
            )
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
                    0 -> HomeScreen(
                        showTopBar = false,
                        onNavigateToPlayer = onNavigateToPlayer,
                        onNavigateToCategory = { scope.launch { pagerState.animateScrollToPage(1) } },
                        onNavigateToFavorites = { scope.launch { pagerState.animateScrollToPage(2) } },
                        onNavigateToSettings = onNavigateToSettings
                    )
                    1 -> CategoryScreen(
                        showTopBar = false,
                        onNavigateToPlayer = onNavigateToPlayer,
                        onNavigateBack = { scope.launch { pagerState.animateScrollToPage(0) } }
                    )
                    2 -> FavoritesScreen(
                        showTopBar = false,
                        onNavigateToPlayer = onNavigateToPlayer,
                        onNavigateBack = { scope.launch { pagerState.animateScrollToPage(0) } }
                    )
                    3 -> RadioRecentTab(playerManager = playerManager, onNavigateToPlayer = onNavigateToPlayer)
                }
            }
        }
    }
}
