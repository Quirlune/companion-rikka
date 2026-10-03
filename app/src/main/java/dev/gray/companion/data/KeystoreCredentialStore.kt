package dev.gray.companion.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.gray.companion.core.CredentialStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API secrets never enter the resource database or exported JSON bundle. */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val prefs = context.applicationContext.getSharedPreferences("profile-secrets", Context.MODE_PRIVATE)
    private val alias = "dev.gray.companion.profile-key-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    override fun get(profileId: String): String {
        val saved = prefs.getString(profileId, null) ?: return ""
        try {
            val bytes = Base64.decode(saved, Base64.NO_WRAP)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(profileId.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("Saved credential cannot be decrypted") }
    }

    override fun put(profileId: String, secret: String) {
        if (secret.isEmpty()) { remove(profileId); return }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(profileId.toByteArray(Charsets.UTF_8))
            val bytes = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            check(prefs.edit().putString(profileId, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
        } catch (_: Exception) { throw IllegalStateException("Credential could not be stored") }
    }

    override fun remove(profileId: String) {
        check(prefs.edit().remove(profileId).commit())
    }
}
