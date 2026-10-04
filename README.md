# Companion

Android companion and world/RP workflows, using the original native RikkaHub frontend with an independent Kotlin execution core.

Character cards anchor a conversation. Main calls contain the primary response and optional workflows. Prompt packages store referenced material. Call groups package optional behavior. Macros, conditions, history projection, inline text processing and native status displays connect these resources.

Native resource editors provide a shared display name / English slug dialog and a multi-select tag catalogue under prompt packages. Memory settings are an independent destination below call groups; the drawer user header opens persona selection/editing. Static character/package queries support loaded and activated entry selection by slug and tags. Dynamic output query addresses and folder selection remain unbound; existing result addresses stay authoritative.

Regex output copies, workflow-completed triggers, source-backed memory and independently versioned History views use the same execution/recovery core. Generic local HTML/CSS macro templates supplement native text/progress/XML displays without adding a JSON-specific dashboard.

New executions always archive model results with their captured original text. Program writes normally replace the current value, with optional trajectory recording and an independent option for regex copies. Complete per-round snapshots remain available for branches, variants and recovery. An optional main-call body source freezes a processed macro's TEXT or inert HTML into the original message and generation branch; older floors never resolve today's macros or templates. HTML has explicit semantic text for History/copy/export.

History exposes archived model body and final display body as separate projections over saved messages. Source-linked small summaries can form parent summaries; reference-backed views retain compression choices without copying whole chats. Each History occurrence may configure its own range, floor wrappers and outgoing API roles in the native macro editor. Original author identity stays separate from the API role. A sole History block emits structured messages; mixed text uses the outer block's role.

Configuration JSON packages support resource imports and declarative edits/bindings with preview, atomic application and conflict-aware undo. Multiple authored openings use the native message variant arrows. Optional reasoning-tag parsing excludes reasoning from both body History sources while retaining its own archive; streamed regex output receives final correction.

Current delivery: **0.33-preview**, architecture revision33, resource wire10 (reads1–10), new execution8 (old journals1–7 retain their accepted semantics), SQLite schema1 and recovery format1. This is a development project. Real provider behavior, broad device adaptation and runtime plugin installation are not claimed as complete.

## Source and architecture

- `core/`: platform-independent resource, prompt, workflow and recovery logic.
- `app/src/main/java/dev/gray/companion/`: Android controller, repository, gateway and editors.
- `app/src/main/java/me/rerere/rikkahub/companion/`: projection into the reused native frontend.
- [Current architecture](docs/ARCHITECTURE.md) describes invariants and extension boundaries.
- [Continuation handoff](docs/HANDOFF.md) records the delivered state, local preview paths, evidence and next-task instructions.
- [Prompt reference language v1](docs/PROMPT_LANGUAGE.md) distinguishes implemented History/static selectors from future compiler contracts; existing legacy macros remain compatible.
- [Configuration packages](docs/CONFIGURATION_PACKAGES.md) describes imports, previews, atomic edits and undo.
- [Upstream README](docs/UPSTREAM_README.md) preserves the original project documentation.

Based on [RikkaHub](https://github.com/rikkahub/rikkahub), starting from commit `4391d5a5e4c9dd19f4713e3a759b4f6b325ae58b`. The repository retains upstream history and the AGPL-3.0 license. Original documentation and credits remain in the source tree.

## Development

Clone with submodules. Use JDK 21 and the Android SDK/NDK versions declared by the Gradle project. Configure the local SDK path in untracked `local.properties`, or use Android Studio's project setup.

```sh
git clone --recurse-submodules https://github.com/Quirlune/companion-rikka.git
cd companion-rikka
./gradlew :core:test --tests 'dev.gray.companion.core.RecoveryTest'
./gradlew :app:assembleDebug
```

Windows uses `gradlew.bat`. API keys and signing keys remain local; source checkout and prompt editing do not require a configured model. The existing desktop preview launcher belongs to the local workspace, not the repository's portable setup.

Inherited GitHub publishing workflows are archived in `.github/upstream-workflows/`; Actions are disabled in this development repository until intentionally configured.
