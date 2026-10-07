package com.radio.chinese.ui.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.radio.chinese.service.HardwareRadioProbe
import com.radio.chinese.service.HwLine
import com.radio.chinese.service.KeyDiagnostics
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.SectionHeader
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 按键诊断页：列出方控/车机按键的实时记录与来源通道。
 *
 * 上车后按一遍方向盘/面板按键——出现记录说明按键送到了应用（标准通道，已
 * 按应用语义处理）；什么都没出现说明该键没走这些通道，需要另找私有通道适配。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onNavigateBack: () -> Unit) {
    val entries by KeyDiagnostics.entries.collectAsState()
    val context = LocalContext.current
    var hwLines by remember { mutableStateOf<List<HwLine>>(emptyList()) }
    var probeToken by remember { mutableIntStateOf(0) }

    // 探测含 exec getprop，放 IO 线程；进页跑一次，点「重新探测」让 token 变化再跑
    LaunchedEffect(probeToken) {
        hwLines = withContext(Dispatchers.IO) { HardwareRadioProbe.probe(context) }
    }

    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedWindowInsets,
                title = { Text("按键诊断") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { KeyDiagnostics.clear() }) {
                        Text("清空")
                    }
                }
            )
        }
    ) { padding ->
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
                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapLarge)
            ) {
                Text(
                    text = "上车后按一遍方向盘/面板按键：这里出现记录＝按键已送到应用（走标准通道）；" +
                        "什么都没出现＝该键没送来，需要另找私有通道。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Dimens.GapMedium))
                HorizontalDivider()
                if (entries.isEmpty()) {
                    Text(
                        text = "（暂无记录）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Dimens.GapLarge)
                    )
                } else {
                    entries.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = Dimens.GapSmall)
                        )
                        HorizontalDivider()
                    }
                }

                // 硬件收音机能力：上车打开这一页就能判断，不需要 adb、不需要翻日志
                SectionHeader(
                    title = "硬件能力",
                    actionLabel = "重新探测",
                    onAction = { probeToken++ },
                    modifier = Modifier.fillMaxWidth()
                )
                if (hwLines.isEmpty()) {
                    Text(
                        text = "正在探测…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Dimens.GapLarge)
                    )
                } else {
                    hwLines.forEach { line ->
                        Text(
                            text = "${line.label}：${line.value}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Dimens.GapSmall)
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
