package com.radio.chinese.ui.opera

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.domain.model.*
import com.radio.chinese.service.OperaRepository
import com.radio.chinese.service.WebDavClient
import com.radio.chinese.service.YunPanConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class BrowseLevel { CATEGORIES, OPERAS, FILES, SEARCH_RESULTS }

data class OperaUiState(
    val needAuth: Boolean = true,
    val authError: String? = null,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val level: BrowseLevel = BrowseLevel.CATEGORIES,
    val categories: List<OperaCategory> = emptyList(),
    val operas: List<OperaItem> = emptyList(),
    val audioFiles: List<OperaAudioFile> = emptyList(),
    val searchResults: List<OperaAudioFile> = emptyList(),
    val searchQuery: String = "",
    val selectedCategory: OperaCategory? = null,
    val selectedOpera: OperaItem? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val downloadedIds: Set<Long> = emptySet(),
    val downloadProgress: Map<Long, Int> = emptyMap(),
    val localDownloads: List<DownloadedOpera> = emptyList(),
    val tabIndex: Int = 0,
    val currentFile: OperaAudioFile? = null,
    val isPlaying: Boolean = false,
    val playError: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    // 播放列表支持
    val currentPlaylist: List<OperaAudioFile> = emptyList(),
    val currentIndex: Int = -1,
    val bitrate: Int = 0, // 码率，单位 bps
)

@HiltViewModel
class OperaViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val operaRepository: OperaRepository,
    private val webDavClient: WebDavClient,
    private val preferences: RadioPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(OperaUiState())
    val uiState: StateFlow<OperaUiState> = _uiState.asStateFlow()

    private var exoPlayer: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val positionUpdater = object : Runnable {
        override fun run() {
            val player = exoPlayer ?: return
            if (player.isPlaying) {
                _uiState.update {
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = player.duration.coerceAtLeast(0)
                    )
                }
                val file = _uiState.value.currentFile
                if (file != null) {
                    viewModelScope.launch {
                        preferences.saveOperaPlayPosition(file.fileId, player.currentPosition)
                    }
                }
            }
            handler.postDelayed(this, 500)
        }
    }
    private var searchJob: Job? = null

    init {
        loadDownloaded()
        initPlayer()
        handler.post(positionUpdater)
    }

    // ========== 授权码验证 ==========

    fun submitAuthCode(code: String) {
        if (code != YunPanConfig.AUTH_CODE) {
            _uiState.update { it.copy(authError = "授权码错误") }
            return
        }
        _uiState.update { it.copy(isConnecting = true, authError = null) }

        viewModelScope.launch {
            operaRepository.setCredentials(
                YunPanConfig.WEBDAV_SERVER_URL,
                YunPanConfig.WEBDAV_USERNAME,
                YunPanConfig.WEBDAV_PASSWORD
            )
            // 重新初始化ExoPlayer以使用新的auth header
            exoPlayer?.release()
            initPlayer()
            val result = operaRepository.verifyConnection()
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isConnecting = false, needAuth = false, isConnected = true) }
                    loadCategories()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isConnecting = false, authError = "WebDAV连接失败: ${e.message ?: "未知错误"}")
                    }
                }
            )
        }
    }

    // ========== 浏览 ==========

    fun loadCategories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, level = BrowseLevel.CATEGORIES) }
            try {
                val cats = operaRepository.getCategories()
                _uiState.update {
                    it.copy(categories = cats, isLoading = false, selectedCategory = null, selectedOpera = null,
                        currentPlaylist = emptyList(), currentIndex = -1)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载分类失败") }
            }
        }
    }

    fun selectCategory(category: OperaCategory) {
        val currentState = _uiState.value
        if (currentState.isLoading) return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val operas = operaRepository.getOperas(category)
                if (operas.isEmpty()) {
                    val files = operaRepository.getAudioFiles(category.name, OperaItem(name = "", folderId = 0, path = category.path))
                    val downloaded = operaRepository.getAllDownloadedIds()
                    _uiState.update {
                        it.copy(
                            selectedCategory = category, selectedOpera = null,
                            audioFiles = files, downloadedIds = downloaded,
                            level = BrowseLevel.FILES, isLoading = false, operas = emptyList(),
                            currentPlaylist = files, currentIndex = -1
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            selectedCategory = category, selectedOpera = null,
                            operas = operas, level = BrowseLevel.OPERAS,
                            isLoading = false, audioFiles = emptyList(),
                            currentPlaylist = emptyList(), currentIndex = -1
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun selectOpera(opera: OperaItem) {
        val currentState = _uiState.value
        if (currentState.isLoading) return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val categoryName = currentState.selectedCategory?.name ?: ""
                val files = operaRepository.getAudioFiles(categoryName, opera)
                val downloaded = operaRepository.getAllDownloadedIds()
                _uiState.update {
                    it.copy(
                        selectedOpera = opera, audioFiles = files,
                        downloadedIds = downloaded, level = BrowseLevel.FILES,
                        isLoading = false, operas = emptyList(),
                        currentPlaylist = files, currentIndex = -1
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载音频失败") }
            }
        }
    }

    fun goBack() {
        val current = _uiState.value.level
        _uiState.update {
            when (current) {
                BrowseLevel.FILES -> it.copy(level = BrowseLevel.OPERAS, audioFiles = emptyList())
                BrowseLevel.OPERAS -> it.copy(level = BrowseLevel.CATEGORIES, operas = emptyList(), selectedCategory = null)
                BrowseLevel.SEARCH_RESULTS -> it.copy(level = BrowseLevel.CATEGORIES, searchResults = emptyList(), searchQuery = "")
                else -> it
            }
        }
    }

    // ========== 搜索 ==========

    fun updateSearchQuery(query: String) {
        // 取消上一次搜索
        searchJob?.cancel()
        _uiState.update { it.copy(searchQuery = query) }
        if (query.length >= 2) {
            searchJob = viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                try {
                    val results = operaRepository.searchFiles(query)
                    _uiState.update { it.copy(searchResults = results, isLoading = false, level = BrowseLevel.SEARCH_RESULTS,
                        currentPlaylist = results, currentIndex = -1) }
                } catch (e: Exception) {
                    // 如果是取消异常，不显示错误
                    if (e is kotlinx.coroutines.CancellationException) {
                        _uiState.update { it.copy(isLoading = false) }
                    } else {
                        _uiState.update { it.copy(isLoading = false, error = e.message ?: "搜索失败") }
                    }
                }
            }
        } else {
            _uiState.update { it.copy(searchResults = emptyList(), currentPlaylist = emptyList(), currentIndex = -1, isLoading = false) }
        }
    }

    /** 取消正在进行的搜索 */
    fun cancelSearch() {
        searchJob?.cancel()
        webDavClient.cancelSearch()
        _uiState.update { it.copy(isLoading = false) }
    }

    // ========== 播放 ==========

    private fun initPlayer() {
        // DefaultDataSource.Factory 同时支持 file:// 和 http://，
        // 远程请求走带 Auth 的 HttpDataSource，本地文件走默认 FileDataSource
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to webDavClient.authHeader()))
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        exoPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _uiState.update { it.copy(isPlaying = playing) }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            val br = audioFormat?.bitrate ?: 0
                            _uiState.update { it.copy(playError = null, durationMs = duration.coerceAtLeast(0), bitrate = br) }
                        }
                        Player.STATE_ENDED -> {
                            _uiState.update { it.copy(isPlaying = false, positionMs = 0L) }
                            playNext()
                        }
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val code = error.errorCode
                    val msg = error.localizedMessage ?: "未知错误"
                    _uiState.update { it.copy(playError = "播放失败(code=$code): $msg") }
                }
            })
        }
    }

    fun playFile(file: OperaAudioFile, localPath: String? = null) {
        val player = exoPlayer ?: return
        val currentState = _uiState.value
        val index = currentState.currentPlaylist.indexOfFirst { it.fileId == file.fileId }
        viewModelScope.launch {
            _uiState.update { it.copy(currentFile = file, playError = null, currentIndex = index) }
            val uri = if (localPath != null) {
                android.net.Uri.fromFile(File(localPath))
            } else {
                val url = file.downloadUrl
                if (url == null) { _uiState.update { it.copy(playError = "获取播放地址失败") }; return@launch }
                android.net.Uri.parse(url)
            }
            val savedPosition = preferences.getOperaPlayPosition(file.fileId)
            val mediaItem = MediaItem.fromUri(uri)
            player.stop(); player.clearMediaItems(); player.setMediaItem(mediaItem)
            if (savedPosition > 0) player.seekTo(savedPosition)
            player.prepare(); player.play()
        }
    }

    /** 播放已下载的文件，同时设置播放列表为所有已下载文件 */
    fun playDownloadedFile(downloaded: DownloadedOpera) {
        val currentState = _uiState.value
        val playlist = currentState.localDownloads.map {
            OperaAudioFile(it.fileId, it.fileName, it.fileSize, it.categoryName, it.operaName, downloadUrl = null)
        }
        val index = playlist.indexOfFirst { it.fileId == downloaded.fileId }

        // 检查本地文件是否存在
        val localFile = java.io.File(downloaded.localPath)
        if (!localFile.exists() || localFile.length() == 0L) {
            _uiState.update { it.copy(currentPlaylist = playlist, currentIndex = index, playError = "下载文件不存在或已损坏，请重新下载") }
            return
        }

        val file = OperaAudioFile(downloaded.fileId, downloaded.fileName, downloaded.fileSize,
            downloaded.categoryName, downloaded.operaName, downloadUrl = null)
        _uiState.update { it.copy(currentPlaylist = playlist, currentIndex = index) }
        playFile(file, downloaded.localPath)
    }

    fun playNext() {
        val currentState = _uiState.value
        val playlist = currentState.currentPlaylist
        val idx = currentState.currentIndex
        if (playlist.isEmpty() || idx < 0 || idx >= playlist.size - 1) return
        val next = playlist[idx + 1]
        // 检查是否已下载
        val local = if (currentState.downloadedIds.contains(next.fileId)) {
            val downloads = currentState.localDownloads
            downloads.find { it.fileId == next.fileId }?.localPath
        } else null
        playFile(next, local)
    }

    fun playPrevious() {
        val currentState = _uiState.value
        val playlist = currentState.currentPlaylist
        val idx = currentState.currentIndex
        if (playlist.isEmpty() || idx <= 0) return
        val prev = playlist[idx - 1]
        val local = if (currentState.downloadedIds.contains(prev.fileId)) {
            currentState.localDownloads.find { it.fileId == prev.fileId }?.localPath
        } else null
        playFile(prev, local)
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) { player.pause()
            _uiState.value.currentFile?.let {
                viewModelScope.launch { preferences.saveOperaPlayPosition(it.fileId, player.currentPosition) }
            }
        } else player.play()
    }

    fun seekTo(positionMs: Long) { exoPlayer?.seekTo(positionMs); _uiState.update { it.copy(positionMs = positionMs) } }
    fun seekForward(seconds: Long = 15) { exoPlayer?.let { it.seekTo((it.currentPosition + seconds * 1000).coerceAtMost(it.duration)) } }
    fun seekBackward(seconds: Long = 15) { exoPlayer?.let { it.seekTo((it.currentPosition - seconds * 1000).coerceAtLeast(0)) } }

    fun retryPlayback() { _uiState.value.currentFile?.let { playFile(it) } }

    fun stopPlayback() {
        exoPlayer?.let { player ->
            _uiState.value.currentFile?.let {
                viewModelScope.launch { preferences.saveOperaPlayPosition(it.fileId, player.currentPosition) }
            }
            player.stop()
        }
        _uiState.update { it.copy(currentFile = null, isPlaying = false, positionMs = 0L, durationMs = 0L) }
    }

    // ========== 下载 ==========

    fun downloadFile(file: OperaAudioFile) {
        viewModelScope.launch {
            _uiState.update { it.copy(downloadProgress = it.downloadProgress + (file.fileId to 0)) }
            val saveDir = File(context.filesDir, "opera_downloads").also { it.mkdirs() }
            operaRepository.downloadFile(file, saveDir) { progress ->
                _uiState.update { it.copy(downloadProgress = it.downloadProgress + (file.fileId to progress)) }
            }.onSuccess {
                val downloaded = operaRepository.getAllDownloadedIds()
                _uiState.update { it.copy(downloadedIds = downloaded, downloadProgress = it.downloadProgress - file.fileId) }
                loadDownloaded()
            }.onFailure { e ->
                _uiState.update { it.copy(downloadProgress = it.downloadProgress - file.fileId, error = "下载失败: ${e.message?.take(60)}") }
            }
        }
    }

    fun deleteDownloaded(fileId: Long) {
        viewModelScope.launch {
            operaRepository.deleteDownloaded(fileId); loadDownloaded()
            _uiState.update { it.copy(downloadedIds = operaRepository.getAllDownloadedIds()) }
        }
    }

    private fun loadDownloaded() {
        viewModelScope.launch { operaRepository.allDownloaded.collect { list -> _uiState.update { it.copy(localDownloads = list) } } }
    }

    fun switchTab(index: Int) { _uiState.update { it.copy(tabIndex = index) } }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacks(positionUpdater)
        exoPlayer?.let { p ->
            _uiState.value.currentFile?.let { viewModelScope.launch { preferences.saveOperaPlayPosition(it.fileId, p.currentPosition) } }
            p.release()
        }
        exoPlayer = null
    }
}
