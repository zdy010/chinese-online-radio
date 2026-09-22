package com.radio.chinese.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 全站圆角 token。此前 11 处 RoundedCornerShape 用了 5 种半径（12/8/16/20/28），
 * 各页各自为政。新增代码请用 MaterialTheme.shapes.{small|medium|large|extraLarge}。
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),      // 标签、小按钮、源条目
    medium = RoundedCornerShape(12.dp),    // 列表卡片（全站统一）
    large = RoundedCornerShape(16.dp),     // 分类大卡、对话框
    extraLarge = RoundedCornerShape(24.dp) // 播放条顶部、搜索框 pill
)

/** 迷你播放条：只圆顶部两角，贴屏幕底边成一体。 */
val MiniPlayerShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
