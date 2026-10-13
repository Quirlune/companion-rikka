package me.rerere.rikkahub.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashHandlerTest {
    @Test fun reportPreservesLocationsWithoutExceptionMessages() {
        val cause = IllegalArgumentException("secret-provider-payload")
        val failure = IllegalStateException("private-conversation-text", cause)
        val report = CrashHandler.sanitizedStack(failure)
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("IllegalArgumentException"))
        assertTrue(report.contains("CrashHandlerTest.kt"))
        assertFalse(report.contains("secret-provider-payload"))
        assertFalse(report.contains("private-conversation-text"))
    }
}
