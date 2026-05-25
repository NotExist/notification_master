package com.notificationmaster.core.debug

/**
 * 全域 uncaught exception handler（Plan 1-zippy-thunder W7）。
 *
 * 攔截所有 thread 的 uncaught exception 寫入 [ProfileLogger]，配合 main thread block / ANR
 * 後事後定位 crash 根因 — 既有 logcat 可能因 buffer rotation 失去 stack trace，profile log
 * 是持久化的補充記錄。
 *
 * 寫完 log 後**回呼原始 default handler**（通常是 Android 系統的 KillProcess），不改變
 * App crash 行為，只是多一份持久 log。
 *
 * Gate：自身寫入 [ProfileLogger.append] 已受 `isDebugDumperEnabled` 控制，handler 註冊本身
 * 不消耗資源（無背景 thread）。
 */
object DebugCrashHandler {

    private const val MAX_STACK_LINES = 40

    fun install() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stack = throwable.stackTraceToString()
                    .lineSequence()
                    .take(MAX_STACK_LINES)
                    .joinToString("\n")
                ProfileLogger.append(
                    "UncaughtExn",
                    "thread=${thread.name} err=${throwable.javaClass.simpleName}: ${throwable.message}\n$stack"
                )
            } catch (_: Throwable) {
                // 不能讓 logging 本身 cascading crash
            }
            original?.uncaughtException(thread, throwable)
        }
    }
}
