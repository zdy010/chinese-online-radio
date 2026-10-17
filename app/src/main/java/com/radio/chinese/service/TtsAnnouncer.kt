package com.radio.chinese.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.radio.chinese.data.local.RadioPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import java.util.Locale

/**
 * 线控切台语音播报。依赖设备已装的中文 TTS：
 * - 偏好里有 引擎包名+音色名 就用它；否则跨所有引擎按 VoiceScoring 选一个默认最优。
 * - 无引擎 / 无中文音色 / 初始化失败 / speak 不回调，一律静默降级（speak 返回 false），绝不卡住切台。
 * - speak 内部 3.5s 超时兜底，防止引擎挂起。
 */
@Singleton
class TtsAnnouncer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: RadioPreferences,
    private val catalog: AndroidVoiceCatalog,
) {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var chosen: TtsOption? = null
    private val engineMutex = Mutex()

    private companion object {
        const val SPEAK_TIMEOUT_MS = 3_500L
        const val SPEAK_RATE = 1.1f
    }

    /** 依当前偏好或跨引擎选优，确保一个可用的中文引擎就绪。check-then-act 于互斥锁内，避免并发重复初始化/泄漏。 */
    private suspend fun ensureReady(): TextToSpeech? = engineMutex.withLock {
        (tts?.takeIf { chosen != null })?.let { return@withLock it }
        val target = resolveOption() ?: return@withLock null
        val engine = initEngine(target.enginePackage) ?: return@withLock null
        runCatching {
            engine.language = Locale.forLanguageTag(target.voice.localeTag)
            target.voice.name.let { vn -> engine.voices?.firstOrNull { it.name == vn }?.let { engine.voice = it } }
            engine.setSpeechRate(SPEAK_RATE)
        }
        // 锁内先关旧实例再换发，不遗留未 shutdown 的引擎
        runCatching { tts?.shutdown() }
        chosen = target
        tts = engine
        engine
    }

    private suspend fun resolveOption(): TtsOption? {
        val options = catalog.listOptions()
        if (options.isEmpty()) return null
        val prefPkg = preferences.preferredTtsEngine.first()
        val prefVoice = preferences.preferredVoice.first()
        if (prefPkg != null && prefVoice != null) {
            options.firstOrNull { it.enginePackage == prefPkg && it.voice.name == prefVoice }?.let { return it }
        }
        val best = VoiceCatalog.pickDefault(options.map { it.voice }) ?: return null
        return options.firstOrNull { it.voice == best }
    }

    private suspend fun initEngine(pkg: String): TextToSpeech? = withContext(Dispatchers.Default) {
        val deferred = CompletableDeferred<Boolean>()
        val engine = TextToSpeech(context, { status ->
            deferred.complete(status == TextToSpeech.SUCCESS)
        }, pkg)
        val ok = withTimeoutOrNull(2_000) { deferred.await() } == true
        if (!ok) {
            runCatching { engine.shutdown() }
            null
        } else engine
    }

    suspend fun isAvailable(): Boolean {
        if (!preferences.voiceAnnounceEnabled.first()) return false
        return ensureReady() != null
    }

    /** 预热：提前在后台解析引擎/音色并初始化，避免首次切台才付冷启动代价。 */
    suspend fun warmUp() {
        if (!preferences.voiceAnnounceEnabled.first()) return
        ensureReady()
    }

    /**
     * 播报；成功 true，降级/失败 false。
     * 完成判定不能只听一次回调：部分厂商引擎念到英文/字母等不支持字符会半途发 onError
     * 但声继续，有的会提前发 onDone——以前立刻 resume 就让新台盖过没念完的播报。
     * 改为：只听自家的 id，回调后再以 isSpeaking 轮确认真正念完；全程 3.5s 封顶，
     * 超时/取消时 stop 引擎，绝不让播报卡住切台。
     */
    suspend fun speak(text: String): Boolean {
        if (!preferences.voiceAnnounceEnabled.first()) return false
        val engine = ensureReady() ?: return false
        val id = "announce-" + System.nanoTime()
        val deadline = android.os.SystemClock.elapsedRealtime() + SPEAK_TIMEOUT_MS
        val callbackOk = withTimeoutOrNull(SPEAK_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(true)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        // 不再提前 resume(false)：声可能还在念，交给下方 isSpeaking 轮询定成败
                    }
                })
                val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            }
        } == true
        val spokeFully = callbackOk && awaitSpeakingDone(engine, deadline)
        if (!spokeFully) runCatching { engine.stop() }   // 未完/超时：停下引擎，避免与新流叠音
        return spokeFully
    }

    /** 轮询直到引擎真正念完或总预算耗尽；300ms 宽限期避开句间瞬间的假“念完”。 */
    private suspend fun awaitSpeakingDone(engine: TextToSpeech, deadline: Long): Boolean {
        delay(300)
        @Suppress("DEPRECATION")
        while (engine.isSpeaking) {
            if (android.os.SystemClock.elapsedRealtime() >= deadline) return false
            delay(120)
        }
        return true
    }

    /** 设置页试听某个引擎+音色：锁内换发引擎，锁外发声（speak 会再拿锁，避免重入死锁）。 */
    suspend fun preview(option: TtsOption): Boolean {
        engineMutex.withLock {
            runCatching { tts?.shutdown() }
            tts = null
            chosen = null
            preferences.setPreferredTtsEngine(option.enginePackage)
            preferences.setPreferredVoice(option.voice.name)
            val engine = initEngine(option.enginePackage) ?: return@withLock
            runCatching {
                engine.language = Locale.forLanguageTag(option.voice.localeTag)
                engine.voices?.firstOrNull { it.name == option.voice.name }?.let { engine.voice = it }
                engine.setSpeechRate(SPEAK_RATE)
            }
            tts = engine
            chosen = option
        }
        return speak("即将播放：中国之声 交通广播")
    }

    /** 释放引擎（onCleared）：尽力而为，非挂起。 */
    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        chosen = null
    }
}
