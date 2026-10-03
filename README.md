# Companion

Android companion and world/RP workflows, using the original native RikkaHub frontend with an independent Kotlin execution core.

Character cards anchor a conversation. Main calls contain the primary response and optional workflows. Prompt packages store referenced material. Call groups package optional behavior. Macros, conditions, history projection, inline text processing and native status displays connect these resources.

Native resource editors provide a shared display name / English slug dialog and a multi-select tag catalogue under prompt packages. Memory settings are an independent destination below call groups; the drawer user header opens persona selection/editing. Metadata aliases preserve existing macro addresses. Per-reference History selections are connected to the new field syntax; general resource/tag query binding remains a separate integration stage.

Regex output copies, workflow-completed triggers, source-backed memory and independently versioned History views use the same execution/recovery core. Generic local HTML/CSS macro templates supplement native text/progress/XML displays without adding a JSON-specific dashboard.

New executions always archive model results with their captured original text. Program writes normally replace the current value, with optional trajectory recording and an independent option for regex copies. Complete per-round snapshots remain available for branches, variants and recovery. An optional main-call body source freezes a processed macro's TEXT or inert HTML into the original message and generation branch; older floors never resolve today's macros or templates. HTML has explicit semantic text for History/copy/export. Wire6 reads1–6, new execution4, older execution1–3 retain their behavior, SQLite table1.

This is a development project. Real provider behavior, broad device adaptation and runtime plugin installation are not claimed as complete.

## Source and architecture

- `core/`: platform-independent resource, prompt, workflow and recovery logic.
- `app/src/main/java/dev/gray/companion/`: Android controller, repository, gateway and editors.
- `app/src/main/java/me/rerere/rikkahub/companion/`: projection into the reused native frontend.
- [Current architecture](docs/ARCHITECTURE.md) describes invariants and extension boundaries.
- [Prompt reference language v1](docs/PROMPT_LANGUAGE.md) defines selection syntax and migration boundaries. Only its History builtin is connected to the runtime; existing legacy macros remain compatible.
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
