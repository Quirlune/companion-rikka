package dev.gray.companion.ui

import org.junit.Assert.*
import org.junit.Test

class HtmlRenderMemoryTest {
    private val identity = HtmlLayoutIdentity("body-1", "layout", 1080, 3f, 1f)

    @Test fun coldBodyHintAccountsForLongChineseTextAndNarrowerColumns() {
        val body = "这是一段用于阅读的正文。".repeat(80)
        val wide = HtmlRenderMemory.estimateBodyHeight(body, 1080, 3f, 1f, 16)
        val narrow = HtmlRenderMemory.estimateBodyHeight(body, 720, 3f, 1f, 16)
        assertTrue(wide > 120f)
        assertTrue(narrow > wide)
        assertTrue(narrow <= 8000f)
    }

    @Test fun coldBodyHintKeepsShortTextCompactAndCapsExtremeInput() {
        assertEquals(120f, HtmlRenderMemory.estimateBodyHeight("你好。", 1080, 3f, 1f, 16), 0.01f)
        assertEquals(8000f, HtmlRenderMemory.estimateBodyHeight("正文\n".repeat(10000), 1080, 3f, 1f, 16), 0.01f)
    }

    @Test fun returningRowsKeepTheirHeightButOtherLayoutsAndTilesDoNotInheritIt() {
        val memory = HtmlRenderMemory()
        memory.record(identity, 2400f)
        assertEquals(2400f, memory.height(identity))
        assertNull(memory.height(identity.copy(tileId = "body-2")))
        assertNull(memory.height(identity.copy(widthPx = 900)))
        assertNull(memory.height(identity.copy(fontScale = 1.3f)))
        assertNull(memory.height(identity.copy(documentDigest = "new-template")))
        // A real correction is allowed to shrink; a placeholder never calls record.
        memory.record(identity, 900f)
        assertEquals(900f, memory.height(identity))
    }

    @Test fun memoryIsBoundedAndInvalidMeasurementsCannotReplaceKnownGeometry() {
        val memory = HtmlRenderMemory(capacity = 2)
        val second = identity.copy(tileId = "body-2")
        val third = identity.copy(tileId = "body-3")
        memory.record(identity, 500f)
        memory.record(second, 600f)
        memory.record(identity, Float.NaN)
        assertEquals(500f, memory.height(identity))
        memory.record(third, 700f)
        assertNull(memory.height(second))
        assertEquals(500f, memory.height(identity))
        assertEquals(700f, memory.height(third))
    }

    @Test fun reportsRejectOldPagesEmptyShellsOlderFragmentsAndWrongWidths() {
        val report = HtmlHeightReport.parse("companion-height:navigation-1:3:360:2400:1")!!
        assertTrue(report.matches("navigation-1", 3, 360f))
        assertFalse(report.matches("navigation-2", 3, 360f))
        assertFalse(report.matches("navigation-1", 4, 360f))
        assertFalse(report.matches("navigation-1", 3, 390f))
        assertNull(HtmlHeightReport.parse("companion-height:navigation-1:0:360:0:1"))
        assertNull(HtmlHeightReport.parse("companion-height:navigation-1:3:360:NaN:1"))
        assertNull(HtmlHeightReport.parse("companion-height:80"))
    }
}
