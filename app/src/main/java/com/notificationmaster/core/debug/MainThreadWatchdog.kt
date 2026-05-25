package com.notificationmaster.core.debug

import android.os.Handler
import android.os.Looper

/**
 * Main thread block 偵測器（Plan 1-zippy-thunder W7）。
 *
 * 後台 thread 每 [PING_INTERVAL_MS] 發一個 ping 到 main looper 並記錄上次 ack 時間。
 * 若 main thread 連續 > [BLOCK_THRESHOLD_MS] 未 ack，dump main thread stack 到 [ProfileLogger]
 * 配合 ANR / FC 事後分析定位阻塞點。**只記錄不 kill App**，讓使用者仍能看到系統 FC stack trace。
 *
 * Gate：由 [DebugPaths.isEnabled] 決定 — debug mode OFF 時完全不啟動，無 perf overhead。
 *
 * Dump 節流：連續阻塞時每 [DUMP_DEBOUNCE_MS] 才 dump 一次，避免 spam profile log。
 */
object MainThreadWatchdog {

    private const val PING_INTERVAL_MS = 500L
    private const val BLOCK_THRESHOLD_MS = 2_000L
    private const val DUMP_DEBOUNCE_MS = 1_000L
    private const val MAX_STACK_LINES = 30

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var watcherThread: Thread? = null
    @Volatile private var lastAckMs: Long = 0L
    @Volatile private var lastDumpMs: Long = 0L
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        lastAckMs = System.currentTimeMillis()
        lastDumpMs = 0L
        watcherThread = Thread({ watchLoop() }, "MainThreadWatchdog").apply {
            isDaemon = true
            start()
        }
        ProfileLogger.append("Watchdog", "started ping=${PING_INTERVAL_MS}ms threshold=${BLOCK_THRESHOLD_MS}ms")
    }

    fun stop() {
        if (!running) return
        running = false
        watcherThread?.interrupt()
        watcherThread = null
        ProfileLogger.append("Watchdog", "stopped")
    }

    private fun watchLoop() {
        while (running) {
            mainHandler.post { lastAckMs = System.currentTimeMillis() }
            val sinceLastAck = System.currentTimeMillis() - lastAckMs
            if (sinceLastAck > BLOCK_THRESHOLD_MS) {
                maybeDumpMainStack(sinceLastAck)
            }
            try {
                Thread.sleep(PING_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun maybeDumpMainStack(blockedMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastDumpMs < DUMP_DEBOUNCE_MS) return
        lastDumpMs = now
        val mainThread = Looper.getMainLooper().thread
        val stack = mainThread.stackTrace
            .take(MAX_STACK_LINES)
            .joinToString("\n  at ") { it.toString() }
        ProfileLogger.append("Watchdog", "MAIN BLOCKED ${blockedMs}ms\n  at $stack")
    }
}
