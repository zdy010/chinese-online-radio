package com.radio.chinese.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Radio
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
import com.radio.chinese.ui.navigation.Screen
import com.radio.chinese.ui.player.PlayerScreen
import com.radio.chinese.ui.radio.RadioScreen
import com.radio.chinese.ui.settings.SettingsScreen
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
    themeMode: Int,
    onThemeChanged: (Int) -> Unit
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
                themeMode = themeMode,
                onThemeChanged = onThemeChanged
            )
        }

        composable(Screen.Audio.route) {
            AudioMainScreen(playerManager = playerManager)
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(navArgument("stationId") { type = NavType.StringType })
        ) { backStackEntry ->
            val stationId = backStackEntry.arguments?.getString("stationId") ?: return@composable
            PlayerScreen(
                stationId = stationId,
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
            ManageScreen(onNavigateBack = { navController.popBackStack() })
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
        BottomNavItem(Screen.Radio, "电台", Icons.Default.Radio, Icons.Outlined.Radio),
        BottomNavItem(Screen.Audio, "音频库", Icons.Default.LibraryMusic, Icons.Outlined.LibraryMusic),
    )

    val showBottomBar = currentRoute in listOf(Screen.Radio.route, Screen.Audio.route)

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
                themeMode = themeMode,
                onThemeChanged = onThemeChanged
            )
        }
    }
}
