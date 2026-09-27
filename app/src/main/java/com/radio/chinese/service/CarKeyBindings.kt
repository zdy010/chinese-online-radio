package com.radio.chinese.service

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 可被车机按键触发的四个功能。token 是持久化用的稳定键名，改名不会丢用户已绑的配置。 */
enum class CarKeyAction(val token: String, val label: String) {
    PREV_STATION("prev_station", "上一电台"),
    NEXT_STATION("next_station", "下一电台"),
    PLAY_PAUSE("play_pause", "播放 / 暂停"),
    GO_BACK("go_back", "返回上一页")
}

/**
 * 一条按键绑定。
 *
 * 当前设置页只产出 [KeyCode]。[BroadcastAction] 是等上车确认方控走私有广播后补的那半——
 * 现在就把存储格式与解析做出来，否则将来要动数据格式、用户已绑的配置会作废。
 */
sealed class KeyBinding {

    data class KeyCode(val code: Int) : KeyBinding()
    data class BroadcastAction(val action: String) : KeyBinding()

    val token: String
        get() = when (this) {
            is KeyCode -> "key:$code"
            is BroadcastAction -> "act:$action"
        }

    /** 设置页展示文案，与 keybind_check.ps1 断言的 KEYCODE_XXX(nn) 格式一致 */
    fun display(): String = when (this) {
        is KeyCode -> "${KeyEvent.keyCodeToString(code)}($code)"
        is BroadcastAction -> action
    }

    companion object {
        /** 认不出的 token 一律当未绑定，不让坏数据把按键整个弄哑 */
        fun parse(raw: String?): KeyBinding? {
            if (raw.isNullOrBlank()) return null
            return when {
                raw.startsWith("key:") -> raw.removePrefix("key:").toIntOrNull()?.let { KeyCode(it) }
                raw.startsWith("act:") -> raw.removePrefix("act:").takeIf { it.isNotBlank() }?.let { BroadcastAction(it) }
                else -> null
            }
        }
    }
}

/**
 * 录制态：设置页点「录制」后，把到达应用的按键攒成一份供用户挑选的候选。
 *
 * 与 [KeyDiagnostics] 的分工：那份是上车排查用的流水账（含来源通道、上限 50 条、不进配置界面），
 * 这份只在录制期间攒、按键码去重，录完即清。二者共用同样的埋点，不做第二套事件通路。
 */
object KeyCapture {

    private const val MAX_CANDIDATES = 12

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    private val _candidates = MutableStateFlow<List<KeyBinding.KeyCode>>(emptyList())
    val candidates: StateFlow<List<KeyBinding.KeyCode>> = _candidates

    fun start() {
        _candidates.value = emptyList()
        _listening.value = true
    }

    fun stop() {
        _listening.value = false
    }

    fun offer(code: Int) {
        if (!_listening.value) return
        _candidates.update { list ->
            if (list.any { it.code == code }) list
            else (list + KeyBinding.KeyCode(code)).takeLast(MAX_CANDIDATES)
        }
    }
}
