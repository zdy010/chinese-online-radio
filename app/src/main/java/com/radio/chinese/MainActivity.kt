package com.radio.chinese

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.service.PlayerManager
import com.radio.chinese.ui.MainScreen
import com.radio.chinese.ui.theme.ChineseRadioTheme
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
            var currentThemeMode by remember { mutableIntStateOf(themeMode) }

            LaunchedEffect(themeMode) {
                currentThemeMode = themeMode
            }

            ChineseRadioTheme(themeMode = currentThemeMode) {
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
