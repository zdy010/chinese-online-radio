package com.radio.chinese.ui.navigation

sealed class Screen(val route: String) {
    data object Radio : Screen("radio")
    data object Audio : Screen("audio")
    data object Player : Screen("player/{stationId}") {
        fun createRoute(stationId: String) = "player/$stationId"
    }
    data object Settings : Screen("settings")
    data object Manage : Screen("manage")

    /** 按键诊断（设置页连点「关于」7 次解锁的隐藏页） */
    data object Diagnostics : Screen("diagnostics")

    /** 车机按键映射 */
    data object KeySettings : Screen("key_settings")
}
