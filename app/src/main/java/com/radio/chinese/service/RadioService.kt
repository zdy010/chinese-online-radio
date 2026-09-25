package com.radio.chinese.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.radio.chinese.R
import com.radio.chinese.RadioApp
import com.radio.chinese.data.local.RadioPreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class RadioService : MediaSessionService() {

    @Inject
    lateinit var preferences: RadioPreferences

    @Inject
    lateinit var webDavClient: WebDavClient

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        initializePlayer()
    }

    /**
     * 开机自启是用 startForegroundService 拉起来的，必须在几秒内 startForeground，
     * 否则系统抛 "did not then call Service.startForeground"。
     * 这里只挂一条「已就绪」通知、不播放，避免一上车就突然出声；
     * 已在播放时不再占用通知栏，让 Media3 自己的播放通知留在原位。
     *
     * Android 14+ 按类型禁止从 BOOT_COMPLETED 起 mediaPlayback 前台服务（实测抛
     * ForegroundServiceStartNotAllowedException 并进入崩溃重启循环），而车机多是安卓 10、
     * 没有这个限制。所以这里必须接住异常并立即 stopSelf：旧系统上正常就绪，
     * 新系统上降级成“什么也没发生”，而不是开机崩溃循环。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (exoPlayer?.isPlaying != true) {
            try {
                ServiceCompat.startForeground(
                    this, READY_NOTIFICATION_ID, buildReadyNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } catch (_: Exception) {
                // 不给前台能力就直接收摊；stopSelf 同时避开“没调 startForeground”的超时异常
                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun buildReadyNotification(): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        return NotificationCompat.Builder(this, RadioApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("已就绪，点按打开继续收听")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun initializePlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // 动态 Auth：仅在 WebDAV 凭据已设置时添加，避免干扰 HTTP/M3U 等公开资源
        val dataSourceFactory = DataSource.Factory {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
            if (webDavClient.username.isNotEmpty()) {
                httpFactory.setDefaultRequestProperties(mapOf("Authorization" to webDavClient.authHeader()))
            }
            DefaultDataSource.Factory(this, httpFactory).createDataSource()
        }

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory)
            )
            .build()

        exoPlayer = player

        val sessionActivityPendingIntent = packageManager?.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val session = MediaSession.Builder(this, player)
            .apply {
                sessionActivityPendingIntent?.let { setSessionActivity(it) }
            }
            .build()

        mediaSession = session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null && !player.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayer = null
        super.onDestroy()
    }

    companion object {
        /** 1002 避开 Media3 自己用的 1001 */
        private const val READY_NOTIFICATION_ID = 1002
    }
}
