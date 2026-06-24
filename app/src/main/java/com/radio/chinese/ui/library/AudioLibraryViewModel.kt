package com.radio.chinese.ui.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.repository.AudioLibraryRepository
import com.radio.chinese.domain.*
import com.radio.chinese.domain.browser.*
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.service.WebDavClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

data class LibraryUiState(
    val sources: List<AudioSource> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showAddDialog: Boolean = false,

    // Browse state
    val browsingSource: AudioSource? = null,
    val browsePath: String = "",
    val browseItems: List<AudioTrack> = emptyList(),
    val browseHistory: List<String> = emptyList(),

    // Player state (from PlayerManager)
    val currentTrack: AudioTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playError: String? = null
)

@HiltViewModel
class AudioLibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AudioLibraryRepository,
    private val webDavClient: WebDavClient,
    private val okHttpClient: OkHttpClient,
    private val playerManager: PlayerManager
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        loadSources()
        // 订阅 PlayerManager 状态
        viewModelScope.launch { playerManager.isOperaMode.collect { isOpera ->
            if (!isOpera) _state.update { it.copy(isPlaying = false, currentTrack = null, positionMs = 0L, durationMs = 0L, playError = null) }
        } }
        viewModelScope.launch { playerManager.isPlaying.collect { v -> _state.update { it.copy(isPlaying = v) } } }
        viewModelScope.launch { playerManager.operaPosition.collect { v -> _state.update { it.copy(positionMs = v) } } }
        viewModelScope.launch { playerManager.operaDuration.collect { v -> _state.update { it.copy(durationMs = v) } } }
        viewModelScope.launch { playerManager.error.collect { v -> _state.update { it.copy(playError = v) } } }
    }

    // ========== 音频源管理 ==========

    fun loadSources() {
        viewModelScope.launch {
            repository.allSources.collect { sources ->
                _state.update { it.copy(sources = sources) }
            }
        }
    }

    fun showAddDialog() = _state.update { it.copy(showAddDialog = true) }
    fun hideAddDialog() = _state.update { it.copy(showAddDialog = false, error = null) }

    fun addSource(name: String, type: SourceType, url: String, username: String, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                // 测试连接
                val browser = createBrowser(name, type, url, username, password)
                val result = browser.testConnection()
                if (result.isFailure) {
                    _state.update { it.copy(isLoading = false, error = "连接失败: ${result.exceptionOrNull()?.message}") }
                    return@launch
                }
                repository.saveSource(name, type, url, username, password)
                _state.update { it.copy(isLoading = false, showAddDialog = false, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "添加失败") }
            }
        }
    }

    fun deleteSource(id: Long) {
        viewModelScope.launch { repository.deleteSource(id) }
    }

    // ========== 浏览 ==========

    fun browseSource(source: AudioSource) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, browsingSource = source, browsePath = "", browseHistory = emptyList()) }
            val browser = createBrowser(source)
            repository.refreshSource(source, "", browser)
                .onSuccess { items ->
                    _state.update { it.copy(isLoading = false, browseItems = items) }
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun browseFolder(path: String) {
        val source = _state.value.browsingSource ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, browsePath = path, browseHistory = it.browseHistory + path) }
            val browser = createBrowser(source)
            repository.listChildren(source, path, browser)
                .onSuccess { items ->
                    _state.update { it.copy(isLoading = false, browseItems = items) }
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun browseBack() {
        val history = _state.value.browseHistory
        if (history.isEmpty()) {
            // 回到来源列表
            _state.update { it.copy(browsingSource = null, browseItems = emptyList(), browsePath = "", browseHistory = emptyList()) }
            return
        }
        val prev = history.dropLast(1)
        val parentPath = prev.lastOrNull() ?: ""
        val source = _state.value.browsingSource ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, browseHistory = prev, browsePath = parentPath) }
            val browser = createBrowser(source)
            repository.listChildren(source, parentPath, browser)
                .onSuccess { items ->
                    _state.update { it.copy(isLoading = false, browseItems = items) }
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun refreshBrowse() {
        val source = _state.value.browsingSource ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val browser = createBrowser(source)
            repository.refreshSource(source, _state.value.browsePath, browser)
                .onSuccess { items ->
                    _state.update { it.copy(isLoading = false, browseItems = items, error = null) }
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    // ========== 播放 ==========

    fun playTrack(track: AudioTrack) {
        val source = _state.value.browsingSource ?: return
        val browser = createBrowser(source)
        val uri = browser.resolveUri(track)
        val file = com.radio.chinese.domain.model.OperaAudioFile(
            fileId = track.path.hashCode().toLong(),
            name = track.name,
            size = track.size,
            categoryName = source.name,
            operaName = track.parentPath.substringAfterLast("/"),
            downloadUrl = uri
        )
        // 直接用现有 PlayerManager 的 opera 播放能力
        playerManager.playOperaFile(file, emptyList())
        _state.update { it.copy(currentTrack = track) }
    }

    fun togglePlayPause() = playerManager.togglePlayPause()
    fun stopPlayback() = playerManager.stopOpera()

    // ========== 工厂方法 ==========

    private fun createBrowser(source: AudioSource): AudioBrowser = createBrowser(
        source.name, source.type, source.url, source.username, source.password
    )

    private fun createBrowser(name: String, type: SourceType, url: String, username: String, password: String): AudioBrowser {
        return when (type) {
            SourceType.WEBDAV -> WebDavBrowser(webDavClient, url, username, password)
            SourceType.LOCAL -> LocalBrowser(context)
            SourceType.M3U -> M3uBrowser(okHttpClient)
            SourceType.HTTP -> HttpBrowser(okHttpClient)
        }
    }
}
