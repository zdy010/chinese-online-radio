package com.radio.chinese.timer

import android.content.Context
import android.os.CountDownTimer
import com.radio.chinese.service.PlayerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SleepTimer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerManager: PlayerManager
) {
    private var countDownTimer: CountDownTimer? = null

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    fun startTimer(minutes: Int) {
        cancelTimer()

        val millis = minutes * 60 * 1000L
        _isActive.value = true

        countDownTimer = object : CountDownTimer(millis, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                _remainingSeconds.value = millisUntilFinished / 1000
            }

            override fun onFinish() {
                _remainingSeconds.value = 0
                _isActive.value = false
                playerManager.stop()
            }
        }.start()
    }

    fun cancelTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
        _remainingSeconds.value = 0
        _isActive.value = false
    }

    fun formatRemainingTime(): String {
        val totalSeconds = _remainingSeconds.value
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}
