package dev.gray.companion

import android.content.Context
import dev.gray.companion.core.CredentialStore
import dev.gray.companion.core.LibraryRepository
import dev.gray.companion.core.ModelGateway
import dev.gray.companion.core.ModelUsageRecord
import dev.gray.companion.data.KeystoreCredentialStore
import dev.gray.companion.data.RikkaGateway
import dev.gray.companion.data.SqliteLibraryRepository
import dev.gray.companion.data.SqliteUsageRepository

/** The Android assembly boundary; one controller still owns admission and all session writes. */
internal class ControllerDependencies(
    val library: LibraryRepository,
    val credentials: CredentialStore,
    val usage: UsageRecords,
    val gateway: (CredentialStore, String, Map<String, Int>, suspend (ModelUsageRecord) -> Unit) -> ModelGateway,
) {
    companion object {
        fun android(context: Context): ControllerDependencies {
            val appContext = context.applicationContext
            val usageRepository = SqliteUsageRepository(appContext)
            return ControllerDependencies(
                library = SqliteLibraryRepository(appContext),
                credentials = KeystoreCredentialStore(appContext),
                usage = object : UsageRecords {
                    override suspend fun append(record: ModelUsageRecord) = usageRepository.append(record)
                    override suspend fun query(from: Long, through: Long) = usageRepository.query(from, through)
                    override fun close() = usageRepository.close()
                },
                gateway = { secrets, sessionId, limits, onUsage ->
                    RikkaGateway(appContext, secrets, sessionId, onUsage, limits)
                },
            )
        }
    }
}

internal interface UsageRecords {
    suspend fun append(record: ModelUsageRecord)
    suspend fun query(from: Long, through: Long): List<ModelUsageRecord>
    fun close()
}
