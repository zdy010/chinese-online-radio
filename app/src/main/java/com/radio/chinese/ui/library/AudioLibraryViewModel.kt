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
    val showBrowseContent: Boolean = false,
    val browsePath: String = "",
    val browseItems: List<AudioTrack> = emptyList(),
    val browseHistory: List<String> = emptyList(),

    // Player state (from PlayerManager)
    val currentTrack: AudioTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bitrateBps: Int = 0,
    val playError: String? = null,
    val repeatMode: RepeatMode = RepeatMode.ALL,
    val playlistIndex: Int = -1,

    // Favorites & Recent
    val favorites: List<AudioFavoriteEntity> = emptyList(),
    val recentPlays: List<AudioRecentEntity> = emptyList(),
    val showFavorites: Boolean = false,
    val searchQuery: String = ""
)

enum class RepeatMode { ALL, ONE, RANDOM }

private fun RepeatMode.toService(): com.radio.chinese.service.OperaRepeatMode = when (this) {
    RepeatMode.ALL -> com.radio.chinese.service.OperaRepeatMode.ALL
    RepeatMode.ONE -> com.radio.chinese.service.OperaRepeatMode.ONE
    RepeatMode.RANDOM -> com.radio.chinese.service.OperaRepeatMode.RANDOM
}

private fun com.radio.chinese.service.OperaRepeatMode.toUi(): RepeatMode = when (this) {
    com.radio.chinese.service.OperaRepeatMode.ALL -> RepeatMode.ALL
    com.radio.chinese.service.OperaRepeatMode.ONE -> RepeatMode.ONE
    com.radio.chinese.service.OperaRepeatMode.RANDOM -> RepeatMode.RANDOM
}

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
        viewModelScope.launch { playerManager.operaBitrate.collect { v -> _state.update { it.copy(bitrateBps = v) } } }
        viewModelScope.launch { playerManager.operaFile.collect { file ->
            if (file != null) _state.update { it.copy(currentTrack = it.currentTrack?.copy(name = file.name)) }
        } }
        viewModelScope.launch { playerManager.error.collect { v -> _state.update { it.copy(playError = v) } } }
        // 循环模式的单一事实源在 PlayerManager（它才能真的影响下一首）
        viewModelScope.launch {
            playerManager.operaRepeatMode.collect { mode -> _state.update { it.copy(repeatMode = mode.toUi()) } }
        }
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

    fun updateSearchQuery(q: String) = _state.update { it.copy(searchQuery = q) }

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
        val entry = com.radio.chinese.config.EasterEggConfig.QUICK_CODES[url.trim()]
        return if (entry != null) {
            ShortcutRes(entry.name, entry.type, entry.url, entry.username, entry.password)
        } else {
            ShortcutRes(name, type, url, username, password)
        }
    }

    fun deleteSource(id: Long) {
        viewModelScope.launch { repository.deleteSource(id) }
    }

    // ========== 浏览 ==========

    fun browseSource(source: AudioSource) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, browsingSource = source, showBrowseContent = true, browsePath = "", browseHistory = emptyList()) }
            val browser = createBrowser(source)
            repository.refreshDir(source, "", browser)
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
            _state.update { it.copy(browsingSource = null, showBrowseContent = false, browseItems = emptyList(), browsePath = "", browseHistory = emptyList()) }
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
            repository.refreshDir(source, _state.value.browsePath, browser)
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

        // 构建播放列表（当前浏览目录中的非文件夹文件）
        val playlist = _state.value.browseItems.filter { !it.isFolder }.map { item ->
            com.radio.chinese.domain.model.OperaAudioFile(
                fileId = item.id.toLongOrNull() ?: item.path.hashCode().toLong(),
                name = item.name,
                size = item.size,
                categoryName = source.name,
                operaName = item.parentPath.substringAfterLast("/"),
                downloadUrl = if (item.id == track.id) uri else browser.resolveUri(item),
                sourcePath = item.path
            )
        }
        val index = playlist.indexOfFirst { it.fileId.toString() == track.id || it.name == track.name }
        val file = playlist.getOrElse(index) {
            com.radio.chinese.domain.model.OperaAudioFile(
                fileId = track.path.hashCode().toLong(),
                name = track.name,
                size = track.size,
                categoryName = source.name,
                operaName = track.parentPath.substringAfterLast("/"),
                downloadUrl = uri,
                sourcePath = track.path
            )
        }
        playerManager.playOperaFile(file, playlist, context = com.radio.chinese.service.OperaPlayContext(source.id, track.path))
        _state.update { it.copy(currentTrack = track, playlistIndex = index.coerceAtLeast(0)) }
        viewModelScope.launch {
            repository.recordPlay(source.id, track.path, track.name, source.name)
        }
    }

    fun seekTo(posMs: Long) = playerManager.seekOperaTo(posMs)
    fun playNext() = playerManager.playOperaNext()
    fun playPrevious() = playerManager.playOperaPrevious()

    /**
     * 收藏/取消收藏。
     *
     * 旧实现从 browsingSource 取 sourceId，在收藏页里它是 null，
     * 导致收藏页的删除按钮静默失效，因此改为由调用方传入真正的 sourceId。
     */
    fun toggleFavorite(sourceId: Long, trackPath: String, trackName: String, sourceName: String) {
        if (sourceId <= 0L) {
            _state.update { it.copy(playError = "这条收藏缺少来源信息，无法取消，请重新添加收藏") }
            return
        }
        // 乐观更新 UI
        val isNowFav = !_state.value.favorites.any { it.trackPath == trackPath }
        _state.update { s ->
            val newFavs = if (isNowFav)
                s.favorites + AudioFavoriteEntity(trackPath = trackPath, trackName = trackName, sourceName = sourceName, sourceId = sourceId)
            else
                s.favorites.filter { it.trackPath != trackPath }
            s.copy(favorites = newFavs)
        }
        viewModelScope.launch {
            repository.toggleFavorite(sourceId, trackPath, trackName, sourceName)
        }
    }

    /** 从收藏列表项直接取消，用收藏自带的 sourceId。 */
    fun removeFavorite(fav: AudioFavoriteEntity) {
        toggleFavorite(fav.sourceId, fav.trackPath, fav.trackName, fav.sourceName)
    }

    fun cycleRepeatMode() {
        playerManager.cycleOperaRepeatMode()
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
                categoryName = source.name, operaName = "", downloadUrl = uri, sourcePath = track.path
            ), emptyList(), context = com.radio.chinese.service.OperaPlayContext(source.id, track.path)
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
