# Companion

Android companion and world/RP workflows, using the original native RikkaHub frontend with an independent Kotlin execution core.

Character cards anchor a conversation. Main calls contain the primary response and optional workflows. Prompt packages store referenced material. Call groups package optional behavior. Macros, conditions, history projection, inline text processing and native status displays connect these resources.

This is a development project. Real provider behavior, broad device adaptation and runtime plugin installation are not claimed as complete.

## Source and architecture

- `core/`: platform-independent resource, prompt, workflow and recovery logic.
- `app/src/main/java/dev/gray/companion/`: Android controller, repository, gateway and editors.
- `app/src/main/java/me/rerere/rikkahub/companion/`: projection into the reused native frontend.
- [Current architecture](docs/ARCHITECTURE.md) describes invariants and extension boundaries.
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
