package com.radio.chinese.ui.radio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
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
import com.radio.chinese.ui.region.RegionScreen
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.canHostRail
import com.radio.chinese.ui.theme.isCarSurface
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(
    playerManager: PlayerManager,
    onNavigateToPlayer: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTag: (String?) -> Unit = {},
    onNavigateToEpg: () -> Unit = {},
) {
    // 页签名字必须对得上内容：早期的「地区」页签底下其实是分类网格——当时电台数据里
    // 没有地区/省份字段，点进去看到的与标签无关，只能改名「全部」。
    // 现在地区由 RegionResolver 从台名/简介推断（「本地优先」排序已在用，451 个台里 229 个能推出省份），
    // 「地区」才名副其实地回到最左：在「全部」页向右滑就能滑到它，不必去点顶栏。
    // 图标给车机档的左侧导航栏用。
    val tabs = listOf(
        SurfaceTab("地区", Icons.Default.Public),
        SurfaceTab("全部", Icons.Default.Radio),
        SurfaceTab("分类", Icons.Default.Category),
        SurfaceTab("收藏", Icons.Default.Favorite),
        SurfaceTab("最近", Icons.Default.History),
    )
    // 默认停在「全部」而不是「地区」：地区目录是「台太多」时的另一条入口，
    // 不该把熟悉的电台列表挤掉。在「全部」向右滑就能滑到「地区」，跟点顶栏那一页等价。
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { tabs.size })
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
            placeholder = "搜索电台、频率或节目",
            actions = {
                // 只留节目单：它是「这一屏内容」的入口；设置是全局一级入口，
                // 已移到与电台/音频库并列的底部导航，不再挤在搜索行里
                IconButton(
                    onClick = onNavigateToEpg,
                    modifier = if (isCarSurface()) Modifier.size(Dimens.TouchMin) else Modifier
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = "节目单")
                }
            }
        )

        val onSelectTab: (Int) -> Unit = { scope.launch { pagerState.animateScrollToPage(it) } }
        // 页签数要算进去：5 个条目在矮车机屏上 rail 装不下，得退回顶部横条
        val railNav = canHostRail(tabs.size)

        if (railNav) {
            // 横屏上顶部横条既占高度又浪费宽度，改成左侧栏后内容区拿到整屏高度
            Row(modifier = Modifier.weight(1f)) {
                SurfaceTabBar(
                    tabs = tabs,
                    currentPage = pagerState.currentPage,
                    onSelect = onSelectTab,
                    modifier = Modifier.width(Dimens.RailWidth),
                    useRail = railNav,
                )
                RadioPagerHost(
                    pagerState = pagerState,
                    homeViewModel = homeViewModel,
                    searchQuery = searchQuery,
                    onNavigateToPlayer = onNavigateToPlayer,
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToTag = onNavigateToTag,
                    scope = scope,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            SurfaceTabBar(
                tabs = tabs,
                currentPage = pagerState.currentPage,
                onSelect = onSelectTab,
                useRail = railNav,
            )
            RadioPagerHost(
                pagerState = pagerState,
                homeViewModel = homeViewModel,
                searchQuery = searchQuery,
                onNavigateToPlayer = onNavigateToPlayer,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToTag = onNavigateToTag,
                scope = scope,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 五个页签的宿主。手机档与车机档共用，避开把 when 复制两遍。 */
@Composable
private fun RadioPagerHost(
    pagerState: PagerState,
    homeViewModel: HomeViewModel,
    searchQuery: String,
    onNavigateToPlayer: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTag: (String?) -> Unit,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    HorizontalPager(state = pagerState, modifier = modifier) { page ->
        when (page) {
            // 页签顺序：0 地区 / 1 全部 / 2 分类 / 3 收藏 / 4 最近。
            // 下面几个跳页回调里的数字必须跟着这份顺序走，加页签时一起改。
            0 -> RegionScreen(
                onNavigateToPlayer = onNavigateToPlayer,
                viewModel = homeViewModel,
                showTopBar = false,
                searchQuery = searchQuery
            )
            1 -> HomeScreen(showTopBar = false, viewModel = homeViewModel, onNavigateToPlayer = onNavigateToPlayer,
                onNavigateToCategory = { scope.launch { pagerState.animateScrollToPage(2) } },
                onNavigateToFavorites = { scope.launch { pagerState.animateScrollToPage(3) } },
                onNavigateToSettings = onNavigateToSettings)
            2 -> CategoryScreen(showTopBar = false, viewModel = homeViewModel, searchQuery = searchQuery, onNavigateToPlayer = onNavigateToPlayer,
                onNavigateToTag = onNavigateToTag,
                onNavigateBack = { scope.launch { pagerState.animateScrollToPage(1) } })
            3 -> FavoritesScreen(showTopBar = false, searchQuery = searchQuery, onNavigateToPlayer = onNavigateToPlayer,
                onNavigateBack = { scope.launch { pagerState.animateScrollToPage(1) } })
            4 -> RadioRecentTab(onNavigateToPlayer = onNavigateToPlayer, searchQuery = searchQuery)
        }
    }
}
