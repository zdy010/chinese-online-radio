package com.radio.chinese.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.audio.AudioMainScreen
import com.radio.chinese.ui.diagnostics.DiagnosticsScreen
import com.radio.chinese.ui.epg.EpgScreen
import com.radio.chinese.ui.iptv.IptvPlayerScreen
import com.radio.chinese.ui.iptv.IptvScreen
import com.radio.chinese.ui.keysettings.KeySettingsScreen
import com.radio.chinese.ui.navigation.Screen
import com.radio.chinese.ui.player.OperaPlayerScreen
import com.radio.chinese.ui.player.PlayerScreen
import com.radio.chinese.ui.radio.RadioScreen
import com.radio.chinese.ui.settings.SettingsScreen
import com.radio.chinese.ui.tag.TagScreen
import com.radio.chinese.ui.manage.ManageScreen

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
fun RadioNavGraph(
    navController: NavHostController,
    playerManager: PlayerManager,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Radio.route
    ) {
        composable(Screen.Radio.route) {
            RadioScreen(
                playerManager = playerManager,
                onNavigateToPlayer = { stationId -> navController.navigate(Screen.Player.createRoute(stationId)) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToTag = { tag -> navController.navigate(Screen.Tags.createRoute(tag)) },
                onNavigateToEpg = { navController.navigate(Screen.Epg.route) },
            )
        }

        composable(Screen.Iptv.route) {
            IptvScreen(
                playerManager = playerManager,
                // 专用播放页是无参数路由：状态一律读 PlayerManager（与 OperaPlayer 同构）
                onNavigateToPlayer = { _ -> navController.navigate(Screen.IptvPlayer.route) },
            )
        }

        composable(Screen.IptvPlayer.route) {
            IptvPlayerScreen(
                playerManager = playerManager,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Epg.route) {
            EpgScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPlayer = { stationId ->
                    navController.navigate(Screen.Player.createRoute(stationId))
                }
            )
        }

        composable(Screen.Audio.route) {
            AudioMainScreen(
                playerManager = playerManager,
                onOpenPlayer = { navController.navigate(Screen.OperaPlayer.route) }
            )
        }

        composable(Screen.OperaPlayer.route) {
            OperaPlayerScreen(
                playerManager = playerManager,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(navArgument("stationId") { type = NavType.StringType })
        ) { backStackEntry ->
            val stationId = backStackEntry.arguments?.getString("stationId") ?: return@composable
            PlayerScreen(
                stationId = stationId,
                playerManager = playerManager,
                onNavigateToTag = { tag -> navController.navigate(Screen.Tags.createRoute(tag)) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Tags.route,
            arguments = listOf(navArgument(Screen.Tags.ARG) {
                type = NavType.StringType
                defaultValue = ""
                nullable = true
            })
        ) { backStackEntry ->
            TagScreen(
                initialTag = backStackEntry.arguments?.getString(Screen.Tags.ARG),
                onNavigateToPlayer = { stationId -> navController.navigate(Screen.Player.createRoute(stationId)) },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToManage = { navController.navigate(Screen.Manage.route) },
                onNavigateToKeySettings = { navController.navigate(Screen.KeySettings.route) },
                onNavigateToDiagnostics = { navController.navigate(Screen.Diagnostics.route) },
            )
        }

        composable(Screen.Manage.route) {
            ManageScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Diagnostics.route) {
            DiagnosticsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.KeySettings.route) {
            KeySettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}

@Composable
fun MainScreen(
    playerManager: PlayerManager,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 切台基准（spec V11）由 iptv 页注册、离开 iptv 页时清除。
    // 不在 IptvScreen 的 onDispose 里清：从浏览页跳到 iptv 播放页时浏览页也会 dispose，
    // 那会把基准清掉，滚轮又切回电台（T12）。这里以路由为准，只有一个清理点。
    LaunchedEffect(currentRoute) {
        // 只在切回其它一级 tab 时清：播放页（含 Task 8 过渡用的通用 Player 页）虽然
        // 也"离开了 iptv tab"，但仍在播 iptv 台，那里清掉基准会让滚轮切回电台（T12）。
        if (currentRoute in listOf(Screen.Radio.route, Screen.Audio.route, Screen.Settings.route)) {
            playerManager.clearSkipBase()
        }
    }

    val bottomNavItems = listOf(
        BottomNavItem(Screen.Radio, "电台", Icons.Default.Radio, Icons.Outlined.Radio),
        // iptv 插在电台与音频库之间：它是"另一个内容源"，不是电台的一个分类
        BottomNavItem(Screen.Iptv, "iptv", Icons.Default.Tv, Icons.Outlined.Tv),
        BottomNavItem(Screen.Audio, "音频库", Icons.Default.LibraryMusic, Icons.Outlined.LibraryMusic),
        // 设置是全局一级入口，不该挤在搜索行里：与电台/音频库并列为第三个底部项
        BottomNavItem(Screen.Settings, "设置", Icons.Default.Settings, Icons.Outlined.Settings),
    )

    val showBottomBar = currentRoute in listOf(
        Screen.Radio.route, Screen.Iptv.route, Screen.Audio.route, Screen.Settings.route,
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = currentRoute == item.screen.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != item.screen.route) {
                                    navController.navigate(item.screen.route) {
                                        popUpTo(Screen.Radio.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(if (selected) item.selectedIcon else item.unselectedIcon, contentDescription = item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            RadioNavGraph(
                navController = navController,
                playerManager = playerManager,
            )
        }
    }
}
