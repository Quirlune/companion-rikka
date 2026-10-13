package me.rerere.rikkahub.utils

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import android.app.ActivityManager
import android.os.Build
import android.webkit.WebView
import me.rerere.rikkahub.BuildConfig
import dev.gray.companion.AppEventLog
import dev.gray.companion.AppLogLevel

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val MAX_STACKTRACE_LENGTH = 8000

object CrashHandler {
    private var installed = false
    private var environment = ""

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        environment = buildString {
            appendLine("velune ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            val webView = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
            appendLine("WebView: ${webView?.packageName ?: "unknown"} ${webView?.versionName.orEmpty()}")
        }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Exception messages can contain provider responses or user text. Store only types/frames.
                markCrashed(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Recording must never prevent the platform handler from terminating a failed process.
            } finally {
                try { AppEventLog.recordCrash(sanitizedStack(throwable)) } catch (_: Throwable) { }
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = buildString {
            append(environment)
            appendLine("Time: ${System.currentTimeMillis()}; mainThread=${thread === android.os.Looper.getMainLooper().thread}")
            append(sanitizedStack(throwable))
        }.take(MAX_STACKTRACE_LENGTH)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
            } // commit() 同步写入，确保崩溃前写完
    }

    internal fun sanitizedStack(throwable: Throwable): String = buildString {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = throwable
        while (current != null && seen.add(current) && seen.size <= 8) {
            appendLine(current.javaClass.name)
            current.stackTrace.take(48).forEach { appendLine("  at $it") }
            current = current.cause
            if (current != null) append("Caused by: ")
        }
    }.take(MAX_STACKTRACE_LENGTH)

    fun recordWebViewExit(context: Context, didCrash: Boolean, priority: Int) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString("webview_exit", "$environment\nTime: ${System.currentTimeMillis()}; WebView renderer exited; didCrash=$didCrash; priority=$priority")
        }
        Log.w(TAG, "WebView renderer exited; didCrash=$didCrash; priority=$priority")
        AppEventLog.record("WebView", "渲染进程退出；didCrash=$didCrash; priority=$priority",
            if (didCrash) AppLogLevel.ERROR else AppLogLevel.WARNING)
    }

    /** Read on IO. No native traces, process descriptions, exception messages or conversation data. */
    fun diagnosticReport(context: Context): String = buildString {
        append(environment)
        appendLine("\n最近 Java/Kotlin 异常：")
        appendLine(getStackTrace(context) ?: "暂无记录（旧版未启用异常记录）")
        appendLine("\n最近 WebView 退出：")
        appendLine(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("webview_exit", null) ?: "暂无记录")
        appendLine("\n系统最近进程退出：")
        if (Build.VERSION.SDK_INT >= 30) {
            val exits = runCatching {
                context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 5)
            }.getOrNull()
            if (exits.isNullOrEmpty()) appendLine("暂无记录或系统未提供")
            exits.orEmpty().forEach { exit ->
                val reason = when (exit.reason) {
                    1 -> "正常退出"; 2 -> "信号终止"; 3 -> "内存不足"; 4 -> "Java/Kotlin 崩溃"
                    5 -> "Native 崩溃"; 6 -> "应用无响应"; 9 -> "系统资源限制"; 10 -> "用户/系统请求关闭"
                    11 -> "用户停止"; 12 -> "依赖进程退出"; 13 -> "其他"; 14 -> "应用更新"; else -> "其他(${exit.reason})"
                }
                appendLine("${exit.timestamp}: $reason; status=${exit.status}; importance=${exit.importance}; PSS=${exit.pss}KB; RSS=${exit.rss}KB")
            }
        } else appendLine("Android 11 以下不提供此记录")
    }
}
