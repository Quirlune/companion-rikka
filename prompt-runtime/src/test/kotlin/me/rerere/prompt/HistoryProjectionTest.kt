package me.rerere.prompt

import org.junit.Assert.*
import org.junit.Test

class HistoryProjectionTest {
    private val ref = ReferenceId("summary", "history")
    private val prefix = listOf(SourceVersion("first", "v1"), SourceVersion("second", "v1"))
    private val current = prefix + SourceVersion("latest", "v1")
    private val artifact =
        Artifact(
            PublicationTicket("ticket", ref, ContextScope("a", "c", "b"), 1, 0, prefix),
            "summary",
            0,
        )
    private val resolved = ResolvedReference(ref, ReferenceStatus.READY, artifact)

    @Test
    fun `summary replaces only matching prefix and retains newest message`() {
        val result = HistoryProjector.project(HistoryPolicy(ref), resolved, current)!!
        assertEquals(prefix, result.covered)
        assertEquals(3, current.size)
        assertNull(HistoryProjector.project(HistoryPolicy(ref), resolved, prefix))
    }

    @Test
    fun `pinning edits reordering and unavailable summary prevent replacement`() {
        assertNull(HistoryProjector.project(HistoryPolicy(ref, setOf("first")), resolved, current))
        assertNull(HistoryProjector.project(HistoryPolicy(ref), resolved, current.reversed()))
        assertNull(
            HistoryProjector.project(
                HistoryPolicy(ref),
                resolved,
                listOf(SourceVersion("first", "v2")) + current.drop(1),
            )
        )
        assertNull(
            HistoryProjector.project(
                HistoryPolicy(ref),
                resolved.copy(status = ReferenceStatus.DISABLED),
                current,
            )
        )
    }
}
