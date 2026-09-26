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
import com.radio.chinese.domain.model.OperaAudioFile
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
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
    private val stationRepository: StationRepository,
    private val webDavClient: WebDavClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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

    /**
     * 过渡性提示（如「正在尝试第 2 个节目源」）。
     * 此前这类进度文字被塞进 error 通道，而 UI 用红底渲染 error，
     * 导致每次正常起播都红框一闪；红色是本应用唯一的情绪信号，不能当进度用。
     */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private var pendingStation: RadioStation? = null

    /** 当前尝试播放的源队列 */
    private var sourceQueue: List<StationSource> = emptyList()
    private var sourceQueueIndex: Int = 0

    // ========== 戏曲播放状态 ==========

    private val _operaFile = MutableStateFlow<OperaAudioFile?>(null)
    val operaFile: StateFlow<OperaAudioFile?> = _operaFile

    private val _operaPlaylist = MutableStateFlow<List<OperaAudioFile>>(emptyList())
    val operaPlaylist: StateFlow<List<OperaAudioFile>> = _operaPlaylist

    private val _operaIndex = MutableStateFlow(-1)
    val operaIndex: StateFlow<Int> = _operaIndex

    private val _operaPosition = MutableStateFlow(0L)
    val operaPosition: StateFlow<Long> = _operaPosition

    private val _operaDuration = MutableStateFlow(0L)
    val operaDuration: StateFlow<Long> = _operaDuration

    private val _operaBitrate = MutableStateFlow(0)
    val operaBitrate: StateFlow<Int> = _operaBitrate

    private val _isOperaMode = MutableStateFlow(false)
    val isOperaMode: StateFlow<Boolean> = _isOperaMode

    /** 戏曲循环模式由本类持有，UI 只读，避免「按钮文字变了但行为不变」 */
    private val _operaRepeatMode = MutableStateFlow(OperaRepeatMode.ALL)
    val operaRepeatMode: StateFlow<OperaRepeatMode> = _operaRepeatMode

    private var pendingOpera: OperaAudioFile? = null
    private var pendingOperaPlaylist: List<OperaAudioFile> = emptyList()
    private var pendingOperaLocalPath: String? = null

    private var operaPositionUpdater: java.lang.Runnable? = null
    private val operaPositionHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
                if (_isOperaMode.value) startOperaPositionUpdater()
            }

            override fun onPlaybackStateChanged(state: Int) {
                _playbackState.value = state
                when (state) {
                    Player.STATE_READY -> {
                        _isPlaying.value = controller?.isPlaying == true
                        _error.value = null
                        _status.value = null
                        if (_isOperaMode.value) {
                            _operaDuration.value = controller?.duration?.coerceAtLeast(0) ?: 0L
                            val br = try {
                                controller?.currentTracks?.groups?.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                                    ?.getTrackFormat(0)?.bitrate ?: 0
                            } catch (_: Exception) { 0 }
                            _operaBitrate.value = br
                        } else {
                            onPlaybackSuccess()
                        }
                    }
                    Player.STATE_ENDED -> {
                        _isPlaying.value = false
                        stopOperaPositionUpdater()
                        if (_isOperaMode.value) {
                            when (_operaRepeatMode.value) {
                                OperaRepeatMode.ONE -> {
                                    clearSavedOperaPosition()
                                    controller?.seekTo(0)
                                    controller?.play()
                                }
                                OperaRepeatMode.RANDOM -> {
                                    saveOperaPosition()
                                    playOperaRandom()
                                }
                                OperaRepeatMode.ALL -> playOperaNext()
                            }
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        _error.value = null
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (_isOperaMode.value) {
                    val code = error.errorCode
                    val msg = error.localizedMessage ?: "未知错误"
                    _error.value = "播放失败(code=$code): $msg"
                } else {
                    onSourceFailed()
                    when (error.errorCode) {
                        androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                            _status.value = "直播流超时，正在换下一个节目源…"
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                            _status.value = "网络连接失败，正在换下一个节目源…"
                        else -> _status.value = "播放失败，正在换下一个节目源…"
                    }
                }
            }
        })

        _isPlaying.value = controller?.isPlaying == true
        _playbackState.value = controller?.playbackState ?: Player.STATE_IDLE
    }

    fun playStation(station: RadioStation) {
        // 互斥：清除戏曲状态
        stopOpera()
        _currentStation.value = station
        _error.value = null
        _status.value = null

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
            _status.value = null
            _error.value = "所有节目源均无法播放"
            _playbackState.value = Player.STATE_IDLE
            return
        }

        val source = sourceQueue[sourceQueueIndex]
        _currentSource.value = source
        _playbackState.value = Player.STATE_BUFFERING
        _status.value = "正在尝试节目源 ${source.label.ifEmpty { "${sourceQueueIndex + 1}/${sourceQueue.size}" }}…"

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
            // 「最近播放」需要跨进程重启存活，此前只存在 ViewModel 内存里
            preferences.addRecentStationId(station.id)
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

    /**
     * 方控/车机媒体键的统一入口：戏曲模式跟随切曲目，电台模式切电台。
     * 电台列表按需从仓库取；无内容播放时 playNext/playPrevious 自身不动作。
     */
    fun handleMediaSkip(forward: Boolean) {
        if (_isOperaMode.value) {
            if (forward) playOperaNext() else playOperaPrevious()
            return
        }
        scope.launch {
            // 用活动列表：节目源管理里标记无效的电台不参与切台
            val stations = stationRepository.getActiveStations()
            // 查询期间用户可能切进戏曲模式，落地前以最新状态为准
            if (_isOperaMode.value) {
                if (forward) playOperaNext() else playOperaPrevious()
            } else if (forward) {
                playNext(stations)
            } else {
                playPrevious(stations)
            }
        }
    }

    // ========== 戏曲播放 ==========

    /** 已下载文件的本地路径映射：fileId → localPath，供上下切歌时使用 */
    private val downloadedPaths = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** 预注册所有已下载文件的本地路径，上下切歌时自动使用本地路径 */
    fun registerDownloadedPaths(paths: Map<Long, String>) {
        downloadedPaths.putAll(paths)
    }

    /** 播放戏曲文件，自动停止电台
     *  @param file 要播放的文件
     *  @param playlist 播放列表（用于上下切歌）
     *  @param localPath 本地路径（已下载文件），null 表示从远程播放
     */
    fun playOperaFile(file: OperaAudioFile, playlist: List<OperaAudioFile>, localPath: String? = null) {
        // 互斥：停止电台
        _currentStation.value = null
        _isOperaMode.value = true
        _error.value = null
        _status.value = null
        _operaPlaylist.value = playlist
        val index = playlist.indexOfFirst { it.fileId == file.fileId }
        _operaIndex.value = index
        _operaFile.value = file

        // 记录本地路径供上下切歌使用
        if (localPath != null) downloadedPaths[file.fileId] = localPath

        val ctrl = controller
        if (ctrl != null) {
            startOperaOnController(ctrl, file, localPath)
        } else {

            pendingOpera = file
            pendingOperaPlaylist = playlist
            pendingOperaLocalPath = localPath
            if (controllerFuture == null) connect()
            else scope.launch {
                _isConnected.first { it }
                val c = controller ?: return@launch
                pendingOpera?.let { startOperaOnController(c, it, pendingOperaLocalPath) }
                pendingOpera = null
                pendingOperaPlaylist = emptyList()
                pendingOperaLocalPath = null
            }
        }
    }

    private fun startOperaOnController(ctrl: MediaController, file: OperaAudioFile, localPath: String?) {
        val uri = if (localPath != null) {
            android.net.Uri.fromFile(java.io.File(localPath))
        } else {
            val url = file.downloadUrl ?: webDavClient.getFileUrl(file.name)
            android.net.Uri.parse(url)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(file.name)
            .setArtist(file.operaName.ifEmpty { file.categoryName })
            .setAlbumTitle(file.categoryName)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()

        ctrl.stop()
        ctrl.clearMediaItems()
        ctrl.setMediaItem(mediaItem)

        // 断点续听
        scope.launch {
            val saved = preferences.getOperaPlayPosition(file.fileId)
            if (saved > 0) ctrl.seekTo(saved)
        }

        ctrl.prepare()
        ctrl.play()
    }

    fun playOperaNext() {
        val playlist = _operaPlaylist.value
        val idx = _operaIndex.value
        if (playlist.isEmpty() || idx < 0 || idx >= playlist.size - 1) {
            _isOperaMode.value = false
            return
        }
        saveOperaPosition()
        val next = playlist[idx + 1]
        val local = downloadedPaths[next.fileId]
        playOperaFile(next, playlist, local)
    }

    fun cycleOperaRepeatMode() {
        _operaRepeatMode.value = when (_operaRepeatMode.value) {
            OperaRepeatMode.ALL -> OperaRepeatMode.ONE
            OperaRepeatMode.ONE -> OperaRepeatMode.RANDOM
            OperaRepeatMode.RANDOM -> OperaRepeatMode.ALL
        }
    }

    fun setOperaRepeatMode(mode: OperaRepeatMode) {
        _operaRepeatMode.value = mode
    }

    /** 随机切歌：避开当前这首，避免「随机到同一首」看起来没反应 */
    private fun playOperaRandom() {
        val playlist = _operaPlaylist.value
        if (playlist.isEmpty()) return
        val currentId = _operaFile.value?.fileId
        val candidates = playlist.filter { it.fileId != currentId }
        if (candidates.isEmpty()) {
            controller?.seekTo(0)
            controller?.play()
            return
        }
        val pick = candidates.random()
        playOperaFile(pick, playlist, downloadedPaths[pick.fileId])
    }

    fun playOperaPrevious() {
        val playlist = _operaPlaylist.value
        val idx = _operaIndex.value
        if (playlist.isEmpty() || idx <= 0) return
        saveOperaPosition()
        val prev = playlist[idx - 1]
        val local = downloadedPaths[prev.fileId]
        playOperaFile(prev, playlist, local)
    }

    fun seekOperaTo(posMs: Long) {
        controller?.seekTo(posMs)
        _operaPosition.value = posMs
    }

    fun seekOperaForward(seconds: Long = 15) {
        val ctrl = controller ?: return
        val dur = ctrl.duration
        // 时长未知（未就绪 / 直播）时不能猜：coerceAtMost(0) 会把整段弹回开头，
        // coerceAtMost(-1) 等于 TIME_UNSET，行为未定义
        if (dur == null || dur <= 0L) return
        ctrl.seekTo((ctrl.currentPosition + seconds * 1000).coerceAtMost(dur))
    }

    fun seekOperaBackward(seconds: Long = 15) {
        val ctrl = controller ?: return
        val dur = ctrl.duration
        if (dur == null || dur <= 0L) return
        ctrl.seekTo((ctrl.currentPosition - seconds * 1000).coerceAtLeast(0))
    }

    private fun saveOperaPosition() {
        val file = _operaFile.value ?: return
        val ctrl = controller ?: return
        val pos = ctrl.currentPosition
        val duration = ctrl.duration ?: 0L
        // 播到结尾的歌不再记断点，否则下次一打开就是末尾、立刻触发自动切歌
        val effective = if (duration > 0 && duration - pos <= 3_000L) 0L else pos
        scope.launch { preferences.saveOperaPlayPosition(file.fileId, effective) }
    }

    /** 单曲循环重播前清掉末尾断点 */
    private fun clearSavedOperaPosition() {
        val file = _operaFile.value ?: return
        scope.launch { preferences.saveOperaPlayPosition(file.fileId, 0L) }
    }

    private fun startOperaPositionUpdater() {
        stopOperaPositionUpdater()
        val updater = object : java.lang.Runnable {
            override fun run() {
                if (!_isOperaMode.value) return
                val ctrl = controller ?: return
                if (ctrl.isPlaying) {
                    _operaPosition.value = ctrl.currentPosition
                    _operaDuration.value = ctrl.duration.coerceAtLeast(0)
                }
                operaPositionHandler.postDelayed(this, 1000)
            }
        }
        operaPositionUpdater = updater
        operaPositionHandler.post(updater)
    }

    private fun stopOperaPositionUpdater() {
        operaPositionUpdater?.let { operaPositionHandler.removeCallbacks(it) }
        operaPositionUpdater = null
    }

    fun stopOpera() {
        saveOperaPosition()
        stopOperaPositionUpdater()
        _isOperaMode.value = false
        _operaFile.value = null
        _operaPlaylist.value = emptyList()
        _operaIndex.value = -1
        controller?.stop()
    }

    fun disconnect() {
        stopOperaPositionUpdater()
        scope.coroutineContext[Job]?.cancelChildren()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        controller = null
        _isConnected.value = false
        _isOperaMode.value = false
    }
}

/** 戏曲播放循环模式。 */
enum class OperaRepeatMode { ALL, ONE, RANDOM }
