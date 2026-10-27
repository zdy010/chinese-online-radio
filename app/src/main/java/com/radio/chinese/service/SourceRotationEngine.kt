package com.radio.chinese.service

import com.radio.chinese.data.repository.StationRepository
import com.radio.chinese.domain.model.StationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 电台多源轮转引擎：管理当前源队列、尝试播放、失败回退与成功记录。
 *
 * 从 PlayerManager 拆出，使"源切换"逻辑可独立单测（不依赖 MediaController）。
 * PlayerManager 通过 [Callbacks] 接收轮转决策结果，自行操作 ExoPlayer。
 */
class SourceRotationEngine(
    private val repository: StationRepository,
    private val scope: CoroutineScope,
) {
    /** PlayerManager 实现的回调接口：引擎只做决策，操作播放器由外部完成。 */
    interface Callbacks {
        /** 请求播放指定源（已选好、已更新状态）。 */
        fun onRequestPlay(source: StationSource, index: Int, total: Int)
        /** 所有源耗尽。 */
        fun onAllSourcesExhausted()
        /** 源分数更新后通知外部（供 UI 显示可用源列表）。 */
        fun onScoresUpdated(scores: List<Pair<StationSource, Float>>)
    }

    private var queue: List<StationSource> = emptyList()
    private var index: Int = 0
    private var callbacks: Callbacks? = null

    /** 为一次播放初始化源队列。 */
    suspend fun initialize(stationId: String, callbacks: Callbacks) {
        this.callbacks = callbacks
        queue = repository.getSortedSources(stationId)
        index = 0
        val scores = repository.getSourcesWithScore(stationId)
        callbacks.onScoresUpdated(scores)
    }

    /** 队列大小（UI 用来显示"第 N 个/M 个节目源"）。 */
    fun queueSize(): Int = queue.size

    /** 当前索引。 */
    fun currentIndex(): Int = index

    /** 开始尝试当前队列的第一个源。 */
    fun startRotation() {
        tryCurrent()
    }

    /** 当前源失败，推进到下一个。 */
    fun onCurrentFailed(stationId: String) {
        val failedUrl = queue.getOrNull(index)?.url ?: return
        scope.launch { repository.recordSourceFailure(stationId, failedUrl) }
        index++
        tryCurrent()
    }

    /** 当前源播放成功，记录并刷新分数。 */
    fun onCurrentSuccess(stationId: String) {
        val successUrl = queue.getOrNull(index)?.url ?: return
        scope.launch {
            repository.recordSourceSuccess(stationId, successUrl, 0)
            val cb = callbacks ?: return@launch
            cb.onScoresUpdated(repository.getSourcesWithScore(stationId))
        }
    }

    /**
     * 用户手动切到指定 URL 的源。
     *
     * 分数写入必须在起播之前完成：新源往往就是那个刚被惩罚过的源，
     * 若开播与 setSourceScore 并发，它秒失败时 recordFailure 与 setScore(1.0)
     * 的先后就不确定，手动选择的分数可能反过来抹掉惩罚，下次它又被排到第一个。
     */
    fun switchTo(stationId: String, sourceUrl: String) {
        val idx = queue.indexOfFirst { it.url == sourceUrl }
        if (idx < 0) return
        index = idx
        scope.launch {
            repository.setSourceScore(stationId, sourceUrl, 1.0f)
            tryCurrent()
        }
    }

    private fun tryCurrent() {
        if (index >= queue.size) {
            callbacks?.onAllSourcesExhausted()
            return
        }
        val source = queue[index]
        callbacks?.onRequestPlay(source, index, queue.size)
    }
}
