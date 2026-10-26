package com.radio.chinese.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.radio.chinese.ui.common.NowPlayingWave
import com.radio.chinese.ui.theme.Dimens

/**
 * 手机档的全源搜索结果列表。车机档不会走到这里（那里保留目录内过滤）。
 *
 * 一切"该显示什么"由 [SearchPhase] 决定，判定在 `IndexQuery.searchPhase` 里且已被单测钉死；
 * 这里只做渲染，不再自己判断有没有索引、在不在建。
 *
 * [onOpenFolder] 由宿主负责退出搜索态后再调 VM：searchQuery 是 UI 局部状态，
 * 不清掉的话目录会被这个结果列表盖住，看着就像长按了没反应。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchResultsList(
    results: List<MergedTrack>,
    query: String,
    phase: SearchPhase,
    indexState: IndexState,
    breadcrumbOf: (TrackHit) -> String,
    isFavorited: (String) -> Boolean,
    onPlay: (TrackHit) -> Unit,
    onOpenFolder: (TrackHit) -> Unit,
    onToggleFavorite: (TrackHit) -> Unit,
    onBuildIndex: () -> Unit,
    onAddSource: () -> Unit,
    playingPath: String? = null,
    modifier: Modifier = Modifier,
) {
    val indexedNow = (indexState as? IndexState.Running)?.p?.indexedFiles ?: 0

    // 长按哪行就弹哪行的菜单。用普通 remember：翻页/旋转后不必保留一个未完成的动作菜单
    var actionHit by remember { mutableStateOf<TrackHit?>(null) }

    actionHit?.let { hit ->
        TrackFolderDialog(
            name = hit.name,
            crumb = breadcrumbOf(hit),
            onOpen = {
                actionHit = null
                onOpenFolder(hit)
            },
            onDismiss = { actionHit = null },
        )
    }

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

            SearchPhase.NoWebdavSource -> EmptyState(
                // 没添加过网络源时问“要不要建立索引”是答非所问，要引导去加来源
                icon = Icons.Default.Search,
                title = "还没有可索引的网络来源",
                hint = "先添加一个 WebDAV / 云盘来源，就能按唱段名搜索全部曲目",
                actionLabel = "添加网络来源",
                onAction = onAddSource,
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
                                onLongClick = { actionHit = primary },
                                onToggleFav = { onToggleFavorite(primary) },
                                // 归并行的任一来源在播就算这行在播：同内容多源时用户看到的是一行
                                isPlaying = playingPath != null &&
                                    merged.sources.any { it.path == playingPath },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(
    name: String,
    crumb: String,
    sourceCount: Int,
    isFav: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFav: () -> Unit,
    isPlaying: Boolean = false,
) {
    var favState by remember { mutableStateOf(isFav) }
    LaunchedEffect(isFav) { favState = isFav }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 单击仍是起播，长按开动作菜单；不能拆成两个 clickable，后一个会吃掉前一个
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = Dimens.RowMinHeight)
            .padding(vertical = Dimens.RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isPlaying) {
            // 与 BrowseScreen 同一个做法：标记占行首图标位，不额外占宽
            NowPlayingWave(size = Dimens.IconLarge)
        } else {
            Icon(
                imageVector = Icons.Default.Audiotrack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.IconLarge)
            )
        }
        Spacer(modifier = Modifier.width(Dimens.GapLarge))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
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

/**
 * 长按搜索结果行弹出的动作菜单，目前只有一项「转到所在目录」。
 *
 * 不做成「长按直接跳」是为了跟电台侧的长按范式保持一致，将来加第二项也不用改交互；
 * 代价是多一步点击。副标题直接把面包屑摆出来，让用户在点之前就看到会跳去哪，
 * 跳错了也能立刻看出原因。
 */
@Composable
private fun TrackFolderDialog(
    name: String,
    crumb: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            ListItem(
                headlineContent = { Text("转到所在目录") },
                supportingContent = {
                    Text(
                        text = crumb,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpen),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
