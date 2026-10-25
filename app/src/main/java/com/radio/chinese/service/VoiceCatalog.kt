package com.radio.chinese.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.radio.chinese.domain.model.EngineProbe
import com.radio.chinese.domain.model.TtsReport
import com.radio.chinese.domain.model.TtsVerdict
import com.radio.chinese.domain.model.VoiceMeta
import com.radio.chinese.domain.model.VoiceScoring
import com.radio.chinese.domain.model.TtsProbe
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** 一个「引擎 × 音色」可选项，供设置页列全清单与试听选择。 */
data class TtsOption(val enginePackage: String, val engineLabel: String, val voice: VoiceMeta)

interface VoiceCatalogSource {
    suspend fun listOptions(): List<TtsOption>
    fun defaultEnginePackage(): String?
}

/** 跨引擎从中文音色里挑默认最优（委托纯函数 VoiceScoring）。 */
object VoiceCatalog {
    fun pickDefault(chineseVoices: List<VoiceMeta>): VoiceMeta? = VoiceScoring.bestChinese(chineseVoices)
}

/**
 * 枚举设备上所有 TTS 引擎（不止默认那个），逐引擎取其音色。
 * 每个引擎单独 new 一个 TextToSpeech 读取 voices，读完立即 shutdown，避免泄漏。
 *
 * 车机（方易通/嘟嘟OS 等）上「有引擎却读不到」是常态，故候选取三渠道并集（见 [TtsProbe.mergeCandidates]）：
 * - 不指定包名让系统按默认合成器解析：受安卓 11+ 包可见性影响最小；
 * - queryIntentServices：需 Manifest 声明 <queries> TTS_SERVICE，厂商 Filter 形态不合标准时会是空；
 * - Settings.Secure.tts_default_synth：系统自己认下来的引擎，读这个设置项不需要可见性。
 * 绑上了但拿不到音色清单（老式/厂商引擎不提供 Voice 接口）时，仍按「内置默认音色」给出候选，
 * 不再把「读不到清单」误判成「没有引擎」而静默不播。
 */
@Singleton
class AndroidVoiceCatalog @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceCatalogSource {

    private companion object {
        // android.speech.tts.Voice.QUALITY_DEFAULT 的已知取值；该静态常量在 compileSdk35 stub 中不可直接引用
        const val QUALITY_DEFAULT = 50
        // 同上的 TextToSpeech.LANG_AVAILABLE
        const val LANG_AVAILABLE = 0
        // 车机冷绑定比手机慢得多，1.5s 会把可用引擎误判成「未就绪」
        const val INIT_TIMEOUT_MS = 3_000L
        const val CACHE_TTL_MS = 10 * 60 * 1000L
        const val DEFAULT_VOICE_NAME = "内置默认音色"
    }

    private val probeMutex = Mutex()
    @Volatile private var cachedOptions: List<TtsOption>? = null
    @Volatile private var cachedAt = 0L
    @Volatile private var lastReport: TtsReport? = null

    override fun defaultEnginePackage(): String? =
        TtsProbe.packageOf(readDefaultSynth()) ?: listEnginePackages().firstOrNull()

    override suspend fun listOptions(): List<TtsOption> = optionsUnderLock(force = false)

    /** 强制重探：设置页「重新检测」与引擎装完后用。 */
    suspend fun refreshOptions(): List<TtsOption> = optionsUnderLock(force = true)

    /** 上次探测的分级结论，供设置页说明断在哪一环；未探测过为 null。 */
    fun lastVerdict(): TtsVerdict? = lastReport?.let { TtsProbe.verdict(it) }
    fun lastReport(): TtsReport? = lastReport

    private suspend fun optionsUnderLock(force: Boolean): List<TtsOption> = probeMutex.withLock {
        val age = android.os.SystemClock.elapsedRealtime() - cachedAt
        val cached = cachedOptions
        if (!force && cached != null && age < CACHE_TTL_MS) return@withLock cached
        val (report, options) = withContext(Dispatchers.Default) { probeEngines() }
        lastReport = report
        cachedOptions = options
        cachedAt = android.os.SystemClock.elapsedRealtime()
        options
    }

    private suspend fun probeEngines(): Pair<TtsReport, List<TtsOption>> {
        val intentPackages = listEnginePackages()
        val synthComponent = readDefaultSynth()
        val candidates = TtsProbe.mergeCandidates(intentPackages, TtsProbe.packageOf(synthComponent))
        val probes = mutableListOf<EngineProbe>()
        val options = mutableListOf<TtsOption>()
        for (c in candidates) {
            val label = if (c.packageLabel.isEmpty()) "系统默认引擎" else engineLabel(c.packageLabel)
            // 引擎绑定是异步的：先等 onInit==SUCCESS，未就绪记为绑定失败，不能当成「无音色」
            val engine = initAwait(c.packageLabel)
            if (engine == null) {
                probes += EngineProbe(c.packageLabel, label, c.source, initOk = false, supportsVoiceApi = false, 0, chineseLanguageOk = false)
                continue
            }
            try {
                val metas = runCatching { engine.voices }.getOrNull()?.map { it.toMeta(c.packageLabel, label) } ?: emptyList()
                val chinese = metas.filter { VoiceScoring.isChinese(it) }
                val zhLang = chinese.isNotEmpty() || chineseLanguageAvailable(engine)
                probes += EngineProbe(c.packageLabel, label, c.source, initOk = true, supportsVoiceApi = metas.isNotEmpty(), chineseVoiceCount = chinese.size, chineseLanguageOk = zhLang)
                options += chinese.map { TtsOption(c.packageLabel, label, it) }
                if (chinese.isEmpty() && zhLang) {
                    // 中文能念但没有音色清单：用引擎内置默认音色，让这条路可选、可试听
                    options += TtsOption(c.packageLabel, label, fallbackMeta(engine, c.packageLabel, label))
                }
            } catch (_: Exception) {
                // 个别引擎取 voices 抛异常：只记该引擎不可用，不影响其余候选
                probes += EngineProbe(c.packageLabel, label, c.source, initOk = true, supportsVoiceApi = false, 0, chineseLanguageOk = false)
            } finally {
                runCatching { engine.shutdown() }
            }
        }
        return TtsReport(synthComponent, intentPackages.size, probes) to options
    }

    /** 空包名 = 不指定引擎，走系统默认解析（车机上常是唯一能绑上的路径）。 */
    private suspend fun initAwait(pkg: String): TextToSpeech? {
        val deferred = CompletableDeferred<Boolean>()
        val listener = TextToSpeech.OnInitListener { status ->
            deferred.complete(status == TextToSpeech.SUCCESS)
        }
        val engine = if (pkg.isBlank()) TextToSpeech(context, listener) else TextToSpeech(context, listener, pkg)
        val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) { deferred.await() } == true
        if (!ok) {
            runCatching { engine.shutdown() }
            return null
        }
        return engine
    }

    private fun chineseLanguageAvailable(engine: TextToSpeech): Boolean = runCatching {
        engine.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) >= LANG_AVAILABLE
    }.getOrDefault(false)

    private fun fallbackMeta(engine: TextToSpeech, pkg: String, label: String): VoiceMeta {
        val zhTag = runCatching { engine.availableLanguages.firstOrNull { it.language == "zh" }?.toLanguageTag() }.getOrNull()
        return VoiceMeta(
            name = DEFAULT_VOICE_NAME,
            localeTag = zhTag ?: "zh-CN",
            isNetwork = false,
            isPremium = false,
            isCheap = false,
            enginePackage = pkg,
            engineLabel = label,
        )
    }

    /** 读系统认定的默认合成器；部分 ROM 未设置时回空串或字面 "null"。 */
    private fun readDefaultSynth(): String? = runCatching {
        // Settings.Secure.TTS_DEFAULT_SYNTH 是 @hide 常量，只能按文档里的 key 名读
        Settings.Secure.getString(context.contentResolver, "tts_default_synth")
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun listEnginePackages(): List<String> {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        return context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    }

    private fun engineLabel(pkg: String): String =
        runCatching {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)

    private fun Voice.toMeta(pkg: String, label: String) = VoiceMeta(
        name = name,
        localeTag = locale.toLanguageTag(),
        isNetwork = features.any { it.contains("network", ignoreCase = true) },
        isPremium = features.any { it.contains("premium", ignoreCase = true) },
        isCheap = quality < QUALITY_DEFAULT, // android.speech.tts.Voice.QUALITY_DEFAULT = 50（静态常量在此 SDK stub 不可直接引用）
        enginePackage = pkg,
        engineLabel = label,
    )
}
