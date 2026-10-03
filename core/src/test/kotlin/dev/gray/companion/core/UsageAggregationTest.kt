package dev.gray.companion.core

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageAggregationTest {
    @Test fun rollingWindowAndCalendarDaysKeepBoundariesAndUnknownUsage() {
        val now = Instant.parse("2026-10-02T10:00:00Z").toEpochMilli()
        val interval = 3 * 60 * 60 * 1_000L
        val start = now - 16 * interval
        fun record(at: Long, tokens: Long?) = ModelUsageRecord(sessionId = "s", profileId = "p", model = "m",
            startedAt = at, finishedAt = at, status = UsageStatus.SUCCESS, totalTokens = tokens)
        val bins = UsageAggregation.rolling48Hours(listOf(record(start - 1, 9), record(start, 10),
            record(start + interval, 20), record(start + 2 * interval, null), record(now, 30),
            record(now - 1, null), record(now + 1, 99)), now)
        assertEquals(16, bins.size)
        assertEquals(start, bins.first().startedAt)
        assertEquals(10L, bins[0].totalTokens)
        assertEquals(20L, bins[1].totalTokens)
        assertNull(bins[2].totalTokens)
        assertEquals(1, bins[2].unknownCalls)
        assertEquals(30L, bins.last().totalTokens)
        assertEquals(2, bins.last().callCount)
        assertEquals(1, bins.last().unknownCalls)
        assertEquals(5, bins.sumOf { it.callCount })

        val midnight = Instant.parse("2026-10-01T16:00:00Z").toEpochMilli()
        val days = UsageAggregation.daily(listOf(record(midnight - 1, 5), record(midnight, 7),
            record(now, null), record(now + 1, 11)), now, 2, ZoneId.of("Asia/Shanghai"))
        assertEquals(5L, days.first().totalTokens)
        assertEquals(7L, days.last().totalTokens)
        assertEquals(midnight, days.last().startedAt)
        assertEquals(1, days.last().unknownCalls)
        assertEquals(2, days.last().callCount)
    }
}
