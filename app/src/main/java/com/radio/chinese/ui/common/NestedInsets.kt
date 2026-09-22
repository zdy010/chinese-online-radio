package com.radio.chinese.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.dp

/**
 * 页面内嵌套 Scaffold / TopAppBar 应使用的零窗口 insets。
 *
 * 所有页面（电台、音频库、播放、设置、节目源管理）都挂在 MainScreen 那个 Scaffold 的内容区里，
 * 外层已经把状态栏与导航栏高度算进 padding 了；内层再各算一遍，就会出现
 * 「播放页/设置页顶部凭空多出一条状态栏高度的空白」，横屏下这条假空白还会继续变高，
 * 把封面顶下去、把收藏按钮挤出屏幕。内层一律用这个零 insets。
 */
val NestedWindowInsets: WindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
