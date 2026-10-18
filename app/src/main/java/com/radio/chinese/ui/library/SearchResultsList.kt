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
import androidx.compose.material.icons.filled.*
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
import com.radio.chinese.domain.TrackHit
import com.radio.chinese.domain.model.MergedTrack
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.theme.Dimens

/**
 * 手机档的全源搜索结果列表。车机档不会走到这里（那里保留目录内过滤）。
 *
 * 空态文案分三种：没搜到 / 还没索引完 / 索引因移动网络暂停——
 * 混用会让用户以为记录丢了，这与 AudioRecentTab、FavoritesScreen 的既有约定同源。
 */
@Composable
fun SearchResultsList(
    results: List<MergedTrack>,
    query: String,
    indexState: IndexState,
    breadcrumbOf: (TrackHit) -> String,
    isFavorited: (String) -> Boolean,
    onPlay: (TrackHit) -> Unit,
    onToggleFavorite: (TrackHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        IndexHint(indexState)

        if (results.isEmpty()) {
            val indexing = indexState is IndexState.Running || indexState is IndexState.PausedNoWifi
            EmptyState(
                icon = Icons.Default.Search,
                title = when {
                    query.isBlank() -> "输入曲目名开始搜索"
                    indexing -> "正在建立索引，请稍后"
                    else -> "没有匹配「$query」的曲目"
                },
                hint = when {
                    indexState is IndexState.PausedNoWifi ->
                        "索引已暂停，已收录 ${indexState.p.indexedFiles} 个曲目，连接 Wi-Fi 后继续"
                    indexState is IndexState.Idle && query.isNotBlank() ->
                        "连接 Wi-Fi 后会自动开始建立节目索引"
                    indexState is IndexState.Running ->
                        "已收录 ${indexState.p.indexedFiles} 个曲目，结果可能不全"
                    else -> "换个关键字试试"
                }
            )
            return
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)
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

/**
 * 索引进行中的软提示：不挡结果，只说明"结果可能不全"。
 *
 * 已完成（Done）与未开始（Idle）都不显示——用户不需要知道一个已经好了的东西。
 */
@Composable
private fun IndexHint(indexState: IndexState) {
    val text = when (indexState) {
        is IndexState.Running ->
            "索引进行中，已收录 ${indexState.p.indexedFiles} 个曲目，结果可能不全"
        is IndexState.PausedNoWifi ->
            "索引已暂停（当前不是 Wi-Fi），已收录 ${indexState.p.indexedFiles} 个曲目"
        is IndexState.PartiallyFailed ->
            "有 ${indexState.p.skippedDirs} 个目录未能收录，下次进入音频库自动重试"
        else -> null
    } ?: return

    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            horizontal = Dimens.ScreenPadding,
            vertical = Dimens.GapTiny
        )
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
                if (favState) Icons.Default.Star else Icons.Default.StarOutline,
                contentDescription = if (favState) "取消收藏" else "收藏这一曲",
                tint = if (favState) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
