package com.radio.chinese.ui.navigation

sealed class Screen(val route: String) {
    data object Radio : Screen("radio")
    data object Audio : Screen("audio")
    data object Player : Screen("player/{stationId}") {
        fun createRoute(stationId: String) = "player/$stationId"
    }
    /** 音频库（戏曲）全屏播放页：无参数，状态一律读 PlayerManager */
    data object OperaPlayer : Screen("opera_player")
    data object Settings : Screen("settings")
    data object Manage : Screen("manage")

    /**
     * 按标签浏览。tag 为空进标签云，非空直接落到该标签的电台列表。
     * 默认值用空字串而不是 null：NavType.StringType 对可空参数必须有默认值，
     * 否则不传 tag 的入口跳转会在运行时报参数缺失。
     */
    data object Tags : Screen("tags?tag={tag}") {
        const val ARG = "tag"
        fun createRoute(tag: String?): String = "tags?tag=" + (tag ?: "")
    }

    /** 按键诊断（设置页连点「关于」7 次解锁的隐藏页） */
    data object Diagnostics : Screen("diagnostics")

    /** 车机按键映射 */
    data object KeySettings : Screen("key_settings")
}
