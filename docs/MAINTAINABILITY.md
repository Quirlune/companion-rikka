# Maintainability implementation handoff

Baseline: revision34, 2026-10-04. Status: implemented and accepted with targeted offline checks and native assembly.

## Authorized scope and invariant

Improve frequently changed Companion modules so small changes have fewer independent integration points. Preserve original frontend defaults, all accepted chats/branches/checkpoints, History authors versus outgoing roles, per-reference formatting isolation, and executions1–8. Resource wire10, SQLite1 and recovery format1 stay unchanged. This is a behavior-preserving refactor, not new runtime functionality or data migration. Stable unrelated upstream/provider/import/media modules remain out of scope unless a concrete integration requires them.

The scheduler remains the single accepted-run writer. AppController remains activity-scoped admission/lifecycle owner, using its existing mutex and cancellation order. Extracted helpers cannot create another scheduler, store, scope or mutex. Exact accepted receipts never rerun handlers. Preserve serialized constructors, defaults and field names; use nonserialized conversions/policies.

## Work packages and ownership

- Root: architecture/AGENTS/handoff docs, integration, shared signatures, final targeted checks/build/package/Git. Root decides policy/runtime interfaces before parallel edits.
- Android worker: AppController.kt, ConfigurationImportPreparation.kt, LibraryEditPreparation.kt and their focused app tests. Extract pure import decoding/preview/apply/merge responsibilities behind immutable draft/preparation values, then pure library-edit checks/normalization/author-name freeze/restore invalidation/removal validation. Controller retains stopAndJoin/editingCount/admission mutex, preview identity, state publication, validation order, cancellation, repository commit and credentials. No asynchronous draft writes outside controller; dismissal/automation identity behavior remains explicit. Keep public methods/state and messages intact. Root integrates runtime converters in AcceptedTurnPublisher.kt. Title jobs retain their existing lifecycle owner; no further extraction without agreement.
- Core Astra: ExecutionSemantics, pure runtime conversions, NodeCandidateExecutor and their core integration. Owns affected core files except root-reserved Actions.kt, LibraryValidator.kt, RunPlan.kt, InvocationPreparation.kt and ActionConfigurationRules.kt. No app writes.
- Root action package: those five reserved files. Replace version decisions using announced policy; isolate concrete action-configuration rules while preserving diagnostic order/path/severity and saved-versus-rendered validation boundaries. Dedicated UI editors stay in place.

## Sequence and decisions

1. Inspect current boundaries and obtain core advice; Android extraction can proceed independently.
2. Centralize execution compatibility decisions and runtime transfer boundaries with explicit shared contracts; preserve all old semantics.
3. Reduce actual scheduler coupling where the consultation identifies a safe coherent boundary. Consolidate action-specific rules only if duplication can be removed without a new generic framework.
4. Root reviews actual diff and runs one concentrated batch of existing affected checks plus meaningful new contract checks only where necessary, then one offline native assembly. No worker tests/builds/install/push/API. No real API, examples, phone install, emulator reset or UI automation. Native experience remains user acceptance.

Decisions after code consultation: ExecutionSemantics is nonserialized, preserves public Int version parameters and legacy persisted default1, and names the existing version thresholds without changing them. RuntimeSnapshot transfers centralize every runtime field; macroHistory is deliberately separate and must remain explicit. NodeCandidateExecutor returns only a validated candidate or failure; Engine retains accepted receipt replay, locked failure/manual admission, wave scheduling, publication and its update mutex. Reasoning/clear callbacks use that original lock; candidate retries retain their exact budget/cancellation/streaming behavior. ActionConfigurationRules remains a few concrete pure functions; do not merge saved configuration checks with rendered invocation checks or change HistoryViews.selection's existing default in static validation.

## Required reports

Workers read this baseline and acknowledge scope before edits. Report files, new interfaces, actual verification (none if not run), deviations and remaining risks. Escalate wire/behavior changes, synchronization changes and ownership conflicts. No speculative abstractions, reflection or catch-all error layers.

## Routine extension map

| Change | Primary entry and contract |
| --- | --- |
| Ordinary action | Actions registry descriptor/handler; generic parameter editor already reflects descriptor fields. Declare reads/writes accurately; specialized editor is optional. Keep saved structural checks in ActionConfigurationRules and rendered checks at invocation boundaries. |
| Execution behavior | ExecutionSemantics names each independent capability; update CURRENT only with an explicit old-journal compatibility decision. Do not scatter numeric thresholds into consumers. Persisted missing-version default remains1. |
| Runtime field | Add backward-defaulted serialized carrier fields as needed, update RuntimeSnapshots conversions, then scheduler staging/publication receipts. Macro history stays independently restored by checkpoint lengths and variant appends. Check actual branch and recovery restoration, not only the current chat. |
| Model candidate/retry/parsing | NodeCandidateExecutor/CandidateModelGateway/OutcomeValidator. Scheduler owns admission and accepted publication; callbacks carry temporary output only. |
| Import format or preview | ConfigurationImportPreparer returns an immutable preparation. AppController owns preview ID and atomic commit; never publish from parser work or automatically run a workflow. |
| Resource editing policy | LibraryEditPreparation; AppController retains cancellation, admission and save order. Preserve archived authors when identity changes and reject frozen environment drift. |
| UI-only change | Existing native editor or optional frontend slot. Projection is display-only; no original ChatService/Room/provider authority is reattached. |

These boundaries reduce repeated integration work; they do not make semantic changes to macros, memory or branches local by definition. Such changes still need explicit source/identity and compatibility decisions.

## Acceptance record

69 unique affected checks passed: 64 core contracts and five app contracts. Initial batch passed63/64; ActionProcessingTest's pre-revision31 no-stream assertion was stale. HEAD's prior engine already allowed provisional body streaming for execution6+, while revision31's dedicated streaming test checked final correction. Updated only that method to require provisional raw body, final processed replacement and retained raw archive; reran only it, then the five not-yet-run app checks and native assembly. No passing groups were repeated. First attempt failed in1m56s; followup checks/assembly succeeded in1m37s. Existing compiler/deprecation warnings remain in logs.

APK0.34-preview/code193 verifies APKv2 with the same certificate as0.33. SHA256: `e69e033604d850505df2820fbe4d4943e98afdcd3517d0f94602a73deb2d5f21`. Android26+, arm64-v8a/x86_64. Logs and initial/final XML evidence: `C:/Users/34601/Documents/Codex/2026-10-04/companion-continuation/work/maintainability-v34-*`; APK and `验收记录.json` in that task's outputs. No API, device installation, app lifecycle/UI or performance verification. Dedicated desktop preview remains the existing0.33 installation. Existing revision33 evidence is retained and not counted as refactor evidence.
