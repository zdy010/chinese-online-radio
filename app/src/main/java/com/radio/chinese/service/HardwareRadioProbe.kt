package com.radio.chinese.service

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.tv.TvInputManager
import android.os.Build

/** 硬件能力探测的一行结果：label + 一句人话 */
data class HwLine(val label: String, val value: String)

/**
 * 探测这台车机有没有把硬件收音机（FM/AM）开放给第三方 App。
 *
 * 五条互补通道，任何一条有信号都说明还有得做：
 * 1. 系统 feature——连全表带关键词过滤一起看，能捞到厂商自造的名字；
 * 2. TvInputManager 输入源——Android 唯一公开的可调谐通路；
 * 3. 音频设备——是否存在 FM/TV 调谐器设备；
 * 4. 已装收音机应用——有应用说明芯片在，问题只剩"能不能被我们控制"；
 * 5. 厂商系统属性（getprop）——方易通/瑞芯微/全志这类常在这里露出来，读属性不需要权限。
 *
 * 全程只读、不加任何权限、不联网、不写文件。结论直接给到"能不能做、下一步看哪条"，
 * 因为看这页的人是在车上、没条件翻日志的人。
 */
object HardwareRadioProbe {

    private const val MAX_LIST = 5
    private const val PROP_MAX_LINES = 4
    private const val LINE_MAX = 200

    /** 已知的车机/SoC 收音机与调谐器包名；枚举被 Android 11+ 包可见性挡住时靠它们兜底 */
    private val KNOWN_PACKAGES = listOf(
        "com.android.fmradio", "com.caf.fmradio", "com.mediatek.fmradio", "com.realtek.fmradio",
        "com.rk.fmradio", "com.sprd.fmradio", "qctcm1.dn",
        "com.fyt.fm", "com.fyt.radio", "com.fyt.media",
        "com.android.tv", "com.amlogic.tv.tuner", "com.droidlogic.tv.tuner"
    )

    fun probe(context: Context): List<HwLine> {
        val pm = context.packageManager
        val env = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" +
            "（API ${Build.VERSION.SDK_INT}）· ${Build.BOARD}"

        val (featText, featHit) = tunerFeatures(pm)
        val (tvText, tvHit) = tvInputs(context)
        val (audioText, audioNode) = audioDevices(context)
        val (appText, appHit) = radioApps(context, pm)
        val (propText, propHit) = propClues()

        val hits = buildList {
            if (featHit) add("系统 feature")
            if (tvHit) add("TvInput 输入源")
            if (appHit) add("已装收音机 App")
            if (propHit) add("厂商系统属性")
        }
        // 音频节点只证明“有这路音频”，不证明能被应用控台，所以不列入“线索”但要在结论里说清
        val verdict = if (hits.isEmpty()) {
            if (audioNode) {
                "除一个只能证明音频通路存在的调谐节点外，其余四条可控通道都无信号：" +
                    "硬件可能在，但没开放给第三方 App；继续用网络电台，或只能做「跳回车机自带收音机」。"
            } else {
                "五条通道都无信号：这台机器基本可以判定没给第三方 App 留硬件收音机接口，继续走网络电台。"
            }
        } else {
            "有线索：" + hits.joinToString("、") + "。" +
                "优先看 TvInput（Android 唯一公开的可调谐通路）和厂商属性指向的私有服务；" +
                "只有「已装收音机 App」时，现实做法一般是跳回车机自带收音机、或在车机设置里把按键指派给那个应用。"
        }

        return listOf(
            HwLine("运行环境", env.cut(LINE_MAX)),
            HwLine("调谐器特性", featText.cut(LINE_MAX)),
            HwLine("TvInput 输入源", tvText.cut(LINE_MAX)),
            HwLine("音频设备", audioText.cut(LINE_MAX)),
            HwLine("收音机应用", appText.cut(LINE_MAX)),
            HwLine("系统属性线索", propText.cut(LINE_MAX)),
            HwLine("综合判断", verdict)
        )
    }

    // ---- 通道一：系统 feature ----

    private fun tunerFeatures(pm: PackageManager): Pair<String, Boolean> {
        val all = runCatching { pm.systemAvailableFeatures.mapNotNull { it.name } }.getOrNull()
        if (all == null) {
            // 拿不到全表也要把标准名字问一遍，别让整个探测报废
            val one = listOf(
                "android.hardware.tuner",
                "android.hardware.fm",
                "android.hardware.fm.radio",
                "android.hardware.broadcastradio"
            ).filter { runCatching { pm.hasSystemFeature(it) }.getOrDefault(false) }
            return if (one.isEmpty()) "读不到 feature 全表，标准名也未命中" to false
            else "命中：${one.joinToString("、")}" to true
        }
        // 不用裸 radio/broadcast 做关键词：android.hardware.telephony.radio.access 这类蜂窝基带
        // 会被算进来，模拟器上就是这样把“没硬件”判成“有线索”的
        val hits = all.filter { broadcastTuner(it) }.distinct()
        val liveTv = all.any { it.equals(PackageManager.FEATURE_LIVE_TV, ignoreCase = true) || it.endsWith(".live_tv") }
        val tail = if (liveTv) "；另有 live_tv（只说明有 TV 输入框架，不等于有调谐器）" else ""
        return if (hits.isEmpty()) {
            "未声明（共 ${all.size} 项 feature，无 fm/tuner/broadcastradio，已排除蜂窝）$tail" to false
        } else {
            "命中 ${hits.size} 项：${hits.take(MAX_LIST).joinToString("、")}$tail" to true
        }
    }

    /**
     * 统一的「这是不是广播调谐」判定：只认 fm / tuner / broadcastradio 这类明确词，
     * 并显式踢掉蜂窝与蓝牙——它们名字里也带 radio/broadcast。包名、feature、属性三路共用这一条。
     */
    private fun broadcastTuner(text: String): Boolean {
        val t = text.lowercase()
        if (t.contains("telephony") || t.contains("rild") || t.contains(".ril") || t.contains("bluetooth") ||
            t.contains("persist.radio") || t.contains("radio.config")
        ) {
            return false
        }
        return t.contains("tuner") || t.contains("broadcastradio") || t.contains("fmradio") ||
            t.contains("fm.radio") || t.contains("fm_radio") || FM_SEGMENT.containsMatchIn(t)
    }

    /** fm 作为独立字段出现：com.android.fm、ro.fm.hall、vendor_fm_uart */
    private val FM_SEGMENT = Regex("(^|[.\\-_])fm([.\\-_]|$)")

    // ---- 通道二：TvInput（系统级调谐器若开放给 App，几乎都走这条） ----

    private fun tvInputs(context: Context): Pair<String, Boolean> {
        val mgr = runCatching {
            context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        }.getOrNull() ?: return "系统未提供 TvInputManager" to false
        val infos = runCatching { mgr.tvInputList }.getOrNull()
        if (infos.isNullOrEmpty()) {
            return "无 TvInput 服务（ROM 没把调谐器做成标准输入源）" to false
        }
        val labels = infos.take(MAX_LIST).map { info ->
            // TvInputInfo 只有 serviceInfo 与 id，没有 packageName；取不到就退回 id
            val label = runCatching { info.loadLabel(context).toString() }.getOrNull().orEmpty()
            val pkg = runCatching { info.serviceInfo?.packageName }.getOrNull().orEmpty()
            when {
                label.isNotBlank() && pkg.isNotBlank() -> "$pkg（$label）"
                label.isNotBlank() -> label
                pkg.isNotBlank() -> pkg
                else -> runCatching { info.id }.getOrNull().orEmpty().cut(48)
            }
        }
        return "发现 ${infos.size} 个输入源：${labels.joinToString("、")}" to true
    }

    // ---- 通道三：音频设备里有没有调谐器节点 ----

    private fun audioDevices(context: Context): Pair<String, Boolean> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "无 AudioManager" to false
        val devices = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS) + am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull() ?: return "音频设备枚举失败" to false

        val fmType = typeConstant("TYPE_FM_TUNER")
        val tvType = typeConstant("TYPE_TV_TUNER")
        // 只按系统常量判，不拿 productName 模糊匹配——内置设备的 productName 就是机型名，全是噪
        val tuners = devices.filter { it.type == fmType || it.type == tvType }
        val constInfo = "（常量 FM_TUNER=$fmType、TV_TUNER=$tvType）"
        return if (tuners.isEmpty()) {
            "共 ${devices.size} 个设备，无 FM/TV 调谐器节点$constInfo" to false
        } else {
            val types = tuners.map { it.type }.distinct().joinToString("、")
            // 这里的布尔值是“存在调谐音频节点”，只用于结论区分，不计入可控线索
            "存在调谐音频节点 type=$types $constInfo（仅说明音频通路存在，不代表可控）" to true
        }
    }

    /** TYPE_FM_TUNER / TYPE_TV_TUNER 在不同 API 级别上不一定公开，用反射取，取不到就跳过 */
    private fun typeConstant(name: String): Int =
        runCatching { AudioDeviceInfo::class.java.getField(name).getInt(null) }.getOrDefault(-1)

    // ---- 通道四：机器上装了哪些收音机应用 ----

    private fun radioApps(context: Context, pm: PackageManager): Pair<String, Boolean> {
        // 自家包名必须排除：本包叫 com.radio.chinese，早先版本把“我们自己”列成了收音机应用
        val self = context.packageName
        val enumerated = runCatching {
            pm.getInstalledPackages(0).mapNotNull { it.packageName }
        }.getOrNull()

        val fromList = enumerated?.filter { pkg -> pkg != self && broadcastTuner(pkg) }.orEmpty()

        // 包可见性受限时枚举会被裁剪，退回按已知包名逐个问
        val byName = KNOWN_PACKAGES.filter { hint ->
            hint != self && runCatching { pm.getPackageInfo(hint, 0); true }.getOrDefault(false)
        }
        val found = (fromList + byName).distinct()

        if (found.isEmpty()) {
            val note = if (enumerated == null) "读不到应用列表（受包可见性限制）" else "未发现广播收音机应用"
            return note to false
        }
        val launchable = found.filter { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }
        val tail = if (launchable.isEmpty()) "" else "；可直接跳转打开：${launchable.take(MAX_LIST).joinToString("、")}"
        return "发现 ${found.size} 个：${found.take(MAX_LIST).joinToString("、")}$tail" to true
    }

    // ---- 通道五：厂商系统属性（车机上往往最有料，且读属性不需要权限） ----

    private fun propClues(): Pair<String, Boolean> {
        return try {
            val proc = Runtime.getRuntime().exec("getprop")
            val hits = proc.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().mapNotNull { line ->
                    // 只看 key！早先连 value 一起匹配，adb 公钥的 base64 里凑巧含 fm 都被当成线索
                    val m = PROP_LINE.find(line.trim()) ?: return@mapNotNull null
                    val key = m.groupValues[1]
                    if (!broadcastTuner(key)) return@mapNotNull null
                    "$key=${m.groupValues[2].take(36)}"
                }.take(PROP_MAX_LINES).toList()
            }
            proc.destroy()
            if (hits.isEmpty()) "属性 key 里未见 fm/tuner/broadcastradio 线索（已排除蜂窝与蓝牙）" to false
            else "命中 ${hits.size} 条：${hits.joinToString(" ⋄ ")}" to true
        } catch (e: Exception) {
            "读不到 getprop（${e.javaClass.simpleName}）" to false
        }
    }

    /** getprop 行形如 [key]: [value] */
    private val PROP_LINE = Regex("^\\[(.*?)]\\s*:\\s*\\[(.*)]$")

    private fun String.cut(limit: Int): String =
        if (length <= limit) this else take(limit) + "…"
}
