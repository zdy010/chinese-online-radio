package com.radio.chinese.ui.keysettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.service.CarKeyAction
import com.radio.chinese.service.KeyBinding
import com.radio.chinese.service.KeyBindingStore
import com.radio.chinese.service.KeyCapture
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.carTouchTarget
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.isCarSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 一次录制的等待上限：30 秒没人按键就自己收，免得用户走开了还在偷偷攒按键 */
private const val CAPTURE_TIMEOUT_MS = 30_000L

@HiltViewModel
class KeySettingsViewModel @Inject constructor(
    private val store: KeyBindingStore
) : ViewModel() {

    val bindings = store.bindings

    fun bind(action: CarKeyAction, binding: KeyBinding) {
        viewModelScope.launch { store.bind(action, binding) }
    }

    fun unbind(action: CarKeyAction) {
        viewModelScope.launch { store.unbind(action) }
    }

    fun clearAll() {
        viewModelScope.launch { store.clearAll() }
    }
}

/**
 * 车机按键设置：把方向盘/面板上任意一个能送到应用的键，绑到四个功能之一。
 *
 * 设计取向有两条，都是为了让这套配置不可能把自己锁死：
 * 1) 绑定只「追加」触发源，默认的标准媒体键切台照常有效；
 * 2) 录制时列出所有到达应用的按键，一个候选都没有即说明该键没送到应用，
 *    用户当场就知道要找车机设置（而不是以为 App 坏了）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeySettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: KeySettingsViewModel = hiltViewModel()
) {
    val bindings by viewModel.bindings.collectAsState()
    var recordingFor by remember { mutableStateOf<CarKeyAction?>(null) }
    val candidates by KeyCapture.candidates.collectAsState()

    // 录制超时自动收；recordingFor 一变（含置空）这个协程就重启，不会留下悬等待
    LaunchedEffect(recordingFor) {
        val action = recordingFor ?: return@LaunchedEffect
        KeyCapture.start()
        delay(CAPTURE_TIMEOUT_MS)
        if (recordingFor == action) {
            recordingFor = null
            KeyCapture.stop()
        }
    }

    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedWindowInsets,
                title = { Text("按键设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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
            ) {
                CarKeyAction.entries.forEach { action ->
                    ListItem(
                        headlineContent = { Text(action.label) },
                        supportingContent = {
                            val bound = bindings[action]
                            Text(
                                when {
                                    bound != null -> "已绑：${bound.display()}"
                                    action == CarKeyAction.GO_BACK -> "未绑定（返回上一页只能绑非媒体键）"
                                    else -> "未绑定"
                                }
                            )
                        },
                        modifier = Modifier.carTouchTarget(),
                        trailingContent = {
                            TextButton(
                                onClick = { recordingFor = action },
                                modifier = Modifier.carTouchTarget()
                            ) {
                                Text("录制")
                            }
                        }
                    )
                    HorizontalDivider()
                }

                TextButton(
                    onClick = { viewModel.clearAll() },
                    modifier = Modifier
                        .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapMedium)
                        .carTouchTarget()
                ) {
                    Text("全部恢复默认")
                }

                /*
                 * 录制面板做成同页内联区块，不用 AlertDialog：弹窗是另一个窗口，
                 * 它会自己接管按键分发，Activity.dispatchKeyEvent 根本收不到，
                 * 用户按什么都不会出候选（已实测踩过）。内联则跟设置页走同一条分发链路。
                 */
                recordingFor?.let { action ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapMedium)
                    ) {
                        Column(modifier = Modifier.padding(Dimens.GapLarge)) {
                            Text(
                                text = "正在为「${action.label}」录制",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(Dimens.GapSmall))
                            Text(
                                text = "请按要绑定的车机按键；30 秒内没有按键则自动取消。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(Dimens.GapMedium))
                            if (candidates.isEmpty()) {
                                Text(
                                    text = "（还没收到按键。什么都没有＝这个键没送到应用）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                candidates.forEach { candidate ->
                                    Text(
                                        text = candidate.display(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .carTouchTarget()
                                            .clickable {
                                                viewModel.bind(action, candidate)
                                                recordingFor = null
                                                KeyCapture.stop()
                                            }
                                            .padding(vertical = Dimens.GapSmall)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Dimens.GapSmall),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        recordingFor = null
                                        KeyCapture.stop()
                                    }
                                ) {
                                    Text("取消")
                                }
                                if (bindings[action] != null) {
                                    TextButton(
                                        onClick = {
                                            viewModel.unbind(action)
                                            recordingFor = null
                                            KeyCapture.stop()
                                        }
                                    ) {
                                        Text("清除该功能绑定")
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "绑定的键是额外加的一个触发源，原来能用的键不会失效。" +
                        "录制时列表为空＝这个键没送到应用，可在「按键诊断」再确认一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapMedium)
                )
            }
        }
    }
}
