package com.radio.chinese

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.service.CarKeyAction
import com.radio.chinese.service.KeyBindingStore
import com.radio.chinese.service.KeyCapture
import com.radio.chinese.service.KeyDiagnostics
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.MainScreen
import com.radio.chinese.ui.theme.AppTheme
import com.radio.chinese.ui.theme.ChineseRadioTheme
import com.radio.chinese.ui.theme.FontScaleOption
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playerManager: PlayerManager

    @Inject
    lateinit var preferences: RadioPreferences

    @Inject
    lateinit var keyBindings: KeyBindingStore

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* No-op, notification is optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermission()

        setContent {
            // initial = SYSTEM：冷启动首帧的深浅结论必须和「无偏好」的解析结果一致，
            // 否则首帧会闪一下别的配色再去对齐 Flow 的值。
            val theme by preferences.theme.collectAsState(initial = AppTheme.SYSTEM)
            val fontScaleKey by preferences.fontScaleKey.collectAsState(initial = FontScaleOption.STANDARD.key)
            val uiMode by preferences.uiMode.collectAsState(initial = 0)

            ChineseRadioTheme(
                theme = theme,
                fontScale = FontScaleOption.fromKey(fontScaleKey).factor,
                uiMode = uiMode
            ) {
                MainScreen(playerManager = playerManager)
            }
        }
    }

    /** 按键诊断：车机/方控按键若走到 Activity 这一层，在这里留下线索（设置页连点「关于」7 次可看） */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            KeyDiagnostics.record("按键", "${KeyEvent.keyCodeToString(event.keyCode)}(${event.keyCode})")
            KeyCapture.offer(event.keyCode)
            // 录制中只攒候选不执行，否则用户按一下就把自己刚绑的键触发了，界面会乱跳
            if (!KeyCapture.listening.value) {
                // 前台（如正在显示迷你播放条）时，车机滚轮的上下曲媒体键会送到 Activity 窗口而不是会话；
                // 不接住就会“拨了没反应”（用户实测问题 3、5）。后台时键走会话，不会到这里；
                // 即便前台两处都到，handleWheelSkip 的 400ms 去抖也会挡住重复切台。
                if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                ) {
                    playerManager.handleWheelSkip(event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT)
                    return true
                }
                if (!isMediaKeyCode(event.keyCode)) {
                    keyBindings.actionFor(event.keyCode)?.let { action ->
                        runCarKeyAction(action)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 自定义按键的四个动作都走已有能力，不另造一套播放控制 */
    fun runCarKeyAction(action: CarKeyAction) {
        when (action) {
            CarKeyAction.PREV_STATION -> playerManager.handleMediaSkip(false)
            CarKeyAction.NEXT_STATION -> playerManager.handleMediaSkip(true)
            CarKeyAction.PLAY_PAUSE -> playerManager.togglePlayPause()
            // 用 dispatcher 而不是 navController.popBackStack()：只有前者会尊重各页已有的 BackHandler
            CarKeyAction.GO_BACK -> onBackPressedDispatcher.onBackPressed()
        }
    }

    /** 媒体键由 RadioService 的会话回调负责，两处都执行会切两次台 */
    private fun isMediaKeyCode(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> true
        else -> false
    }

    /**
     * 按键诊断：真被路由到 Activity 的 Intent（车机面板键的私有 action）会在这里留痕。
     *
     * 但桌面重新拉起应用发的也是 ACTION_MAIN（MainActivity 为 singleTop），
     * 光记成「Intent <action>」会让人以为某个物理键有信号——实车上 src/地址本 就被这样误读过。
     * 所以明确标为「非按键」，同时把 action 写出来保留排查线索。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.action?.let { KeyDiagnostics.record("非按键", "应用被重新拉起 action=$it") }
    }

    override fun onStart() {
        super.onStart()
        playerManager.connect()
    }

    override fun onStop() {
        super.onStop()
        // Don't disconnect - keep background playback
    }

    override fun onDestroy() {
        super.onDestroy()
        playerManager.disconnect()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
