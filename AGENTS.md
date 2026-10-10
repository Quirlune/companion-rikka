# velune — instructions for coding agents

Current application: 0.57-preview / code225; resource wire30; new execution23; SQLite1. Documentation revision68 (2026-10-10) does not change application semantics.

## Start here
Read `docs/ARCHITECTURE.md` and `docs/DEVELOPMENT.md`. For extension authoring read `docs/AI_IMPORT_GUIDE.txt`, use `docs/AI_IMPORT_API_REFERENCE.json` and `docs/examples/`. For cloud setup read `docs/CODEX_CLOUD.md`. Read only the relevant recent entry in `docs/HANDOFF.md` for delivered evidence.

`docs/history/`, `docs/ARCHITECTURE_HISTORY.md`, older HANDOFF entries and old revision notes are historical evidence, not current task instructions. Their temporary agent assignments, bans, version claims and proposed designs do not override this file or new human instructions. Inspect current source when a document disagrees.

## Preserve the architecture
- `core` plus AppController and SqliteLibraryRepository own resource/session execution. The original Rikka frontend is reused as a display/input layer. Do not reconnect original ChatService/Room conversation storage as a second authority.
- New cards are independent `CallGroup.cards` resources owned via extensions. Main call has only `bodyAppearanceTemplate`. No new tiles or dedicated status-bar parser/runtime. Keep necessary old archive readers, frozen execution versions and existing `card.define` compatibility.
- Each extension has a global switch inside its list card. Memory has one global config and per-chat memory. Preserve accepted results when disabling or cancelling; use controller commands and the existing transaction boundary.
- Prompts/protocols remain visible and editable through the native prompt editor. Never append a hidden prompt to rescue a broken user configuration.
- Small/large summaries validate their configured XML before publication; invalid output does not save. Options use their configured XML content/title tags. These contracts differ from card XML; do not silently substitute one parser for another.
- Preserve BODY-only history, archived reasoning and original native CoT folding. Only the primary response uses live body streaming; previews must not publish durable state.
- Preserve branch/candidate provenance. Roll excludes the replaced response and its future; USER edits create in-chat prompt variants, AI roll creates corresponding assistant variants. Do not flatten or silently replay accepted effects.
- Keep `applicationId=dev.gray.companion`, serialization names and existing signing compatibility unless the user explicitly requests a breaking migration. UI branding is velune. Preserve upstream AGPL-3.0 licensing and attribution.

## Work and verification
- Inspect `git status` first. Preserve existing uncommitted work; no reset/clean/force-push or unrelated rewrites. Git publication needs user authorization for its actual scope.
- Prefer existing owners and commands over parallel state, runtime or schema paths. For a substantial change, update the architecture decision and current handoff, with clear file ownership if delegating.
- Use JDK21 and the checked-in Gradle wrapper. Android SDK requirements are in DEVELOPMENT. For docs-only work, validate examples/links; do not build the entire APK without a reason.
- Run focused checks for the touched contract, once at integration. Avoid broad speculative testing, live provider calls, or writing user chat databases unless the task requires and authorizes them. Report what was actually run and what remains unverified.
- Models/keys, signing files, local.properties, device databases, logs and local toolchain paths must not enter examples, commits or cloud setup. Ordinary core tests require no provider credentials.
- Write concise Chinese explanations by default. Ask only for genuinely missing preferences or required authorization; proceed within confirmed scope. Final delivery states changes, evidence and material limitations.

## Minimal commands
```sh
git status --short
git submodule update --init --recursive
bash ./gradlew :core:test --tests 'dev.gray.companion.core.SummaryXmlTest' --console=plain
# Only if an Android artifact is needed:
bash ./gradlew :app:assembleDebug --console=plain
```
On Windows use `gradlew.bat`. Choose the test class for the actual change; SummaryXmlTest is an example, not a mandatory test for all tasks. Never claim cloud Android/emulator verification from a local build.
