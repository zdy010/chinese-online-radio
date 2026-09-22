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
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.FontScaleOption
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: Int = 0,
    val fontScaleKey: String = FontScaleOption.STANDARD.key
)

/**
 * 工信部 App 备案号。
 * 拿到备案号后填入（如 "粤ICP备2026XXXXXX号-1A"），关于区域将自动展示并支持跳转核验。
 * 为空字符串时不显示。
 */
const val ICP_FILING_NUMBER = ""
const val ICP_QUERY_URL = "https://beian.miit.gov.cn"

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
        viewModelScope.launch {
            preferences.fontScaleKey.collect { key ->
                _uiState.value = _uiState.value.copy(fontScaleKey = key)
            }
        }
    }

    fun setThemeMode(mode: Int) {
        viewModelScope.launch {
            preferences.setThemeMode(mode)
        }
    }

    fun setFontScaleKey(key: String) {
        viewModelScope.launch {
            preferences.setFontScaleKey(key)
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
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedWindowInsets,
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
                .verticalScroll(rememberScrollState())
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

            // 字体大小：面向中老年用户的刚需，此前设置页只有主题 / 源管理 / 关于三项
            ListItem(
                headlineContent = { Text("字体大小") },
                supportingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.ChipGap)) {
                        FontScaleOption.entries.forEach { option ->
                            FilterChip(
                                selected = uiState.fontScaleKey == option.key,
                                onClick = { viewModel.setFontScaleKey(option.key) },
                                label = { Text(option.label) }
                            )
                        }
                    }
                },
                leadingContent = {
                    Icon(Icons.Default.FormatSize, contentDescription = null)
                }
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

            // ICP filing number (shown once available; tap to verify on MIIT site)
            if (ICP_FILING_NUMBER.isNotBlank()) {
                val context = LocalContext.current
                ListItem(
                    headlineContent = { Text("App 备案号") },
                    supportingContent = { Text("$ICP_FILING_NUMBER（点击查询核验）") },
                    leadingContent = {
                        Icon(Icons.Default.Verified, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(ICP_QUERY_URL)
                                )
                            )
                        }
                    }
                )

                HorizontalDivider()
            }

            // 版本信息置底：在可滚动列里不能用 weight(1f)（高度约束无限），改用固定间距
            Spacer(modifier = Modifier.height(Dimens.GapXLarge))
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
