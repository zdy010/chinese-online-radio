package com.radio.chinese.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.domain.sidecar.LrcLine
import com.radio.chinese.domain.sidecar.LrcParser
import com.radio.chinese.domain.sidecar.SidecarMediaSource
import com.radio.chinese.service.OperaPlayContext
import com.radio.chinese.service.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

enum class LyricsStatus { NONE, LOADING, READY, MISSING }
enum class CoverSource { NONE, EMBEDDED, SIDECAR }

data class OperaPlayerUiState(
    val lyricsStatus: LyricsStatus = LyricsStatus.NONE,
    val lines: List<LrcLine> = emptyList(),
    val currentIndex: Int = -1,
    val coverBytes: ByteArray? = null,
    val coverSource: CoverSource = CoverSource.NONE
)

/**
 * 播放页附属内容状态机：歌词/封面只在本页存活时加载（迷你条零流量）。
 * keys=(fileId,context) 一个键同时决定取料目标与旧响应失效——行车中换段，
 * 迟到的旧段歌词/封面必须被 flatMapLatest 丢掉而不是挂在新段上。
 * 播控动作不经过本 VM：一律由页面直调 PlayerManager（单一状态源）。
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class OperaPlayerViewModel @Inject constructor(
    private val playerManager: PlayerManager,
    private val sidecar: SidecarMediaSource
) : ViewModel() {

    private data class SidecarEvent(
        val clear: Boolean = false,
        val lyricsStatus: LyricsStatus? = null,
        val lines: List<LrcLine> = emptyList(),
        val cover: ByteArray? = null
    )

    private val _uiState = MutableStateFlow(OperaPlayerUiState())
    val uiState: StateFlow<OperaPlayerUiState> = _uiState.asStateFlow()

    private val keys = combine(playerManager.operaFile, playerManager.operaContext) { file, ctx ->
        (file?.fileId ?: -1L) to ctx
    }.distinctUntilChanged()

    init {
        viewModelScope.launch {
            keys.flatMapLatest { (fileId, ctx) ->
                if (fileId == -1L || ctx == null) flowOf(SidecarEvent(clear = true))
                else loadSidecars(ctx)
            }.collect { e ->
                if (e.clear) {
                    _uiState.value = OperaPlayerUiState()
                    return@collect
                }
                if (e.lyricsStatus != null) _uiState.update {
                    it.copy(
                        lyricsStatus = e.lyricsStatus,
                        lines = if (e.lyricsStatus == LyricsStatus.LOADING) it.lines else e.lines,
                        currentIndex = if (e.lyricsStatus == LyricsStatus.LOADING) it.currentIndex else -1
                    )
                }
                if (e.cover != null) _uiState.update {
                    // 内嵌封面优先：sidecar 图迟到也不许覆盖已落位的 EMBEDDED
                    if (it.coverSource != CoverSource.EMBEDDED) it.copy(coverBytes = e.cover, coverSource = CoverSource.SIDECAR)
                    else it
                }
            }
        }
        // 内嵌封面：artworkData 一到就落位，并把同段的 sidecar 图挤掉
        viewModelScope.launch {
            combine(keys, playerManager.operaMediaMetadata) { k, md -> k.first to md?.artworkData }
                .collect { (fileId, art) ->
                    if (fileId == -1L) _uiState.update { it.copy(coverBytes = null, coverSource = CoverSource.NONE) }
                    else if (art != null && art.isNotEmpty()) _uiState.update { it.copy(coverBytes = art, coverSource = CoverSource.EMBEDDED) }
                }
        }
        viewModelScope.launch {
            combine(
                playerManager.operaPosition,
                _uiState.map { it.lines }.distinctUntilChanged()
            ) { pos, lines -> LrcParser.indexAt(lines, pos) }
                .collect { idx -> _uiState.update { it.copy(currentIndex = idx) } }
        }
    }

    private fun loadSidecars(ctx: OperaPlayContext) = flow {
        emit(SidecarEvent(clear = true))
        emit(SidecarEvent(lyricsStatus = LyricsStatus.LOADING))
        // readLyrics 自身返回 Result，runCatching 只捕异常/取消：双层压平，超时与失败都落到 null
        val text = runCatching { withTimeout(8_000) { sidecar.readLyrics(ctx) } }.getOrNull()?.getOrNull()
        val parsed = text?.let { LrcParser.parse(it) }
        emit(SidecarEvent(
            lyricsStatus = if (!parsed.isNullOrEmpty()) LyricsStatus.READY else LyricsStatus.MISSING,
            lines = parsed ?: emptyList()
        ))
        // 现读元数据当前值：已有内嵌封面就不浪费 sidecar 图流量（更晚到的内嵌由上面收集器覆盖）
        val hasEmbedded = playerManager.operaMediaMetadata.value?.artworkData?.isNotEmpty() == true
        if (!hasEmbedded) {
            val img = runCatching { withTimeout(8_000) { sidecar.readImage(ctx) } }.getOrNull()?.getOrNull()
            if (img != null && img.isNotEmpty()) emit(SidecarEvent(cover = img))
        }
    }

    /** 点歌词行跳播：歌词播放器标配动作，seek 直调 PlayerManager。 */
    fun seekToLine(index: Int) {
        val line = _uiState.value.lines.getOrNull(index) ?: return
        playerManager.seekOperaTo(line.timeMs)
        _uiState.update { it.copy(currentIndex = index) }
    }
}
