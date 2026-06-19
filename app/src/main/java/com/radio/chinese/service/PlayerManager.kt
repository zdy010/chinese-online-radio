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
import com.radio.chinese.domain.model.RadioStation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: RadioPreferences
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _currentStation = MutableStateFlow<RadioStation?>(null)
    val currentStation: StateFlow<RadioStation?> = _currentStation

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    fun connect() {
        val sessionToken = SessionToken(context, ComponentName(context, RadioService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                _isConnected.value = true
                setupListeners()
            } catch (e: Exception) {
                _isConnected.value = false
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupListeners() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Station changed - will be updated via playStation
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> _isPlaying.value = false
                    Player.STATE_READY -> _isPlaying.value = controller?.isPlaying == true
                }
            }
        })

        _isPlaying.value = controller?.isPlaying == true
    }

    fun playStation(station: RadioStation) {
        _currentStation.value = station
        RadioService.stationFlow.value = station

        val controller = controller ?: return

        val metadata = MediaMetadata.Builder()
            .setTitle(station.name)
            .setArtist(station.frequency.ifEmpty { station.description })
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(android.net.Uri.parse(station.streamUrl))
            .setMediaMetadata(metadata)
            .build()

        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.play()

        scope.launch {
            preferences.setLastPlayedStationId(station.id)
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
