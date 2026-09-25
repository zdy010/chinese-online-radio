package com.radio.chinese.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.radio.chinese.data.local.RadioPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * 开机后恢复播放会话，不自动播放。
 *
 * 部分车机 ROM 的省电策略会拒绝后台启动前台服务：静默失败即可——
 * 用户上车手动打开 App 的代价，远小于开机弹一个错误框。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var preferences: RadioPreferences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        runBlocking {
            try {
                if (preferences.bootAutoStart.first()) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, RadioService::class.java)
                    )
                }
            } catch (_: Exception) {
                // 见类注释：被 ROM 拦下就放弃，不打扰用户
            } finally {
                pending.finish()
            }
        }
    }
}
