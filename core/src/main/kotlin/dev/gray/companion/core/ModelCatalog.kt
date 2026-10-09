package dev.gray.companion.core

/** Models select an API connection. Only adapters ever attach credentials. */
object ModelCatalog {
    fun legacyConnectionId(profileId: String): String = "legacy-api:$profileId"

    fun normalize(library: Library): Library {
        val connections = library.connections.toMutableList()
        val profiles = library.profiles.map { profile ->
            if (profile.apiConnectionId != null) profile else {
                val connection = ApiConnection(
                    id = legacyConnectionId(profile.id), name = "${profile.name} API",
                    protocol = profile.protocol, baseUrl = profile.baseUrl, responseApi = profile.responseApi
                )
                val existing = connections.firstOrNull { it.id == connection.id }
                require(existing == null || existing.protocol == connection.protocol &&
                    existing.baseUrl == connection.baseUrl && existing.responseApi == connection.responseApi) {
                    "旧模型的 API 连接标识冲突"
                }
                if (existing == null) connections += connection
                profile.copy(apiConnectionId = connection.id)
            }
        }
        return if (profiles == library.profiles && connections == library.connections) library
            else library.copy(profiles = profiles, connections = connections)
    }

    fun resolveProfile(library: Library, id: String? = library.selectedProfileId): ModelProfile? {
        val profile = library.profiles.firstOrNull { it.id == id } ?: return null
        val connectionId = profile.apiConnectionId ?: return profile // legacy bundles remain readable
        val connection = library.connections.firstOrNull { it.id == connectionId && it.enabled } ?: return null
        return profile.copy(protocol = connection.protocol, baseUrl = connection.baseUrl,
            responseApi = connection.responseApi, resolvedHeaders = connection.customHeaders,
            resolvedProvider = connection.nativeProvider)
    }

    fun credentialId(profile: ModelProfile): String = profile.apiConnectionId ?: profile.id
}
