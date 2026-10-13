package dev.gray.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEventLogTest {
    @Test fun boundedRedactedAndErrorSerialSurvivesClear() {
        AppEventLog.clear()
        val before = AppEventLog.errorSerial.value
        val secret = "sk-abcdefghijklmnopqrstuvwxyz012345"
        AppEventLog.record("测试", "https://example.com/?api_key=$secret Authorization: $secret", AppLogLevel.ERROR)
        val entry = AppEventLog.entries.value.last()
        assertFalse(entry.message.contains(secret))
        assertFalse(entry.message.contains("example.com"))
        val headers = AppEventLog.sanitize("Authorization: Bearer short-token, \"api_key\":\"json-token\"")
        assertFalse(headers.contains("short-token"))
        assertFalse(headers.contains("json-token"))
        assertEquals(before + 1L, AppEventLog.errorSerial.value)
        repeat(300) { AppEventLog.record("测试", "事件 $it") }
        assertEquals(256, AppEventLog.entries.value.size)
        AppEventLog.clear()
        assertTrue(AppEventLog.entries.value.isEmpty())
        assertEquals(before + 1L, AppEventLog.errorSerial.value)
    }
}
