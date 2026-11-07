package com.radio.chinese.ui.common

import androidx.compose.runtime.compositionLocalOf
import com.radio.chinese.domain.model.EpgProgram

/**
 * 列表行的 EPG 状态，由电台页签宿主统一提供。
 *
 * 之前靠调用方给 [com.radio.chinese.ui.home.StationListItem] 传 `nowProgram` / `hasEpg`，
 * 结果只有「全部」页传了，地区 / 分类 / 收藏 / 最近 / 标签页一个都看不到节目单，
 * 用户自然觉得「几乎没看到 EPG」。改成 CompositionLocal 后，所有复用这个行组件的
 * 页面自动生效，调用方不用再记得传参。
 *
 * iptv 页不提供这个 Local（上游没有节目单数据），那里保持默认值：整块不渲染。
 */
data class EpgInlineState(
    val nowTitles: Map<String, String> = emptyMap(),
    val stationIds: Set<String> = emptySet(),
    val expanded: Map<String, List<EpgProgram>> = emptyMap(),
    val minute: Int = 0,
    val onToggle: (String) -> Unit = {},
)

val LocalEpgInline = compositionLocalOf { EpgInlineState() }
