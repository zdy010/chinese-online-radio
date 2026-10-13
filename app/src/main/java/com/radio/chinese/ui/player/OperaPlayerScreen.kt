package com.radio.chinese.ui.player

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.service.OperaRepeatMode
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.bitrateText
import com.radio.chinese.ui.common.mediaTimeText
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface
import com.radio.chinese.ui.theme.usesRailNavigation

/**
 * 音频库（戏曲）全屏播放页。
 *
 * 播控状态一律读 PlayerManager（单一状态源，绝不另起炉灶）；
 * 歌词/封面由 OperaPlayerViewModel 按需加载。九项播控能力与音频库
 * 迷你条完全对等：滑条、±10 秒、上/下一段、播放暂停、循环、停止、标题、时间码率。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperaPlayerScreen(
    playerManager: PlayerManager,
    onNavigateBack: () -> Unit,
    viewModel: OperaPlayerViewModel = hiltViewModel()
) {
    val file by playerManager.operaFile.collectAsState()
    val playing by playerManager.isPlaying.collectAsState()
    val pos by playerManager.operaPosition.collectAsState()
    val dur by playerManager.operaDuration.collectAsState()
    val br by playerManager.operaBitrate.collectAsState()
    val repeat by playerManager.operaRepeatMode.collectAsState()
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedWindowInsets,
                // 与电台 PlayerScreen 同理：车机档抬顶栏短边到 88dp，返回键才拿得到完整命中区
                modifier = if (isCarSurface()) Modifier.heightIn(min = Dimens.TouchMin) else Modifier,
                title = { Text("正在播放") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = if (isCarSurface()) Modifier.size(Dimens.TouchMin) else Modifier) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        val f = file
        if (f == null) {
            // 无播放内容直接落到本页：不崩、给出路
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Default.LibraryMusic,
                    title = "未播放任何唱段",
                    hint = "回到音频库列表，点一首唱段再进来",
                    actionLabel = "返回",
                    onAction = onNavigateBack
                )
            }
            return@Scaffold
        }

        val stopAndExit = {
            // 停止后不回退就停在一片空然的死页上：清场后自动返回列表
            playerManager.stopOpera()
            onNavigateBack()
        }

        val info: @Composable () -> Unit = {
            Column(Modifier.padding(horizontal = Dimens.ScreenPadding)) {
                Text(f.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOf(f.categoryName, f.operaName).filter { it.isNotEmpty() }.joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val bt = bitrateText(br)
                Text(
                    text = if (bt != null) "${mediaTimeText(pos, dur)} · $bt" else mediaTimeText(pos, dur),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Dimens.GapSmall))
            }
        }
        val slider: @Composable () -> Unit = {
            PlayerSlider(pos = pos, dur = dur, onSeek = { playerManager.seekOperaTo(it) })
        }
        val controls: @Composable () -> Unit = {
            ControlRow(
                playing = playing,
                repeat = repeat,
                canSeek = dur > 0L,
                onPlayPause = { playerManager.togglePlayPause() },
                onPrevious = { playerManager.playOperaPrevious() },
                onNext = { playerManager.playOperaNext() },
                onStop = stopAndExit,
                onCycleRepeat = { playerManager.cycleOperaRepeatMode() },
                onForward = { playerManager.seekOperaForward(10) },
                onBackward = { playerManager.seekOperaBackward(10) }
            )
        }
        val contentArea: @Composable (Modifier) -> Unit = { m ->
            OperaContentArea(m, state, onLineClick = { viewModel.seekToLine(it) })
        }

        if (usesRailNavigation()) {
            // 车机横屏：左信息+内容，右进度+大控件；±10 秒贴主播放键两侧在 ControlRow 内
            Row(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.weight(1f)) {
                    info
                    contentArea(Modifier.weight(1f))
                }
                Column(
                    Modifier
                        .width(Dimens.PlayerSideWidth)
                        .padding(end = Dimens.ScreenPadding, bottom = Dimens.GapLarge)
                ) {
                    slider
                    controls
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                info
                contentArea(Modifier.weight(1f))
                slider
                controls
            }
        }
    }
}

@Composable
private fun PlayerSlider(pos: Long, dur: Long, onSeek: (Long) -> Unit) {
    if (dur <= 0L) return // 时长未知时不给可拖 0 的滑条（与迷你条同一防御）
    var isDragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableLongStateOf(0L) }
    Slider(
        value = (if (isDragging) dragMs else pos).toFloat(),
        onValueChange = { dragMs = it.toLong(); isDragging = true },
        onValueChangeFinished = { isDragging = false; onSeek(dragMs) },
        valueRange = 0f..dur.toFloat(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.SliderTouchHeight)
            .padding(horizontal = Dimens.GapMedium)
            .semantics { contentDescription = "播放进度" },
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

/**
 * 内容区四态矩阵（spec 3.3）：封面+歌词 → 两页可滑 + 圆点；
 * 只有一样 → 静态呈现不提供滑动；都没有 → 音符占位 + 下一步提示。
 */
@Composable
private fun OperaContentArea(
    modifier: Modifier,
    state: OperaPlayerUiState,
    onLineClick: (Int) -> Unit
) {
    val hasCover = state.coverBytes != null
    val hasLyrics = state.lyricsStatus == LyricsStatus.READY && state.lines.isNotEmpty()
    val car = isCarSurface()
    when {
        hasCover && hasLyrics -> {
            val pagerState = rememberPagerState(pageCount = { 2 })
            Column(modifier = modifier.fillMaxWidth()) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                    if (page == 0) CoverPage(state.coverBytes!!)
                    else LyricPage(state, car, onLineClick)
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Dimens.GapSmall),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(2) { i ->
                        Box(
                            Modifier
                                .size(if (car) 10.dp else 6.dp)
                                .background(
                                    if (pagerState.currentPage == i) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape
                                )
                        )
                        if (i == 0) Spacer(Modifier.width(Dimens.GapSmall))
                    }
                }
            }
        }
        hasCover -> CoverPage(state.coverBytes!!)
        hasLyrics -> LyricPage(state, car, onLineClick)
        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(Dimens.CoverSmall).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, null, Modifier.size(Dimens.IconLarge),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(Dimens.GapMedium))
                Text(
                    text = if (state.lyricsStatus == LyricsStatus.LOADING) "正在加载歌词…"
                    else "该唱段暂无歌词\n在音频同目录放同名 .lrc 即可显示歌词",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun CoverPage(bytes: ByteArray) {
    // BitmapFactory 对纯 Java/Kotlin 字节流足够；失败解码返回 null 由占位分支兜住
    val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (bmp != null) {
                Image(
                    bmp,
                    contentDescription = "专辑封面",
                    modifier = Modifier.size(Dimens.CoverLargeMax).clip(RoundedCornerShape(Dimens.GapLarge))
                )
            }
        }
    }
}

@Composable
private fun LyricPage(state: OperaPlayerUiState, car: Boolean, onLineClick: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentIndex) {
        if (state.currentIndex in state.lines.indices) {
            listState.animateScrollToItem(state.currentIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapSmall)
    ) {
        itemsIndexed(state.lines) { i, line ->
            Text(
                text = line.text,
                style = if (i == state.currentIndex) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                color = if (i == state.currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (car) it.heightIn(min = Dimens.TouchMin) else it }
                    .clickable { onLineClick(i) }
                    .padding(vertical = Dimens.GapTiny)
            )
        }
    }
}

@Composable
private fun ControlRow(
    playing: Boolean,
    repeat: OperaRepeatMode,
    canSeek: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onCycleRepeat: () -> Unit,
    onForward: () -> Unit,
    onBackward: () -> Unit
) {
    val car = isCarSurface()
    val btnMod = if (car) Modifier.size(Dimens.TouchMin) else Modifier
    val repeatLabel = when (repeat) {
        OperaRepeatMode.ALL -> "列表循环"
        OperaRepeatMode.ONE -> "单曲循环"
        OperaRepeatMode.RANDOM -> "随机播放"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onCycleRepeat) {
            Text(repeatLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        if (canSeek) {
            IconButton(onClick = onBackward, modifier = btnMod) {
                Icon(Icons.Default.Replay10, "后退 10 秒", modifier = if (car) Modifier.size(Dimens.IconLarge) else Modifier)
            }
        }
        IconButton(onClick = onPrevious, modifier = btnMod) {
            Icon(Icons.Default.SkipPrevious, "上一段", modifier = Modifier.size(Dimens.IconMedium))
        }
        IconButton(onClick = onPlayPause, modifier = Modifier.size(Dimens.PlayButtonSize)) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "暂停播放" else "继续播放",
                modifier = Modifier.size(Dimens.PlayIconSize)
            )
        }
        IconButton(onClick = onNext, modifier = btnMod) {
            Icon(Icons.Default.SkipNext, "下一段", modifier = Modifier.size(Dimens.IconMedium))
        }
        if (canSeek) {
            IconButton(onClick = onForward, modifier = btnMod) {
                Icon(Icons.Default.Forward10, "前进 10 秒", modifier = if (car) Modifier.size(Dimens.IconLarge) else Modifier)
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onStop, modifier = btnMod) {
            Icon(Icons.Default.Close, "停止播放")
        }
    }
}
