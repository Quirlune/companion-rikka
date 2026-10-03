package dev.gray.companion.core

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable enum class UsageStatus { SUCCESS, FAILED, CANCELLED }
@Serializable data class ModelUsageRecord(
    val id: String = newId(), val sessionId: String, val profileId: String, val model: String,
    val startedAt: Long, val finishedAt: Long, val status: UsageStatus,
    val promptTokens: Long? = null, val completionTokens: Long? = null, val totalTokens: Long? = null
)
data class UsageBucket(val startedAt: Long, val totalTokens: Long?, val callCount: Int, val unknownCalls: Int)

object UsageAggregation {
    /** Sixteen contiguous three-hour bins, ending at now; never includes future time. */
    fun rolling48Hours(records: List<ModelUsageRecord>, now: Long): List<UsageBucket> {
        val interval = 3 * 60 * 60 * 1_000L
        val starts = LongArray(16) { now - (16 - it) * interval }
        return aggregate(records, starts, now)
    }

    /** Local calendar days, including today. DST does not move records to another day. */
    fun daily(records: List<ModelUsageRecord>, now: Long, days: Int,
        zoneId: ZoneId = ZoneId.systemDefault()): List<UsageBucket> {
        require(days > 0)
        val first = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate().minusDays(days.toLong() - 1)
        val starts = LongArray(days) { first.plusDays(it.toLong()).atStartOfDay(zoneId).toInstant().toEpochMilli() }
        return aggregate(records, starts, now)
    }

    /** Eight local-clock bins per day; DST changes the duration, not the display grid. */
    fun threeHourly(records: List<ModelUsageRecord>, now: Long, days: Int = 7, zoneId: ZoneId = ZoneId.systemDefault()): List<UsageBucket> {
        require(days > 0)
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val first = today.minusDays(days.toLong() - 1)
        val starts = LongArray(days * 8) { index ->
            first.plusDays((index / 8).toLong()).atTime((index % 8) * 3, 0).atZone(zoneId).toInstant().toEpochMilli()
        }
        return aggregate(records, starts, now)
    }

    private fun aggregate(records: List<ModelUsageRecord>, starts: LongArray, now: Long): List<UsageBucket> {
        val tokens = LongArray(starts.size)
        val calls = IntArray(starts.size)
        val unknown = IntArray(starts.size)
        records.forEach { record ->
            if (record.startedAt < starts.first() || record.startedAt > now) return@forEach
            val found = starts.binarySearch(record.startedAt)
            val index = if (found >= 0) found else -found - 2
            calls[index]++
            val value = record.totalTokens
            if (value == null) unknown[index]++ else tokens[index] += value
        }
        return starts.indices.map { index ->
            UsageBucket(starts[index], if (calls[index] > 0 && calls[index] == unknown[index]) null else tokens[index], calls[index], unknown[index])
        }
    }
}
