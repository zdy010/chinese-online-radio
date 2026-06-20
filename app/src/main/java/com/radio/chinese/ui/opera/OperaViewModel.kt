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

enum class BrowseLevel { CATEGORIES, OPERAS, FILES }

data class OperaUiState(
    val level: BrowseLevel = BrowseLevel.CATEGORIES,
    val categories: List<OperaCategory> = emptyList(),
    val operas: List<OperaItem> = emptyList(),
    val audioFiles: List<OperaAudioFile> = emptyList(),
    val selectedCategory: OperaCategory? = null,
    val selectedOpera: OperaItem? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val downloadedIds: Set<Long> = emptySet(),
    val downloadProgress: Map<Long, Int> = emptyMap(),
    val currentFile: OperaAudioFile? = null,
    val isPlaying: Boolean = false,
    val playError: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val tabIndex: Int = 0,
    val localDownloads: List<DownloadedOpera> = emptyList(),
    val needPassword: Boolean = true,
    val hasPassword: Boolean = false
)

@HiltViewModel
class OperaViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val operaRepository: OperaRepository,
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
                // 每10秒保存一次位置到本地
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
        loadSavedPassword()
        loadDownloaded()
        initPlayer()
        handler.post(positionUpdater)
    }

    private fun loadSavedPassword() {
        viewModelScope.launch {
            val pwd = preferences.operaSharePassword.first()
            if (pwd.isNotEmpty()) {
                operaRepository.sharePassword = pwd
                _uiState.update { it.copy(isLoading = true) }
                try {
                    val cats = operaRepository.getCategories()
                    _uiState.update {
                        it.copy(
                            categories = cats, level = BrowseLevel.CATEGORIES,
                            isLoading = false, needPassword = false, hasPassword = true,
                            selectedCategory = null, selectedOpera = null
                        )
                    }
                } catch (e: Exception) {
                    // 保存的密码失效，清空并重新显示密码输入
                    preferences.saveOperaSharePassword("")
                    operaRepository.sharePassword = ""
                    _uiState.update {
                        it.copy(isLoading = false, needPassword = true, hasPassword = false)
                    }
                }
            } else {
                _uiState.update { it.copy(needPassword = true, hasPassword = false) }
            }
        }
    }

    /** 用户提交提取码 */
    fun submitPassword(password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            operaRepository.sharePassword = password
            try {
                val cats = operaRepository.getCategories()
                // 只有成功后保存密码并隐藏输入
                preferences.saveOperaSharePassword(password)
                _uiState.update {
                    it.copy(
                        categories = cats, level = BrowseLevel.CATEGORIES,
                        isLoading = false, needPassword = false, hasPassword = true,
                        selectedCategory = null, selectedOpera = null
                    )
                }
            } catch (e: Exception) {
                operaRepository.sharePassword = ""
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "加载失败: ${e.message?.take(60)}",
                        needPassword = true,   // 保持显示密码输入
                        hasPassword = false
                    )
                }
            }
        }
    }

    private fun initPlayer() {
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _uiState.update { it.copy(isPlaying = playing) }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            _uiState.update {
                                it.copy(
                                    playError = null,
                                    durationMs = duration.coerceAtLeast(0)
                                )
                            }
                        }
                        Player.STATE_BUFFERING -> {
                            // 保持当前 isPlaying 状态
                        }
                        Player.STATE_ENDED -> {
                            _uiState.update { it.copy(isPlaying = false, positionMs = 0L) }
                        }
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    _uiState.update {
                        it.copy(playError = error.localizedMessage ?: "播放失败")
                    }
                }
            })
        }
    }

    // ========== 浏览 ==========

    fun loadCategories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val cats = operaRepository.getCategories()
                _uiState.update {
                    it.copy(
                        categories = cats, level = BrowseLevel.CATEGORIES,
                        isLoading = false, selectedCategory = null, selectedOpera = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun selectCategory(category: OperaCategory) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedCategory = category) }
            try {
                val operas = operaRepository.getOperas(category.folderId)
                _uiState.update {
                    it.copy(operas = operas, level = BrowseLevel.OPERAS, isLoading = false)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun selectOpera(category: OperaCategory, opera: OperaItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedOpera = opera) }
            try {
                val files = operaRepository.getAudioFiles(
                    category.name, opera.name, opera.folderId
                )
                val downloaded = operaRepository.getAllDownloadedIds()
                _uiState.update {
                    it.copy(
                        audioFiles = files, downloadedIds = downloaded,
                        level = BrowseLevel.FILES, isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun goBack() {
        val current = _uiState.value.level
        _uiState.update {
            when (current) {
                BrowseLevel.FILES -> it.copy(level = BrowseLevel.OPERAS, audioFiles = emptyList())
                BrowseLevel.OPERAS -> it.copy(level = BrowseLevel.CATEGORIES, operas = emptyList(), selectedCategory = null)
                else -> it
            }
        }
    }

    // ========== 播放 ==========

    fun playFile(file: OperaAudioFile, localPath: String? = null) {
        val player = exoPlayer ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(currentFile = file, playError = null) }

            // 获取播放URL（在线）或本地路径
            val uri = if (localPath != null) {
                android.net.Uri.fromFile(File(localPath))
            } else {
                try {
                    val url = operaRepository.getDownloadUrl(
                        file.fileId,
                        etag = file.etag,
                        s3KeyFlag = file.s3KeyFlag,
                        size = file.size
                    )
                    if (url == null) {
                        _uiState.update { it.copy(playError = "获取播放地址失败") }
                        return@launch
                    }
                    android.net.Uri.parse(url)
                } catch (e: Exception) {
                    _uiState.update { it.copy(playError = "获取播放地址失败: ${e.message?.take(50)}") }
                    return@launch
                }
            }

            // 恢复播放位置
            val savedPosition = preferences.getOperaPlayPosition(file.fileId)

            val mediaItem = MediaItem.fromUri(uri)
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(mediaItem)

            if (savedPosition > 0) {
                player.seekTo(savedPosition)
            }

            player.prepare()
            player.play()
        }
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            // 保存当前位置
            val file = _uiState.value.currentFile
            if (file != null) {
                viewModelScope.launch {
                    preferences.saveOperaPlayPosition(file.fileId, player.currentPosition)
                }
            }
        } else {
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        _uiState.update { it.copy(positionMs = positionMs) }
    }

    fun seekForward(seconds: Long = 15) {
        val player = exoPlayer ?: return
        val newPos = (player.currentPosition + seconds * 1000).coerceAtMost(player.duration)
        player.seekTo(newPos)
    }

    fun seekBackward(seconds: Long = 15) {
        val player = exoPlayer ?: return
        val newPos = (player.currentPosition - seconds * 1000).coerceAtLeast(0)
        player.seekTo(newPos)
    }

    /** 重新获取URL并重试播放，用于URL过期场景 */
    fun retryPlayback() {
        val file = _uiState.value.currentFile ?: return
        playFile(file)
    }

    fun stopPlayback() {
        val player = exoPlayer ?: return
        // 保存当前位置
        val file = _uiState.value.currentFile
        if (file != null) {
            viewModelScope.launch {
                preferences.saveOperaPlayPosition(file.fileId, player.currentPosition)
            }
        }
        player.stop()
        _uiState.update {
            it.copy(currentFile = null, isPlaying = false, positionMs = 0L, durationMs = 0L)
        }
    }

    // ========== 下载 ==========

    fun downloadFile(file: OperaAudioFile) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(downloadProgress = it.downloadProgress + (file.fileId to 0))
            }
            val saveDir = File(context.filesDir, "opera_downloads").also { it.mkdirs() }
            operaRepository.downloadFile(file, saveDir) { progress ->
                _uiState.update {
                    it.copy(downloadProgress = it.downloadProgress + (file.fileId to progress))
                }
            }.onSuccess {
                val downloaded = operaRepository.getAllDownloadedIds()
                _uiState.update {
                    it.copy(
                        downloadedIds = downloaded,
                        downloadProgress = it.downloadProgress - file.fileId
                    )
                }
                loadDownloaded()
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        downloadProgress = it.downloadProgress - file.fileId,
                        error = "下载失败: ${e.message?.take(60)}"
                    )
                }
            }
        }
    }

    fun deleteDownloaded(fileId: Long) {
        viewModelScope.launch {
            operaRepository.deleteDownloaded(fileId)
            loadDownloaded()
            val downloaded = operaRepository.getAllDownloadedIds()
            _uiState.update { it.copy(downloadedIds = downloaded) }
        }
    }

    private fun loadDownloaded() {
        viewModelScope.launch {
            operaRepository.allDownloaded.collect { list ->
                _uiState.update { it.copy(localDownloads = list) }
            }
        }
    }

    fun switchTab(index: Int) {
        _uiState.update { it.copy(tabIndex = index) }
    }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacks(positionUpdater)
        // 最后保存一次播放位置
        val player = exoPlayer
        val file = _uiState.value.currentFile
        if (player != null && file != null) {
            viewModelScope.launch {
                preferences.saveOperaPlayPosition(file.fileId, player.currentPosition)
            }
        }
        player?.release()
        exoPlayer = null
    }
}
