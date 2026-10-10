package dev.gray.companion.core

/** Live configuration only. Never walk accepted worlds, recovery resources or saved branches. */
object ExtensionLiveMigration {
    fun upgrade(library: Library): Library {
        var extensions = library.extensions.map { extension ->
            if (extension.memory != null || extension.enabled != null) extension
            else extension.copy(enabled = library.sessions.any { extension.id in it.enabledExtensionIds })
        }
        var groups = library.groups
        val mains = library.mainCalls.map { main ->
            if (main.cards.isEmpty()) main else {
                // New IDs are stored once; repeating the migration sees an empty main.cards.
                val group = CallGroup(name = "${main.name.ifBlank { "主调用" }} · 卡片", cards = main.cards)
                groups = groups + group
                extensions = extensions + ConfigurationExtension(name = group.name,
                    resources = listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id)),
                    enabled = true, mainScopeId = main.id)
                main.copy(cards = emptyList())
            }
        }
        return if (extensions == library.extensions && groups == library.groups && mains == library.mainCalls) library
            else library.copy(extensions = extensions, groups = groups, mainCalls = mains)
    }
}
