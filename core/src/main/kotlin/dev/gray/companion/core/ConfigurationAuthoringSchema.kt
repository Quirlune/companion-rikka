package dev.gray.companion.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Discover the actual resource serializers without constructing defaults, random IDs or a Library. */
@OptIn(ExperimentalSerializationApi::class)
object ConfigurationAuthoringSchema {
    private val roots get() = ResourceKindCatalog.ordered.filter { it.name in ConfigurationWorkspaces.editableKinds }
    private val descriptors: Map<String, SerialDescriptor> by lazy {
        linkedMapOf<String, SerialDescriptor>().also { found -> roots.forEach { collect(it.serializer.descriptor, found) } }
    }

    fun describe(type: String? = null): JsonObject = buildJsonObject {
        put("configFormat", ConfigurationCommands.FORMAT)
        put("resourceWire", LibraryFormat.CURRENT_VERSION)
        put("limitations", "字段、可空与可省略信息来自实际 serializer；不是完整 JSON Schema 或业务校验，兼容字段仍可能出现。修改须经过草稿校验和提交复核。")
        if (type == null) {
            put("roots", buildJsonArray { roots.forEach { root -> add(buildJsonObject {
                put("kind", root.name)
                put("array", root.array)
                put("type", shortName(root.serializer.descriptor))
            }) } })
        } else {
            val requested = type.trim()
            val matches = descriptors.values.filter {
                shortName(it) == requested || it.serialName.removeSuffix("?") == requested
            }
            val descriptor = matches.singleOrNull()
            if (descriptor == null) {
                put("error", buildJsonObject {
                    put("code", "UNKNOWN_TYPE")
                    put("type", requested)
                    put("message", "未知或不开放的设计资源类型；请从根类型与字段引用继续查询。")
                })
            } else {
                val reachable = linkedMapOf<String, SerialDescriptor>()
                collect(descriptor, reachable)
                put("type", shortName(descriptor))
                put("types", buildJsonObject { reachable.values.forEach { put(shortName(it), definition(it)) } })
            }
        }
    }

    private fun collect(descriptor: SerialDescriptor, found: MutableMap<String, SerialDescriptor>) {
        if (descriptor.serialName.startsWith("kotlinx.serialization.json.")) return
        when (descriptor.kind) {
            StructureKind.LIST, StructureKind.MAP ->
                repeat(descriptor.elementsCount) { collect(descriptor.getElementDescriptor(it), found) }
            StructureKind.CLASS, StructureKind.OBJECT, SerialKind.ENUM -> {
                val key = descriptor.serialName.removeSuffix("?")
                if (found.putIfAbsent(key, descriptor) != null) return
                if (descriptor.kind != SerialKind.ENUM)
                    repeat(descriptor.elementsCount) { collect(descriptor.getElementDescriptor(it), found) }
            }
            else -> Unit
        }
    }

    private fun definition(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
        put("kind", descriptor.kind.toString())
        if (descriptor.kind == SerialKind.ENUM) {
            put("values", buildJsonArray { repeat(descriptor.elementsCount) { add(JsonPrimitive(descriptor.getElementName(it))) } })
        } else {
            put("fields", buildJsonArray { repeat(descriptor.elementsCount) { index -> add(buildJsonObject {
                put("name", descriptor.getElementName(index))
                put("optional", descriptor.isElementOptional(index))
                put("type", fieldType(descriptor.getElementDescriptor(index)))
            }) } })
        }
    }

    private fun fieldType(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
        put("nullable", descriptor.isNullable)
        when (descriptor.kind) {
            StructureKind.LIST -> {
                put("kind", "array")
                put("items", fieldType(descriptor.getElementDescriptor(0)))
            }
            StructureKind.MAP -> {
                put("kind", "map")
                put("keys", fieldType(descriptor.getElementDescriptor(0)))
                put("values", fieldType(descriptor.getElementDescriptor(1)))
            }
            else -> {
                put("kind", descriptor.kind.toString())
                put("type", shortName(descriptor))
            }
        }
    }

    private fun shortName(descriptor: SerialDescriptor): String = descriptor.serialName.removeSuffix("?").substringAfterLast('.')
}
