package com.radio.chinese.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.radio.chinese.domain.model.PlayabilityLevel
import com.radio.chinese.domain.model.PlayabilityScore

/**
 * 台级可用性查询入口。
 *
 * 由 MainActivity 用 PlayabilityStore 提供，并在分数变化时重建 lambda —— 这样
 * StationListItem **不用**为着色加参数，六个调用点（首页/分类/地区/收藏/标签/iptv）
 * 一处改完全部生效。
 */
val LocalPlayability = compositionLocalOf<(String) -> Int> { { PlayabilityScore.UNKNOWN } }

/** 列表台名该用什么颜色；0（未知）返回 null，保持默认文字色 */
@Composable
fun playabilityNameColor(stationId: String): Color? {
    val scoreOf = LocalPlayability.current
    return playabilityColorOf(scoreOf(stationId))
}

/**
 * 七档固定色。
 *
 * 不用连续渐变的理由：列表里相邻两档差 1 分根本看不出，深色主题下低饱和色差更小；
 * 档位离散才能让"这个台最近不太行"一眼可见。
 */
@Composable
fun playabilityColorOf(score: Int): Color? = when (PlayabilityScore.levelOf(score)) {
    PlayabilityLevel.BAD_SEVERE -> Color(0xFFC62828)
    PlayabilityLevel.BAD -> Color(0xFFE53935)
    PlayabilityLevel.WARN -> Color(0xFFFB8C00)
    PlayabilityLevel.UNKNOWN -> null
    PlayabilityLevel.GOOD -> Color(0xFF7CB342)
    PlayabilityLevel.GOOD_STRONG -> Color(0xFF43A047)
    PlayabilityLevel.GOOD_BEST -> Color(0xFF2E7D32)
}
