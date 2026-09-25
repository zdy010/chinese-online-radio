package com.radio.chinese.ui.radio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.category.CategoryScreen
import com.radio.chinese.ui.common.SearchBarRow
import com.radio.chinese.ui.common.SurfaceTab
import com.radio.chinese.ui.common.SurfaceTabBar
import com.radio.chinese.ui.favorites.FavoritesScreen
import com.radio.chinese.ui.home.HomeScreen
import com.radio.chinese.ui.home.HomeViewModel
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface
import com.radio.chinese.ui.theme.usesRailNavigation
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
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
    // 改名「全部」后两个页签各自名副其实。图标给车机档的左侧导航栏用。
    val tabs = listOf(
        SurfaceTab("全部", Icons.Default.Radio),
        SurfaceTab("分类", Icons.Default.Category),
        SurfaceTab("收藏", Icons.Default.Favorite),
        SurfaceTab("最近", Icons.Default.History),
    )
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
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = if (isCarSurface()) Modifier.size(Dimens.TouchMin) else Modifier
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            }
        )

        val onSelectTab: (Int) -> Unit = { scope.launch { pagerState.animateScrollToPage(it) } }
        val railNav = usesRailNavigation()

        if (railNav) {
            // 横屏上顶部横条既占高度又浪费宽度，改成左侧栏后内容区拿到整屏高度
            Row(modifier = Modifier.weight(1f)) {
                SurfaceTabBar(
                    tabs = tabs,
                    currentPage = pagerState.currentPage,
                    onSelect = onSelectTab,
                    modifier = Modifier.width(Dimens.RailWidth)
                )
                RadioPagerHost(
                    pagerState = pagerState,
                    homeViewModel = homeViewModel,
                    searchQuery = searchQuery,
                    onNavigateToPlayer = onNavigateToPlayer,
                    onNavigateToSettings = onNavigateToSettings,
                    scope = scope,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            SurfaceTabBar(
                tabs = tabs,
                currentPage = pagerState.currentPage,
                onSelect = onSelectTab
            )
            RadioPagerHost(
                pagerState = pagerState,
                homeViewModel = homeViewModel,
                searchQuery = searchQuery,
                onNavigateToPlayer = onNavigateToPlayer,
                onNavigateToSettings = onNavigateToSettings,
                scope = scope,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 四个页签的宿主。手机档与车机档共用，避开把 when 复制两遍。 */
@Composable
private fun RadioPagerHost(
    pagerState: PagerState,
    homeViewModel: HomeViewModel,
    searchQuery: String,
    onNavigateToPlayer: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    HorizontalPager(state = pagerState, modifier = modifier) { page ->
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
