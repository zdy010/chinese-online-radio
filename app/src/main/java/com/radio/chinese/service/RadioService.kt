package com.radio.chinese.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
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

    /** 方控/车机的上一曲/下一曲要落到应用自己的电台/曲目列表上 */
    @Inject
    lateinit var playerManager: PlayerManager

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
        // 只对开机自启这一种来源挂「已就绪」前台通知。本服务 exported 且带 media3 的
        // intent-filter，方控媒体键与外部 controller 也会走 onStartCommand；
        // 在那里无条件 startForeground（失败还 stopSelf）会把用户按方向盘播放键吞掉。
        if (intent?.action == ACTION_PREPARE_SESSION && exoPlayer?.isPlaying != true) {
            try {
                ServiceCompat.startForeground(
                    this, READY_NOTIFICATION_ID, buildReadyNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } catch (e: Exception) {
                // 安卓 14+ 禁从 BOOT_COMPLETED 起 mediaPlayback：降级成“无事发生”，但留痕迹
                android.util.Log.w(TAG, "开机就绪通知未能建起，本次开机自启作废", e)
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
                setCallback(buildSessionCallback())
            }
            .build()

        mediaSession = session
    }

    /**
     * 车机/方控的「上一曲/下一曲」在本应用里是切电台。
     *
     * 默认行为在单条直播流上不可用：NEXT 无动作、PREV 把直播位置 seek 回起点。
     * 媒体按钮在 onMediaButtonEvent 层直接消费（不转成播放器命令）；系统媒体控件
     * 走的命令层在 onPlayerCommandRequest 拦下并拒绝默认 seek。两处都转给
     * PlayerManager.handleMediaSkip：戏曲模式跟随切曲目，电台模式切电台。
     */
    private fun buildSessionCallback(): MediaSession.Callback = object : MediaSession.Callback {

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val keyEvent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                ?: return false
            val forward = when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> true
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> false
                else -> return false
            }
            // 滚轮/方控一格 = 一次 DOWN；长按产生的重复事件不连切多个台
            if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                KeyDiagnostics.record("媒体键", "${KeyEvent.keyCodeToString(keyEvent.keyCode)}(${keyEvent.keyCode})")
                playerManager.handleMediaSkip(forward)
            }
            // DOWN/UP 都消费：不让默认实现把直播流位置 seek 回起点
            return true
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            command: Int
        ): Int {
            val forward = when (command) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> true
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> false
                else -> return SessionResult.RESULT_SUCCESS
            }
            KeyDiagnostics.record("会话命令", commandLabel(command))
            playerManager.handleMediaSkip(forward)
            // 媒体键已在按钮层消费；到这里的是系统媒体控件/外部 controller，拒绝默认 seek
            return SessionResult.RESULT_ERROR_NOT_SUPPORTED
        }
    }

    private fun commandLabel(command: Int): String = when (command) {
        Player.COMMAND_SEEK_TO_NEXT -> "SEEK_TO_NEXT"
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> "SEEK_TO_NEXT_MEDIA_ITEM"
        Player.COMMAND_SEEK_TO_PREVIOUS -> "SEEK_TO_PREVIOUS"
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> "SEEK_TO_PREVIOUS_MEDIA_ITEM"
        else -> "COMMAND_$command"
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
        /** 开机自启专用 action，由 BootReceiver 发出 */
        const val ACTION_PREPARE_SESSION = "com.radio.chinese.action.PREPARE_SESSION"

        private const val TAG = "RadioService"

        /** 1002 避开 Media3 自己用的 1001 */
        private const val READY_NOTIFICATION_ID = 1002
    }
}
