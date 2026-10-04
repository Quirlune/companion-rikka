package dev.gray.companion.core

/** Shared pure evaluation for current dashboards and frozen final BODY publication. */
data class ResolvedMacroDisplay(val tile: MacroTile, val value: String, val error: String?,
    val waiting: Boolean = false, val html: String? = null)

object MacroDisplay {
    fun configuredTiles(library: Library, session: Session): List<MacroTile> {
        val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        return main?.tiles.orEmpty() + library.loadedCallGroups(session, main).flatMap { it.tiles }
    }

    fun resolve(library: Library, session: Session, placement: MacroTilePlacement): List<ResolvedMacroDisplay> {
        val resolver = MacroResolver(library, session)
        return configuredTiles(library, session).filter { it.enabled && it.placement == placement }
            .map { evaluate(library, session, it, resolver) }
            .filter { !it.tile.hideWhenEmpty || it.value.isNotEmpty() || it.error != null || it.waiting }
    }

    fun evaluate(library: Library, session: Session, tile: MacroTile): ResolvedMacroDisplay =
        evaluate(library, session, tile, MacroResolver(library, session))

    private fun evaluate(library: Library, session: Session, tile: MacroTile, resolver: MacroResolver): ResolvedMacroDisplay {
        val rendered = resolver.render(tile.template)
        val patternIssue = SafePatterns.validate(tile.pattern, tile.captureGroup)
        val currentSource = MacroOutputContracts.sourceAddress(tile)?.let { address ->
            when {
                address.startsWith("result.") -> MacroReferences.currentVersion(library, session, address) != null ||
                    session.results.containsKey(address.removePrefix("result."))
                address.startsWith("state.") -> session.state.containsKey(address.removePrefix("state."))
                else -> false
            }
        } == true
        val pending = tile.kind in setOf(MacroTileKind.STATUS, MacroTileKind.HTML) && rendered.isEmpty() &&
            isDeclaredPending(tile, library, session)
        val contract = if (patternIssue == null && !pending && (currentSource || tile.kind == MacroTileKind.HTML))
            MacroOutputContracts.read(tile, rendered) else null
        val value = if (patternIssue == null) contract?.value ?:
            SafePatterns.extract(tile.pattern, rendered, tile.captureGroup).orEmpty() else ""
        val error = patternIssue ?: contract?.error
        return ResolvedMacroDisplay(tile, value, error, pending && value.isEmpty() && error == null, contract?.html)
    }

    private fun isDeclaredPending(tile: MacroTile, library: Library, session: Session): Boolean {
        val address = tile.template.trim().removeSurrounding("{{", "}}").trim()
        val key = when {
            address.startsWith("result.") -> address.removePrefix("result.")
            address.startsWith("macro.") -> address.removePrefix("macro.")
            else -> return false
        }
        if (key.isBlank() || key.any { it in "@#{}" }) return false
        if (ResultReferences.declarations(library, session).none { it.key == key }) return false
        return "result.$key" !in session.macroHistory && "result.$key" !in session.currentOnlyMacros && key !in session.results
    }

    internal fun freezeBody(library: Library, session: Session, main: MainCall, reply: ChatMessage, executionVersion: Int = 8): BodyFreezeResult {
        val tile = configuredTiles(library, session).singleOrNull { it.id == main.bodySourceTileId }
            ?: return BodyFreezeResult(error = "正文磁贴未加载或不存在")
        if (!tile.enabled || tile.placement != MacroTilePlacement.CHAT ||
            tile.kind !in setOf(MacroTileKind.TEXT, MacroTileKind.HTML))
            return BodyFreezeResult(error = "正文来源必须是已启用的聊天 TEXT / HTML 磁贴")
        val sourceResolver = MacroResolver(library, session, executionVersion = executionVersion)
        val display = evaluate(library, session, tile, sourceResolver)
        if (sourceResolver.issues.isNotEmpty()) return BodyFreezeResult(error = "正文磁贴引用不可用")
        if (display.error != null || display.waiting || display.value.isBlank())
            return BodyFreezeResult(error = display.error ?: "正文磁贴内容为空或尚未生成")
        val text = if (tile.kind == MacroTileKind.HTML) {
            if (main.bodyTextTemplate.isBlank()) return BodyFreezeResult(error = "HTML 正文需要语义文本模板")
            val resolver = MacroResolver(library, session, executionVersion = executionVersion)
            val semantic = resolver.render(main.bodyTextTemplate)
            if (resolver.issues.isNotEmpty() || semantic.isBlank() || display.html.isNullOrBlank())
                return BodyFreezeResult(error = "HTML 正文或语义文本不可用")
            semantic
        } else display.value
        val frozen = BodyDisplaySnapshot(tile.id, display.html, tile.style)
        val revisions = if (text != reply.text) reply.textRevisions + TextRevision(
            reply.activeTextRevisionId ?: reply.id, reply.text,
            reply.activeTextRevisionCreatedAt ?: reply.createdAt, reply.bodyDisplay) else reply.textRevisions
        val createdAt = maxOf(System.currentTimeMillis(),
            (revisions.maxOfOrNull { it.createdAt } ?: reply.createdAt) + 1)
        return BodyFreezeResult(reply.copy(text = text, bodyDisplay = frozen, textRevisions = revisions,
            activeTextRevisionId = if (text != reply.text) newId() else reply.activeTextRevisionId,
            activeTextRevisionCreatedAt = if (text != reply.text) createdAt else reply.activeTextRevisionCreatedAt))
    }
}

internal data class BodyFreezeResult(val message: ChatMessage? = null, val error: String? = null)
