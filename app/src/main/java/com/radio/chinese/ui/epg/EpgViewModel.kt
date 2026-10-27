package com.radio.chinese.ui.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radio.chinese.data.repository.EpgRepository
import com.radio.chinese.data.repository.EpgSnapshot
import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.epg.EpgQuery
import com.radio.chinese.domain.model.EpgProgram
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/** 日期切换行的一项。label 是「今天/明天/后天」，date 是请求用的 yyyy-MM-dd。 */
data class EpgDay(val date: String, val label: String)

/** 节目单页的一行：一个台 + 它当天的节目。 */
data class EpgRow(
    val station: RadioStation,
    val programs: List<EpgProgram>,
    val refreshFailed: Boolean,
    val fetchedAt: Long?,
)

data class EpgUiState(
    val days: List<EpgDay> = emptyList(),
    val selectedDate: String = "",
    val rows: List<EpgRow> = emptyList(),
    val minute: Int = 0,
    val query: String = "",
    val loading: Boolean = true,
) {
    /**
     * 页面搜索只过滤已经拿到的节目，不触发新的请求：在 72 个台上做实时搜索等于
     * 每次输入都打穿一次接口，远超「单台单日按需」的边界。
     */
    val visibleRows: List<EpgRow>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return rows
            return rows.filter { row ->
                row.station.name.contains(q, ignoreCase = true) ||
                    row.programs.any { it.title.contains(q, ignoreCase = true) }
            }
        }
}

@HiltViewModel
class EpgViewModel @Inject constructor(
    private val epgRepository: EpgRepository,
    private val stationRepository: StationRepository,
    // 公开给 EpgScreen 的 rememberStationRowOnClick / rememberNowPlaying 用：
    // 节目单页的行点击语义与波浪标记必须和其余电台列表走同一个判定，不能各写一套
    val playerManager: PlayerManager,
) : ViewModel() {

    private val _state = MutableStateFlow(EpgUiState(minute = nowMinute()))
    val state: StateFlow<EpgUiState> = _state

    private var loadJob: Job? = null

    init {
        val today = LocalDate.now(EpgQuery.BROADCAST_ZONE)
        val days = (0L..2L).map { offset ->
            EpgDay(date = today.plusDays(offset).toString(), label = dayLabel(offset))
        }
        _state.value = _state.value.copy(days = days, selectedDate = days.first().date)
        load(days.first().date)

        // 只更新分钟数，不重排列表：每分钟把行顺序抖一次，正在往下翻的手会跟丢。
        // 排序在每次加载时基于当刻时间算一次就够了。
        viewModelScope.launch {
            while (true) {
                delay(60_000L)
                _state.value = _state.value.copy(minute = nowMinute())
            }
        }
    }

    fun selectDate(date: String) {
        if (date == _state.value.selectedDate) return
        _state.value = _state.value.copy(selectedDate = date, rows = emptyList())
        load(date)
    }

    fun updateQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun retry() = load(_state.value.selectedDate)

    private fun load(date: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(selectedDate = date, loading = true)
            val stations = stationRepository.getActiveStations().associateBy { it.id }
            // 只列真正存在于台库里的台：映射表里若残留一个已下架的台，没有台名可显示也点不开
            val ids = epgRepository.stationIds.filter { it in stations }

            // 先把缓存铺上去：第二次进页面基本都是秒开，不必等 72 个台逐个确认
            val cached = epgRepository.cachedPrograms(date).groupBy { it.stationId }
            if (cached.isNotEmpty()) {
                publish(date, buildRows(stations, ids, cached, emptyMap()))
            }

            epgRepository.cleanup(LocalDate.now(EpgQuery.BROADCAST_ZONE).toString())
            val snapshots = epgRepository.loadAll(ids, date)
            publish(date, buildRows(stations, ids, cached, snapshots))
        }
    }

    private fun publish(date: String, rows: List<EpgRow>) {
        if (_state.value.selectedDate != date) return
        _state.value = _state.value.copy(rows = rows, loading = false)
    }

    /**
     * 拼行。当天完全没有节目的台不出现——无论是拉取失败还是接口确实回了空，
     * 这一行都只剩台名，占着屏幕却不提供信息，等于把接口故障摊给用户看。
     */
    private fun buildRows(
        stations: Map<String, RadioStation>,
        ids: List<String>,
        cached: Map<String, List<EpgProgram>>,
        snapshots: Map<String, EpgSnapshot>,
    ): List<EpgRow> {
        val rows = ids.mapNotNull { id ->
            val station = stations[id] ?: return@mapNotNull null
            val snapshot = snapshots[id]
            val programs = snapshot?.programs ?: cached[id].orEmpty()
            if (programs.isEmpty()) return@mapNotNull null
            EpgRow(
                station = station,
                programs = programs,
                refreshFailed = snapshot?.refreshFailed ?: false,
                fetchedAt = snapshot?.fetchedAt,
            )
        }
        return EpgQuery.nowPlayingFirst(
            items = rows,
            minute = _state.value.minute,
            programsOf = { it.programs },
            nameOf = { it.station.name },
        )
    }

    private fun dayLabel(offset: Long): String = when (offset) {
        0L -> "今天"
        1L -> "明天"
        else -> "后天"
    }

    private fun nowMinute(): Int =
        EpgQuery.minuteOf(LocalDateTime.now(EpgQuery.BROADCAST_ZONE))
}
