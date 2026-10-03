# Companion architecture

Active baseline: revision 23, 2026-10-03. Historical decisions and validation evidence are in ARCHITECTURE_HISTORY.md; superseded behavior there is not the current contract.

## Purpose and boundaries

A local Android companion/world/RP framework with four independent resources: character cards, main calls, prompt packages and call groups. Users compose prompts, conditions, workflows and macro displays without changing application code. Preserve the complete original RikkaHub frontend and its native theme/layout/motion where the user has not requested changes.

This optimization keeps existing behavior and data. It does not implement a new renderer, distributed scheduler, runtime plugin installer, real API demo or additional example chats. Retain upstream licensing and source history. Upstream base: rikkahub/rikkahub, commit 4391d5a5e4c9dd19f4713e3a759b4f6b325ae58b. Material utilities remain the original pinned submodule.

## Dependency direction

- Compose/native frontend -> Companion projection and editors -> AppController -> core and repository/gateway ports.
- core is a plain JVM module. Resource resolution, prompt compilation, planning, conditions, processing and message operations have no Android/UI/network implementation dependency.
- ModelGateway, LibraryRepository and CredentialStore are integration interfaces. The Android adapter uses the existing provider SDK, SQLite records and Keystore.
- Original Conversation/MessageNode/UIMessage are display projections only. Do not activate the original ChatService or Room conversation execution for Companion.
- Optional original modules stay dormant; source presence does not establish enabled runtime behavior or zero power consumption.

## Runtime contracts

- One planner and scheduler handle normal execution and recovery. Reserve history.process, turn.prepare, user.message and reply.completed for the runtime; custom events use the same machinery.
- Each prepared invocation reads a captured environment. Node outcomes are validated before any accepted state, macro, injection, reply or event publication.
- Accepted publication receipts preserve exact macro versions and message identities. Replaying a receipt never reruns a handler, increments state or repeats external model calls.
- Recovery edits the actual failed node's saved PromptBlock list and recompiles it against the captured original input/history/persona/macros/state/upstream results. Structural drift is an explicit conflict. No extra user floor is inserted.
- A partial and complete turn share their variant and reply IDs. Failed regeneration keeps the selected answer and world until successful candidate completion.
- Initial model request plus at most two retry rounds; at most two candidates per round, serial fallback without extra budget. Only explicitly pure model invocations may race. First valid candidate publishes once.
- Missing current producers stay masked; historical values remain available only through explicit historical selection. Required dependents pause or are explicitly skipped. Recognition false is ordinary nonactivation.
- Pure output contracts are shared with display; views never invoke models or mutate macro history. Attribution requires a direct current source, not guessed multi-source or retroactive ownership.
- Controller editing, run admission, cancellation and durable commits keep one synchronization order. Short accepted-result commits survive cancellation. UI navigation does not own recovery jobs.

## Optimization rounds and ownership

The user authorizes a private GitHub repository in the current account, commits/pushes and several focused optimization rounds. No API calls, data resets, phone installation or extra sample insertion.

1. Establish the current snapshot and upstream ancestry in Git; exclude local configuration, credentials, user data and build artifacts. Keep inherited publishing automations disabled unless explicitly configured.
2. Astra owns core runtime refactoring: explicit preparation/candidate validation/publication responsibilities, normal/resume unity, journal compatibility checks and centralized event/action capabilities. Prefer a few concrete internal types over a generic framework. Each type must explain a real invariant or remove duplicated execution logic.
3. Sol owns AppController and app-owned editor integration: separate transaction/publication coordination from presentation, injectable dependencies at the assembly boundary, centralized resource kinds and registry-driven action menus. Keep the public controller API and original components stable.
4. Root owns GitHub, this baseline, onboarding/upstream provenance, LibraryFormat.kt and its compatibility check, SqliteLibraryRepository and final integration. Other core files belong to Astra; other app files belong to Sol except root-owned repository and onboarding.
5. Cross-review the changed boundaries, repair concrete issues, then concentrate existing affected checks and one native assembly/data-preserving preview update. Workers do not build, test, install, push or touch emulator data.

## Public compatibility decisions

- Append RunRecovery.formatVersion and executionVersion, both Int with legacy default 1. Unknown journal format/semantics produce a recovery conflict while retaining accepted data. Keep version 1 semantics throughout this refactor.
- LibraryFormat is the single wire compatibility entry: current resource format 2, reads version 1 and upgrades its tag and captured resource snapshots without changing execution meaning. SQLite table schema remains 1; resource metadata version is separate. Model/field structure remains compatible. Root provides encode/decode/upgrade plus the shared strict Json instance; Astra sets Library.schemaVersion default to 2 and validation against LibraryFormat.CURRENT_VERSION after agreement.
- ActionDescriptor has backward-defaulted ActionPresentation (STANDARD/COMPATIBILITY) and ActionExecution (HANDLER/PURE_MODEL). usesModel selects model configuration; it does not authorize competing attempts. PURE_MODEL requires usesModel and forbids declared writes. Returned state, macro, injection or event effects are a nonretryable contract violation. Custom handlers remain responsible for external purity.
- No change to macro syntax/addresses, stored action IDs, priorities, visible UI defaults or resource binding semantics. Resource-kind typing affects editor routing, not stored IDs.

## Concrete boundaries and extension work

- WorkflowEngine owns scheduling, admission and accepted mutable state. InvocationPreparer assembles captured inputs/prompts/model configuration; OutcomeValidator applies pure output processing and display contracts; OutcomePublisher builds an exact receipt without changing scheduler state. Normal execution and recovery use these same boundaries.
- AcceptedTurnPublisher owns the Android transaction and its chat projection. ControllerDependencies assembles repositories, credentials, usage and the model gateway at the Android boundary. AppController retains UI state, run admission and public editing operations.
- EditorKind and ResourceKind centralize editor routing while retaining existing saved route keys. New resources still need an explicit model, codec/validation support and editor; avoid a generic runtime resource framework until an actual resource requires it.
- Register a new action with stable ID, parameter descriptors and write declarations in ActionRegistry. STANDARD actions appear automatically in the generic action menu. Use HANDLER unless the handler only requests a model and returns candidate text; usesModel alone allows neither retries nor racing. Specialized editors are optional; existing descriptors remain editable through generic fields.
- Custom events use ordinary names; RuntimeEvents owns lifecycle names. Keep the declared read/write ordering in RunPlan and keep output views pure. Regex belongs in the input/output/trigger/display processing that consumes it.
- LibraryFormat owns resource wire upgrades. Change its version only with an explicit migration and preservation evidence; version changes do not automatically change SQLite tables. Separately bump journal executionVersion when old accepted receipts cannot safely replay under changed execution semantics.
- SQLite encoding caches retain original decoded object identities. Migrated sessions/saved snapshots are copied and encoded on the next save; unchanged records reuse their payload. No per-token database writes are introduced.
- Restoring a saved chat rebinds only its owner and known historical regeneration candidate session identities. Message, turn, variant, accepted receipt and macro version identities stay fixed; the archived snapshot stays independent.

## Decision record

2026-10-03: preserved upstream Git ancestry and pinned material submodule; created private Quirlune/companion-rikka. Inherited publishing workflows are archived and Actions disabled. Two optimization rounds extracted real preparation/validation/publication and Android assembly/commit boundaries, then centralized action capabilities, lifecycle events and typed editor routing. Cross-review identified and repaired rejected-outcome event dispatch and the accepted-result cancellation window. Archive restoration now keeps recoverable breakpoint ownership valid. Resource format 1 upgrades to 2 with the same journal execution semantics (1).

Final integration: failed regeneration retains its old journal when preflight returns no replacement; gateway initialization participates in the controller cleanup boundary. Model configuration editors work for registered usesModel actions. Declaration-based conflict checks now include descriptor defaults, preventing a known later-wave collision before any model request. Five historical assertions were updated to the established partial-save, historical-selector and visibility contracts; the conflict test still requires zero model requests and unchanged prior data.

## Quality and completion

Readable names, ordinary control flow and small explicit data contracts matter more than line count. Do not introduce speculative layers, reflection, global service locators or per-token persistence. Reuse immutable snapshots and parsing caches. Do not claim performance gains without measurement.

Final necessary evidence: existing recovery/plan/action/history checks affected by runtime extraction, one legacy resource/journal compatibility check and native assembly. No broad repeated regressions. Native gestures, real provider cancellation and device performance remain user acceptance. Record commits and actual evidence here at completion.

Long-chat checkpoint storage remains an open measurement question: captured wave sessions can grow with history. Measure serialized size and save latency with real workloads before changing the snapshot representation. This refactor does not claim measured speed or battery gains.

Acceptance, 2026-10-03: 35 distinct affected core checks covered. First run: 29 passed, six failures exposed the stale assertions and default-argument conflict defect recorded above. Targeted follow-up: seven of eight passed; the remaining fixture incorrectly converted a masked empty macro to a number and was corrected with an exists guard. Its final rerun passed. No passing groups were broadly repeated. Offline native :app:assembleDebug passed in 4m33s. Local workspace evidence: work/optimization-final-build.log, optimization-final-fixed.log, optimization-final-complete.log and optimization-checks-final.json.

Data-preserving preview replacement and launch succeeded. Read-only comparison retained all 13 existing resource/session records and their complete content, two chats and one message; DB integrity was ok, crash/startup-error logs empty. SQLite resource metadata remains 1 until the next save; loading returns the upgraded format 2 in memory, and the explicit legacy-wire check includes journals with absent version fields. No samples, model requests, reset or physical-phone install occurred. Native visual/gesture behavior and real provider/device performance were not exercised.
