package com.radio.chinese.ui.navigation

sealed class Screen(val route: String) {
    data object Radio : Screen("radio")
    data object Audio : Screen("audio")
    data object Player : Screen("player/{stationId}") {
        fun createRoute(stationId: String) = "player/$stationId"
    }
    data object Settings : Screen("settings")
    data object Manage : Screen("manage")
}
