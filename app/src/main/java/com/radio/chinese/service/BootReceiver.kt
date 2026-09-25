package com.radio.chinese.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.radio.chinese.data.local.RadioPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
                // goAsync 不会把工作移出主线程；开机瞬间几十个应用同时在抢 IO，
                // 在主线程等 DataStore 冷读盘一旦超预算就是广播超时 ANR。
                withContext(Dispatchers.IO) {
                    if (preferences.bootAutoStart.first()) {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, RadioService::class.java)
                                .setAction(RadioService.ACTION_PREPARE_SESSION)
                        )
                    }
                }
            } catch (e: Exception) {
                // 见类注释：被 ROM 拦下就放弃，但要留下痕迹，否则现场无法区分
                // “ROM 拦了”与“代码写错了”
                Log.w(TAG, "开机自启已跳过", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
