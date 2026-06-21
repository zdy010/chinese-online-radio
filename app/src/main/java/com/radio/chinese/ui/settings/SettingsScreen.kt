package com.radio.chinese.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.local.RadioPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: Int = 0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: RadioPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        viewModelScope.launch {
            preferences.themeMode.collect { mode ->
                _uiState.value = _uiState.value.copy(themeMode = mode)
            }
        }
    }

    fun setThemeMode(mode: Int) {
        viewModelScope.launch {
            preferences.setThemeMode(mode)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToManage: () -> Unit,
    onThemeChanged: (Int) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showThemeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Theme Setting
            ListItem(
                headlineContent = { Text("主题") },
                supportingContent = {
                    Text(
                        when (uiState.themeMode) {
                            1 -> "浅色模式"
                            2 -> "深色模式"
                            else -> "跟随系统"
                        }
                    )
                },
                leadingContent = {
                    Icon(Icons.Default.Palette, contentDescription = null)
                },
                modifier = Modifier.clickable { showThemeDialog = true }
            )

            HorizontalDivider()

            // Station Source Management
            ListItem(
                headlineContent = { Text("节目源管理") },
                supportingContent = { Text("检测电台可用性、添加自定义电台、标记无效源") },
                leadingContent = {
                    Icon(Icons.Default.Radio, contentDescription = null)
                },
                modifier = Modifier.clickable(onClick = onNavigateToManage)
            )

            HorizontalDivider()

            // About
            ListItem(
                headlineContent = { Text("关于") },
                supportingContent = { Text("时光收音机 v${getVersionName()}") },
                leadingContent = {
                    Icon(Icons.Default.Info, contentDescription = null)
                }
            )

            HorizontalDivider()

            // Version info at bottom
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "时光收音机 v${getVersionName()}\n基于公开广播流媒体地址",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(16.dp)
            )
        }
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("选择主题") },
            text = {
                Column {
                    listOf(
                        0 to "跟随系统",
                        1 to "浅色模式",
                        2 to "深色模式"
                    ).forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setThemeMode(mode)
                                    onThemeChanged(mode)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.themeMode == mode,
                                onClick = {
                                    viewModel.setThemeMode(mode)
                                    onThemeChanged(mode)
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun getVersionName(): String {
    val context = LocalContext.current
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}
