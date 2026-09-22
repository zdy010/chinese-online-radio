package com.radio.chinese.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.ui.graphics.vector.ImageVector
import com.radio.chinese.domain.model.StationCategory

/** 分类图标：只有 UI 层依赖 Compose Icons，故与 [StationCategory] 分开定义。 */
fun categoryIcon(key: String?): ImageVector = when (StationCategory.fromKey(key)) {
    StationCategory.NEWS -> Icons.Default.Newspaper
    StationCategory.MUSIC -> Icons.Default.MusicNote
    StationCategory.TRAFFIC -> Icons.Default.Traffic
    StationCategory.ARTS -> Icons.Default.TheaterComedy
    StationCategory.SPORTS -> Icons.Default.SportsSoccer
    StationCategory.FINANCE -> Icons.Default.TrendingUp
    StationCategory.OPERA -> Icons.Default.MusicNote
    StationCategory.TV_AUDIO -> Icons.Default.Tv
    StationCategory.WORLD -> Icons.Default.Language
    StationCategory.ADULT -> Icons.Default.Lock
    StationCategory.GENERAL -> Icons.Default.Radio
}
