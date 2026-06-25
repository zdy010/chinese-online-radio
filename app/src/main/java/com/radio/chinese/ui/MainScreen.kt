package com.radio.chinese.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
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
import com.radio.chinese.ui.category.CategoryScreen
import com.radio.chinese.ui.favorites.FavoritesScreen
import com.radio.chinese.ui.home.HomeScreen
import com.radio.chinese.ui.manage.ManageScreen
import com.radio.chinese.ui.navigation.Screen
import com.radio.chinese.ui.library.AudioLibraryScreen
import com.radio.chinese.ui.player.PlayerScreen
import com.radio.chinese.ui.settings.SettingsScreen

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
    themeMode: Int,
    onThemeChanged: (Int) -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToPlayer = { stationId ->
                    navController.navigate(Screen.Player.createRoute(stationId))
                },
                onNavigateToCategory = {
                    navController.navigate(Screen.Category.route)
                },
                onNavigateToFavorites = {
                    navController.navigate(Screen.Favorites.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("stationId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val stationId = backStackEntry.arguments?.getString("stationId") ?: return@composable
            PlayerScreen(
                stationId = stationId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Category.route) {
            CategoryScreen(
                onNavigateToPlayer = { stationId ->
                    navController.navigate(Screen.Player.createRoute(stationId))
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Favorites.route) {
            FavoritesScreen(
                onNavigateToPlayer = { stationId ->
                    navController.navigate(Screen.Player.createRoute(stationId))
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToManage = { navController.navigate(Screen.Manage.route) },
                onThemeChanged = onThemeChanged
            )
        }

        composable(Screen.Manage.route) {
            ManageScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Opera.route) {
            AudioLibraryScreen()
        }
    }
}

@Composable
fun MainScreen(
    playerManager: PlayerManager,
    themeMode: Int,
    onThemeChanged: (Int) -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomNavItems = listOf(
        BottomNavItem(Screen.Home, "首页", Icons.Default.Home, Icons.Outlined.Home),
        BottomNavItem(Screen.Opera, "音频库", Icons.Default.Radio, Icons.Default.Radio),
        BottomNavItem(Screen.Category, "分类", Icons.Default.Category, Icons.Outlined.Category),
        BottomNavItem(Screen.Favorites, "收藏", Icons.Default.Favorite, Icons.Outlined.FavoriteBorder),
    )

    val showBottomBar = currentRoute in listOf(
        Screen.Home.route,
        Screen.Opera.route,
        Screen.Category.route,
        Screen.Favorites.route
    )

    // 拦截系统返回键：在底部 tab 页面上不弹出导航栈，双击返回退出
    var lastBackPressMs by remember { mutableLongStateOf(0L) }
    val context = androidx.compose.ui.platform.LocalContext.current
    BackHandler(enabled = showBottomBar) {
        val now = System.currentTimeMillis()
        if (now - lastBackPressMs < 2000L) {
            // 第二次返回，退出 app
            (context as? android.app.Activity)?.finish()
        } else {
            lastBackPressMs = now
            // 显示 Toast 提示
            android.widget.Toast.makeText(context, "再按一次退出", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

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
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
            RadioNavGraph(
                navController = navController,
                playerManager = playerManager,
                themeMode = themeMode,
                onThemeChanged = onThemeChanged
            )
        }
    }
}
