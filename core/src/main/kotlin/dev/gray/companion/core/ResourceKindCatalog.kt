package dev.gray.companion.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Public configuration names, archive folders and typed resource access share one ordered catalogue. */
internal object ResourceKindCatalog {
    internal class Kind<T>(
        val name: String,
        val array: String,
        val serializer: KSerializer<T>,
        val read: (Library) -> List<T>,
        val write: (Library, List<T>) -> Library,
        val id: (T) -> String,
        val slug: ((T) -> String)? = null,
        val canonical: (T) -> T = { it },
        val canonicalImage: (JsonElement?) -> JsonElement? = { it }
    ) {
        fun values(library: Library): List<JsonElement> = read(library).map { LibraryFormat.json.encodeToJsonElement(serializer, canonical(it)) }
        fun find(library: Library, target: String): JsonElement? = read(library).firstOrNull { id(it) == target }
            ?.let { LibraryFormat.json.encodeToJsonElement(serializer, canonical(it)) }
        fun idsForSlug(library: Library, target: String): List<String> =
            slug?.let { selector -> read(library).filter { selector(it) == target }.map(id) }.orEmpty()
        fun replace(library: Library, target: String, image: JsonElement?, index: Int?, path: String): Library {
            val values = read(library).toMutableList()
            val oldIndex = values.indexOfFirst { id(it) == target }
            if (image == null) {
                if (oldIndex >= 0) values.removeAt(oldIndex)
            } else {
                val source = canonicalImage(image) ?: JsonNull
                val decoded = try { LibraryFormat.json.decodeFromJsonElement(serializer, source) }
                    catch (_: Exception) {
                        SafeJsonFieldDiagnostics.requireValid(serializer.descriptor, source, path)
                        throw InvalidResourceField(path, "资源字段无效")
                    }
                if (id(decoded) != target) throw InvalidResourceField("$path.id", "资源 ID 与目标不一致")
                if (oldIndex >= 0) values[oldIndex] = decoded
                else values.add((index ?: values.size).coerceIn(0, values.size), decoded)
            }
            return write(library, values)
        }
    }

    val ordered = listOf(
        Kind("character", "characters", CharacterCard.serializer(), { it.characters }, { l, v -> l.copy(characters = v) }, { it.id }, { it.slug }),
        Kind("mainCall", "mainCalls", MainCall.serializer(), { it.mainCalls }, { l, v -> l.copy(mainCalls = v) }, { it.id }, { it.slug },
            CollectionCompatibility::canonicalMain, CollectionCompatibility::canonicalMainImage),
        Kind("package", "packages", PromptPackage.serializer(), { it.packages }, { l, v -> l.copy(packages = v) }, { it.id }, { it.slug }),
        Kind("group", "groups", CallGroup.serializer(), { it.groups }, { l, v -> l.copy(groups = v) }, { it.id }, { it.slug }),
        Kind("persona", "personas", UserPersona.serializer(), { it.personas }, { l, v -> l.copy(personas = v) }, { it.id }, { it.slug }),
        Kind("tag", "tags", PromptTag.serializer(), { it.promptTags }, { l, v -> l.copy(promptTags = v) }, { it.id }, { it.slug }),
        Kind("profile", "profiles", ModelProfile.serializer(), { it.profiles }, { l, v -> l.copy(profiles = v) }, { it.id }),
        Kind("connection", "connections", ApiConnection.serializer(), { it.connections }, { l, v -> l.copy(connections = v) }, { it.id }),
        Kind("extension", "extensions", ConfigurationExtension.serializer(), { it.extensions }, { l, v -> l.copy(extensions = v) }, { it.id })
    )
    val names: Set<String> = ordered.map { it.name }.toSet()
    val arrays: Map<String, String> = ordered.associate { it.array to it.name }
    val folders: Map<String, String> = ordered.associate { it.name to it.array }
    fun get(name: String): Kind<*>? = ordered.firstOrNull { it.name == name }
}

internal class InvalidResourceField(val path: String, message: String) : IllegalArgumentException(message)

/** Checks the input tree before decoding so serializer exceptions never become user-visible text. */
internal object SafeJsonFieldDiagnostics {
    fun requireValid(descriptor: SerialDescriptor, value: JsonElement, path: String) {
        val serialName = descriptor.serialName.removeSuffix("?")
        if (serialName == "kotlinx.serialization.json.JsonElement") return
        if (serialName == "kotlinx.serialization.json.JsonPrimitive" && value is JsonPrimitive) return
        if (value == JsonNull) {
            if (!descriptor.isNullable) invalid(path, "字段不可为空")
            return
        }
        if (serialName in setOf("kotlinx.serialization.json.JsonObject", "kotlinx.serialization.json.JsonArray",
                "kotlinx.serialization.json.JsonPrimitive")) {
            val good = when (serialName.substringAfterLast('.')) {
                "JsonObject" -> value is JsonObject
                "JsonArray" -> value is JsonArray
                "JsonPrimitive" -> value is JsonPrimitive
                else -> true
            }
            if (!good) invalid(path, "字段类型无效")
            return
        }
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val obj = value as? JsonObject ?: invalid(path, "字段须为对象")
                val names = (0 until descriptor.elementsCount).associateBy { descriptor.getElementName(it) }
                obj.forEach { (key, child) ->
                    val at = "$path.${safeSegment(key)}"
                    val position = names[key] ?: invalid(at, "未知字段")
                    requireValid(descriptor.getElementDescriptor(position), child, at)
                }
                for (index in 0 until descriptor.elementsCount) {
                    val name = descriptor.getElementName(index)
                    if (!descriptor.isElementOptional(index) && name !in obj) invalid("$path.$name", "缺少必填字段")
                }
            }
            StructureKind.LIST -> {
                val array = value as? JsonArray ?: invalid(path, "字段须为数组")
                array.forEachIndexed { index, child -> requireValid(descriptor.getElementDescriptor(0), child, "$path[$index]") }
            }
            StructureKind.MAP -> {
                val obj = value as? JsonObject ?: invalid(path, "字段须为对象")
                obj.forEach { (key, child) -> requireValid(descriptor.getElementDescriptor(1), child, "$path.${safeSegment(key)}") }
            }
            kotlinx.serialization.descriptors.SerialKind.ENUM -> {
                val primitive = value as? JsonPrimitive ?: invalid(path, "枚举值无效")
                if (!primitive.isString || (0 until descriptor.elementsCount).none { descriptor.getElementName(it) == primitive.content })
                    invalid(path, "枚举值无效")
            }
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> if (value !is JsonPrimitive || !value.isString ||
                (descriptor.kind == PrimitiveKind.CHAR && value.content.length != 1)) invalid(path, "字段须为字符串")
            PrimitiveKind.BOOLEAN -> if (value !is JsonPrimitive || value.isString || value.content !in setOf("true", "false")) invalid(path, "字段须为布尔值")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> {
                val primitive = value as? JsonPrimitive ?: invalid(path, "字段须为整数")
                val content = primitive.content
                if (primitive.isString || !content.matches(Regex("-?(0|[1-9][0-9]*)"))) invalid(path, "字段须为整数")
                val inRange = when (descriptor.kind) {
                    PrimitiveKind.BYTE -> content.toByteOrNull() != null
                    PrimitiveKind.SHORT -> content.toShortOrNull() != null
                    PrimitiveKind.INT -> content.toIntOrNull() != null
                    else -> content.toLongOrNull() != null
                }
                if (!inRange) invalid(path, "字段须为整数")
            }
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> {
                val primitive = value as? JsonPrimitive ?: invalid(path, "字段须为数字")
                val number = if (descriptor.kind == PrimitiveKind.FLOAT) primitive.content.toFloatOrNull()?.toDouble()
                    else primitive.content.toDoubleOrNull()
                if (primitive.isString || number?.isFinite() != true) invalid(path, "字段须为数字")
            }
            else -> Unit
        }
    }

    private fun safeSegment(key: String): String = if (key.length <= 80 && key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) key else "[unknown]"
    private fun invalid(path: String, message: String): Nothing = throw InvalidResourceField(path, message)
}
