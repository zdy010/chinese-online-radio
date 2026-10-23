package com.radio.chinese.ui.epg

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.epg.EpgQuery
import com.radio.chinese.ui.common.AppSearchField
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.carTouchTarget
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface

/**
 * 独立节目单页：一眼扫到所有接入节目单的台此刻各在播什么，可切今天/明天/后天。
 *
 * 只列接入映射表的台。没有 EPG 的台在这里根本不出现——不显示「暂无节目单」，
 * 也不给一个点了没内容的入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpgScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPlayer: (String) -> Unit,
    viewModel: EpgViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedWindowInsets,
                modifier = if (isCarSurface()) Modifier.heightIn(min = Dimens.TouchMin) else Modifier,
                title = {
                    Text(if (EpgQuery.isBroadcastZoneLocal()) "节目单" else "节目单 · 北京时间")
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = if (isCarSurface()) Modifier.size(Dimens.TouchMin) else Modifier
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 日期切换行：三天是接口能给的极限，不往后再加
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(Dimens.ChipGap)
            ) {
                state.days.forEach { day ->
                    FilterChip(
                        selected = state.selectedDate == day.date,
                        onClick = { viewModel.selectDate(day.date) },
                        label = { Text(day.label) },
                        modifier = Modifier.carTouchTarget()
                    )
                }
            }

            AppSearchField(
                value = state.query,
                onValueChange = viewModel::updateQuery,
                placeholder = "搜索电台或节目",
                modifier = Modifier.padding(
                    horizontal = Dimens.ScreenPadding,
                    vertical = Dimens.GapSmall
                )
            )

            when {
                state.rows.isEmpty() && state.loading ->
                    LoadingState(text = "正在获取节目单…")

                state.rows.isEmpty() ->
                    EmptyState(
                        icon = Icons.Default.CalendarMonth,
                        title = "暂时没有节目单",
                        hint = "节目单需要联网获取，可稍后重试",
                        actionLabel = "重新加载",
                        onAction = viewModel::retry
                    )

                state.visibleRows.isEmpty() ->
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "没有找到相关节目",
                        hint = "换个关键字，或点上方日期看看其它排播"
                    )

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = Dimens.ScreenPadding,
                            end = Dimens.ScreenPadding,
                            top = Dimens.GapTiny,
                            bottom = Dimens.GapHuge
                        ),
                        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)
                    ) {
                        items(state.visibleRows, key = { it.station.id }) { row ->
                            EpgStationCard(
                                row = row,
                                minute = state.minute,
                                onClick = {
                                    viewModel.play(row.station)
                                    onNavigateToPlayer(row.station.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一行三层：台名 / 当前节目·时段 / 接下来节目。没有当前节目时退化成只显示下一档。 */
@Composable
private fun EpgStationCard(
    row: EpgRow,
    minute: Int,
    onClick: () -> Unit,
) {
    val current = EpgQuery.currentAt(row.programs, minute)
    val next = EpgQuery.nextAfter(row.programs, minute)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .padding(Dimens.CardPadding)
                .fillMaxWidth()
                .heightIn(min = Dimens.RowMinHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.station.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(Dimens.GapTiny))
                if (current != null) {
                    Text(
                        text = current.title + " · " +
                            EpgQuery.formatRange(current.startMin, current.endMin),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (next != null) {
                    Spacer(modifier = Modifier.height(Dimens.GapTiny))
                    Text(
                        text = "接下来 " + EpgQuery.formatMinute(next.startMin) + " " + next.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 旧数据才标注时间；正常刷新不占这一行
                if (row.refreshFailed && row.fetchedAt != null) {
                    Text(
                        text = "数据截至 " + EpgQuery.formatFetchedAt(row.fetchedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "播放",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = Dimens.GapSmall)
                    .size(Dimens.IconLarge)
            )
        }
    }
}
