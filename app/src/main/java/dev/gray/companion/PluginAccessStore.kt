package dev.gray.companion

import android.content.Context
import dev.gray.companion.core.ConfigurationExtension
import dev.gray.companion.core.CredentialKind
import dev.gray.companion.core.CredentialSlot
import dev.gray.companion.core.CredentialStore
import dev.gray.companion.core.PluginCapabilities
import dev.gray.companion.core.PluginCodes
import dev.gray.companion.core.PluginExecutionContext
import dev.gray.companion.data.KeystoreCredentialStore
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Local device policy, deliberately absent from Library, bundles and configuration tools. */
@Serializable
data class PluginAccessStatus(
    val granted: Boolean = false,
    /** Last explicitly approved code/manifest fingerprint; blank means no grant. */
    val fingerprint: String = "",
    val configuredCredentials: Set<String> = emptySet(),
)

/** Secrets use the existing Keystore adapter, with a separate, plugin-scoped namespace. */
class PluginAccessStore(
    context: Context,
    private val credentials: CredentialStore = KeystoreCredentialStore(context.applicationContext),
) : PluginHostAccess {
    private val grants = context.applicationContext.getSharedPreferences("plugin-capability-grants-v1", Context.MODE_PRIVATE)

    fun status(extension: ConfigurationExtension): PluginAccessStatus {
        val code = extension.code
        val fingerprint = grants.getString(extension.id, null).orEmpty()
        val aliases = code?.permissions.orEmpty().flatMap { it.credentials }.distinct()
        return PluginAccessStatus(
            granted = code?.version == 2 && fingerprint.isNotBlank() && fingerprint == PluginCapabilities.fingerprint(code),
            fingerprint = fingerprint,
            configuredCredentials = aliases.filter { alias ->
                runCatching { credential(extension.id, alias).isNotEmpty() }.getOrDefault(false)
            }.toSet(),
        )
    }

    /** Only called by the native access panel after the controller checks its saved before-image. */
    fun grant(extension: ConfigurationExtension) {
        val code = requireNotNull(extension.code)
        require(code.version == 2 && PluginCodes.validate(code).none { it.error })
        check(grants.edit().putString(extension.id, PluginCapabilities.fingerprint(code)).commit())
    }

    fun revoke(pluginId: String) {
        check(grants.edit().remove(pluginId).commit())
    }

    fun setCredential(extension: ConfigurationExtension, alias: String, secret: String) {
        val code = requireNotNull(extension.code)
        require(code.version == 2 && PluginCapabilities.validAlias(alias))
        require(code.permissions.any { alias in it.credentials })
        require(secret.toByteArray(Charsets.UTF_8).size <= MAX_SECRET_BYTES)
        require('\r' !in secret && '\n' !in secret && '\u0000' !in secret)
        val slot = slot(extension.id, alias)
        if (secret.isEmpty()) credentials.remove(slot) else credentials.put(slot, secret)
    }

    override fun isGranted(context: PluginExecutionContext): Boolean =
        context.code.version == 2 && grants.getString(context.pluginId, null) == PluginCapabilities.fingerprint(context.code)

    /** Host-only resolution. Neither this method nor CredentialStore is bound into JavaScript. */
    override fun credential(pluginId: String, alias: String): String {
        require(PluginCapabilities.validAlias(alias))
        return credentials.get(slot(pluginId, alias))
    }

    private fun slot(pluginId: String, alias: String): CredentialSlot {
        val owner = MessageDigest.getInstance("SHA-256")
            .digest("${pluginId.length}:$pluginId:$alias".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return CredentialSlot(owner, CredentialKind.PLUGIN_SECRET)
    }

    private companion object {
        const val MAX_SECRET_BYTES = 16 * 1024
    }
}
