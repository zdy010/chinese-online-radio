package com.radio.chinese.service

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: RadioPreferences,
    private val stationRepository: StationRepository
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _currentStation = MutableStateFlow<RadioStation?>(null)
    val currentStation: StateFlow<RadioStation?> = _currentStation

    /** 当前正在使用的节目源 */
    private val _currentSource = MutableStateFlow<StationSource?>(null)
    val currentSource: StateFlow<StationSource?> = _currentSource

    /** 当前电台的所有节目源（带可用性分数） */
    private val _sourceScores = MutableStateFlow<List<Pair<StationSource, Float>>>(emptyList())
    val sourceScores: StateFlow<List<Pair<StationSource, Float>>> = _sourceScores

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private var pendingStation: RadioStation? = null

    /** 当前尝试播放的源队列 */
    private var sourceQueue: List<StationSource> = emptyList()
    private var sourceQueueIndex: Int = 0

    fun connect() {
        val sessionToken = SessionToken(context, ComponentName(context, RadioService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                _isConnected.value = true
                setupListeners()
                pendingStation?.let { station ->
                    pendingStation = null
                    startPlayback(station)
                }
            } catch (e: Exception) {
                _error.value = "播放器连接失败：${e.message?.take(60) ?: "未知错误"}"
                _isConnected.value = false
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupListeners() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                _playbackState.value = state
                when (state) {
                    Player.STATE_READY -> {
                        _isPlaying.value = controller?.isPlaying == true
                        _error.value = null
                        // 播放成功，提升当前源的可用性
                        onPlaybackSuccess()
                    }
                    Player.STATE_ENDED -> {
                        _isPlaying.value = false
                    }
                    Player.STATE_BUFFERING -> {
                        _error.value = null
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 当前源播放失败，尝试下一个
                onSourceFailed()
                when (error.errorCode) {
                    androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                        _error.value = "直播流超时，尝试其他源…"
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        _error.value = "网络连接失败，尝试其他源…"
                    else -> _error.value = "播放失败，尝试其他源…"
                }
            }
        })

        _isPlaying.value = controller?.isPlaying == true
        _playbackState.value = controller?.playbackState ?: Player.STATE_IDLE
    }

    fun playStation(station: RadioStation) {
        _currentStation.value = station
        _error.value = null

        // 加载该电台的排序节目源
        scope.launch {
            val sorted = stationRepository.getSortedSources(station.id)
            sourceQueue = sorted
            sourceQueueIndex = 0
            val scores = stationRepository.getSourcesWithScore(station.id)
            _sourceScores.value = scores

            val ctrl = controller
            if (ctrl != null) {
                tryPlaySource()
            } else {
                pendingStation = station
                if (controllerFuture == null) connect()
                else scope.launch {
                    _isConnected.first { it }
                    pendingStation?.let {
                        pendingStation = null
                        startPlayback(it)
                    }
                }
            }
        }
    }

    /** 尝试播放队列中的下一个源 */
    private fun tryPlaySource() {
        if (sourceQueueIndex >= sourceQueue.size) {
            // 所有源都失败了
            _error.value = "所有节目源均无法播放"
            _playbackState.value = Player.STATE_IDLE
            return
        }

        val source = sourceQueue[sourceQueueIndex]
        _currentSource.value = source
        _playbackState.value = Player.STATE_BUFFERING
        _error.value = "尝试节目源 ${source.label.ifEmpty { "${sourceQueueIndex + 1}/${sourceQueue.size}" }}…"

        val ctrl = controller ?: return
        val station = _currentStation.value ?: return

        val metadata = MediaMetadata.Builder()
            .setTitle(station.name)
            .setArtist(source.label.ifEmpty { station.frequency.ifEmpty { station.description } })
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(android.net.Uri.parse(source.url))
            .setMediaMetadata(metadata)
            .build()

        ctrl.stop()
        ctrl.clearMediaItems()
        ctrl.setMediaItem(mediaItem)
        ctrl.prepare()
        ctrl.play()

        scope.launch {
            preferences.setLastPlayedStationId(station.id)
        }
    }

    private fun startPlayback(station: RadioStation) {
        scope.launch {
            val sorted = stationRepository.getSortedSources(station.id)
            sourceQueue = sorted
            sourceQueueIndex = 0
            val scores = stationRepository.getSourcesWithScore(station.id)
            _sourceScores.value = scores
            tryPlaySource()
        }
    }

    /** 播放成功：记录可用性并提升分数 */
    private fun onPlaybackSuccess() {
        val station = _currentStation.value ?: return
        val source = _currentSource.value ?: return
        scope.launch {
            stationRepository.recordSourceSuccess(station.id, source.url, 0)
            // 刷新分数
            _sourceScores.value = stationRepository.getSourcesWithScore(station.id)
        }
    }

    /** 当前源失败：记录并尝试下一个 */
    private fun onSourceFailed() {
        val station = _currentStation.value ?: return
        val source = _currentSource.value ?: return
        scope.launch {
            stationRepository.recordSourceFailure(station.id, source.url)
        }
        sourceQueueIndex++
        tryPlaySource()
    }

    /** 用户手动切换到指定节目源 */
    fun switchToSource(sourceUrl: String) {
        val station = _currentStation.value ?: return
        val sources = sourceQueue
        val idx = sources.indexOfFirst { it.url == sourceUrl }
        if (idx < 0) return

        sourceQueueIndex = idx
        // 手动切换时将此源分数设最高
        scope.launch {
            stationRepository.setSourceScore(station.id, sourceUrl, 1.0f)
            tryPlaySource()
        }
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.isPlaying) {
            ctrl.pause()
        } else {
            ctrl.play()
        }
    }

    fun stop() {
        controller?.stop()
    }

    fun playNext(stations: List<RadioStation>) {
        val current = _currentStation.value ?: return
        val currentIndex = stations.indexOfFirst { it.id == current.id }
        if (currentIndex >= 0 && currentIndex < stations.size - 1) {
            playStation(stations[currentIndex + 1])
        } else if (stations.isNotEmpty()) {
            playStation(stations[0])
        }
    }

    fun playPrevious(stations: List<RadioStation>) {
        val current = _currentStation.value ?: return
        val currentIndex = stations.indexOfFirst { it.id == current.id }
        if (currentIndex > 0) {
            playStation(stations[currentIndex - 1])
        } else if (stations.isNotEmpty()) {
            playStation(stations.last())
        }
    }

    fun disconnect() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        controller = null
        _isConnected.value = false
    }
}
