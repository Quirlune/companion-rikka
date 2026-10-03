package me.rerere.rikkahub.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gray.companion.AppController
import dev.gray.companion.core.Library
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.ui.pages.search.MessageSearchScope
import me.rerere.rikkahub.ui.pages.search.SearchPageContent
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference

private const val SORT_ORDER_PREF_KEY = "search_page_sort_order"

/** Active Companion messages, displayed through the original search page. */
@Composable
fun CompanionSearchPage(
    controller: AppController,
    onResult: (sessionId: String, messageId: String) -> Unit,
) {
    val context = LocalContext.current
    val library by controller.library.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(MessageSearchScope.CURRENT_ASSISTANT) }
    var sort by rememberSaveable {
        mutableStateOf(runCatching {
            MessageSearchSort.valueOf(
                context.readStringPreference(SORT_ORDER_PREF_KEY, MessageSearchSort.RELEVANCE.name)!!
            )
        }.getOrDefault(MessageSearchSort.RELEVANCE))
    }
    var immediate by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MessageSearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query, scope, sort, immediate, library.sessions, library.selectedSessionId) {
        results = emptyList()
        if (query.isBlank()) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            if (!immediate) delay(300)
            results = withContext(Dispatchers.Default) { searchActiveMessages(library, query, scope, sort) }
        } finally {
            loading = false
        }
    }

    SearchPageContent(
        searchQuery = query,
        searchScope = scope,
        sortOrder = sort,
        results = results,
        isLoading = loading,
        onQueryChange = { query = it; immediate = false },
        onScopeChange = { scope = it; immediate = true },
        onSortChange = { sort = it; context.writeStringPreference(SORT_ORDER_PREF_KEY, it.name); immediate = true },
        onSearch = { immediate = true },
        onResultClick = { onResult(it.conversationId, it.messageId) },
    )
}

private data class RankedHit(val result: MessageSearchResult, val occurrences: Int, val time: Long)
private val whitespace = Regex("\\s+")

private suspend fun searchActiveMessages(
    library: Library,
    query: String,
    scope: MessageSearchScope,
    sort: MessageSearchSort,
): List<MessageSearchResult> {
    val needle = query.trim().replace(whitespace, " ")
    if (needle.isEmpty()) return emptyList()
    val selectedCharacter = library.sessions.firstOrNull { it.id == library.selectedSessionId }?.characterId
    if (scope == MessageSearchScope.CURRENT_ASSISTANT && selectedCharacter == null) return emptyList()
    val context = currentCoroutineContext()
    val ranked = buildList {
        for (session in library.sessions) {
            context.ensureActive()
            if (scope == MessageSearchScope.CURRENT_ASSISTANT && session.characterId != selectedCharacter) continue
            for (message in session.messages) {
                context.ensureActive()
                if (message.deleted) continue
                val text = message.text.replace(whitespace, " ").trim()
                val first = text.indexOf(needle, ignoreCase = true)
                if (first < 0) continue
                var count = 0
                var at = first
                while (at >= 0) {
                    count++
                    at = text.indexOf(needle, startIndex = at + needle.length, ignoreCase = true)
                }
                add(RankedHit(
                    MessageSearchResult(
                        nodeId = message.id,
                        messageId = message.id,
                        conversationId = session.id,
                        title = session.title,
                        updateAt = Instant.ofEpochMilli(message.createdAt),
                        snippet = matchingSnippet(text, first, needle.length),
                    ),
                    occurrences = count,
                    time = message.createdAt,
                ))
            }
        }
    }
    val ordered = when (sort) {
        MessageSearchSort.RELEVANCE -> ranked.sortedWith(compareByDescending<RankedHit> { it.occurrences }.thenByDescending { it.time })
        MessageSearchSort.NEWEST_FIRST -> ranked.sortedWith(compareByDescending<RankedHit> { it.time }.thenByDescending { it.occurrences })
        MessageSearchSort.OLDEST_FIRST -> ranked.sortedWith(compareBy<RankedHit> { it.time }.thenByDescending { it.occurrences })
    }
    return ordered.map { it.result }
}

private fun matchingSnippet(text: String, matchStart: Int, matchLength: Int): String {
    val start = (matchStart - 48).coerceAtLeast(0)
    val end = (matchStart + matchLength + 80).coerceAtMost(text.length)
    return buildString {
        if (start > 0) append('…')
        append(text, start, matchStart)
        append('[')
        append(text, matchStart, matchStart + matchLength)
        append(']')
        append(text, matchStart + matchLength, end)
        if (end < text.length) append('…')
    }
}
