package com.radio.chinese.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationCategory

/**
 * 电台副标题的唯一规则。
 *
 * 历史上「国家 · 语言」被塞在 frequency 字符串里，首页、播放器、管理页三处各自拼接，
 * 结果同一台在不同页面显示不一样的副标题。现在规则收敛成一条：
 * 真频率 > 国家 · 语言 > 只有国家 > 只有语言 > 分类中文名 > 空串（调用方不渲染该行）。
 */
fun stationSubtitle(station: RadioStation, categoryLabel: String): String {
    val place = listOf(station.country, station.language).filter { it.isNotBlank() }.joinToString(" · ")
    return when {
        station.frequency.isNotBlank() -> station.frequency.trim()
        place.isNotBlank() -> place
        categoryLabel.isNotBlank() -> categoryLabel
        else -> ""
    }
}

/** 手机档与车机档共用的副标题文本；结论为空时整块不渲染，不留空行。 */
@Composable
fun StationSubtitleText(
    station: RadioStation,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    textAlign: TextAlign? = null,
) {
    val text = stationSubtitle(station, StationCategory.labelOf(station.category))
    if (text.isBlank()) return
    Text(
        text = text,
        style = style,
        textAlign = textAlign,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
