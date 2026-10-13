package dev.gray.companion

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

enum class AppLogLevel { INFO, WARNING, ERROR }

data class AppLogEntry(
    val id: Long,
    val timeMillis: Long,
    val level: AppLogLevel,
    val source: String,
    val message: String,
)

/** A small local event journal. Callers supply statuses, never prompts or response bodies. */
object AppEventLog {
    private const val MAX_ENTRIES = 256
    private const val MAX_MESSAGE = 420
    private const val MAX_SOURCE = 48
    private val gate = Any()
    private val diskGate = Any()
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "AppEventLog").apply { isDaemon = true } }
    private val mutableEntries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entries: StateFlow<List<AppLogEntry>> = mutableEntries.asStateFlow()
    private val mutableErrorSerial = MutableStateFlow(0L)
    val errorSerial: StateFlow<Long> = mutableErrorSerial.asStateFlow()
    private var file: AtomicFile? = null
    private var sequence = System.currentTimeMillis().coerceAtLeast(1L) * 1_000L
    private var loaded = false
    private var errorsBeforeLoad = 0L
    private var clearedBeforeLoad = false

    fun install(context: Context) {
        synchronized(gate) {
            if (file != null) return
            file = AtomicFile(File(context.applicationContext.filesDir, "app-events.json"))
        }
        writer.execute { load() }
    }

    fun record(source: String, message: String, level: AppLogLevel = AppLogLevel.INFO) {
        synchronized(gate) {
            val now = System.currentTimeMillis()
            sequence = maxOf(sequence + 1L, now.coerceAtLeast(1L) * 1_000L)
            val entry = AppLogEntry(sequence, now, level, sanitize(source).take(MAX_SOURCE), sanitize(message).take(MAX_MESSAGE))
            mutableEntries.value = (mutableEntries.value + entry).takeLast(MAX_ENTRIES)
            if (level == AppLogLevel.ERROR) {
                mutableErrorSerial.value++
                if (!loaded) errorsBeforeLoad++
            }
        }
        writer.execute { persist() }
    }

    fun clear() {
        synchronized(gate) {
            mutableEntries.value = emptyList()
            if (!loaded) clearedBeforeLoad = true
        }
        writer.execute { persist() }
    }

    /** Fatal exceptions must be saved before Android terminates the process. */
    fun recordCrash(sanitizedStack: String) {
        // An early crash can beat the queued initial read. Merge the old journal first.
        load()
        record("崩溃", sanitizedStack, AppLogLevel.ERROR)
        persist()
    }

    private fun load() {
        synchronized(diskGate) {
            if (synchronized(gate) { loaded }) return
            val stored = runCatching {
                val target = synchronized(gate) { file } ?: return
                if (!target.baseFile.exists()) null else {
                    val root = JSONObject(target.openRead().bufferedReader().use { it.readText() })
                    val rows = root.optJSONArray("entries") ?: JSONArray()
                    val list = buildList {
                        for (index in 0 until rows.length()) {
                            val item = rows.optJSONObject(index) ?: continue
                            val level = runCatching { AppLogLevel.valueOf(item.optString("level")) }.getOrNull() ?: continue
                            add(AppLogEntry(item.optLong("id"), item.optLong("timeMillis"), level,
                                sanitize(item.optString("source")).take(MAX_SOURCE),
                                sanitize(item.optString("message")).take(MAX_MESSAGE)))
                        }
                    }
                    list to root.optLong("errorSerial")
                }
            }.getOrNull()
            synchronized(gate) {
                if (stored != null) {
                    if (!clearedBeforeLoad) {
                        mutableEntries.value = (stored.first + mutableEntries.value).distinctBy { it.id }
                            .sortedWith(compareBy<AppLogEntry> { it.timeMillis }.thenBy { it.id }).takeLast(MAX_ENTRIES)
                    }
                    sequence = maxOf(sequence, stored.first.maxOfOrNull { it.id } ?: 0L)
                    mutableErrorSerial.value = maxOf(mutableErrorSerial.value, stored.second + errorsBeforeLoad)
                }
                loaded = true
            }
        }
    }

    private fun persist() {
        synchronized(diskGate) {
            val (target, currentEntries, currentErrorSerial) = synchronized(gate) {
                Triple(file, mutableEntries.value, mutableErrorSerial.value)
            }
            target ?: return
            runCatching {
                val rows = JSONArray()
                currentEntries.forEach { entry -> rows.put(JSONObject().apply {
                    put("id", entry.id); put("timeMillis", entry.timeMillis)
                    put("level", entry.level.name); put("source", entry.source); put("message", entry.message)
                }) }
                val data = JSONObject().put("errorSerial", currentErrorSerial)
                    .put("entries", rows).toString().toByteArray(Charsets.UTF_8)
                val stream = target.startWrite()
                try { stream.write(data); target.finishWrite(stream) }
                catch (failure: Exception) { target.failWrite(stream); throw failure }
            }
        }
    }

    internal fun sanitize(value: String): String = value
        .replace(Regex("(?i)https?://[^\\s<>]+"), "[链接已隐藏]")
        .replace(Regex("(?i)[\"']?\\b(?:authorization|proxy-authorization|x-api-key|api[_ -]?key|[a-z0-9_-]*token|[a-z0-9_-]*secret|password|[a-z0-9_-]*cookie)\\b[\"']?\\s*[:=]\\s*[\"']?(?:Bearer\\s+)?[^\\s,;\"'}]+[\"']?"), "[凭据已隐藏]")
        .replace(Regex("(?i)\\bBearer\\s+[^\\s,;]+"), "Bearer [已隐藏]")
        .replace(Regex("(?i)\\b(?:sk|or)-[A-Za-z0-9_-]{8,}"), "[凭据已隐藏]")
        .replace(Regex("[A-Za-z0-9_+/-]{48,}={0,2}"), "[长值已隐藏]")
        .replace(Regex("[\\p{Cntrl}&&[^\\n]]+"), " ")
        .trim().take(MAX_MESSAGE)
}
