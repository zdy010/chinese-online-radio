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
    val durationMs: Long = 0L
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
                        it.copy(isConnecting = false, authError = "WebDAV连接失败: ${e.message?.take(60)}")
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
                    it.copy(categories = cats, isLoading = false, selectedCategory = null, selectedOpera = null)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载分类失败") }
            }
        }
    }

    fun selectCategory(category: OperaCategory) {
        val currentState = _uiState.value
        // 防止重复点击
        if (currentState.isLoading) return
        
        viewModelScope.launch {
            // 先进入加载状态，保持当前level不变，只显示loading
            _uiState.update { 
                it.copy(
                    isLoading = true, 
                    error = null,
                    // 不在这里改变 level 或 selectedCategory，避免触发错误的UI重组
                ) 
            }
            try {
                val operas = operaRepository.getOperas(category)
                
                // 如果分类下没有子文件夹（剧目），直接获取音频文件
                if (operas.isEmpty()) {
                    val files = operaRepository.getAudioFiles(category.name, OperaItem(name = "", folderId = 0, path = category.path))
                    val downloaded = operaRepository.getAllDownloadedIds()
                    // 一次性原子化更新所有相关状态
                    _uiState.update {
                        it.copy(
                            selectedCategory = category,
                            selectedOpera = null,
                            audioFiles = files, 
                            downloadedIds = downloaded, 
                            level = BrowseLevel.FILES, 
                            isLoading = false,
                            operas = emptyList()
                        )
                    }
                } else {
                    // 一次性原子化更新所有相关状态
                    _uiState.update { 
                        it.copy(
                            selectedCategory = category,
                            selectedOpera = null,
                            operas = operas, 
                            level = BrowseLevel.OPERAS, 
                            isLoading = false,
                            audioFiles = emptyList()
                        ) 
                    }
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false, 
                        error = e.message ?: "加载失败"
                        // 保持原来的level不变
                    ) 
                }
            }
        }
    }

    fun selectOpera(opera: OperaItem) {
        val currentState = _uiState.value
        // 防止重复点击
        if (currentState.isLoading) return
        
        viewModelScope.launch {
            // 先进入加载状态，不改变level或selectedOpera
            _uiState.update { 
                it.copy(
                    isLoading = true, 
                    error = null
                ) 
            }
            try {
                val categoryName = currentState.selectedCategory?.name ?: ""
                val files = operaRepository.getAudioFiles(categoryName, opera)
                val downloaded = operaRepository.getAllDownloadedIds()
                // 一次性原子化更新所有相关状态
                _uiState.update {
                    it.copy(
                        selectedOpera = opera,
                        audioFiles = files, 
                        downloadedIds = downloaded, 
                        level = BrowseLevel.FILES, 
                        isLoading = false,
                        operas = emptyList()
                    )
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false, 
                        error = e.message ?: "加载音频失败"
                        // 保持原来的level不变
                    ) 
                }
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
        _uiState.update { it.copy(searchQuery = query) }
        if (query.length >= 2) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                try {
                    val results = operaRepository.searchFiles(query)
                    _uiState.update { it.copy(searchResults = results, isLoading = false, level = BrowseLevel.SEARCH_RESULTS) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "搜索失败") }
                }
            }
        } else {
            _uiState.update { it.copy(searchResults = emptyList()) }
        }
    }

    // ========== 播放 ==========

    private fun initPlayer() {
        val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to webDavClient.authHeader()))
        exoPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _uiState.update { it.copy(isPlaying = playing) }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> _uiState.update { it.copy(playError = null, durationMs = duration.coerceAtLeast(0)) }
                        Player.STATE_ENDED -> _uiState.update { it.copy(isPlaying = false, positionMs = 0L) }
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    _uiState.update { it.copy(playError = error.localizedMessage ?: "播放失败") }
                }
            })
        }
    }

    fun playFile(file: OperaAudioFile, localPath: String? = null) {
        val player = exoPlayer ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(currentFile = file, playError = null) }
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
