package com.radio.chinese.service

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 同进程内公布 `RadioService` 建起来的那个 ExoPlayer，供 UI 绑视频输出面。
 *
 * 为什么需要它：`PlayerManager` 通过 `MediaController`（会话代理）控播，而 Media3 的
 * `MediaController` 不支持 `setVideoSurface*` —— 调了就抛 `UnsupportedOperationException`，
 * 画面必须绑在播放器所在进程的那个对象上。`RadioService` 在清单里没有 `android:process`，
 * 与 Activity 同进程，所以这里把实例交出去是安全的。
 *
 * 只允许主线程读写（`RadioService.onCreate` 与 Compose 都在主线程）。
 */
@Singleton
class VideoPlayerMount @Inject constructor() {

    private val _player = MutableStateFlow<ExoPlayer?>(null)

    /** 冷启动直接进视频页时播放器可能还没建起来，UI 订阅这个流等它。 */
    val playerFlow: StateFlow<ExoPlayer?> = _player

    var player: ExoPlayer? = null
        private set

    /** 当前唯一持有画面的 `PlayerView`；转场交叠时后来者顶掉前者。 */
    var mountedView: PlayerView? = null
        private set

    fun attach(player: ExoPlayer) {
        this.player = player
        _player.value = player
    }

    /** 认实例再摘：旧 Service 实例销毁时不得把新建好的实例摘空。 */
    fun detach(player: ExoPlayer) {
        if (this.player === player) {
            this.player = null
            this.mountedView = null
            _player.value = null
        }
    }

    /** 声明画面所有权。恒返回 true —— 后来者总是拿到所有权，前者被顶掉。 */
    fun acquireStage(view: PlayerView): Boolean {
        mountedView = view
        return true
    }

    /** 释放画面所有权。只有持有者本人释放才返回 true；被顶掉的前者返回 false，不得去 setPlayer(null)。 */
    fun releaseStage(view: PlayerView): Boolean {
        if (mountedView === view) {
            mountedView = null
            return true
        }
        return false
    }
}
