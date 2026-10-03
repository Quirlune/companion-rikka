package dev.gray.companion.core

import java.util.Base64
import kotlinx.serialization.Serializable

/** Small portable presentation image. It never participates in prompt compilation. */
@Serializable
data class AvatarImage(val mediaType: String = "image/jpeg", val base64: String = "") {
    fun decodedBytes(): ByteArray? {
        if (mediaType !in setOf("image/jpeg", "image/png") || base64.length > MAX_ENCODED_LENGTH) return null
        val bytes = try { Base64.getDecoder().decode(base64) } catch (_: IllegalArgumentException) { return null }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val matchesSignature = when (mediaType) {
            "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
            else -> bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte)
        }
        return bytes.takeIf { matchesSignature }
    }

    companion object {
        const val MAX_BYTES = 32 * 1024
        const val MAX_ENCODED_LENGTH = ((MAX_BYTES + 2) / 3) * 4
    }
}
