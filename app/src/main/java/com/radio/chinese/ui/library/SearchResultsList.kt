package com.radio.chinese.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.radio.chinese.domain.IndexState
import com.radio.chinese.domain.SearchPhase
import com.radio.chinese.domain.TrackHit
import com.radio.chinese.domain.model.IndexQuery
import com.radio.chinese.domain.model.MergedTrack
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.theme.Dimens

/**
 * 手机档的全源搜索结果列表。车机档不会走到这里（那里保留目录内过滤）。
 *
 * 一切"该显示什么"由 [SearchPhase] 决定，判定在 `IndexQuery.searchPhase` 里且已被单测钉死；
 * 这里只做渲染，不再自己判断有没有索引、在不在建。
 */
@Composable
fun SearchResultsList(
    results: List<MergedTrack>,
    query: String,
    phase: SearchPhase,
    indexState: IndexState,
    breadcrumbOf: (TrackHit) -> String,
    isFavorited: (String) -> Boolean,
    onPlay: (TrackHit) -> Unit,
    onToggleFavorite: (TrackHit) -> Unit,
    onBuildIndex: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val indexedNow = (indexState as? IndexState.Running)?.p?.indexedFiles ?: 0

    Column(modifier = modifier.fillMaxSize()) {
        HintLine(phase, indexedNow, onBuildIndex)

        when (phase) {
            // 建立中：明确挡结果。半截结果会让人以为搜漏了
            SearchPhase.Indexing -> EmptyState(
                icon = Icons.Default.Refresh,
                title = "正在建立索引，请稍后搜索",
                hint = if (indexedNow > 0) "已收录 $indexedNow 个曲目，建完自动显示结果"
                else "建完自动显示结果，可以退出本页等它跑完",
                modifier = Modifier.weight(1f)
            )

            SearchPhase.Deferred, SearchPhase.NotIndexed -> EmptyState(
                icon = Icons.Default.Search,
                title = "尚未建立索引，无法搜索全部曲目",
                hint = "建立后可以按唱段名直接搜索，不必一层层点目录",
                actionLabel = "现在建立索引",
                onAction = onBuildIndex,
                modifier = Modifier.weight(1f)
            )

            SearchPhase.Disabled -> EmptyState(
                icon = Icons.Default.Search,
                title = "索引功能已关闭",
                hint = "在设置的「音频库」里打开「允许建立网络源索引」后就能搜索全部曲目",
                modifier = Modifier.weight(1f)
            )

            SearchPhase.Ready, SearchPhase.ReadyStale -> {
                if (results.isEmpty()) {
                    // “没搜到”和“还没索引完”是两回事，文案不能混用
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = if (query.isBlank()) "输入曲目名开始搜索"
                        else "没有匹配「$query」的曲目",
                        hint = if (query.isBlank()) null else "换个关键字试试，或点上面的提示更新索引",
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding),
                        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
                        modifier = Modifier.weight(1f)
                    ) {
                        // key 必须稳定：行内收藏态是 remember 的，无 key 会把星标串到别的行
                        items(results, key = { "${it.name}|${it.size}" }) { merged ->
                            val primary = merged.sources.first()
                            SearchResultRow(
                                name = merged.name,
                                crumb = breadcrumbOf(primary),
                                sourceCount = merged.sources.size,
                                isFav = isFavorited(primary.path),
                                onClick = { onPlay(primary) },
                                onToggleFav = { onToggleFavorite(primary) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 提示行：进行中带已收录数（否则一分多钟的等待看着像卡死）；可操作的阶段才响应点击。 */
@Composable
private fun HintLine(phase: SearchPhase, indexedNow: Int, onBuildIndex: () -> Unit) {
    val text = IndexQuery.searchHint(phase, indexedNow) ?: return
    val actionable = phase == SearchPhase.ReadyStale || phase == SearchPhase.Deferred
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = actionable, onClick = onBuildIndex)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapTiny)
    )
}

@Composable
private fun SearchResultRow(
    name: String,
    crumb: String,
    sourceCount: Int,
    isFav: Boolean,
    onClick: () -> Unit,
    onToggleFav: () -> Unit,
) {
    var favState by remember { mutableStateOf(isFav) }
    LaunchedEffect(isFav) { favState = isFav }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = Dimens.RowMinHeight)
            .padding(vertical = Dimens.RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Audiotrack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.IconLarge)
        )
        Spacer(modifier = Modifier.width(Dimens.GapLarge))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 同内容多来源时标出来：否则用户以为搜到了两首不同的戏
            val sub = if (sourceCount > 1) "$crumb · $sourceCount 个来源" else crumb
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = { favState = !favState; onToggleFav() }) {
            Icon(
                if (favState) Icons.Default.Star else Icons.Outlined.StarOutline,
                contentDescription = if (favState) "取消收藏" else "收藏这一曲",
                tint = if (favState) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
