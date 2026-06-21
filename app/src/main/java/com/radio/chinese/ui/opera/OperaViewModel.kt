package com.radio.chinese.ui.opera

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.domain.model.*
import com.radio.chinese.service.OperaRepository
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.service.WebDavClient
import com.radio.chinese.service.YunPanConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
    private val preferences: RadioPreferences,
    private val playerManager: PlayerManager
) : ViewModel() {

    private val _baseState = MutableStateFlow(OperaUiState())
    private val _downloadedFlow = operaRepository.allDownloaded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 将 PlayerManager 播放状态合并为一个 data class
    private data class PlayerState(
        val isOpera: Boolean = false,
        val operaFile: OperaAudioFile? = null,
        val playlist: List<OperaAudioFile> = emptyList(),
        val index: Int = -1,
        val position: Long = 0L,
        val duration: Long = 0L,
        val bitrate: Int = 0,
        val playing: Boolean = false,
        val error: String? = null
    )

    private val _playerState = MutableStateFlow(PlayerState())

    val uiState: StateFlow<OperaUiState> = combine(
        _baseState, _downloadedFlow, _playerState
    ) { base, downloaded, ps ->
        base.copy(
            localDownloads = downloaded,
            downloadedIds = downloaded.map { it.fileId }.toSet(),
            currentFile = if (ps.isOpera) ps.operaFile else null,
            currentPlaylist = if (ps.isOpera) ps.playlist else base.currentPlaylist,
            currentIndex = if (ps.isOpera) ps.index else -1,
            positionMs = if (ps.isOpera) ps.position else 0L,
            durationMs = if (ps.isOpera) ps.duration else 0L,
            bitrate = if (ps.isOpera) ps.bitrate else 0,
            isPlaying = ps.playing && ps.isOpera,
            playError = if (ps.isOpera) ps.error else null
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OperaUiState())

    private var searchJob: Job? = null

    init {
        // 订阅 PlayerManager 的播放状态
        viewModelScope.launch { playerManager.isOperaMode.collect { v -> _playerState.update { it.copy(isOpera = v) } } }
        viewModelScope.launch { playerManager.operaFile.collect { v -> _playerState.update { it.copy(operaFile = v) } } }
        viewModelScope.launch { playerManager.operaPlaylist.collect { v -> _playerState.update { it.copy(playlist = v) } } }
        viewModelScope.launch { playerManager.operaIndex.collect { v -> _playerState.update { it.copy(index = v) } } }
        viewModelScope.launch { playerManager.operaPosition.collect { v -> _playerState.update { it.copy(position = v) } } }
        viewModelScope.launch { playerManager.operaDuration.collect { v -> _playerState.update { it.copy(duration = v) } } }
        viewModelScope.launch { playerManager.operaBitrate.collect { v -> _playerState.update { it.copy(bitrate = v) } } }
        viewModelScope.launch { playerManager.isPlaying.collect { v -> _playerState.update { it.copy(playing = v) } } }
        viewModelScope.launch { playerManager.error.collect { v -> _playerState.update { it.copy(error = v) } } }

        // 启动时检查是否已验证过授权码
        viewModelScope.launch {
            val verified = preferences.operaAuthVerified.first()
            if (verified) {
                autoConnect()
            }
        }
    }

    /** 使用已保存的凭证自动连接 */
    private fun autoConnect() {
        viewModelScope.launch {
            _baseState.update { it.copy(isConnecting = true, authError = null) }
            // 先尝试使用保存的WebDAV凭证
            val savedServerUrl = preferences.webDavServerUrl.first()
            val savedUsername = preferences.webDavUsername.first()
            val savedPassword = preferences.webDavPassword.first()
            if (savedServerUrl.isNotBlank() && savedUsername.isNotBlank()) {
                operaRepository.setCredentials(savedServerUrl, savedUsername, savedPassword)
            } else {
                // 使用默认凭证
                operaRepository.setCredentials(
                    YunPanConfig.WEBDAV_SERVER_URL,
                    YunPanConfig.WEBDAV_USERNAME,
                    YunPanConfig.WEBDAV_PASSWORD
                )
            }
            val result = operaRepository.verifyConnection()
            result.fold(
                onSuccess = {
                    _baseState.update { it.copy(isConnecting = false, needAuth = false, isConnected = true) }
                    loadCategories()
                },
                onFailure = { e ->
                    // 连接失败，清除验证状态，重新显示授权码输入
                    preferences.setOperaAuthVerified(false)
                    _baseState.update {
                        it.copy(
                            isConnecting = false,
                            needAuth = true,
                            authError = "WebDAV连接失败: ${e.message ?: "未知错误"}"
                        )
                    }
                }
            )
        }
    }

    // ========== 授权码验证 ==========

    fun submitAuthCode(code: String) {
        if (code != YunPanConfig.AUTH_CODE) {
            _baseState.update { it.copy(authError = "授权码错误") }
            return
        }
        _baseState.update { it.copy(isConnecting = true, authError = null) }

        viewModelScope.launch {
            operaRepository.setCredentials(
                YunPanConfig.WEBDAV_SERVER_URL,
                YunPanConfig.WEBDAV_USERNAME,
                YunPanConfig.WEBDAV_PASSWORD
            )
            // 保存WebDAV凭证到DataStore
            preferences.saveWebDavCredentials(
                YunPanConfig.WEBDAV_SERVER_URL,
                YunPanConfig.WEBDAV_USERNAME,
                YunPanConfig.WEBDAV_PASSWORD
            )
            val result = operaRepository.verifyConnection()
            result.fold(
                onSuccess = {
                    // 保存授权码验证状态
                    preferences.setOperaAuthVerified(true)
                    _baseState.update { it.copy(isConnecting = false, needAuth = false, isConnected = true) }
                    loadCategories()
                },
                onFailure = { e ->
                    _baseState.update {
                        it.copy(isConnecting = false, authError = "WebDAV连接失败: ${e.message ?: "未知错误"}")
                    }
                }
            )
        }
    }

    // ========== 浏览 ==========

    fun loadCategories() {
        viewModelScope.launch {
            _baseState.update { it.copy(isLoading = true, error = null, level = BrowseLevel.CATEGORIES) }
            try {
                val cats = operaRepository.getCategories()
                _baseState.update {
                    it.copy(categories = cats, isLoading = false, selectedCategory = null, selectedOpera = null,
                        currentPlaylist = emptyList(), currentIndex = -1)
                }
            } catch (e: Exception) {
                _baseState.update { it.copy(isLoading = false, error = e.message ?: "加载分类失败") }
            }
        }
    }

    fun selectCategory(category: OperaCategory) {
        val currentState = uiState.value
        if (currentState.isLoading) return
        
        viewModelScope.launch {
            _baseState.update { it.copy(isLoading = true, error = null) }
            try {
                val operas = operaRepository.getOperas(category)
                if (operas.isEmpty()) {
                    val files = operaRepository.getAudioFiles(category.name, OperaItem(name = "", folderId = 0, path = category.path))
                    _baseState.update {
                        it.copy(
                            selectedCategory = category, selectedOpera = null,
                            audioFiles = files,
                            level = BrowseLevel.FILES, isLoading = false, operas = emptyList(),
                            currentPlaylist = files, currentIndex = -1
                        )
                    }
                } else {
                    _baseState.update {
                        it.copy(
                            selectedCategory = category, selectedOpera = null,
                            operas = operas, level = BrowseLevel.OPERAS,
                            isLoading = false, audioFiles = emptyList(),
                            currentPlaylist = emptyList(), currentIndex = -1
                        )
                    }
                }
            } catch (e: Exception) {
                _baseState.update { it.copy(isLoading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun selectOpera(opera: OperaItem) {
        val currentState = uiState.value
        if (currentState.isLoading) return
        
        viewModelScope.launch {
            _baseState.update { it.copy(isLoading = true, error = null) }
            try {
                val categoryName = currentState.selectedCategory?.name ?: ""
                val files = operaRepository.getAudioFiles(categoryName, opera)
                _baseState.update {
                    it.copy(
                        selectedOpera = opera, audioFiles = files,
                        level = BrowseLevel.FILES,
                        isLoading = false, operas = emptyList(),
                        currentPlaylist = files, currentIndex = -1
                    )
                }
            } catch (e: Exception) {
                _baseState.update { it.copy(isLoading = false, error = e.message ?: "加载音频失败") }
            }
        }
    }

    fun goBack() {
        val current = uiState.value.level
        _baseState.update {
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
        _baseState.update { it.copy(searchQuery = query) }
        if (query.length >= 2) {
            searchJob = viewModelScope.launch {
                _baseState.update { it.copy(isLoading = true) }
                try {
                    val results = operaRepository.searchFiles(query)
                    _baseState.update { it.copy(searchResults = results, isLoading = false, level = BrowseLevel.SEARCH_RESULTS,
                        currentPlaylist = results, currentIndex = -1) }
                } catch (e: Exception) {
                    // 如果是取消异常，不显示错误
                    if (e is kotlinx.coroutines.CancellationException) {
                        _baseState.update { it.copy(isLoading = false) }
                    } else {
                        _baseState.update { it.copy(isLoading = false, error = e.message ?: "搜索失败") }
                    }
                }
            }
        } else {
            _baseState.update { it.copy(searchResults = emptyList(), currentPlaylist = emptyList(), currentIndex = -1, isLoading = false) }
        }
    }

    /** 取消正在进行的搜索 */
    fun cancelSearch() {
        searchJob?.cancel()
        _baseState.update { it.copy(isLoading = false) }
    }

    // ========== 播放（委托给 PlayerManager）==========

    fun playFile(file: OperaAudioFile, localPath: String? = null) {
        val playlist = uiState.value.currentPlaylist.ifEmpty { listOf(file) }
        playerManager.playOperaFile(file, playlist, localPath)
    }

    /** 播放已下载的文件，播放列表设为所有已下载文件 */
    fun playDownloadedFile(downloaded: DownloadedOpera) {
        val playlist = uiState.value.localDownloads.map {
            OperaAudioFile(it.fileId, it.fileName, it.fileSize, it.categoryName, it.operaName, downloadUrl = null)
        }
        val localFile = java.io.File(downloaded.localPath)
        if (!localFile.exists() || localFile.length() == 0L) {
            _baseState.update { it.copy(playError = "下载文件不存在或已损坏，请重新下载") }
            return
        }
        val file = OperaAudioFile(downloaded.fileId, downloaded.fileName, downloaded.fileSize,
            downloaded.categoryName, downloaded.operaName, downloadUrl = null)
        playerManager.playOperaFile(file, playlist, downloaded.localPath)
    }

    fun playNext() { playerManager.playOperaNext() }
    fun playPrevious() { playerManager.playOperaPrevious() }
    fun togglePlayPause() { playerManager.togglePlayPause() }
    fun seekTo(positionMs: Long) { playerManager.seekOperaTo(positionMs) }
    fun seekForward(seconds: Long = 15) { playerManager.seekOperaForward(seconds) }
    fun seekBackward(seconds: Long = 15) { playerManager.seekOperaBackward(seconds) }
    fun retryPlayback() { playerManager.playOperaFile(uiState.value.currentFile ?: return, uiState.value.currentPlaylist) }

    fun stopPlayback() {
        playerManager.stopOpera()
    }

    // ========== 下载 ==========

    fun downloadFile(file: OperaAudioFile) {
        viewModelScope.launch {
            _baseState.update { it.copy(downloadProgress = it.downloadProgress + (file.fileId to 0)) }
            val saveDir = File(context.filesDir, "opera_downloads").also { it.mkdirs() }
            operaRepository.downloadFile(file, saveDir) { progress ->
                _baseState.update { it.copy(downloadProgress = it.downloadProgress + (file.fileId to progress)) }
            }.onSuccess {
                _baseState.update { it.copy(downloadProgress = it.downloadProgress - file.fileId) }
            }.onFailure { e ->
                _baseState.update { it.copy(downloadProgress = it.downloadProgress - file.fileId, error = "下载失败: ${e.message?.take(60)}") }
            }
        }
    }

    fun deleteDownloaded(fileId: Long) {
        viewModelScope.launch {
            operaRepository.deleteDownloaded(fileId)
        }
    }

    fun cancelDownload(fileId: Long) {
        operaRepository.cancelDownload(fileId)
        _baseState.update { it.copy(downloadProgress = it.downloadProgress - fileId) }
    }

    fun switchTab(index: Int) { _baseState.update { it.copy(tabIndex = index) } }

    override fun onCleared() {
        super.onCleared()
    }
}
