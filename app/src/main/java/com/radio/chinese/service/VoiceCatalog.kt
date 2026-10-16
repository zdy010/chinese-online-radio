package com.radio.chinese.service

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.radio.chinese.domain.model.VoiceMeta
import com.radio.chinese.domain.model.VoiceScoring
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 一个「引擎 × 音色」可选项，供设置页列全清单与试听选择。 */
data class TtsOption(val enginePackage: String, val engineLabel: String, val voice: VoiceMeta)

interface VoiceCatalogSource {
    fun listOptions(): List<TtsOption>
    fun defaultEnginePackage(): String?
}

/** 跨引擎从中文音色里挑默认最优（委托纯函数 VoiceScoring）。 */
object VoiceCatalog {
    fun pickDefault(chineseVoices: List<VoiceMeta>): VoiceMeta? = VoiceScoring.bestChinese(chineseVoices)
}

/**
 * 枚举设备上所有 TTS 引擎（不止默认那个），逐引擎取其音色。
 * 每个引擎单独 new 一个 TextToSpeech 读取 voices，读完立即 shutdown，避免泄漏。
 * 安卓 11+ 需在 Manifest 声明 <queries> TEXT_TO_SPEECH，否则 queryIntentServices 看不到别的引擎。
 */
@Singleton
class AndroidVoiceCatalog @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceCatalogSource {

    private companion object {
        // android.speech.tts.Voice.QUALITY_DEFAULT 的已知取值；该静态常量在 compileSdk35 stub 中不可直接引用
        const val QUALITY_DEFAULT = 50
    }

    override fun defaultEnginePackage(): String? = listEnginePackages().firstOrNull()

    override fun listOptions(): List<TtsOption> {
        val result = mutableListOf<TtsOption>()
        for (pkg in listEnginePackages()) {
            val label = engineLabel(pkg)
            val tts = TextToSpeech(context, null, pkg)
            try {
                val voices: Set<Voice> = tts.voices ?: emptySet()
                for (v in voices) result += TtsOption(pkg, label, v.toMeta(pkg, label))
            } catch (_: Exception) {
                // 个别引擎取 voices 抛异常：跳过该引擎，不影响其余候选
            } finally {
                runCatching { tts.shutdown() }
            }
        }
        return result
    }

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
