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
- ActionDescriptor may gain backward-defaulted presentation metadata and an explicit execution capability. Names/signatures must be agreed with root before app integration. usesModel alone must never make an arbitrary handler eligible for competing/replayed model requests.
- No change to macro syntax/addresses, stored action IDs, priorities, visible UI defaults or resource binding semantics. Resource-kind typing affects editor routing, not stored IDs.

## Quality and completion

Readable names, ordinary control flow and small explicit data contracts matter more than line count. Do not introduce speculative layers, reflection, global service locators or per-token persistence. Reuse immutable snapshots and parsing caches. Do not claim performance gains without measurement.

Final necessary evidence: existing recovery/plan/action/history checks affected by runtime extraction, one legacy resource/journal compatibility check and native assembly. No broad repeated regressions. Native gestures, real provider cancellation and device performance remain user acceptance. Record commits and actual evidence here at completion.
