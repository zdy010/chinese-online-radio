package com.radio.chinese.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Player : Screen("player/{stationId}") {
        fun createRoute(stationId: String) = "player/$stationId"
    }
    data object Category : Screen("category")
    data object CategoryDetail : Screen("category/{categoryId}") {
        fun createRoute(categoryId: String) = "category/$categoryId"
    }
    data object Favorites : Screen("favorites")
    data object Settings : Screen("settings")
}
