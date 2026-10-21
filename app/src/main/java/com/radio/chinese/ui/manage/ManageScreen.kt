package com.radio.chinese.ui.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationCategory
import com.radio.chinese.domain.model.StationFilter
import com.radio.chinese.domain.model.StationTag
import com.radio.chinese.service.StreamStatus
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.SearchBarRow
import com.radio.chinese.ui.common.StatusChip
import com.radio.chinese.ui.common.StationActionDialog
import com.radio.chinese.ui.common.stationSubtitle
import com.radio.chinese.ui.common.carTouchTarget
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface
import kotlinx.coroutines.launch

// 分类表单一来源：以前这里手写一份且漏了 tv_audio，导致同一电台在不同页显示不同分类名
private val CATEGORIES = StationCategory.ordered()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageScreen(
    onNavigateBack: () -> Unit,
    viewModel: ManageViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var actionStation by remember { mutableStateOf<RadioStation?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    // 搜索命中复用 StationFilter：与主页同一套规则，避免两页搜出不同结果
    val visibleStations = remember(uiState.stations, uiState.searchQuery) {
        StationFilter.apply(uiState.stations, category = null, query = uiState.searchQuery)
    }

    // Show easter egg message
    LaunchedEffect(uiState.easterEggMessage) {
        uiState.easterEggMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearEasterEggMessage()
        }
    }

    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedWindowInsets,
                title = { Text("节目源管理") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.checkAllStations() },
                        enabled = !uiState.isChecking
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "检测全部")
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加电台")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        // 车机档限宽居中，手机档这层 Box 不改变任何尺寸
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
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
        ) {
            // 搜索：电台几百个时靠翻不现实，先按关键字过滤
            SearchBarRow(
                value = uiState.searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                placeholder = "搜索电台名称、频率、标签"
            )

            // Checking progress
            if (uiState.isChecking) {
                LinearProgressIndicator(
                    progress = {
                        if (uiState.totalCount > 0)
                            uiState.checkedCount.toFloat() / uiState.totalCount
                        else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.ScreenPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "正在检测… ${uiState.checkedCount}/${uiState.totalCount}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    // 全量检测要跑几十秒，不给个取消入口就只能干等或退页
                    TextButton(
                        onClick = { viewModel.cancelCheckAll() },
                        contentPadding = PaddingValues(horizontal = Dimens.GapSmall),
                        modifier = Modifier.carTouchTarget()
                    ) {
                        Text("取消检测", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // Summary
            if (uiState.checkResults.isNotEmpty() && !uiState.isChecking) {
                val valid = uiState.checkResults.values.count { it.status == StreamStatus.VALID }
                val invalid = uiState.checkResults.values.count { it.status == StreamStatus.INVALID }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "检测完成：有效 $valid 个，疑似失效 $invalid 个（点击单项可标记或重新检测）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (visibleStations.isEmpty()) {
                // 搜不到时给一句明确反馈，而不是留一片空白让人以为没加载出来
                Text(
                    text = if (uiState.searchQuery.isBlank()) "还没有电台"
                    else "没有匹配「${uiState.searchQuery}」的电台",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.GapMedium, vertical = Dimens.GapXLarge),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(visibleStations, key = { it.id }) { station ->
                        StationManageItem(
                            station = station,
                            isInvalid = uiState.invalidIds.contains(station.id),
                            isCustom = uiState.customIds.contains(station.id),
                            checkResult = uiState.checkResults[station.id],
                            onClick = { actionStation = station }
                        )
                    }
                }
            }
        }
        }
    }

    // Action bottom sheet / dialog
    actionStation?.let { station ->
        StationActionDialog(
            station = station,
            isInvalid = uiState.invalidIds.contains(station.id),
            isCustom = uiState.customIds.contains(station.id),
            onDismiss = { actionStation = null },
            onCheck = {
                viewModel.checkSingleStation(station)
                actionStation = null
            },
            onMarkInvalid = {
                viewModel.markInvalid(station.id)
                actionStation = null
            },
            onMarkValid = {
                viewModel.markValid(station.id)
                actionStation = null
            },
            onDelete = {
                viewModel.removeCustomStation(station.id)
                actionStation = null
            }
        )
    }

    // Add station dialog
    if (showAddDialog) {
        AddStationDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url, category, freq, desc, tags ->
                viewModel.addCustomStation(name, url, category, freq, desc, tags)
                showAddDialog = false
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationManageItem(
    station: RadioStation,
    isInvalid: Boolean,
    isCustom: Boolean,
    checkResult: com.radio.chinese.service.StreamCheckResult?,
    onClick: () -> Unit
) {
    val borderColor = when {
        checkResult?.status == StreamStatus.VALID -> MaterialTheme.colorScheme.primary
        checkResult?.status == StreamStatus.INVALID -> MaterialTheme.colorScheme.error
        isInvalid -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 长按在按住瞬间就会触发 onLongClick，菜单立即弹出，不用等松手；
            // 松手（普通点击）仍保留入口，不砍掉原有的可达方式
            .combinedClickable(onClick = onClick, onLongClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isInvalid) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(borderColor)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.RowMinHeight)
                .padding(Dimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            val icon = when {
                checkResult?.status == StreamStatus.CHECKING -> Icons.Default.HourglassEmpty
                checkResult?.status == StreamStatus.VALID -> Icons.Default.CheckCircle
                checkResult?.status == StreamStatus.INVALID -> Icons.Default.Error
                isInvalid -> Icons.Default.Warning
                else -> Icons.Default.Radio
            }
            val tint = when {
                checkResult?.status == StreamStatus.VALID -> MaterialTheme.colorScheme.primary
                checkResult?.status == StreamStatus.INVALID -> MaterialTheme.colorScheme.error
                isInvalid -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                checkResult?.status == StreamStatus.CHECKING -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, contentDescription = null, tint = tint,
                modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(10.dp))

            // Station info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = station.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1
                    )
                    if (isCustom) {
                        Spacer(modifier = Modifier.width(Dimens.GapSmall))
                        StatusChip(
                            text = "自定义",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    if (isInvalid) {
                        Spacer(modifier = Modifier.width(Dimens.GapSmall))
                        StatusChip(
                            text = "已标记无效",
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                val subtitle = buildString {
                    // 副标题走全站唯一规则（真频率 > 国家·语言 > 分类名），
                    // 再加检测结论；以前这里自己拼 frequency + 分类，与首页不一致
                    append(stationSubtitle(station, StationCategory.labelOf(station.category)))
                    if (checkResult != null && checkResult.status != StreamStatus.CHECKING) {
                        if (isNotEmpty()) append(" · ")
                        append(checkResult.message)
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Icon(Icons.Default.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddStationDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, category: String, frequency: String, description: String, tags: List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var streamUrl by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("general") }
    var frequency by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var pickedTags by remember { mutableStateOf(listOf<String>()) }
    var showCategoryMenu by remember { mutableStateOf(false) }

    val isValid = name.isNotBlank() && streamUrl.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义电台") },
        text = {
            // 可滚动：五个输入框 + 分类菜单，键盘弹起或大字号下“添加”会不可达
            //
            // 这里不要再把 LocalUiSurface 用 CompositionLocalProvider 显式带进对话框：
            // 此前注释断言“AlertDialog 是独立组合窗口、拿不到应用层形态，Dimens 换档全失效”，
            // 那个结论来自由于读错了量的尺子——贴着对话框底边被裁切的 chip
            // 在 uiautomator 里上报的是剩余可见高度（88dp 会读成 21dp）。
            // 2026-10-13 A/B 实测：删掉 provider 后（debug 包），四个分组 16 个 chip
            // 仍然全部 88.0dp、命中区违规 0，与带 provider 的包逐一一致。
            // 形态 local 本来就会随对话框内容一起传递，其余对话框无需特殊处理。
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("电台名称 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = streamUrl,
                    onValueChange = { streamUrl = it },
                    label = { Text("流媒体地址 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = frequency,
                    onValueChange = { frequency = it },
                    label = { Text("频率（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Category dropdown
                ExposedDropdownMenuBox(
                    expanded = showCategoryMenu,
                    onExpandedChange = { showCategoryMenu = it }
                ) {
                    OutlinedTextField(
                        value = CATEGORIES.find { it.first == category }?.second ?: "综合",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("分类") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showCategoryMenu) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = showCategoryMenu,
                        onDismissRequest = { showCategoryMenu = false }
                    ) {
                        CATEGORIES.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    category = id
                                    showCategoryMenu = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述（可选）") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                // 标签用内联 chip 多选，不用下拉菜单：词表 48 项塞进下拉很不好选，
                // 而对话框里的普通 DropdownMenu 根本点不开（项目已记过这个坑）。
                // 外层已有 verticalScroll，chip 比列表长也不会挡住“添加”按钮。
                Text(
                    text = if (pickedTags.isEmpty()) "标签（可选，最多 6 个）"
                    else "已选 ${pickedTags.size}/6：" + pickedTags.joinToString("、") {
                        StationTag.labelOf(it)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StationTag.orderedByGroup().forEach { (group, tags) ->
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = Dimens.GapSmall),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.ChipGap)) {
                        tags.forEach { tag ->
                            val on = tag.key in pickedTags
                            FilterChip(
                                selected = on,
                                onClick = {
                                    pickedTags = when {
                                        on -> pickedTags - tag.key
                                        pickedTags.size >= 6 -> pickedTags
                                        else -> pickedTags + tag.key
                                    }
                                },
                                // 车机档命中区同样要≥88dp；外层 verticalScroll 保证“添加”按钮仍可达
                                modifier = Modifier.carTouchTarget(),
                                label = { Text(tag.label) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, streamUrl, category, frequency, description, pickedTags) },
                enabled = isValid
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
