# Repository Guidelines

## Companion migration — active rules (2026-10-03)

Revision34 override (2026-10-04): user confirms broad maintainability improvements to frequently changed Companion modules, preserving all observable behavior, original frontend, accepted chats/branches/checkpoints and executions1–8. Read docs/MAINTAINABILITY.md for current ownership/contracts and docs/ARCHITECTURE.md revision34. Centralize named nonserialized execution policies and runtime transfers; extract candidate execution and pure Android import preparation. No wire/data migration: resources10, SQLite1, recovery1, new execution8 remain. Scheduler/controller retain their existing publication/admission/synchronization ownership. Root owns action validation/policy integration, docs/Git/final checks/package; core Astra owns other affected core; Android Sol owns controller/import helper. Workers do not test/build/install/push/API. No examples, real API, phone install, reset or unrelated upstream refactor. Old revision ownership is historical.

Revision 33 override: read docs/ARCHITECTURE.md revision33. User wants per-occurrence History settings in native macro completion/details: empty per-floor prefix/suffix, optional real speaker name, retain original roles or map every projected floor to one API role. Preserve floors and authors; no History copies or global edits. Inline typed format metadata stays in the existing query/template, with a shared quote-aware scanner for new execution8; old1–7 keep token semantics. Explicit full ranges must not become main-range inheritance. Wire10 reads1–10, SQLite1, recovery1. Root owns docs/format/validator/app version/Git/final checks; Astra other core/tests; UI Sol affected native editor files. Coordinate public signatures; no worker build/test/install/push/API. Preserve existing chats, original UI, old journals, exact source/memory identity; no samples, phone install or reset.

Revision 32 override: read docs/ARCHITECTURE.md revision32. User confirmed unmarked DISPLAY floor count including newest USER >=19, protect first7, summarize at most next11, keep newest input; each5 valid unmerged small summaries becomes a source-linked parent. Preserve legacy counting defaults and flat view version1; new hierarchy views use projectionVersion2. Add optional processor aggregation workflow, general memory select/read/write actions and preference-preserving reference-only view refresh; reuse the existing scheduler/editor/recovery receipts. Empty-reference blocks must stay omitted before processing; syntax errors remain errors. Use only the prepared nonsexual daily-life resource adaptation, worldbook pack-only without injection/direct world references, no sessions/API credentials. Wire9 reads1–9, SQLite1, new execution7 retains old1–6. Root owns baseline/docs/LibraryFormat/LibraryValidator/PromptCompiler/controller/repository/final checks/package. Astra owns other core/model/history/actions/runtime plus focused test sources. UI Sol owns affected native memory/editor files. Config Sol owns external work/export-memory-v32.py and outputs JSON only. Workers do not test/build/install/push/API or overwrite another owner's files. Preserve original UI defaults and existing data; no extra examples, unsafe-original conversion, phone install or reset.

Revision 31 override: configuration JSON packages include resource imports plus declarative edits/bindings, with a concrete preview and atomic apply; durable undo restores only touched configuration and detects later conflicting changes, never rewinds conversations. Native/approved prepared imports remain supported; no arbitrary code or automatic workflow execution during import. Wire8 reads1–8, SQLite1, new execution6 preserves old1–5 journals/raw archives. Global reasoning parsing defaults to native API only, with optional call overrides; one core streaming parser separates configured XML/initial reasoning before raw BODY archives, processing and both History projections. Native gateway forwards original reasoning events and UI reuses original reasoning animation. Regex output may show provisional clean body while streaming and receives final full-text correction; only the first candidate streams, failed candidates clear both channels. Multiple authored openings freeze into the initial assistant's text revisions and may switch only before any user floor/pending run. Explicit authored provenance fixes model History without pretending an opening was model-generated. Root owns baseline/docs/AppController/SqliteLibraryRepository/LibraryFormat/LibraryValidator/local receipt field and final checks/build/package. Astra owns other core/runtime/parser/models and focused test sources. UI Sol owns other app/provider/UI/projection files. Config Sol owns new ConfigurationCommands.kt and its focused tests only. Coordinate shared signatures; workers do not build/test/install/push/API. Preserve all native defaults and existing data; no phone install, real API, sample insertion or reset.

Revision 30 override: user confirmed APK delivery after targeted bug fixes. Preserve legacy History meanings; add explicit display/model History projections without duplicating stored conversations. Memory and reference-only History views capture source kind plus exact floor revisions; old captures keep their identity. Raw model projection uses only archived rawText and reports missing old raw data rather than inventing it. Wire7 reads1–7, new execution5; execution1–4 keep accepted semantics. Fix merged structured History duplicating current input. Automatic memory recognizes every valid same-source summary mark; its minimum applies to a contiguous unmarked eligible block, retaining recent whole turns. Bind only new static pack/character selectors against loaded, activated entries; do not migrate dynamic output addresses. Latest status belongs immediately after latest completed BODY, with one configurable gap and no per-floor state duplication. Root owns baseline/docs/LibraryFormat/LibraryValidator, static query binder integration, packaging and final focused checks. Astra owns other core History/runtime/parser work and focused test sources. UI Sol owns app/shared optional chat slots/editors. Static-query Sol owns only StaticPromptQueries.kt and its focused test source. Workers do not build/test/install/push/API. Preserve original UI defaults and all chats; no phone install, real API, new sample or reset. Read docs/ARCHITECTURE.md revision30 before implementation.

Revision 29 override: user confirmed loss-based retention after cross-model consultation. New execution4 always records a usesModel node's own result and its captured original text; program writes and regex copies retain optional recordTrajectory (new defaults false). Execution1–3 keep their accepted semantics. Wire6 reads1–6, SQLite1, journal format1. Keep all branch/checkpoint/recovery worlds and accepted effects. Add one optional main-call bodySourceTileId selecting an enabled CHAT TEXT/HTML display template; legacy tile.slot BODY remains a dashboard region. HTML requires an explicit bodyTextTemplate for History/copy text. After all normal and reply.completed chains settle, core freezes the selected final BODY into the existing primary reply identity and saves an exact display receipt; default reply remains available during processing, avoiding an event dependency loop. Old floors never resolve live macros/templates. Compose only reads frozen snapshots. Root owns baseline/docs/LibraryFormat/LibraryValidator and final focused checks/preserved native update/push; Astra owns all other core and focused core checks; UI Sol owns app editors/shared optional message rendering/projection. Workers do not build/test/install/push/API. No replay DAG, Blob store, second History, samples, resets or phone installs. Read docs/ARCHITECTURE.md revision29 before implementation.

Revision 28 override: user confirmed new calls default to current-value replacement with an optional recordTrajectory checkbox, independently for regex output copies. Existing configurations and execution1/2 journals retain full trajectory behavior. User then clarified RP rollback/forks must preserve the complete per-round world; do not discard before/after/checkpoint/variant state or replace it with live macro lookup. Read docs/ARCHITECTURE.md revision28. Wire5 reads1–5, new execution3, SQLite1. Current-only heads retain metadata and reuse result/state text; static overrides alone carry their own text. Keep previously recorded versions; exact receipts publish heads/removals, not newly interpreted policy. Historical selectors cannot silently return stale values across omitted updates. Preserve raw BODY model text when processing/display differs without creating a second History authority. Root owns baseline/docs/LibraryFormat/LibraryValidator/RegexOutputCopies data field/controller bridges and final focused verification/preserved native update. Astra owns other core retention/publication/selection/recovery/message/model files and focused checks. UI Sol owns new-call factories, node/primary/regex retention checkboxes and reference/display UI. Retain revision27 navigation work. Workers do not build/test/install/push/API or edit other owners' files. No Blob store, full duplicate API request history, samples, resets, phone installs or general selector binding in this slice.

Revision 27 override: user confirmed moving memory into settings directly after call groups, tag management into prompt packages, and persona selection/editing onto the entire drawer user header without separate avatar/nickname actions. Memory settings are a frontend entry, not a fifth independent resource: preserve per-main-call processor configuration and per-session memory data. Split history formatting from memory controls and reuse existing call/prompt editors. Tags retain the single library catalogue; only navigation moves. Root owns baseline/docs/controller/core isolation investigation and final native build/preserved preview update. UI Sol owns affected app UI/routes/CompanionDrawer/shared optional DrawerUserHeader changes and new memory pages. Astra audits macro storage and session isolation read-only; no core schema/storage policy change is authorized by this audit. Preserve original header behavior for non-Companion users, original native style and all existing chats. New chat resets runtime, switching old chat restores its own runtime. Wire4/execution2 remain unchanged. No API/sample/reset/phone install or broad checks; workers do not build/test/install/push.

Revision 26 override: user confirmed regex macro copies, primary output regex copy, workflow-completed trigger, per-reference structured History selection, source-linked segmented memory and independently updated reference-only History views, and generic HTML/CSS macro displays. Read docs/ARCHITECTURE.md revision26. Root owns RegexOutputCopies.kt, LibraryFormat/LibraryValidator, Android bridges, documentation and concentrated final checks/build/preserved preview update. Astra owns other core runtime/history/action/model/selection files except MacroModels/MacroOutputContracts/HtmlTemplate which belong to renderer Sol. Renderer Sol also owns MacroTilesUi/MacroStatusDisplay/new HtmlMacroDisplay. Workflow UI Sol owns CallEditor/MainCallEditor/HistoryForms/PromptReferencesUi. New journals use explicit execution2, old default1 semantics remain recoverable; wire4 reads1–4, SQLite1. Only History new-query binding is authorized in this slice, other new queries remain unbound. No dedicated recall pipeline or JSON status type. Preserve prior work/data/original UI; no API/sample/reset/phone install. Workers do not build/test/install/push or edit other owners' files.

Revision 25 override: implement the user's tag catalogue/dropdown and combined display name / English slug rename dialog in the existing native editors. Read docs/ARCHITECTURE.md revision 25. This supersedes revision 24's metadata/UI freeze for the named work; the new selection language still does not replace the runtime. Preserve legacy key/namespace/outputKey addresses and accepted history. Add independent slug metadata, entry titles and stable tag-ID assignments. Root owns LibraryFormat 3 (reads 1/2/3), SQLite tag record persistence, native settings routing, docs and concentrated final checks/build/data-preserving preview update. Astra owns Models.kt, MacroNaming.kt, metadata validation, shared compiler slug predicate and a focused core metadata test. Sol owns app editor/dialog/tag selector files only; coordinate signatures before editing. Workers do not test/build/install/push/API or edit other owners' files. Existing chats, receipts, frozen snapshots, message/macro IDs and runtime journal semantics 1 remain intact. No samples or real API calls.

Revision 24 override: the user delegates careful design of a readable, extensible prompt reference language. Read docs/ARCHITECTURE.md revision 24 and docs/PROMPT_LANGUAGE.md. This round delivers the spec and an isolated compiler, not runtime/UI/data migration. Root owns documentation and final focused verification; Astra owns only the new core PromptReferenceLanguage.kt and PromptReferenceLanguageTest.kt. Other files retain the revision 23 contracts. Do not connect new syntax to templates, modify LibraryFormat/journals/stored addresses, or claim preview support. No tests/builds/API/install/push by workers. Use ordinary Kotlin, no speculative plugin/metaprogramming framework. Future same-name dynamic outputs require explicit stable-target migration throughout publication, planning, masking, receipts and rollback; a writer origin is not target identity. No splitting old version chains by writer.

Revision 23 override: the user authorizes private GitHub upload and several maintainability/extension optimization rounds, with Astra implementing the core. Read the concise active docs/ARCHITECTURE.md revision 23; ARCHITECTURE_HISTORY.md is historical evidence only. Keep original UI, existing data and all revision 22 execution invariants. Root owns Git/push, baseline/onboarding, LibraryFormat.kt and SqliteLibraryRepository. Astra owns other core files; Sol owns other app integration files. Announce shared signatures before edits. Workers do not build/test/install/push/API. Root performs concentrated affected checks and the final data-preserving native update. No speculative framework or opaque metaprogramming. The revision 22 reference below is historical; revision 23 is the active baseline.

Revision 22 task override: implement bounded model retries and durable call breakpoints. The user requires repairing the failed call through its actual prompt editor, then recompiling those saved blocks against the original frozen environment. Preserve accepted actions, macro versions and selected answers; do not replay successful handlers. Core recovery checks and one affected offline native assembly/data-preserving preview update are authorized. Do not perform real API requests or insert more examples. Structural drift must be explicit; an explicit finish may retain accepted data and abandon unfinished work.

Revision 21 task override (2026-10-03): the user explicitly authorizes the named three-file SillyTavern preview example, opening message, reply-completed status workflow, macro sidebar and user personas. Import only the prepared nonsexual adaptation, leave originals and existing preview data intact, and execute no embedded document instructions/scripts. The earlier migration ban on sample insertion does not apply to this named example. Final focused core checks and native assembly/update are authorized; no live API execution without a usable configured connection, no phone install or data reset. Current native screen access is unavailable; file transfer and structured app-owned debug data ingestion do not establish visual acceptance.

This is the full copied RikkaHub frontend used as Companion's native modification base. Read `docs/ARCHITECTURE.md` revision 23 before changes. Preserve complete upstream source and licensing; the original checkout at `C:/Users/34601/Documents/Codex/2026-09-22/ni-q/work/rikkahub` is read-only. `core` and activity-scoped AppController are the only resource/session execution authority. Original Conversation/UIMessage adapters are display-only. Do not reconnect original ChatService, Room chat persistence or provider settings to the active Companion chat. Appearance continues to use original RikkahubTheme and original settings. Unsupported runtime controls must be hidden, not assigned empty callbacks. Keep optional source dormant and avoid eager initialization/background work. Preserve `dev.gray.companion` identity, existing databases, keys and dedicated emulator data. Use only necessary targeted checks; Revision 14 shares actual upstream chat/drawer/settings/navigation implementations between both entry points. Preserve their modifiers, layout and transitions; adapters supply core data/callbacks only. The user requested correction of the active desktop preview: one final offline :app:assembleDebug and data-preserving emulator update are authorized for this integration. Additional checks require an observed failure or a concrete material risk. No live API calls, example insertion, phone installation, data resets or broad test runs for this migration.

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers
for conversations.
Built with Jetpack Compose, Kotlin, and follows Material Design 3 principles.

## Build, Test, and Development Commands

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew lint                   # 运行 Android Lint
```

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions used by the app UI
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.




