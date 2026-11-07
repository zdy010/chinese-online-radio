package com.radio.chinese.data.repository

import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.domain.model.PlayabilityScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放可用性评分的持久化与内存缓存。
 *
 * 内存里是唯一真值（ConcurrentHashMap），落盘异步：列表着色要立刻读到新分数，
 * 不能等一次 DataStore edit。进程重启后从 JSON 恢复，只存有播放记录的键
 * （12k 条清单不会全写进去）。
 *
 * 键约定：台级 = `stationId`；源级 = `stationId#sourceUrl`（同一台不同源分别记分，
 * 下次起播按源分数降序，见 PlayerManager）。
 */
@Singleton
class PlayabilityStore @Inject constructor(
    private val preferences: RadioPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Int>()
    private val serializer = MapSerializer(String.serializer(), Int.serializer())

    private val _revision = MutableStateFlow(0)

    /** UI 订阅它触发重组；分数本身用 [scoreOf] 同步读，避免每个列表行都发一次 Flow */
    val revision: StateFlow<Int> = _revision

    init {
        scope.launch {
            runCatching {
                val raw = preferences.playabilityJson.first()
                if (raw.isNotBlank()) {
                    Json.decodeFromString(serializer, raw).forEach { (k, v) -> cache[k] = v }
                    _revision.value++
                }
            }
        }
    }

    fun scoreOf(key: String): Int = cache[key] ?: PlayabilityScore.UNKNOWN

    fun reportSuccess(key: String) = update(key, PlayabilityScore.onSuccess(scoreOf(key)))

    fun reportFailure(key: String) = update(key, PlayabilityScore.onFailure(scoreOf(key)))

    private fun update(key: String, value: Int) {
        val previous = cache.put(key, value)
        if (previous == value) return
        _revision.value++
        scope.launch {
            runCatching { preferences.setPlayabilityJson(Json.encodeToString(serializer, cache.toMap())) }
        }
    }

    companion object {
        fun sourceKey(stationId: String, url: String) = "$stationId#$url"
    }
}
