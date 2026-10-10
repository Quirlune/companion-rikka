package dev.gray.companion.core

import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*

class BodyAppearanceTemplatesTest {
    @Test fun oldMainCallDefaultsAndCustomTemplateRoundTripsWithoutMacroExpansion() {
        val old = Json.decodeFromString<MainCall>("""{"id":"old"}""")
        assertFalse(BodyAppearanceTemplates.isCustom(old.bodyAppearanceTemplate))
        val template = "<style>.article{max-width:680px}</style><div class='article'>{{正文}} {{input}}</div>"
        val updated = old.copy(bodyAppearanceTemplate = template)
        val restored = Json.decodeFromString<MainCall>(Json.encodeToString(MainCall.serializer(), updated))
        assertEquals(template, restored.bodyAppearanceTemplate)
        assertEquals(old.workflows, restored.workflows)
    }

    @Test fun defaultAndPlaceholderValidation() {
        assertNull(BodyAppearanceTemplates.issue(""))
        assertNull(BodyAppearanceTemplates.issue(" {{正文}} "))
        assertNotNull(BodyAppearanceTemplates.issue("<hr>"))
        assertNotNull(BodyAppearanceTemplates.issue("{{正文}}{{正文}}"))
        assertNotNull(BodyAppearanceTemplates.issue("a".repeat(BodyAppearanceTemplates.MAX_LENGTH) + "{{正文}}"))
    }
}
