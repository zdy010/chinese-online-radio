package com.radio.chinese.service

import android.os.Handler
import android.os.Looper

/**
 * 戏曲播放位置轮询器。
 *
 * 每秒从 MediaController 读 currentPosition/duration 写入回调。
 * 独立成类便于单测——PlayerManager 持有主线程 Handler，JVM 里构造不出；
 * 本类只依赖 [PosReader] 和可注入的 Handler，测试里可换 clock。
 */
class OperaPositionTracker(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val intervalMs: Long = 1_000L,
) {
    /** 读取当前位置与时长；返回 null 表示暂停或不可用。 */
    fun interface PosReader {
        fun read(): Pair<Long, Long>?
    }

    private var runnable: Runnable? = null

    /**
     * 启动轮询。重复调用先停旧的再开新的（幂等启动）。
     * @param reader 每 tick 被调，返回 (position, duration)；null 跳过本 tick 但不中断循环
     * @param onTick 读到值时在主线程回调
     */
    fun start(reader: PosReader, onTick: (pos: Long, duration: Long) -> Unit) {
        stop()
        val task = object : Runnable {
            override fun run() {
                reader.read()?.let { (pos, dur) -> onTick(pos, dur) }
                handler.postDelayed(this, intervalMs)
            }
        }
        runnable = task
        handler.post(task)
    }

    /** 停止轮询，幂等。 */
    fun stop() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }
}
