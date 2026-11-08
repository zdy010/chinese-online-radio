package com.radio.chinese

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class RadioApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        // NotificationChannel 是 API 26 才有；getSystemService(Class) 是 API 23 才有。
        // legacy TV 分支（minSdk 22）上两者都不能碰，否则 Application.onCreate 直接
        // NoSuchMethodError 崩溃（Android 5.1 TV AVD 实测）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        @Suppress("DEPRECATION")
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        // 实际挂前台通知的是 Media3 自带的 DefaultMediaNotificationProvider，
        // 它用的是自己那个渠道 id，渠道名是库里内置的英文 "Now playing"，
        // 用户在系统通知设置里看到的就是英文。渠道名创建后不可修改，
        // 所以必须赶在服务创建它之前用同名中文渠道抢先占位。
        val media3Channel = NotificationChannel(
            MEDIA3_DEFAULT_CHANNEL_ID,
            getString(R.string.now_playing),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(media3Channel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "radio_playback_channel"

        /** androidx.media3 DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID */
        const val MEDIA3_DEFAULT_CHANNEL_ID = "default_channel_id"
    }
}
