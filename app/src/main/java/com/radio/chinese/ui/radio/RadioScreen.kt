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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.category.CategoryScreen
import com.radio.chinese.ui.common.SearchBarRow
import com.radio.chinese.ui.favorites.FavoritesScreen
import com.radio.chinese.ui.home.HomeScreen
import com.radio.chinese.ui.home.HomeViewModel
import com.radio.chinese.ui.theme.Dimens
import androidx.compose.runtime.collectAsState
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
    // 页签名字必须对得上内容：原先第二个页签叫「地区」，但电台数据里根本没有地区/省份字段，
    // 实际展示的是分类网格，用户点进去看到的是与标签无关的内容。第一个页签是全部电台+分类筛选条，
    // 改名「全部」后两个页签各自名副其实。
    val tabs = listOf("全部", "分类", "收藏", "最近")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    val homeViewModel: HomeViewModel = hiltViewModel()

    // 搜索联动 HomeViewModel
    LaunchedEffect(searchQuery) { homeViewModel.updateSearchQuery(searchQuery) }

    var lastBackMs by remember { mutableLongStateOf(0L) }
    val ctx = LocalContext.current
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackMs < 2000L) (ctx as? android.app.Activity)?.finish()
        else { lastBackMs = now; android.widget.Toast.makeText(ctx, "再按一次退出", android.widget.Toast.LENGTH_SHORT).show() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索栏 + 设置：与音频库页共用 SearchBarRow，两屏顶部排版对齐
        SearchBarRow(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "搜索电台名称或频率",
            actions = {
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            }
        )

        // Tab
        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = pagerState.currentPage == index, onClick = { scope.launch { pagerState.animateScrollToPage(index) } }, text = { Text(title) })
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> HomeScreen(showTopBar = false, viewModel = homeViewModel, onNavigateToPlayer = onNavigateToPlayer,
                    onNavigateToCategory = { scope.launch { pagerState.animateScrollToPage(1) } },
                    onNavigateToFavorites = { scope.launch { pagerState.animateScrollToPage(2) } },
                    onNavigateToSettings = onNavigateToSettings)
                1 -> CategoryScreen(showTopBar = false, viewModel = homeViewModel, searchQuery = searchQuery, onNavigateToPlayer = onNavigateToPlayer,
                    onNavigateBack = { scope.launch { pagerState.animateScrollToPage(0) } })
                2 -> FavoritesScreen(showTopBar = false, searchQuery = searchQuery, onNavigateToPlayer = onNavigateToPlayer,
                    onNavigateBack = { scope.launch { pagerState.animateScrollToPage(0) } })
                3 -> RadioRecentTab(onNavigateToPlayer = onNavigateToPlayer, searchQuery = searchQuery)
            }
        }
    }
}
