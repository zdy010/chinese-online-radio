package com.radio.chinese.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.radio.chinese.data.repository.EpgSnapshot
import com.radio.chinese.domain.epg.EpgQuery
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface

/**
 * 播放页的「此刻在播 / 下一档」小卡片。点击展开全天节目单。
 *
 * 无数据时**整个 composable 不渲染**：接口失败、没接节目单的台，页面上不该多出一块
 * 「暂无节目单」的占位——那是把功能的失败暴露成界面的噪音。
 */
@Composable
fun NowOnAirCard(
    snapshot: EpgSnapshot,
    minute: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val programs = snapshot.programs
    if (programs.isEmpty()) return

    val current = EpgQuery.currentAt(programs, minute)
    val next = EpgQuery.nextAfter(programs, minute)
    // 落在空档且当天已经没有下一档：没有可说的内容
    if (current == null && next == null) return

    val labelWidth = rememberLabelWidth()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = Dimens.GapLarge,
                vertical = Dimens.GapSmall
            )
        ) {
            if (current != null) {
                EpgLine(
                    label = "正在播出",
                    leading = EpgQuery.formatRange(current.startMin, current.endMin),
                    title = current.title,
                    emphasized = true,
                    labelWidth = labelWidth
                )
            }
            if (current != null && next != null) {
                Spacer(modifier = Modifier.height(Dimens.GapTiny))
            }
            if (next != null) {
                EpgLine(
                    label = "接下来",
                    leading = EpgQuery.formatMinute(next.startMin),
                    title = next.title,
                    emphasized = false,
                    labelWidth = labelWidth
                )
            }
            // 副标题行：平时不出现，只在需要说明时拼一行，不白占高度
            val staleNote = if (snapshot.refreshFailed && snapshot.fetchedAt != null)
                "数据截至 " + EpgQuery.formatFetchedAt(snapshot.fetchedAt) else null
            // 设备不在东八区：上面两个时刻都是北京时间，不写出来会被读成当地时间
            val zoneNote = if (EpgQuery.isBroadcastZoneLocal()) null else "时间为北京时间"
            val note = listOfNotNull(zoneNote, staleNote).joinToString(" · ")
            if (note.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Dimens.GapTiny))
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 标签列宽：量出来，不是按字号算出来。
 *
 * 字号乘个系数看着等价，实际总是短一截：本项目 Typography 自带字号倍率，中文字形的
 * advance 也不是精确的 1em，真机上「正在播出」被截成了「正在」。measure 走的是 Text 同一套排版，
 * 量多少就是多少。加 4dp 是留给行尾不被抹掉一笔。
 */
@Composable
private fun rememberLabelWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    return remember(measurer, style, density) {
        with(density) { measurer.measure("正在播出", style).size.width.toDp() + 4.dp }
    }
}

/**
 * 一行：标签 · 时段 · 节目名。两行共用同一个 labelWidth，时段列才能对齐。
 */
@Composable
private fun EpgLine(
    label: String,
    leading: String,
    title: String,
    emphasized: Boolean,
    labelWidth: Dp,
) {
    Row(
        modifier = if (isCarSurface()) Modifier.heightIn(min = Dimens.TouchMin) else Modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(labelWidth)
        )
        Text(
            text = leading,
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasized) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(Dimens.GapSmall))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
