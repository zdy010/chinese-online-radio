package com.radio.chinese.domain.model

/**
 * TTS 引擎探测的纯判定层。
 *
 * 存在的原因：车机（方易通/嘟嘟OS 等）上「有引擎却读不到」是常态，而旧实现把
 * 「音色清单为空」直接当成「没有引擎」，一条链静默失效且不报错。这里把三件事拆开，
 * 好让上层能分辨到底是哪一环断的：
 * 1) 候选从哪些渠道来（标准 action 枚举 / 系统设置项 / 不指定引擎由系统解析）；
 * 2) 一个引擎到底能不能念中文（无 Voice 接口的老引擎照样能念）；
 * 3) 失败该给用户看什么结论（而不是笼统一句"未检测到"）。
 */

/** 引擎候选的来源渠道；不同渠道断掉的含义与处置办法不同。 */
enum class CandidateSource {
    /** 不指定包名，交给系统按默认合成器解析——受包可见性影响最小的路径。 */
    SYSTEM_DEFAULT,

    /** queryIntentServices(android.intent.action.TTS_SERVICE) 枚举所得。 */
    INTENT_QUERY,

    /** Settings.Secure.tts_default_synth 里的包名——系统认下来的引擎，读它不需要可见性。 */
    SETTINGS_DEFAULT_SYNTH,
}

/** 一个候选引擎的探测结果快照（不含 Android 对象，便于单测）。 */
data class EngineProbe(
    val packageLabel: String,
    val displayName: String,
    val source: CandidateSource,
    val initOk: Boolean,
    /** 是否给出音色清单：老式/厂商引擎常为 false，但它仍可用内置音色念中文。 */
    val supportsVoiceApi: Boolean,
    val chineseVoiceCount: Int,
    /** isLanguageAvailable(zh-CN) 是否可用。 */
    val chineseLanguageOk: Boolean,
)

/** 一次完整探测的结论材料。 */
data class TtsReport(
    val defaultSynthComponent: String?,
    val intentQueryCount: Int,
    val engines: List<EngineProbe>,
)

/** 分级诊断结论类型，决定设置页文案与用户下一步动作。 */
enum class TtsVerdictLevel { OK, LEGACY_DEFAULT_VOICE, NO_CHINESE_DATA, BIND_FAILED, ENGINE_INVISIBLE, NO_ENGINE }

data class TtsVerdict(val level: TtsVerdictLevel, val headline: String, val detail: String)

data class TtsCandidate(val packageLabel: String, val source: CandidateSource)

object TtsProbe {
    /** 空包名 = 不指定引擎，由系统解析默认合成器。 */
    const val SYSTEM_DEFAULT: String = ""

    /**
     * 合并候选：系统默认解析恒在首位（它最不受车机包可见性影响），其后是枚举结果与设置项包名。
     * 同包去重，保留先出现的来源，避免同一引擎被重复绑定探测。
     */
    fun mergeCandidates(
        intentPackages: List<String>,
        defaultSynthPackage: String?,
    ): List<TtsCandidate> {
        val ordered = mutableListOf(TtsCandidate(SYSTEM_DEFAULT, CandidateSource.SYSTEM_DEFAULT))
        fun add(pkg: String?, source: CandidateSource) {
            val p = pkg?.trim().orEmpty()
            if (p.isEmpty()) return
            if (ordered.none { it.packageLabel == p }) ordered += TtsCandidate(p, source)
        }
        intentPackages.forEach { add(it, CandidateSource.INTENT_QUERY) }
        add(defaultSynthPackage, CandidateSource.SETTINGS_DEFAULT_SYNTH)
        return ordered
    }

    /** 从 tts_default_synth 的组件串取包名，形如 "com.x.tts/.Service"。 */
    fun packageOf(component: String?): String? =
        component?.substringBefore('/')?.trim()?.takeIf { it.isNotEmpty() }

    /** 能不能念中文：绑定成功是前提；无音色清单但中文语言可用也算。 */
    fun speakable(e: EngineProbe): Boolean =
        e.initOk && (e.chineseVoiceCount > 0 || e.chineseLanguageOk)

    /** 分级结论：先判能不能播，再按渠道区分"看不到"还是"真没有"还是"缺语音包"。 */
    fun verdict(report: TtsReport): TtsVerdict {
        val usable = report.engines.filter { speakable(it) }
        val synthPkg = packageOf(report.defaultSynthComponent)

        if (usable.isNotEmpty()) {
            val zhTotal = usable.sumOf { it.chineseVoiceCount }
            return if (zhTotal > 0) {
                TtsVerdict(
                    TtsVerdictLevel.OK,
                    "语音引擎可用",
                    "共 $zhTotal 个中文音色，可逐个试听选定",
                )
            } else {
                TtsVerdict(
                    TtsVerdictLevel.LEGACY_DEFAULT_VOICE,
                    "引擎支持中文，但不提供音色清单",
                    "老式引擎将用其内置默认音色播报，音色清单不可选",
                )
            }
        }

        if (report.intentQueryCount == 0 && synthPkg != null) {
            return TtsVerdict(
                TtsVerdictLevel.ENGINE_INVISIBLE,
                "系统有引擎但枚举不到：$synthPkg",
                "标准 TTS action 查不到它（车机 ROM 常见）。按包名直连仍未绑上，需为该包补可见性声明",
            )
        }

        if (report.engines.none { it.initOk }) {
            return if (report.engines.isEmpty()) {
                TtsVerdict(
                    TtsVerdictLevel.NO_ENGINE,
                    "系统未注册任何语音引擎",
                    "嘟嘟桌面的播报可能走自带语音 SDK，不对外提供标准 TTS；需安装中文 TTS 引擎 APK",
                )
            } else {
                TtsVerdict(
                    TtsVerdictLevel.BIND_FAILED,
                    "找到引擎但绑定失败或超时",
                    "引擎可能被停用、冷启动过慢或未授予可见性；可在系统设置里确认引擎状态后重试",
                )
            }
        }

        return TtsVerdict(
            TtsVerdictLevel.NO_CHINESE_DATA,
            "引擎已就绪，但没有中文语音数据",
            "到车机系统设置→语言/文字转语音输出，为该引擎下载中文语音包后重试",
        )
    }

    /**
     * 失败重试闸门：整条解析全失败后，在窗口内不再重复"枚举+逐引擎绑定"。
     * 没有时间闸门时，每次线控切台都会重跑一遍多引擎冷绑定，车机上表现为切台卡顿数秒。
     * 时钟由调用方传入，纯逻辑不依赖系统时间，便于单测。
     */
    class RetryGate(private val ttlMillis: Long) {
        private var blockedUntil = 0L
        fun blocked(nowMillis: Long): Boolean = nowMillis < blockedUntil
        fun block(nowMillis: Long) { blockedUntil = nowMillis + ttlMillis }
        fun reset() { blockedUntil = 0L }
    }
}
