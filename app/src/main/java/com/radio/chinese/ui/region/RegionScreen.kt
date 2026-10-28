package com.radio.chinese.ui.region

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.RegionBucket
import com.radio.chinese.domain.model.RegionSection
import com.radio.chinese.domain.model.StationFilter
import com.radio.chinese.domain.model.StationRegion
import com.radio.chinese.ui.common.EmptyState
import com.radio.chinese.ui.common.LoadingState
import com.radio.chinese.ui.common.NestedWindowInsets
import com.radio.chinese.ui.common.StationActionDialog
import com.radio.chinese.ui.common.carTouchTarget
import com.radio.chinese.ui.common.rememberStationRowOnClick
import com.radio.chinese.ui.home.HomeUiState
import com.radio.chinese.ui.home.HomeViewModel
import com.radio.chinese.ui.home.StationListItem
import com.radio.chinese.ui.theme.Dimens

/**
 * 按地区选台。
 *
 * 451 个台平铺在「全部」里只能靠搜索找，这一页把它们按 中央 / 本地 / 各省 / 境外 切成
 * 三四十个入口，先选地区再看台。
 *
 * 地区不在 radio_stations.json 里存字段：仓库已有 [com.radio.chinese.domain.model.RegionResolver]
 * 从台名/简介反推省市（「本地优先」排序在用），这里复用同一套推断，避免同一件事两个真相来源。
 *
 * 两级结构照 CategoryScreen：第一级是地区目录，第二级是该地区的电台列表。
 * 页内状态用 rememberSaveable——左右翻页会重建 composable，普通 remember 会把选中地区丢掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionScreen(
    onNavigateToPlayer: (String) -> Unit,
    onNavigateBack: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    showTopBar: Boolean = true,
    searchQuery: String = "",
) {
    val uiState by viewModel.uiState.collectAsState()
    val buckets by viewModel.regionBuckets.collectAsState()
    val userRegion by viewModel.userRegion.collectAsState()
    val currentStation by viewModel.playerManager.currentStation.collectAsState()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsState()
    // 与首页同一套行点击语义：点非当前台只起播并留在列表
    val onRowClick = rememberStationRowOnClick(viewModel.playerManager, onNavigateToPlayer)

    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    // 与其他筛选页一致的屏蔽入口：长按行弹动作菜单（用户实测问题 4）
    var actionStation by remember { mutableStateOf<RadioStation?>(null) }

    // 与分类页同一个理由：页签形态没有顶栏返回钮，系统返回会被首页的「双击退出」吃掉，
    // 进了某个省就再也退不回地区目录。
    BackHandler(enabled = selectedKey != null) { selectedKey = null }

    // 搜索沿用顶栏那一个输入框：只在有命中时才保留分组，台数也跟着变成命中数，
    // 免得用户打了字却发现地区目录一动不动。
    val shownBuckets = remember(buckets, searchQuery) {
        if (searchQuery.isBlank()) buckets
        else buckets.mapNotNull { bucket ->
            val hit = StationFilter.apply(bucket.stations, null, searchQuery)
            if (hit.isEmpty()) null else bucket.copy(stations = hit)
        }
    }
    val selectedBucket = shownBuckets.firstOrNull { it.key == selectedKey }

    Scaffold(
        contentWindowInsets = NestedWindowInsets,
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    windowInsets = NestedWindowInsets,
                    title = { Text(selectedBucket?.label ?: "按地区选台") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectedBucket != null) selectedKey = null else onNavigateBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        },
    ) { padding ->
        val m = if (showTopBar) Modifier.padding(padding) else Modifier
        when {
            uiState.isLoading && buckets.isEmpty() -> {
                LoadingState(text = "正在加载电台…", modifier = m)
            }

            buckets.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.Public,
                    title = "还没有可浏览的地区",
                    hint = "先在设置里添加电台源，再回来看看",
                    modifier = m,
                )
            }

            selectedBucket == null -> {
                RegionIndex(
                    buckets = shownBuckets,
                    userProvince = userRegion.province,
                    total = uiState.stations.size,
                    onPick = { selectedKey = it },
                    onChangeRegion = { pickerOpen = true },
                    modifier = m,
                )
            }

            else -> {
                RegionStationList(
                    bucket = selectedBucket,
                    uiState = uiState,
                    currentStationId = currentStation?.id,
                    isPlaying = isPlaying,
                    showBackRow = !showTopBar,
                    onBack = { selectedKey = null },
                    onPlay = { station -> onRowClick(station) },
                    onFavorite = viewModel::toggleFavorite,
                    onLongPress = { station -> actionStation = station },
                    modifier = m,
                )
            }
        }
    }

    if (pickerOpen) {
        RegionPickerDialog(
            provinces = viewModel.provinceNames,
            current = userRegion.province,
            onDismiss = { pickerOpen = false },
            onPick = { province ->
                viewModel.setManualRegion(province)
                pickerOpen = false
            },
        )
    }

    actionStation?.let { station ->
        StationActionDialog(
            station = station,
            // 地区页只列有效电台（getActiveStations 已隐去屏蔽项），“恢复为有效”分支不会命中
            isInvalid = false,
            isCustom = uiState.customIds.contains(station.id),
            onDismiss = { actionStation = null },
            onMarkInvalid = { viewModel.markStationInvalid(station.id); actionStation = null },
            onMarkValid = { viewModel.restoreStation(station.id); actionStation = null },
            onDelete = { viewModel.deleteCustomStation(station.id); actionStation = null },
        )
    }
}

@Composable
private fun RegionIndex(
    buckets: List<RegionBucket>,
    userProvince: String,
    total: Int,
    onPick: (String) -> Unit,
    onChangeRegion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
    ) {
        item(key = "region-header") {
            RegionHeaderCard(
                // 未设置时不写「本地 · 未知」：先告诉用户这一栏是干什么的，再给个入口去设
                label = if (userProvince.isBlank()) "未设置本地地区" else "本地 · $userProvince",
                hint = if (userProvince.isBlank()) "选一个常用省份，「本地」会直接列出该省的电台"
                else "共 $total 个电台，按地区浏览",
                onClick = onChangeRegion,
            )
        }
        itemsIndexed(buckets, key = { _, bucket -> bucket.key }) { index, bucket ->
            // 进「国外」段时插一条分隔标题：没有它，台湾/香港会和美国/英国
            // 平排在一起，看不出哪个是国内哪个是国外
            if (index == 0 || buckets[index - 1].section != bucket.section) {
                RegionSectionHeader(bucket.section)
            }
            RegionBucketRow(
                bucket = bucket,
                icon = bucketIcon(bucket),
                onClick = { onPick(bucket.key) },
            )
        }
    }
}

@Composable
private fun RegionHeaderCard(label: String, hint: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.RowMinHeight)
                .padding(Dimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(Dimens.IconLarge),
            )
            Spacer(modifier = Modifier.width(Dimens.GapMedium))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** 大段分隔标题。港澳台算中国段，国外按国家算另一段，靠它把两层语义分开。 */
@Composable
private fun RegionSectionHeader(section: RegionSection) {
    Text(
        text = if (section == RegionSection.CN) "中国" else "国外",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Dimens.GapMedium),
    )
}

@Composable
private fun RegionBucketRow(bucket: RegionBucket, icon: ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.RowMinHeight)
                .padding(horizontal = Dimens.CardPadding, vertical = Dimens.RowVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.IconMedium),
            )
            Spacer(modifier = Modifier.width(Dimens.GapMedium))
            Column(Modifier.weight(1f)) {
                Text(text = bucket.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${bucket.stations.size} 个电台",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun RegionStationList(
    bucket: RegionBucket,
    uiState: HomeUiState,
    currentStationId: String?,
    isPlaying: Boolean,
    showBackRow: Boolean,
    onBack: () -> Unit,
    onPlay: (RadioStation) -> Unit,
    onFavorite: (String) -> Unit,
    onLongPress: (RadioStation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (showBackRow) {
            // 无顶栏形态给一条看得见的返回行，与分类页同款：
            // 不能只靠「滑回上一页 / 返回键」这种看不见的出路
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .carTouchTarget()
                    .clickable(onClick = onBack)
                    .padding(horizontal = Dimens.ScreenPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回地区")
                Spacer(modifier = Modifier.width(Dimens.GapSmall))
                Text(bucket.label, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (bucket.stations.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Radio,
                title = "这个地区下还没有电台",
                hint = "换个地区看看，或直接搜索台名",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(Dimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
            ) {
                items(bucket.stations, key = { it.id }) { station ->
                    StationListItem(
                        station = station,
                        isPlaying = currentStationId == station.id && isPlaying,
                        isFavorite = uiState.favoriteIds.contains(station.id),
                        onClick = { onPlay(station) },
                        onFavoriteClick = { onFavorite(station.id) },
                        onLongClick = { onLongPress(station) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RegionPickerDialog(
    provinces: List<String>,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择本地省份") },
        text = {
            // 上限跟着屏高收敛：横屏手机与超宽车机只有三四百 dp 高，
            // 单用 RegionPickerMaxHeight 会把「取消」顶出屏幕
            LazyColumn(
                modifier = Modifier.heightIn(
                    max = Dimens.dialogListMaxHeight(Dimens.RegionPickerMaxHeight)
                )
            ) {
                items(provinces, key = { it }) { province ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .carTouchTarget()
                            .clickable { onPick(province) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 整行都可点，RadioButton 只是选中态指示，不给它单独的点击回调
                        RadioButton(selected = province == current, onClick = { onPick(province) })
                        Text(text = province, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 分组图标。省份共用 Place，外国共用 Language，中央/本地/其他各有自己的符号。
 * 国外按国家分组后 key 是动态的，不能再拿 key 比字面量，改看 section。
 */
private fun bucketIcon(bucket: RegionBucket): ImageVector = when {
    bucket.key == StationRegion.CENTRAL -> Icons.Default.AccountBalance
    bucket.key == StationRegion.LOCAL -> Icons.Default.MyLocation
    bucket.key == StationRegion.OTHER -> Icons.Default.MoreHoriz
    // 电视伴音不是地区，图标必须与省/国家那把图钉分开，否则看上去又是一个普通分组
    bucket.key == StationRegion.TV_AUDIO -> Icons.Default.Tv
    bucket.section == RegionSection.FOREIGN -> Icons.Default.Language
    else -> Icons.Default.Place
}
