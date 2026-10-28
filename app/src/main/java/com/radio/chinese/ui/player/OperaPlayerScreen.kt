package com.radio.chinese.ui.player

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.OperaSpeed
import com.radio.chinese.service.OperaRepeatMode
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.bitrateText
import com.radio.chinese.ui.common.mediaClock
import com.radio.chinese.ui.common.mediaTimeText
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface

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
    // 播控状态统一从 ViewModel 代理读取，不再直接 collect PlayerManager
    val playback by viewModel.playbackState.collectAsState()
    val file = playback.file
    val playing = playback.isPlaying
    val pos = playback.position
    val dur = playback.duration
    val br = playback.bitrate
    val repeat = playback.repeatMode
    val speed = playback.speed
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
                speed = speed,
                onPlayPause = { playerManager.togglePlayPause() },
                onPrevious = { playerManager.playOperaPrevious() },
                onNext = { playerManager.playOperaNext() },
                onStop = stopAndExit,
                onCycleRepeat = { playerManager.cycleOperaRepeatMode() },
                onCycleSpeed = { playerManager.cycleOperaSpeed() },
                onForward = { playerManager.seekOperaForward(10) },
                onBackward = { playerManager.seekOperaBackward(10) }
            )
        }
        val contentArea: @Composable (Modifier) -> Unit = { m ->
            OperaContentArea(m, state, onLineClick = { viewModel.seekToLine(it) })
        }

        // 播控一律排在底部横排：车机横屏原先把进度条+控件+倍速塞在右侧定宽栏里，
        // 与电台播放页「控件在下方」的直觉不一致，很别扭（用户实测问题 6）。
        // 唱段内容区拿到整屏宽度，反而比左侧窄栏更好读。
        Column(Modifier.fillMaxSize().padding(padding)) {
            info()
            contentArea(Modifier.weight(1f))
            slider()
            controls()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSlider(pos: Long, dur: Long, onSeek: (Long) -> Unit) {
    if (dur <= 0L) return // 时长未知时不给可拖 0 的滑条（与迷你条同一防御）
    var isDragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableLongStateOf(0L) }
    val shown = if (isDragging) dragMs else pos
    val frac = (shown.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.GapLarge)) {
        // 自己画细圆角轨道 + 小圆点拇指：Material3 默认拇指是一枚竖胶囊、轨道很细，
        // 在戏曲页上看着突兀（用户实测“进度条好丑”）。
        Slider(
            value = shown.toFloat(),
            onValueChange = { dragMs = it.toLong(); isDragging = true },
            onValueChangeFinished = { isDragging = false; onSeek(dragMs) },
            valueRange = 0f..dur.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.SliderTouchHeight)
                .semantics { contentDescription = "播放进度" },
            colors = SliderDefaults.colors(
                thumbColor = active,
                activeTrackColor = active,
                inactiveTrackColor = inactive,
            ),
            thumb = {
                Box(
                    Modifier
                        .size(14.dp)
                        .shadow(2.dp, CircleShape)
                        .background(active, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            },
            track = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(inactive)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(active)
                    )
                }
            }
        )
        // 两端时间：左已播、右总时长，比只在顶部堆一行“0:21 / 4:21”更像播放器
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                mediaClock(shown),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                mediaClock(dur),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
        hasCover -> Box(modifier.fillMaxSize()) { CoverPage(state.coverBytes!!) }
        hasLyrics -> Box(modifier.fillMaxSize()) { LyricPage(state, car, onLineClick) }
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
    // 封面吃满内容区可用空间，不再钉死 Dimens.CoverLargeMax：手机竖屏内容区有五百多 dp 高，
    // 固定 220dp 的图会在上下各留一大片死白，视觉重心整个塌掉（用户实测「小而不协调」）。
    // 取宽高里较小者再留边距：正方形不变形，矮屏（车机横屏）也不会被顶出去。
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val side = minOf(maxWidth * 0.86f, maxHeight * 0.92f).coerceAtLeast(Dimens.CoverSmall)
        if (bmp != null) {
            Image(
                bmp,
                contentDescription = "专辑封面",
                modifier = Modifier
                    .size(side)
                    .clip(RoundedCornerShape(Dimens.GapLarge))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        } else {
            // 解码失败要占住同一块位置：整页留白会让人以为功能坏了，而不是这一张图坏了
            Box(
                Modifier
                    .size(side)
                    .clip(RoundedCornerShape(Dimens.GapLarge))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.MusicNote, null,
                    modifier = Modifier.size(Dimens.IconLarge),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlRow(
    playing: Boolean,
    repeat: OperaRepeatMode,
    canSeek: Boolean,
    speed: Float,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onCycleRepeat: () -> Unit,
    onCycleSpeed: () -> Unit,
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
    // 播放模式改用通用图标（循环/单曲循环/随机），不再占“列表循环”四个字——
    // 那四个字把同行的“前进 10 秒”顶出了屏（用户实测）。当前模式仍放进 contentDescription，
    // 无障碍与长按提示不丢。
    val repeatIcon = when (repeat) {
        OperaRepeatMode.ALL -> Icons.Default.Repeat
        OperaRepeatMode.ONE -> Icons.Default.RepeatOne
        OperaRepeatMode.RANDOM -> Icons.Default.Shuffle
    }
    val repeatBtn = @Composable {
        IconButton(onClick = onCycleRepeat, modifier = btnMod) {
            Icon(repeatIcon, contentDescription = "$repeatLabel（点按切换）")
        }
    }
    val stopBtn = @Composable {
        IconButton(onClick = onStop, modifier = btnMod) {
            Icon(Icons.Default.Close, "停止播放")
        }
    }
    // 倍速合并成一个循环按钮放在控件行上（用户实测：一排五枚档位芯片太抢位置）。
    // 与当时选芯片的理由（“循环按钮看不出再点会到什么速度”）并不冲突：
    // 按钮文案就是当前档位，点一下下一档，当前值始终可见。
    val speedBtn = @Composable {
        TextButton(
            onClick = onCycleSpeed,
            modifier = if (car) Modifier.heightIn(min = Dimens.TouchMin) else Modifier
        ) {
            Text(
                OperaSpeed.label(speed),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    val seekBackBtn = @Composable {
        if (canSeek) {
            IconButton(onClick = onBackward, modifier = btnMod) {
                Icon(Icons.Default.Replay10, "后退 10 秒", modifier = if (car) Modifier.size(Dimens.IconLarge) else Modifier)
            }
        }
    }
    val seekFwdBtn = @Composable {
        if (canSeek) {
            IconButton(onClick = onForward, modifier = btnMod) {
                Icon(Icons.Default.Forward10, "前进 10 秒", modifier = if (car) Modifier.size(Dimens.IconLarge) else Modifier)
            }
        }
    }
    val prevBtn = @Composable {
        IconButton(onClick = onPrevious, modifier = btnMod) {
            Icon(Icons.Default.SkipPrevious, "上一段", modifier = Modifier.size(Dimens.IconMedium))
        }
    }
    val playBtn = @Composable {
        IconButton(onClick = onPlayPause, modifier = Modifier.size(Dimens.PlayButtonSize)) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "暂停播放" else "继续播放",
                modifier = Modifier.size(Dimens.PlayIconSize)
            )
        }
    }
    val nextBtn = @Composable {
        IconButton(onClick = onNext, modifier = btnMod) {
            Icon(Icons.Default.SkipNext, "下一段", modifier = Modifier.size(Dimens.IconMedium))
        }
    }
    if (car) {
        // 车机档：循环与倍速并进主控件行（用户实测：它们单独占一行，下面空出一条谁都不碰的横带）。
        // 用 FlowRow 而不是 Row：八个 88dp 目标单行要 704dp，density 报 160 的
        // 1280dp 面板装得下，报 240 的只有 853dp、再遇到窄一档的面板就会把末尾的
        // 「停止」挤出屏。FlowRow 装得下就是一行，装不下才折行，不会丢控件。
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.GapMedium, vertical = Dimens.GapSmall),
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapSmall, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapTiny, Alignment.CenterVertically),
        ) {
            repeatBtn()
            speedBtn()
            seekBackBtn()
            prevBtn()
            playBtn()
            nextBtn()
            seekFwdBtn()
            stopBtn()
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeatBtn()
            speedBtn()
            Spacer(Modifier.weight(1f))
            seekBackBtn()
            prevBtn()
            playBtn()
            nextBtn()
            seekFwdBtn()
            Spacer(Modifier.weight(1f))
            stopBtn()
        }
    }
}
