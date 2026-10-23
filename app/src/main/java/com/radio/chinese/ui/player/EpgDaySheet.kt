package com.radio.chinese.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.radio.chinese.domain.epg.EpgQuery
import com.radio.chinese.domain.model.EpgProgram
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.theme.Dimens

/**
 * 全天节目单。从播放页的 [NowOnAirCard] 展开。
 *
 * 打开就滚到当前那一档：一屏只放得下六七档，从 00:00 开始列的话用户进来先看到的是
 * 清晨的节目，得手动往下翻很久才能对上「现在」。列表数据已在内存里，滚动不产生请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpgDaySheet(
    programs: List<EpgProgram>,
    minute: Int,
    onDismiss: () -> Unit,
) {
    if (programs.isEmpty()) return

    val currentIndex = programs.indexOfFirst { minute >= it.startMin && minute < it.endMin }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (currentIndex > 0) currentIndex else 0
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { NestedWindowInsets },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (EpgQuery.isBroadcastZoneLocal()) "今日节目单" else "今日节目单 · 北京时间",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    start = Dimens.GapHuge,
                    end = Dimens.GapHuge,
                    bottom = Dimens.GapSmall
                )
            )
            LazyColumn(
                state = listState,
                // 底部多留一截：系统导航条会压住最后一行的下半截
                contentPadding = PaddingValues(
                    start = Dimens.GapHuge,
                    end = Dimens.GapHuge,
                    bottom = Dimens.GapHuge
                )
            ) {
                itemsIndexed(
                    items = programs,
                    key = { _, program -> "${program.stationId}-${program.date}-${program.startMin}" }
                ) { index, program ->
                    EpgDayRow(program = program, isCurrent = index == currentIndex)
                }
            }
        }
    }
}

@Composable
private fun EpgDayRow(program: EpgProgram, isCurrent: Boolean) {
    val content = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            // 非当前行不铺底色：sheet 自身已有背景色，再拿 surface 盖一层会在
            // 深色主题下偏出一块“比背景更亮”的斑
            .then(
                if (isCurrent) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = Dimens.GapSmall, vertical = Dimens.GapSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = EpgQuery.formatRange(program.startMin, program.endMin),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1
        )
        Text(
            text = program.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = Dimens.GapLarge)
                .weight(1f)
        )
        if (isCurrent) {
            Text(
                text = "正在播出",
                style = MaterialTheme.typography.labelSmall,
                color = content,
                maxLines = 1
            )
        }
    }
}
