package com.radio.chinese.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.SectionHeader
import com.radio.chinese.ui.common.carTouchTarget
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.FontScaleOption
import com.radio.chinese.ui.theme.isCarSurface
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: Int = 0,
    val fontScaleKey: String = FontScaleOption.STANDARD.key,
    val uiMode: Int = 0,
    val bootAutoStart: Boolean = false
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
        viewModelScope.launch {
            preferences.uiMode.collect { mode ->
                _uiState.value = _uiState.value.copy(uiMode = mode)
            }
        }
        viewModelScope.launch {
            preferences.bootAutoStart.collect { enabled ->
                _uiState.value = _uiState.value.copy(bootAutoStart = enabled)
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

    fun setUiMode(mode: Int) {
        viewModelScope.launch {
            preferences.setUiMode(mode)
        }
    }

    fun setBootAutoStart(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setBootAutoStart(enabled)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToManage: () -> Unit,
    onNavigateToKeySettings: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onThemeChanged: (Int) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showThemeDialog by remember { mutableStateOf(false) }
    // 隐藏入口：连点「关于」7 次解锁「按键诊断」（上车排查方控的后门，寻常使用碰不到）
    var aboutTapCount by rememberSaveable { mutableIntStateOf(0) }

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
        // 车机档限宽居中：1280dp 宽屏上 ListItem 拉满整行会让点击目标相距过远，
        // 拇指要探出去按。手机档这一层 Box 不改变任何尺寸。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
        Column(
            modifier = Modifier
                // 顺序不能反：fillMaxWidth 在前会把宽度钉成屏宽，后面的 widthIn 缩不回来
                .then(
                    if (isCarSurface()) Modifier.widthIn(max = Dimens.CarContentMaxWidth)
                    else Modifier
                )
                .fillMaxWidth()
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

            // 界面形态：加装车机上报的 density 不可控，自动判定必须能被推翻
            ListItem(
                headlineContent = { Text("界面模式") },
                supportingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.ChipGap)) {
                        listOf(
                            0 to "自动",
                            1 to "手机",
                            2 to "车机"
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = uiState.uiMode == mode,
                                onClick = { viewModel.setUiMode(mode) },
                                label = { Text(label) },
                                // 形态误判时这三枚胶囊是唯一的自救入口，命中区不能是最小的
                                modifier = Modifier.carTouchTarget()
                            )
                        }
                    }
                },
                leadingContent = {
                    Icon(Icons.Default.Tablet, contentDescription = null)
                }
            )

            HorizontalDivider()

            // 车机按键映射：方向盘/面板上任何一个能送到应用的键，都能绑到这四个功能
            ListItem(
                headlineContent = { Text("按键设置") },
                supportingContent = { Text("把方向盘或面板按键绑到上一电台、下一电台、播放暂停、返回") },
                leadingContent = {
                    Icon(Icons.Default.Keyboard, contentDescription = null)
                },
                modifier = Modifier.clickable(onClick = onNavigateToKeySettings)
            )

            HorizontalDivider()

            // 开机自启：只拉起会话不出声；能不能起来取决于车机 ROM 放不放行
            ListItem(
                headlineContent = { Text("开机自启") },
                supportingContent = { Text("开机后在后台准备好收音机服务（不自动播放、不恢复电台），需车机允许本应用自启") },
                leadingContent = {
                    Icon(Icons.Default.Power, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = uiState.bootAutoStart,
                        onCheckedChange = { viewModel.setBootAutoStart(it) }
                    )
                },
                modifier = Modifier
                    .carTouchTarget()
                    .clickable {
                        viewModel.setBootAutoStart(!uiState.bootAutoStart)
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
                },
                modifier = Modifier.clickable { aboutTapCount++ }
            )

            HorizontalDivider()

            // 按键诊断入口：连点「关于」7 次才出现
            if (aboutTapCount >= 7) {
                ListItem(
                    headlineContent = { Text("按键诊断") },
                    supportingContent = { Text("查看方控/车机按键走到了哪条通道") },
                    leadingContent = {
                        Icon(Icons.Default.Build, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onNavigateToDiagnostics)
                )

                HorizontalDivider()
            }

            // 操作说明：把车机方控用法与隐藏诊断入口就近写清楚，省得用户去翻外部文档。
            // 放在「关于」与诊断入口之后，不推动上方已验收的点击目标位置。
            SectionHeader(title = "操作说明 v${getVersionName()}")
            Text(
                text =
                    "· 首页点电台即可收听，播放页可换节目源、收藏、设定时关闭\n" +
                    "· 方向盘或车机的「上一曲 / 下一曲」切换电台；播放戏曲时同样按键切换曲目\n" +
                    "· 键名对不上？进「按键设置」点「录制」，把车上任意按键绑到这四个功能上；默认按键不会被顶掉\n" +
                    "· 在节目源管理里标记为无效的电台，切台时会自动跳过\n" +
                    "· 连点上方「关于」 7 次打开按键诊断页：上车按一遍按键，出现记录＝按键已送达应用，没有记录则说明该键没走标准通道；「清空」可逐键分辨",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Dimens.GapSmall)
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
