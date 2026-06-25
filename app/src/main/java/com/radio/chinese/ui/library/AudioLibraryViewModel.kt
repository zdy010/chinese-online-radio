package com.radio.chinese.ui.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.entity.AudioFavoriteEntity
import com.radio.chinese.data.entity.AudioRecentEntity
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
    val playError: String? = null,

    // Favorites & Recent
    val favorites: List<AudioFavoriteEntity> = emptyList(),
    val recentPlays: List<AudioRecentEntity> = emptyList(),
    val showFavorites: Boolean = false
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
        // 订阅收藏与最近播放
        viewModelScope.launch { repository.favorites.collect { favs -> _state.update { s -> s.copy(favorites = favs) } } }
        viewModelScope.launch { repository.recentPlays.collect { recents -> _state.update { s -> s.copy(recentPlays = recents) } } }
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
        // 解析隐藏快捷码
        val (finalName, finalType, finalUrl, finalUsername, finalPassword) =
            resolveShortcut(name, type, url, username, password)

        // 本地存储需要运行时权限
        if (finalType == SourceType.LOCAL) {
            val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                _state.update { it.copy(isLoading = false, error = "请先授予「媒体音频」权限") }
                return
            }
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val browser = createBrowser(finalName, finalType, finalUrl, finalUsername, finalPassword)
                val result = browser.testConnection()
                if (result.isFailure) {
                    _state.update { it.copy(isLoading = false, error = "连接失败: ${result.exceptionOrNull()?.message}") }
                    return@launch
                }
                repository.saveSource(finalName, finalType, finalUrl, finalUsername, finalPassword)
                _state.update { it.copy(isLoading = false, showAddDialog = false, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "添加失败") }
            }
        }
    }

    private data class ShortcutRes(val name: String, val type: SourceType, val url: String, val username: String, val password: String)

    private fun resolveShortcut(name: String, type: SourceType, url: String, username: String, password: String): ShortcutRes {
        if (type != SourceType.WEBDAV) return ShortcutRes(name, type, url, username, password)
        return when (url.trim()) {
            "138265275541" -> ShortcutRes("123云盘·豫剧", SourceType.WEBDAV, "https://webdav.123pan.cn/webdav", "13826527554", "a5vp9lqi")
            "138265275542" -> ShortcutRes("123云盘", SourceType.WEBDAV, "https://webdav.123pan.cn/webdav", "13826527554", "gw7ym269")
            else -> ShortcutRes(name, type, url, username, password)
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
        playerManager.playOperaFile(file, emptyList())
        _state.update { it.copy(currentTrack = track) }
        // 记录最近播放
        viewModelScope.launch {
            repository.recordPlay(source.id, track.path, track.name, source.name)
        }
    }

    fun toggleFavorite(trackPath: String, trackName: String, sourceName: String) {
        val source = _state.value.browsingSource ?: return
        viewModelScope.launch {
            repository.toggleFavorite(source.id, trackPath, trackName, sourceName)
        }
    }

    fun isFavorited(trackPath: String): Boolean {
        return _state.value.favorites.any { it.trackPath == trackPath }
    }

    fun showFavorites() = _state.update { it.copy(showFavorites = true) }
    fun hideFavorites() = _state.update { it.copy(showFavorites = false) }

    fun playFavorite(fav: AudioFavoriteEntity) {
        viewModelScope.launch {
            val source = repository.getSource(fav.sourceId) ?: return@launch
            val track = AudioTrack(
                id = fav.trackPath.hashCode().toString(), name = fav.trackName,
                path = fav.trackPath, sourceId = fav.sourceId
            )
            playTrackWithSource(track, source)
        }
    }

    fun playRecent(recent: AudioRecentEntity) {
        viewModelScope.launch {
            val source = repository.getSource(recent.sourceId) ?: return@launch
            val track = AudioTrack(
                id = recent.trackPath.hashCode().toString(), name = recent.trackName,
                path = recent.trackPath, sourceId = recent.sourceId
            )
            playTrackWithSource(track, source)
        }
    }

    private fun playTrackWithSource(track: AudioTrack, source: AudioSource) {
        val browser = createBrowser(source)
        val uri = browser.resolveUri(track)
        playerManager.playOperaFile(
            com.radio.chinese.domain.model.OperaAudioFile(
                fileId = track.path.hashCode().toLong(), name = track.name, size = 0,
                categoryName = source.name, operaName = "", downloadUrl = uri
            ), emptyList()
        )
        _state.update { it.copy(currentTrack = track, browsingSource = source) }
        viewModelScope.launch {
            repository.recordPlay(source.id, track.path, track.name, source.name)
        }
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
