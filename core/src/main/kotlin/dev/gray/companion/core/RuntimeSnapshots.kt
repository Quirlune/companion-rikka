package dev.gray.companion.core

/** Runtime values only; macro history is restored separately through checkpoint lengths/appends. */
fun Session.runtimeSnapshot(): RuntimeSnapshot = RuntimeSnapshot(
    state = state,
    results = results,
    resultOrigins = resultOrigins,
    promptInjections = promptInjections,
    historyDigests = historyDigests,
    unavailableResultKeys = unavailableResultKeys,
    historyMemory = historyMemory,
    currentOnlyMacros = currentOnlyMacros
)

fun RunResult.runtimeSnapshot(): RuntimeSnapshot = RuntimeSnapshot(
    state = state,
    results = results,
    resultOrigins = resultOrigins,
    promptInjections = promptInjections,
    historyDigests = historyDigests,
    unavailableResultKeys = unavailableResultKeys,
    historyMemory = historyMemory,
    currentOnlyMacros = currentOnlyMacros
)

fun RunRecovery.runtimeSnapshot(): RuntimeSnapshot = RuntimeSnapshot(
    state = state,
    results = results,
    resultOrigins = resultOrigins,
    promptInjections = promptInjections,
    historyDigests = historyDigests,
    unavailableResultKeys = unavailableKeys,
    historyMemory = historyMemory,
    currentOnlyMacros = currentOnlyMacros
)

/** Preserve chat identity, messages, branch records, macro history and pending recovery. */
fun Session.withRuntime(runtime: RuntimeSnapshot): Session = copy(
    state = runtime.state,
    results = runtime.results,
    resultOrigins = runtime.resultOrigins,
    promptInjections = runtime.promptInjections,
    historyDigests = runtime.historyDigests,
    unavailableResultKeys = runtime.unavailableResultKeys,
    historyMemory = runtime.historyMemory,
    currentOnlyMacros = runtime.currentOnlyMacros
)
