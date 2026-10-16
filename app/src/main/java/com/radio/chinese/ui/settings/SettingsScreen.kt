package com.radio.chinese.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.local.RegionGazetteer
import com.radio.chinese.data.RegionRepository
import com.radio.chinese.domain.model.Region
import com.radio.chinese.domain.model.VoiceScoring
import com.radio.chinese.service.AndroidVoiceCatalog
import com.radio.chinese.service.TtsAnnouncer
import com.radio.chinese.service.TtsOption
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.SectionHeader
import com.radio.chinese.ui.common.carTouchTarget
import com.radio.chinese.ui.theme.AppTheme
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.FontScaleOption
import com.radio.chinese.ui.theme.isCarSurface
import com.radio.chinese.ui.theme.resolveColorScheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val theme: AppTheme = AppTheme.SYSTEM,
    val fontScaleKey: String = FontScaleOption.STANDARD.key,
    val uiMode: Int = 0,
    val bootAutoStart: Boolean = false,
    val voiceAnnounceEnabled: Boolean = true,
    val localFirstEnabled: Boolean = true
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
    private val preferences: RadioPreferences,
    @ApplicationContext private val appContext: Context,
    private val catalog: AndroidVoiceCatalog,
    private val announcer: TtsAnnouncer,
    private val regionRepository: RegionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    /** 设备上所有中文「引擎×音色」候选，供试听与选择。 */
    private val _voiceOptions = MutableStateFlow<List<TtsOption>>(emptyList())
    val voiceOptions: StateFlow<List<TtsOption>> = _voiceOptions
    private val _selectedVoiceName = MutableStateFlow<String?>(null)
    val selectedVoiceName: StateFlow<String?> = _selectedVoiceName
    private val _selectedEngine = MutableStateFlow<String?>(null)
    val selectedEngine: StateFlow<String?> = _selectedEngine
    private val _regionLabel = MutableStateFlow<String>("")
    val regionLabel: StateFlow<String> = _regionLabel
    private val _regionMessage = MutableStateFlow<String?>(null)
    val regionMessage: StateFlow<String?> = _regionMessage

    private val gazetteer by lazy {
        RegionGazetteer.parse(
            appContext.assets.open("region_gazetteer.json").bufferedReader().use { it.readText() }
        )
    }
    val provinceList: List<String> get() = gazetteer.provinceNames
    fun citiesOf(province: String): List<String> =
        gazetteer.citiesToProvince.filterValues { it == province }.keys.filter { it != province }

    init {
        viewModelScope.launch {
            preferences.theme.collect { theme ->
                _uiState.value = _uiState.value.copy(theme = theme)
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
        observeListenEnhance()
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            preferences.setThemeKey(theme.key)
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

    // ========== 收听增强 ==========

    /** 在 init 块内被调：加载播报开关、本地优先开关、音色候选、当前地区。 */
    private fun observeListenEnhance() {
        viewModelScope.launch { preferences.voiceAnnounceEnabled.collect { v -> _uiState.value = _uiState.value.copy(voiceAnnounceEnabled = v) } }
        viewModelScope.launch { preferences.localFirstEnabled.collect { v -> _uiState.value = _uiState.value.copy(localFirstEnabled = v) } }
        viewModelScope.launch { preferences.preferredVoice.collect { _selectedVoiceName.value = it } }
        viewModelScope.launch { preferences.preferredTtsEngine.collect { _selectedEngine.value = it } }
        viewModelScope.launch { _regionLabel.value = formatRegion(regionRepository.currentUserRegion()) }
        viewModelScope.launch {
            val opts = withContext(Dispatchers.Default) { catalog.listOptions().filter { VoiceScoring.isChinese(it.voice) } }
            _voiceOptions.value = opts
        }
    }

    private fun formatRegion(r: Region): String =
        listOf(r.province, r.city).filter { it.isNotBlank() }.distinct().joinToString(" ")

    fun setVoiceAnnounce(enabled: Boolean) {
        viewModelScope.launch { preferences.setVoiceAnnounceEnabled(enabled) }
    }

    fun setLocalFirst(enabled: Boolean) {
        viewModelScope.launch { preferences.setLocalFirstEnabled(enabled) }
    }

    /** 选定引擎+音色并试听（TtsAnnouncer.preview 会记住偏好并播样例）。 */
    fun previewOption(opt: TtsOption) {
        viewModelScope.launch {
            announcer.preview(opt)
            _selectedVoiceName.value = opt.voice.name
            _selectedEngine.value = opt.enginePackage
        }
    }

    /** 先试缓存定位刷新；失败提示用户去手动选。 */
    fun relocate() {
        viewModelScope.launch {
            val r = regionRepository.refreshFromLocation()
            if (r != null && !r.isEmpty) {
                _regionLabel.value = formatRegion(r)
                _regionMessage.value = null
            } else {
                _regionMessage.value = "未能自动定位，可手动选择省市"
            }
        }
    }

    fun setManualRegion(province: String, city: String) {
        viewModelScope.launch {
            preferences.setManualRegion("$province|$city")
            _regionLabel.value = formatRegion(Region(province, city))
            _regionMessage.value = null
        }
    }

    override fun onCleared() {
        announcer.shutdown()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToManage: () -> Unit,
    onNavigateToKeySettings: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
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
                supportingContent = { Text(uiState.theme.label) },
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

            // 收听增强：线控播报 + 本地节目优先
            SectionHeader(title = "收听增强")
            ListenEnhanceSection(viewModel)

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
                    "· 键名对不上？进「按键设置」点「录制」，把车上任意按键绑到这四个功能上；没绑过的键照默认工作，同一个键一旦绑定就以绑定为准\n" +
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
        ThemePickerDialog(
            current = uiState.theme,
            onSelect = { theme ->
                viewModel.setTheme(theme)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }
}

/**
 * 主题选择对话框。
 *
 * 必须能滚到底：9 行 × 车机档 Dimens.RowMinHeight(88dp) = 792dp，而 density 上报 240 的
 * 车机横屏只有 480dp 高（与 usesRailNavigation 当年「四个条目装不下导致收藏不可达」
 * 是同一个坑）。这里 verticalScroll 与 heightIn(max) 两个约束都要给，只给一个会装不下或撑破窗口。
 */
@Composable
private fun ThemePickerDialog(
    current: AppTheme,
    onSelect: (AppTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    // 上限取「屏高 70%」与 420dp 的较小值：屏幕矮就跟着矮，不让对话框顶到状态栏
    val maxHeight = minOf((LocalConfiguration.current.screenHeightDp * 0.7f).dp, 420.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择主题") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                AppTheme.groupedForPicker().forEach { (group, themes) ->
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Dimens.GapLarge, bottom = Dimens.GapTiny)
                    )
                    themes.forEach { theme ->
                        ThemePickerRow(
                            theme = theme,
                            selected = current == theme,
                            systemDark = systemDark,
                            onSelect = { onSelect(theme) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 一行 = 单选钮 + 名称与场景说明 + 行尾三色预览。整行可点。 */
@Composable
private fun ThemePickerRow(
    theme: AppTheme,
    selected: Boolean,
    systemDark: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.RowMinHeight)
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 单选钮与整行走同一个回调：命中区只有一处，不会两个入口给两个结论
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(Dimens.GapSmall))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = theme.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = theme.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(Dimens.ChipGap))
        ThemeSwatchRow(theme = theme, systemDark = systemDark)
    }
}

/**
 * 三个色块用「该主题解析后的实际颜色」画，不引第二套判色路径：
 * 一律走 resolveColorScheme，system 档按当前系统深浅预览。
 * 给不识字或看不清的老人一个靠颜色选对主题的兜底。
 */
@Composable
private fun ThemeSwatchRow(theme: AppTheme, systemDark: Boolean) {
    val scheme = theme.resolveColorScheme(theme.isDarkConsidering(systemDark))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GapTiny)) {
        listOf(scheme.primary, scheme.surfaceContainerHigh, scheme.onSurface).forEach { c ->
            Box(
                modifier = Modifier
                    .size(Dimens.ThemeSwatchSize)
                    .background(c, MaterialTheme.shapes.extraSmall)
                    .border(
                        width = Dimens.SwatchBorderWidth,
                        color = MaterialTheme.colorScheme.outline,
                        shape = MaterialTheme.shapes.extraSmall
                    )
            )
        }
    }
}

@Composable
private fun getVersionName(): String {
    val context = LocalContext.current
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}

/**
 * 收听增强分区：线控播报开关 + 引擎×音色试听选择；本地优先开关 + 地区（定位/手动）。
 * 音色列表只展中文候选（避免英文音色刷屏），每项显示「引擎名 · 音色名」，点即试听并写偏好。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListenEnhanceSection(viewModel: SettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val options by viewModel.voiceOptions.collectAsState()
    val selectedVoice by viewModel.selectedVoiceName.collectAsState()
    val selectedEngine by viewModel.selectedEngine.collectAsState()
    val regionLabel by viewModel.regionLabel.collectAsState()
    val regionMessage by viewModel.regionMessage.collectAsState()
    var voiceExpanded by remember { mutableStateOf(false) }
    var regionExpanded by remember { mutableStateOf(false) }
    var manualProvince by remember { mutableStateOf("") }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> viewModel.relocate() }   // 拒绝时 relocate 内部会回退为手动提示

    fun isSelected(opt: TtsOption): Boolean =
        opt.enginePackage == selectedEngine && opt.voice.name == selectedVoice

    ListItem(
        headlineContent = { Text("线控播报") },
        supportingContent = { Text("用方向盘/线控切台时先语音提示“即将播放”再开播；需设备已装中文语音引擎") },
        leadingContent = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
        trailingContent = {
            Switch(checked = uiState.voiceAnnounceEnabled, onCheckedChange = { viewModel.setVoiceAnnounce(it) })
        },
        modifier = Modifier
            .carTouchTarget()
            .clickable { viewModel.setVoiceAnnounce(!uiState.voiceAnnounceEnabled) }
    )

    if (uiState.voiceAnnounceEnabled) {
        val currentLabel = options.firstOrNull { isSelected(it) }
            ?.let { "${it.engineLabel} · ${it.voice.name}" } ?: "自动（跨引擎选优）"
        ListItem(
            headlineContent = { Text("播报音色") },
            supportingContent = {
                Text(if (options.isEmpty()) "未检测到中文语音引擎" else "当前：$currentLabel（点选试听）")
            },
            leadingContent = { Icon(Icons.Default.Tune, contentDescription = null) },
            modifier = Modifier.carTouchTarget().clickable { voiceExpanded = !voiceExpanded }
        )
        if (voiceExpanded) {
            Column(Modifier.padding(horizontal = Dimens.ScreenPadding)) {
                options.forEach { opt ->
                    Row(
                        Modifier.fillMaxWidth().carTouchTarget().clickable { viewModel.previewOption(opt) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${opt.engineLabel} · ${opt.voice.name}" + if (opt.voice.isNetwork) "（联网）" else "",
                            color = if (isSelected(opt)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected(opt)) Icon(Icons.Default.Check, contentDescription = null)
                    }
                }
            }
        }
    }

    HorizontalDivider(Modifier.padding(top = Dimens.GapSmall))

    ListItem(
        headlineContent = { Text("本地节目优先") },
        supportingContent = { Text("按当前所在地区，把本地电台排在首页与切台顺序前面") },
        leadingContent = { Icon(Icons.Default.MyLocation, contentDescription = null) },
        trailingContent = {
            Switch(checked = uiState.localFirstEnabled, onCheckedChange = { viewModel.setLocalFirst(it) })
        },
        modifier = Modifier
            .carTouchTarget()
            .clickable { viewModel.setLocalFirst(!uiState.localFirstEnabled) }
    )

    if (uiState.localFirstEnabled) {
        ListItem(
            headlineContent = { Text("本地地区") },
            supportingContent = { Text(if (regionLabel.isBlank()) "未设置" else regionLabel) },
            leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = null) },
            modifier = Modifier.carTouchTarget().clickable { regionExpanded = !regionExpanded }
        )
        regionMessage?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)
            )
        }
        if (regionExpanded) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapSmall),
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapSmall)
            ) {
                OutlinedButton(
                    onClick = { locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION) },
                    modifier = Modifier.carTouchTarget()
                ) { Text("重新定位") }
                TextButton(
                    onClick = { viewModel.setManualRegion("", "") },
                    modifier = Modifier.carTouchTarget()
                ) { Text("清除") }
            }
            var provMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = provMenu, onExpandedChange = { provMenu = it }) {
                OutlinedTextField(
                    value = manualProvince,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("省份（手动兜底）") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(provMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth().padding(horizontal = Dimens.ScreenPadding)
                )
                ExposedDropdownMenu(expanded = provMenu, onDismissRequest = { provMenu = false }) {
                    viewModel.provinceList.forEach { p ->
                        DropdownMenuItem(text = { Text(p) }, onClick = { manualProvince = p; viewModel.setManualRegion(p, ""); provMenu = false })
                    }
                }
            }
            val cities = viewModel.citiesOf(manualProvince)
            if (cities.isNotEmpty()) {
                var cityMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = cityMenu, onExpandedChange = { cityMenu = it }) {
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("城市") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(cityMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth().padding(horizontal = Dimens.ScreenPadding)
                    )
                    ExposedDropdownMenu(expanded = cityMenu, onDismissRequest = { cityMenu = false }) {
                        cities.forEach { c ->
                            DropdownMenuItem(text = { Text(c) }, onClick = { viewModel.setManualRegion(manualProvince, c); cityMenu = false })
                        }
                    }
                }
            }
        }
    }
}
