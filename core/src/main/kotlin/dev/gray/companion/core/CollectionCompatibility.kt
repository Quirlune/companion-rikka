package dev.gray.companion.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Wire 12 retires only installation metadata. Ordinary associations remain the runtime authority. */
object CollectionCompatibility {
    fun canonicalMain(main: MainCall): MainCall =
        if (main.extensionBindings.isEmpty() && main.extensionBindingOrigins.isEmpty()) main
        else main.copy(extensionBindings = emptyList(), extensionBindingOrigins = emptyList())

    /** Operates on just the two retired fields, without decoding defaults or generating any IDs. */
    fun canonicalMainImage(value: JsonElement?): JsonElement? {
        val image = value as? JsonObject ?: return value
        val empty = JsonArray(emptyList())
        if (image["extensionBindings"] == empty && image["extensionBindingOrigins"] == empty) return image
        return JsonObject(image + mapOf("extensionBindings" to empty, "extensionBindingOrigins" to empty))
    }

    fun canonicalChange(change: ConfigurationChange): ConfigurationChange {
        if (change.kind != "mainCall") return change
        val before = canonicalMainImage(change.before)
        val after = canonicalMainImage(change.after)
        return if (before === change.before && after === change.after) change else change.copy(before = before, after = after)
    }

    fun canonicalReceipt(receipt: ConfigurationEditReceipt): ConfigurationEditReceipt {
        val changes = receipt.changes.map(::canonicalChange)
        return if (changes.indices.all { changes[it] === receipt.changes[it] }) receipt else receipt.copy(changes = changes)
    }

    /** Existing load/save boundaries call this for live configuration only. Never recurse into captured worlds. */
    fun canonicalLive(library: Library): Library {
        val mains = library.mainCalls.map(::canonicalMain)
        val receipts = library.configurationEdits.map(::canonicalReceipt)
        return if (mains.indices.all { mains[it] === library.mainCalls[it] } &&
            receipts.indices.all { receipts[it] === library.configurationEdits[it] }) library
        else library.copy(mainCalls = mains, configurationEdits = receipts)
    }
}
