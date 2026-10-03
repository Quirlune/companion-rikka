package dev.gray.companion.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TileCssTest {
    @Test fun nativeStylesRoundTripCssWithoutChangingLegacyDefaults() {
        val legacy = Json.decodeFromString<MacroTileStyle>("""{"foreground":"#80402010","fontSize":20}""")
        assertEquals(100, legacy.widthPercent)
        assertEquals(legacy, TileCss.parse(TileCss.format(legacy)))
        val changed = TileCss.parse("color: #40201080; background-color: inherit; font-size: 20px; border-radius: 8px; width: 50%;")
        assertEquals("#80402010", changed.foreground)
        assertEquals(50, changed.widthPercent)
        assertEquals(changed, TileCss.parse(TileCss.format(changed)))
    }
}
