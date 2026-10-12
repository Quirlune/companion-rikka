package me.rerere.ai.util

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

interface KeyRoulette {
    fun next(keys: String, providerId: String = ""): String

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /** Process-local LRU state. API keys are never written by the roulette. */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)

        /** One-time migration of the exact legacy plaintext cache, without opening it. */
        fun removeLegacyPlaintextCache(context: Context) {
            runCatching { File(context.cacheDir, LRU_CACHE_FILE).delete() }
        }
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex() // 空格换行和逗号

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        return if (keyList.isNotEmpty()) {
            keyList.random()
        } else {
            keys
        }
    }
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天

private object LruState {
    // Shared by provider instances in this process only; random salt prevents stable key fingerprints.
    private val salt = ByteArray(32).also(SecureRandom()::nextBytes)
    val used = mutableMapOf<String, MutableMap<String, Long>>()

    fun fingerprint(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(salt + key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {

    init { KeyRoulette.removeLegacyPlaintextCache(context) }

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys

        synchronized(LruState) {
            KeyRoulette.removeLegacyPlaintextCache(context)
            val now = System.currentTimeMillis()
            val keysByFingerprint = keyList.associateBy(LruState::fingerprint)
            val providerCache = (LruState.used[providerId] ?: emptyMap())
                .filter { (fingerprint, lastUsed) -> fingerprint in keysByFingerprint && now - lastUsed < EXPIRE_DURATION_MS }
                .toMutableMap()

            // 优先选从未使用的 key，否则选最久未使用的
            val selected = keyList.firstOrNull { LruState.fingerprint(it) !in providerCache }
                ?: keysByFingerprint.getValue(providerCache.minByOrNull { it.value }!!.key)

            providerCache[LruState.fingerprint(selected)] = now
            LruState.used[providerId] = providerCache
            LruState.used.entries.removeIf { (id, cache) ->
                id != providerId && cache.values.all { now - it >= EXPIRE_DURATION_MS }
            }
            return selected
        }
    }

}
