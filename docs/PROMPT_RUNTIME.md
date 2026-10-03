# Prompt runtime foundation

This is an opt-in foundation, not a replacement UI. Existing Compose screens, provider implementations,
UIMessage parts, attachment URLs, OCR, document processing, tool handling and conversation storage remain
upstream-compatible. No rules or custom presets are installed by default, and no additional model calls
occur until rules are configured.

## Boundaries

- `prompt-runtime`: pure Kotlin/JVM protocol, persistent references/packages/presets/rules, snapshot
  compilation, summary coverage, bounded background scheduling. No Android, Compose, provider SDK or
  message model dependencies.
- `app/.../data/ai/runtime`: adapters to existing UIMessage, assistants, conversations and GenerationLoop.
- `GenerationLoop`: provider/tool execution and streaming. Prompt assembly was extracted into
  `ChatPromptAssembler`; transient injections never become its returned conversation messages.
- `ChatService`: passes a request context and emits successful reply events via PromptRuntimeService.
  Notification events retain the upstream bus and are never treated as successful task events.

This deliberately trims responsibilities rather than deleting arbitrary upper-layer features. Search,
workspace, voice and MCP remain application features and are not dependencies of the new JVM module.

## Addressing and consistency

`ReferenceId(provider, name)` is the stable address. UI names are not identifiers. Dynamic results are
scoped by assistant, conversation and branch. Static package entries are deliberately reusable across scopes.
Every publication contains the exact message IDs/revisions on which it depends. Source revisions hash role
and serialized message parts; timestamps, translations and token usage do not invalidate results.

Upstream stores alternatives per message node rather than a persistent branch object. The adapter derives
a conservative branch fingerprint from all selected alternative IDs. New conversations have distinct scope;
adding an ordinary message preserves scope; selecting/adding an alternative isolates results. Introducing a
new alternative can invalidate more earlier results than necessary. This favors isolation over accidental reuse.

Reserve a publication ticket **before** running a task. A persisted source-event order is inherited by the
entire causal chain, preventing delayed descendants of an older event from overwriting a newer chain. Within
one order, the latest reserved ticket wins regardless of completion order. Hosts preparing events asynchronously
should reserve `store.nextEventOrder()` at the source boundary and pass it as `RuntimeEvent.order`.
Provider disable/reenable advances an epoch, invalidating in-flight tickets and hiding earlier epochs.
Enqueueing does not guarantee publication. `publish` returns null for obsolete, disabled or duplicate tickets.

Compilation selects a preset and all references under one store lock. Empty, disabled, stale and unknown
references produce no messages. Their statuses remain available in the snapshot. Version snapshots stay
fixed for a provider request and its retries; a later tool step is a new request and can take a new snapshot.

The initial policy is **use the latest completed, still applicable result**, omitting unavailable references.
Prompt compilation does not wait for an exact analysis batch. Explicit task joins within one dispatch are
supported as described below. A newer in-flight
task does not hide the last valid completed result. Same-event competing writers to one reference are rejected.

## App API example

Use existing Koin-injected `RuntimeStore`, `EventRuntime`, `ContextHistoryApi`, and `PromptRuntimeService`.
Configuration APIs are suspending; invoke them in a coroutine. This example uses the built-in detached model
action, which reuses provider routing and multimodal inputs but does not write history or execute tools.

```kotlin
val result = ReferenceId("example.analysis", "result")
store.register(ReferenceDefinition(result, "Analysis result"))

store.putRule(EventRule(
    id = "example.analysis.after-reply",
    eventType = PromptRuntimeService.REPLY_COMPLETED,
    actionId = ModelTaskAction.ID,
    output = result,
    assistantId = assistant.id.toString(),
    arguments = mapOf(
        "assistantId" to analyst.id.toString(),
        "instructions" to "Analyze the supplied material. Return concise findings.",
        "request" to "Analyze the latest completed reply in context.",
        // Optional "modelId": existing model configuration UUID, not the provider's model-name string.
    ),
))

store.setPreset(assistant.id.toString(), PromptPreset("with-analysis", listOf(
    PromptBlock("system", PromptSource.Builtin(PromptPreset.LEGACY_SYSTEM)),
    PromptBlock("analysis", PromptSource.Reference(result)),
    PromptBlock("history", PromptSource.History),
)))

store.setEnabled("example.analysis", false) // Slot remains configured, no placeholder is sent.
```

Register two rules with different outputs for parallel tasks. Use `artifact.published` plus an
`EventCondition.Equals("reference", "result")` (and a provider condition) to trigger another task from a result.
Conditions support equals, all, any and not; arbitrary code evaluation is intentionally absent.
Events carry causal IDs and visited rule IDs; a rule cannot recur in one chain, and depth is capped.

`EventRuntime.tasks` exposes queued/running/success/obsolete/failure/cancellation records. Cancel by task ID.
Background concurrency defaults to three with a bounded queue; foreground chat does not use that semaphore.
Provider rate limits are not yet coordinated between foreground and background calls.
`lastDispatchError` reports downstream scheduling failures separately from already-published successful work.

## Explicit dependencies and result provenance

`EventRule.dependsOn` contains rule IDs selected by the **same dispatch**. Roots run in parallel; a dependent
waits outside the execution semaphore and starts only after all its prerequisites publish successfully.
Registration order is irrelevant. A cycle or a dependency excluded by conditions/assistant filtering rejects
the dispatch before output reservations or actions. Split conditional groups into separate events when they
need independent admission. Static package entries cannot be task outputs.

```kotlin
// Assume registered dynamic outputs a, b, combined and registered task actions.
store.putRule(EventRule("a", "analyze", "analyze-a", a))
store.putRule(EventRule("b", "analyze", "analyze-b", b))
store.putRule(EventRule("combine", "analyze", "combine-results", combined,
    dependsOn = setOf("a", "b")))
events.registerAction("combine-results") { input ->
    val aText = input.dependencies.getValue("a").text
    val bText = input.dependencies.getValue("b").text
    "$aText\n$bText" // Or invoke an application-supplied model action with these exact inputs.
}
```

`TaskInput.dependencies` holds the exact published artifacts keyed by prerequisite rule ID. It never resolves
the latest global reference to fill a missing batch input. Failure/cancellation/obsolete publication skips
dependents; independent siblings continue. A failed prerequisite is observed without waiting for other slow
prerequisites. Waiting records use WAITING, dependency failures use SKIPPED with a reason. Cancellation of a
join does not cancel shared prerequisites. `timeoutMillis` measures action execution, not dependency/queue
waiting. The queue limit counts active tasks, including dependency waiters, but excludes completed tasks.

Automatic `artifact.published` events include `inputArtifacts`; task actions can inspect these through
`TaskInput.triggerArtifacts`. Their outputs and explicit joins persist the exact consumed artifact IDs in
`Artifact.dependencies`. Scope/source mismatches are rejected. Dependencies are checked before execution and
atomically at publication; superseded/disabled inputs cannot publish new derived outputs. Reference resolution
checks the transitive provenance chain: replacement of an upstream artifact or disabling/re-enabling its
provider makes previous descendants STALE. Merely reserving a replacement does not hide existing completed
reference chains. Immutable snapshots already compiled for a request remain unchanged.

This round extends only the JVM API. The existing Android `model.generate` action does **not yet** map
`TaskInput.dependencies` into model messages; use an action that explicitly consumes that map for joins.
The engine provides neither a visual graph editor nor cross-dispatch joins, fallback-on-failure branches,
durable job recovery, or a foreground wait-for-batch policy.

`PromptRuntimeService.requests` retains the last request per scope (up to 16 scopes), including compiled
reference decisions and the actual UIMessage list **after** legacy transformers. It is an in-memory inspection
API, not a persisted network log; provider-specific JSON encoding still occurs downstream.

## Static packages and named entries

```kotlin
val pack = PromptPackage("example.prompts", 1, listOf(
    ReferenceDefinition(ReferenceId("example.prompts", "style"), "Style", staticText = "..."),
))
store.importPackage(pack)
val exported = store.exportPackage(pack.id)
// Both are @Serializable: use the application's Json for file import/export.
```

Package import replaces that namespace atomically, preserving its enabled state. The caller should confirm
updates in a future editor; this API does not provide an update dialog. Dynamic namespaces cannot be imported
as static packages. To detach content, resolve it and create `PromptSource.Literal(resolved.text)`.
Assistant `promptEntries` provides identity/instructions/examples defaults plus arbitrary named entries,
addressed as `assistant.entry.<name>`. The legacy systemPrompt remains unchanged and is used by default.

## Non-destructive context operations

`ContextHistoryApi.select` selects exact message IDs in current branch order without copying attachments.
`locate` returns the source only if its stored revision still matches. Semantic search is not implemented.

For compression, register a model rule for `context.compression.requested` with a summary output. Dispatch
`historyApi.compressionEvent(conversation, prefixCount)`, then set that reference in the preset's
`HistoryPolicy(summary = reference)`. The event contains only the selected prefix as input/provenance.

An available summary replaces only an exact, nonempty prefix while retaining at least the newest message.
Missing/disabled/edited/out-of-order coverage falls back to history. Pinned IDs prevent summary replacement.
Source history is never deleted; artifacts retain source pointers. Tool-bearing prefixes are rejected by the
compression API until tool-group-aware compression is implemented. Legacy context-message limits still apply;
pinning currently protects against summarization, not those older limits. Arbitrary interior-range compression,
automatic semantic recall, token-budget allocation and foreground exact-batch waiting remain later work.

## Storage and lifecycle limits

App-private `files/prompt-runtime/state.json` stores schema version 1, packages, presets, rules, provider epochs,
tickets and artifacts. Writes use same-directory atomic replacement with file sync; a failed commit leaves
in-memory state unchanged. Corrupt or unsupported data is surfaced, never silently reset. One store instance
validates loaded state before any reads or writes: identifiers must be unique, namespaces consistent, ticket
versions and provider epochs possible, publication fences present, and artifact provenance complete and acyclic.
Dependency context must match the consuming artifact's scope and message revisions. Duplicate source-message
IDs are also rejected at event/reservation/resolution boundaries. Optional prompt references may remain
unregistered, and event rule dependencies are validated per dispatch (so rules can be configured incrementally).
New dependency fields default to empty for previously valid schema-1 files; a previous binary does not understand
nonempty new fields, so backward reading is supported but downgrading a written file is not promised. One store
owns the file. `store.pruneArtifacts(keepLatestPerReference = 1)` explicitly retains the newest N versions per
scope/reference plus all transitive ancestors of those retained outputs. Publication tickets remain as fences
against late work. A failed retention commit leaves the previous history intact. This is not an age/size quota:
the number of scopes, tickets and referenced ancestors can still grow. The initial whole-file backend is suited
to foundation validation, not unbounded production archives. RuntimePersistence is the replacement boundary
for a future indexed transactional store. Upstream backup/export does not yet include this new file.

Tasks, deduplication (last 1024 event IDs), request inspections and diagnostic records are process-local.
Android process death cancels work; no restart recovery or foreground service is added for background analyses.
Completed artifacts survive. Disabling a provider blocks queued work and publication, but an already-running
network request may finish and incur usage. No automatic retry of task side effects is performed; redispatch
a new event to retry. These limits must be addressed before presenting long-running automation as reliable.

## Configuration and inspection API baseline — 2026-09-23, round 3

Status: implemented; 57 standalone JVM tests passed (35 earlier tests plus 22 new tests). Exact commands,
verification evidence and environment limitations are recorded in the round-3 handoff report. This round
implements the first management/inspection batch, not durable execution.

### Goals, boundaries and dependency direction

Keep the pure JVM module and existing application source compatibility. Add configuration transactions,
configuration reads/removal/copy support, optimistic concurrency, side-effect-free inspection, observable
committed state, and structured runtime diagnostics. Existing artifact isolation, provenance, publication
fences, timeout scope and non-destructive history behavior remain mandatory. No app/UI work, new SDK,
database replacement, task recovery/retries, cross-dispatch joins, token budgeting, typed action schemas,
backup integration, or automatic scope deletion is included in this batch.

Configuration commands and read models depend only on existing domain types and shared diagnostics.
RuntimeStore owns persistence, validation, revisions and notifications. EventRuntime owns action registration,
event selection, admission and execution diagnostics. Inspection must reuse production planning/compilation
logic; it must not execute actions or reserve publication tickets. The persistence interface stays replaceable.

### Configuration contract

- Provide a detached configuration snapshot containing providers, reference definitions, packages, preset
  bindings and rules, with a persisted `configurationRevision` (default zero for older schema-1 data).
  Event orders, tickets and artifact writes do not advance this revision.
- Apply a finite list of typed configuration commands under the store mutex. Commands are synchronous data,
  not user callbacks running under the lock. Validate the final candidate, save once, then expose it.
  An invalid candidate, stale expected revision or failed save leaves configuration, revision and notification
  unchanged. A no-op does not write or advance revisions. A caller may pass an expected configuration
  revision; stale editors get a structured conflict, including when their proposed mutation is a no-op.
- Support register/update/remove dynamic references, import/export/remove packages, set/remove preset
  bindings (removal restores the existing default), put/remove rules, and provider enable/disable. Read
  snapshots plus ordinary `copy`/import/set operations support duplication without introducing a separate
  preset-library model. Existing one-operation setters delegate to the same mutation path.
- Configuration preview applies the same command/validation logic without persistence or revision changes.
  Return structured issues with severity, stable code and target path. Missing optional prompt references
  and temporarily missing rule prerequisites are warnings: incremental configuration remains supported.
  An absent rule output is an error. Remove operations never silently cascade other configuration.
  Removal of a dynamic definition with tickets or retained artifacts is rejected; lifecycle deletion is a
  later, separate contract. Removing a package retains/advances its provider fence so delete/recreate cannot
  reset disable history. No change may reinterpret a namespace containing historical dynamic data as static.
- Detached inputs and snapshots must not let callers mutate committed nested collections outside the mutex.
  Copies preserve stable IDs unless the caller explicitly supplies new IDs; copying a package requires
  remapping entry provider IDs. Do not claim a named preset library or automatic deep graph duplication.

### Observability and previews

- Add a lightweight committed-state revision signal, initially uninitialized until load; a successful load
  exposes its initial revision. Every effective durable commit advances the state revision, independently of
  the configuration revision. Emit only after persistence succeeds while still serialized by the mutex.
  Expose latest configuration and scoped artifact observation using this signal, with detached values and
  equality suppression. These flows are conflated current state, not an audit/event log; slow consumers may
  skip intermediate values. They must initialize correctly without an unrelated first write.
- Event preview reports selected/excluded rules and reasons, dependency/admission issues, disabled outputs,
  missing action implementations, competing writers, invalid event order, duplicate/depth limits and current
  queue availability. It reads a coherent store snapshot and never changes sequence, deduplication, tasks,
  committed revisions or model usage. First access may initialize the ordinary loaded-state revision signal,
  as any read does; preview emits no mutation notification. Availability is advisory: dispatch rechecks at admission.
- Share event planning between preview and dispatch. Pin a configuration revision during planning and guard
  reservation against concurrent configuration changes; fail with a conflict before issuing tickets rather
  than mixing old rules and new definitions. Capture action implementations for the dispatch once. Detach
  incoming event collections once at the preview/dispatch boundary so later caller edits cannot change task
  inputs while the already-reserved publication ticket continues to describe an older source snapshot.
- Admission refinement: rules whose output provider is disabled remain visible in the preview but do not
  consume queue slots or require an installed action implementation. Enabled joins depending on them still
  enter the existing SKIPPED path. Competing writers are rejected for the selected rule set, and preview must
  validate trigger artifact identities/context/usability rather than claiming a plan executable with bad input.
- Add a prompt preview wrapper over the existing compiler, returning the compiled snapshot plus structured
  missing/disabled/stale reference and unknown-builtin issues. It must not call a model or save anything;
  existing compile behavior remains source-compatible. Previews do not invent live model capabilities.

### Diagnostics contract

- A shared problem model supplies stable error code, severity, message and optional target path. Use a
  structured operation exception compatible with existing IllegalArgumentException checks for rejected
  API input. Preserve storage failure causes; do not label storage failures as model/action failures.
- Extend TaskRecord with defaulted fields for root correlation, enqueue/start/finish wall timestamps,
  elapsed execution duration measured with monotonic time (action start through publication, excluding
  queue/dependency waiting and downstream dispatch), attempt count (zero before execution, one once
  started), and a structured problem. Retain legacy `error` and existing status values. End states remain
  immutable; cancellation after publication cannot erase a successful result.
- Distinguish timeout, cancellation, missing/unusable dependency, obsolete publication, action failure,
  persistence failure, configuration conflict, queue rejection and invalid plan. Do not guess retryability
  from arbitrary exception text and do not introduce retries. Task problems identify the rule/task through
  their containing record; downstream dispatch failures retain event/root correlation separately from an
  already successful parent task. Keep legacy lastDispatchError available.
- Diagnostics remain bounded and process-local and do not copy prompts, result texts or credentials into
  additional diagnostic fields. Arbitrary action exception messages may contain sensitive data: document
  them as local developer diagnostics, not sanitized telemetry ready for export.

### Implementation sequence and acceptance

1. Define shared diagnostic and configuration/preview DTO contracts; confirm ownership before parallel edits.
2. Implement store transactions/revisions/reads/observation and event inspection/diagnostics in disjoint files.
   Integrate using explicit store snapshot and reservation revision contracts; no cross-agent edits to a file.
3. Verify existing 35 tests, new rollback/CAS/no-op/reload tests, detached collection behavior, subscriptions
   (initial value, successful commit, failed commit), deletion fences and invalid final candidates; verify
   preview makes no durable/task/action changes and agrees with dispatch when state is unchanged; cover
   configuration changes between plan and reservation, queue rejection, unknown actions/builtins, dependency
   failure, timeout/cancel and terminal diagnostic stability. Use deterministic coroutine gates where possible.
4. Run standalone JVM tests, formatting checks for affected Kotlin files and `git diff --check`; preserve
   all pre-existing uncommitted changes. Report actual commands/results and remaining app limitations.

Open questions are deliberately deferred: storage scaling thresholds, durable job execution/replay semantics,
platform background scheduling, export format/migration chain and typed action capability schemas. Any change
to the above state model, revision meaning, deletion safety or existing behavior requires an Astra decision.

### Public entry points

`store.configuration()` returns `ConfigurationSnapshot`. Use `ConfigurationCommand` values with
`previewConfiguration(commands, expectedRevision)` and `applyConfiguration(commands, expectedRevision)`.
The preview's candidate retains the base configuration revision, and `valid` means no ERROR issues;
WARNING issues still permit a commit. Passing the preview's revision to apply protects against intervening
configuration edits; the preview itself does not reserve a commit. The result is `ConfigurationMutation`
with the committed snapshot and a `changed` flag. Convenience removal APIs are `removeReference`,
`removePackage` and `removeRule`; use `ConfigurationCommand.RemovePreset` to restore a default preset.
Normal data-class `copy` plus set/import supports duplication.

`store.stateRevisions` exposes the nullable initialization/committed revision signal.
`store.observeConfiguration()` and `store.observeArtifacts(scope)` initialize on collection and expose latest
detached values. They are not an event log. `store.previewPrompt(scope, sources, builtins)` returns
`PromptPreview`; `snapshot` is null when an unknown builtin prevents compilation, while unavailable optional
references produce warnings and are omitted under the existing compile policy.

`events.preview(event)` returns `EventPreview` with selected and excluded rules, action availability, output
enablement, queue capacity and structured issues. Duplicate/over-depth events are reported by preview;
`dispatch` retains the earlier empty-result behavior for those causal-chain stops. `events.tasks` contains
the extended `TaskRecord`; `events.dispatchProblems` retains the latest 256 downstream scheduling problems
with child event/root correlation. `RuntimeOperationException.problem` and task/dispatch problem fields use
`ProblemCode`, `ProblemSeverity`, message and optional target path. Diagnostic messages are local developer
information, not sanitized telemetry.

## Verification

From repository root: `gradlew -p prompt-runtime test` runs standalone JVM tests without Android SDK.
The module uses JDK 21 and emits JVM 17 bytecode. Full app checks:
`gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.

Core tests cover simultaneous execution, failure isolation, causal chains, timeout/cancellation, stale
publication, disable epochs, persistence, source revisions, package detach and summary coverage. App tests
cover multimodal identity preservation and branch/revision mapping. See the delivered verification report for
which commands actually completed on the development machine.
