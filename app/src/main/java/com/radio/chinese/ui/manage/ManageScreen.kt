package com.radio.chinese.ui.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.StreamStatus

private val CATEGORIES = listOf(
    "news" to "新闻", "music" to "音乐", "traffic" to "交通",
    "arts" to "文艺", "sports" to "体育", "finance" to "财经",
    "opera" to "戏曲", "general" to "综合"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageScreen(
    onNavigateBack: () -> Unit,
    viewModel: ManageViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var actionStation by remember { mutableStateOf<RadioStation?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
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
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
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
                Text(
                    text = "正在检测... ${uiState.checkedCount}/${uiState.totalCount}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
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
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(uiState.stations, key = { it.id }) { station ->
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
            onConfirm = { name, url, category, freq, desc ->
                viewModel.addCustomStation(name, url, category, freq, desc)
                showAddDialog = false
            }
        )
    }
}

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
            .clickable(onClick = onClick),
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
                .padding(12.dp),
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
                        Spacer(modifier = Modifier.width(6.dp))
                        SuggestionChip(
                            onClick = {},
                            label = { Text("自定义", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(20.dp)
                        )
                    }
                    if (isInvalid) {
                        Spacer(modifier = Modifier.width(6.dp))
                        SuggestionChip(
                            onClick = {},
                            label = { Text("已标记无效", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(20.dp)
                        )
                    }
                }
                val subtitle = buildString {
                    if (station.frequency.isNotBlank()) append(station.frequency)
                    if (station.frequency.isNotBlank() && station.category.isNotBlank()) append(" · ")
                    append(CATEGORIES.find { it.first == station.category }?.second ?: station.category)
                    if (checkResult != null && checkResult.status != StreamStatus.CHECKING) {
                        append(" · ${checkResult.message}")
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

@Composable
private fun StationActionDialog(
    station: RadioStation,
    isInvalid: Boolean,
    isCustom: Boolean,
    onDismiss: () -> Unit,
    onCheck: () -> Unit,
    onMarkInvalid: () -> Unit,
    onMarkValid: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(station.name) },
        text = {
            Column {
                if (station.streamUrl.isNotBlank()) {
                    Text(
                        text = station.primaryUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    val sourceCount = station.allSources().size
                    if (sourceCount > 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "共 $sourceCount 个节目源",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Check button
                ListItem(
                    headlineContent = { Text("检测连接") },
                    leadingContent = { Icon(Icons.Default.Refresh, null) },
                    modifier = Modifier.clickable(onClick = onCheck)
                )
                HorizontalDivider()

                // Mark invalid / valid
                if (isInvalid) {
                    ListItem(
                        headlineContent = { Text("恢复为有效") },
                        leadingContent = { Icon(Icons.Default.CheckCircle, null,
                            tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable(onClick = onMarkValid)
                    )
                } else {
                    ListItem(
                        headlineContent = { Text("标记为无效") },
                        supportingContent = { Text("标记后不在主页显示，但保留记录") },
                        leadingContent = { Icon(Icons.Default.Warning, null,
                            tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable(onClick = onMarkInvalid)
                    )
                }

                // Delete (only for custom)
                if (isCustom) {
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("删除此电台") },
                        supportingContent = { Text("仅可删除自定义添加的电台") },
                        leadingContent = { Icon(Icons.Default.Delete, null,
                            tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable(onClick = onDelete)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddStationDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, category: String, frequency: String, description: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var streamUrl by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("general") }
    var frequency by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var showCategoryMenu by remember { mutableStateOf(false) }

    val isValid = name.isNotBlank() && streamUrl.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义电台") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, streamUrl, category, frequency, description) },
                enabled = isValid
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
