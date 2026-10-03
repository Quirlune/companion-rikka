package dev.gray.companion.core

/** Request provenance belongs to this candidate, never to the public handler result. */
internal class CandidateModelGateway(
    private val delegate: ModelGateway,
    private val parsing: ReasoningParsingConfig,
    private val onReasoning: suspend (ReasoningSnapshot) -> Unit = {}
) : ModelGateway {
    data class Capture(val body: String, val fullText: String, val reasoning: ReasoningSnapshot?)
    private val captures = mutableListOf<Capture>()
    private var requests = 0
    val requestCount: Int get() = synchronized(captures) { requests }
    val singleCapture: Capture? get() = synchronized(captures) { captures.singleOrNull().takeIf { requests == 1 } }

    override fun maxParallelCandidates(profile: ModelProfile): Int = delegate.maxParallelCandidates(profile)
    override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
        generateAttempt(profile, messages, onDelta)

    override suspend fun generateAttempt(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
        request(profile, messages, onDelta).text

    override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (ModelDelta) -> Unit): ModelResponse =
        request(profile, messages) { onDelta(ModelDelta.Text(it)) }

    private suspend fun request(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): ModelResponse {
        synchronized(captures) { requests++ }
        val parser = ReasoningTextParser(parsing)
        val streamed = StringBuilder()
        var previousReasoning: ReasoningSnapshot? = parser.reasoning
        previousReasoning?.let { onReasoning(it) }
        val response = delegate.generateResponseAttempt(profile, messages) { delta ->
            if (delta is ModelDelta.Text) streamed.append(delta.text)
            val text = parser.accept(delta)
            val reasoning = parser.reasoning
            if (reasoning != null && reasoning != previousReasoning) {
                previousReasoning = reasoning
                onReasoning(reasoning)
            }
            if (text.isNotEmpty()) onDelta(text)
        }
        // Some gateways buffer text instead of sending deltas. Others finalize a corrected full response.
        val parsed = when {
            streamed.toString() == response.text -> parser.finish(response.reasoning)
            streamed.isEmpty() -> {
                parser.accept(ModelDelta.Text(response.text))
                parser.finish(response.reasoning)
            }
            else -> ReasoningTextParser.parse(response.text, parsing, response.reasoning ?: parser.nativeSnapshot)
        }
        parsed.reasoning?.let { onReasoning(it) }
        synchronized(captures) { captures += Capture(parsed.text, response.text, parsed.reasoning) }
        return parsed
    }
}
