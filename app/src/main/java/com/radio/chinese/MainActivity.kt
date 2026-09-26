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
import com.radio.chinese.service.KeyDiagnostics
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.MainScreen
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* No-op, notification is optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermission()

        setContent {
            val themeMode by preferences.themeMode.collectAsState(initial = 0)
            val fontScaleKey by preferences.fontScaleKey.collectAsState(initial = FontScaleOption.STANDARD.key)
            val uiMode by preferences.uiMode.collectAsState(initial = 0)
            var currentThemeMode by remember { mutableIntStateOf(themeMode) }

            LaunchedEffect(themeMode) {
                currentThemeMode = themeMode
            }

            ChineseRadioTheme(
                themeMode = currentThemeMode,
                fontScale = FontScaleOption.fromKey(fontScaleKey).factor,
                uiMode = uiMode
            ) {
                MainScreen(
                    playerManager = playerManager,
                    themeMode = currentThemeMode,
                    onThemeChanged = { mode ->
                        currentThemeMode = mode
                    }
                )
            }
        }
    }

    /** 按键诊断：车机/方控按键若走到 Activity 这一层，在这里留下线索（设置页连点「关于」7 次可看） */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            KeyDiagnostics.record("按键", "${KeyEvent.keyCodeToString(event.keyCode)}(${event.keyCode})")
        }
        return super.dispatchKeyEvent(event)
    }

    /** 按键诊断：真被路由到 Activity 的 Intent（车机面板键的私有 action）也会留痕 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.action?.let { KeyDiagnostics.record("Intent", it) }
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
