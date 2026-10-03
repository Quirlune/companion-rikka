# Repository Guidelines

## Companion migration — active rules (2026-10-03)

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




