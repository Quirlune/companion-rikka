> 当前入口（文档修订68）：作者契约以 [AI_IMPORT_GUIDE.txt](AI_IMPORT_GUIDE.txt) 与当前源码为准，架构以 [ARCHITECTURE.md](ARCHITECTURE.md) 为准。下文包含历史版本补充；旧参与范围、磁贴或主调用卡片说明不覆盖当前全局扩展/调用组卡片规则。

Revision52: wire21/execution14, Companion0.48-preview/code213. Builtin automatic options have one Library.automaticOptions typed config, admitted via select key automaticOptions. enabled/modelProfileId/count1–12/prompt literal; profile reference protects deletion. No second plugin/group/workflow. Options remain transient composer state; captured closure resets auxiliary configuration. Structure/context/strict option XML and parsing are builtin; user authoring covers only tone/stance/directions. See current standalone guide and serializer appendix.

# JSON configuration packages

Revision51 current baseline:0.47-preview/code212, wire20 (reads1–20), execution13, 2026-10-10. ConfigurationExtension.memory is an optional native MemoryPluginRules capability referencing an explicit GROUP member and its leaf/aggregation workflow IDs; small摘要 still uses history.process, aggregate afterWorkflowId, existing model/action/source graph paths. Native projection/marking/generation follow the current session plugin choice. No automatic History copy, Session import or code host privilege is required. Ordinary config/ZIP .v1 markers remain unchanged. Newly imported memory resources are inactive until session participation is selected; protect missing group/workflow references. Old main-owned processor fields and execution1–12 remain compatible, with explicit live migration only. Fresh libraries carry actual native Memory off by default. Standalone guide and serializer reference are updated for this additive field.

Current source baseline: revision50, Companion0.46.2-preview/code211, resource wire19 (reads1–19), execution12, 2026-10-09. Configuration and ZIP formats retain their `.v1` markers; do not add a separate `version` or `schemaVersion` field to those documents. Revision40 adds secret-free `nativeProvider`/`nativeModel` payloads through the existing provider codec. Prefer graphical editor/export for those typed payloads and complex History/action structures. Android previews, apply and undo share original native serializer legality checks.

Revision50: typed builtin History supports speaker:user/assistant/system, matching original BODY authors before range selection and independent of format.apiRole. With speaker and no explicit range, read the complete matching pool instead of the main's all-speaker default range. Local floor wrappers retain source numbers; mixed-author summaries cannot inject excluded floors. Existing per-reference editor and completion expose these controls. New execution12 omits blank request text and joins adjacent identical final API roles with newline after prompt/History/injection layout, then again at the same pure helper before candidate transport to cover corrections/retries. Source archives remain separate. The old history.mergeMessages whole-history option is retained for existing configs, with only an explicit-disable UI and no fresh enable toggle. Executions1–11 retain their original interpretation and reject speaker selection.

For conversion by an AI without prior conversation, use [the standalone Chinese authoring guide](AI_IMPORT_GUIDE.txt). It includes strict schemas, all17 registered actions, foreign-format mapping limits, four independent prompt slots, contextOrder, code-plugin contracts and complete ZIP source examples. Its field appendix was generated from current serializers; the example bundles passed core archive read, configuration preview and apply against the default library. Device import, model connectivity and plugin execution were not exercised by that guide check.

Revision49 correction supersedes revision48's ordering rule: prompt.inject.arguments.contextOrder is an independent positive integer, default1. Execution11 persists it with the accepted injection and uses it for same-slot prompt position; workflow.queued/priority exclusively controls execution scheduling. No live priority is inferred or migrated into the new field. Existing turn.prepare remains an optional timing stage. Stored queueOrder is only compatibility metadata for old accepted injections; execution10 retains its former interpretation, while new11 injections record queueOrder0 and their explicit contextOrder. Same-order ties use stable declaration order, not completion time.

Prompt slots introduced in revision48: PromptBlock(injectionSlot=true, slotReferences=["{{pack.world.background}}"]) is a read-only receiver with no literal template or processing. New special defaults include 世界背景, 其他角色, 补充信息, 额外规则; existing defaults fill missing slots only through an explicit draft action. Each source is one complete macro reference; structured History uses separate blocks, with history.text permitted as text. prompt.inject.arguments.targetBlockId selects the actual slot on the target model node. Slot role/position govern its sources and injections; each writer keeps its own injection ID. Use trigger="turn.prepare" to inject before the normal user.message default. Configure contextOrder independently for same-slot content placement; queued/priority remains optional execution scheduling. Import never silently reroutes triggers. Missing/disabled runtime targets fail without unanchored fallback. Old executions retain their original interpretation.

Revision47: new MainCall.specialDefault=true requires its primary workflow to contain one visible BODY model.generate node with no node dependencies; workflow triggers/conditions and model input/output processing remain editable. Ordinary calls retain multiple nodes. Omitted specialDefault defaults false, preserving legacy default graphs. // is editor completion only: inserted references still use double braces. Execution9 supports result references by resource/call/node slug, for example {{type:main.in:chat.call:reply.name:answer}}, alongside legacy result keys.

ConfigurationExtension may include code={runtime:"javascript",version:1,source:"..."}; ZIP source may use {$text:"code/execute.js"}. It defines execute(context), returning a string or {text:string}, and is invoked by an ordinary plugin.run node with arguments.pluginId. Code is bounded pure text JavaScript in isolated QuickJS; compositions provide model, injection and card behaviors. Import installs inert resources/code and never enables or executes them. Current-chat enablement uses Session.enabledExtensionIds, loading collection members alongside existing bindings. Closing a plugin withdraws its active owned injections and results; independently bound shared groups stay loaded. Native APK/Dex/host APIs are not this plugin format.

New completed branch snapshots capture the active resource dependency closure and session choices. Safe cheap recipes replay with those old definitions; expensive or stateful results retain their accepted values. New saved chats capture choices at save time. Old snapshots without captured definitions continue using stored values. Execution9 archives BODY only: existing reasoning recognition removes thought, preserves unrelated XML, and stores original BODY separately from processed frontend BODY. Old accepted archives are not swept or rewritten.

Revision45: standard foreign-card JSON/PNG imports produce character resources without inventing models, main calls or chats. The custom SillyTavern preview envelope/converter is removed; unknown formatted objects are rejected. Explicit authored native config/ZIP may contain workflows and model configuration; the import sheet shows their source and call count before applying. Import never executes them. Identity prompt text is literal; cardDefinition field sources and static name/tag/any/not/all queries participate in reference protection. The registered pure text action is `text.render`; `text.compose` is not registered. New display authoring uses `card.define` and reader placements, described below. Existing legacy tiles remain readable/editable with their historical output contracts; no new-tile UI is offered.

Revision44: character resources support `boundGroupIds: []` alongside `boundPackageIds`. A `bind` command of kind `character` may supply `groups` as resource IDs. Active groups combine session, character and main bindings once per stable ID; their workflows, packages, defaults and cards use the original loading/runtime path. Inherited bindings are never copied into the session. Old character cards omit this field and load no extra groups. Removing a local session association preserves character/main inheritance. Character-only groups follow character selection; sharing or importing resources never selects a session or starts execution. Wire16 exports with character bindings require0.43.1 or later;0.43/code205 does not understand the new field.

Legacy extension-owned displays use ordinary `CallGroup.tiles`, referenced by a GROUP member of `ConfigurationExtension.resources`; there is no TILE resource kind or requirement for MAIN membership. Their editor is available only for owners with existing tiles. New extension cards create an ordinary group containing card.define nodes and collection membership, through the same resource admission. Explicit current-session inclusion edits only `Session.groupIds`; portable config packages do not import sessions or perform that selection. Existing group workflows/packages/defaults accompany group selection; the UI discloses calls. Removing a local association preserves inherited character/main bindings; deleting the collection preserves all group content and associations. Legacy HTML `htmlTemplate`/`htmlCss` remain native string fields supporting referenced `$text` ZIP markers. Existing bindings use `name/pattern/captureGroup/kind`, TEXT or NUMBER, 1–32 items, with text placeholders confined to element content and numbers also allowed in CSS. Final BODY source is configured separately from this compatibility editor.


Behavior-defined cards are `WorkflowNode(actionId="card.define", cardDefinition=...)`. The typed definition carries title, presentation CARD/SIDE_PANEL/STRIP, a compact-mode threshold rather than fixed dimensions, and fields with macro source, RE2J pattern/capture group and TEXT/NUMBER/PROGRESS/LIST presentation. Authors never select reader layout. Card nodes must be invisible as raw chat output and cannot use outputProcessing or regexOutputCopy. Ordinary accepted `ActionOutcome.text` contains a self-contained versioned CardSnapshot, preserving title/labels/types/ranges/presentation with values; existing result origin, activation/masking, branch and recovery receipts remain authoritative. The frontend never re-extracts historical accepted values from a changed definition. UI manual recovery remains restricted to original PURE_MODEL actions; typed candidate validation does not open a new manual card path.

Reader choices are Session.cardPlacements(nodeId,region,order,collapsed), outside RuntimeSnapshot and excluded from configuration imports. Absent placement is the unplaced tray. CARD supports CHAT/SIDEBAR/INPUT, SIDE_PANEL only SIDEBAR, STRIP CHAT/INPUT. Existing layout survives disabling/unloading; incompatible old snapshot/definition regions hide until placed or generated compatibly. Frontend flow/conditional columns and bounded scroll handle phone width; no absolute coordinates or fixed card size. LibraryValidator additionally requires card.define IDs globally unique while preserving old ordinary node-ID acceptance. Wire15 defaults older nodes to null definitions and older sessions to empty placements. Controller.updateCardLayout compares expected layout under its original mutex and rejects active operations atomically; it never invokes stopAndJoin.

`companion.config.v1` is a declarative JSON format for importing and editing Companion configuration. Importing it never starts a chat or calls a model. The app first shows a concrete preview, then saves the selected plan in one transaction. Its local undo receipt restores only the configuration values that plan changed.

```json
{
  "format": "companion.config.v1",
  "label": "城镇设定",
  "resources": {
    "characters": [
      { "id": "town_character", "namespace": "traveler", "name": "旅人", "entries": [] }
    ],
    "mainCalls": [
      { "id": "town_main", "name": "城镇对话" }
    ],
    "packages": [
      { "id": "town_lore", "namespace": "town", "name": "城镇资料", "entries": [
        { "id": "town_square", "key": "square", "text": "广场有一座钟楼。" }
      ] }
    ],
    "groups": [
      { "id": "town_group", "name": "旁白", "workflows": [] }
    ]
  },
  "commands": [
    { "op": "bind", "kind": "character", "id": "town_character", "packages": ["town_lore"] },
    { "op": "bind", "kind": "mainCall", "id": "town_main", "packages": ["town_lore"], "groups": ["town_group"] }
  ]
}
```

`resources` accepts `characters`, `mainCalls`, `packages`, `groups`, and optionally `personas`, `tags`, `profiles`, `connections`, `extensions`. Each item is the same typed object used by Companion's native resource JSON. An item with an existing ID updates that item; a new ID creates it. Omitted IDs, including nested IDs, are generated when the preview is made and remain fixed in that plan. Give resources explicit stable IDs when another item or command refers to them in the same package. Resource lists are processed before commands.

`commands` supports these operations:

| Operation | Fields | Effect |
| --- | --- | --- |
| `put` | `kind`, `value` | Create or replace one typed resource by ID. |
| `mergePatch` | `kind`, `id` or `slug`, `patch` | Merge object fields into an existing resource. Arrays are replaced; `null` removes a field so its typed default applies. The stable `id` cannot be patched. |
| `remove` | `kind`, `id` or `slug` | Remove an existing resource when no remaining configuration or chat refers to it. |
| `bind` | `kind`, `id` or `slug`, `packages` and/or `groups` | Replace package bindings on a character, main call or group; characters and main calls also support `groups`. References in these arrays are IDs. |
| `select` | `key`, `value` | Set `selectedProfileId`, `selectedPersonaId`, `reasoningParsing` or `titleConfig`. |

The `kind` names are `character`, `mainCall`, `package`, `group`, `persona`, `tag`, `profile`, `connection`, and `extension`. `slug` locates an existing character, main call, package, group, persona or tag; profiles, connections and extensions use IDs. Unknown commands, fields and types are rejected. A package cannot edit sessions, messages, saved chats, runtime state or API keys. Credentials stay in the app's local credential store. Do not place secrets in configurable request headers or custom request bodies.

Extensions are independent collections over existing resources and typed public fields. Import saves inert definitions, optionally including bounded JavaScript code; current-chat participation is explicitly controlled by Session.enabledExtensionIds. Deleting a user collection removes its definition, not its constituent resources or independent package/group associations. Closing it withdraws its active owned participation without rewriting saved BODY or shared state history. Built-in Memory remains a separate undeletable system destination. Wire12 introduced canonical clearing of obsolete installation metadata; current wire19 retains that compatibility rule. Collections contain references, not duplicate parameter values, runtime state or credentials. "Domain" means existing macros, not a separate resource kind.

An extension object has `id` (optional generated ID), required nonblank `name`, `resources` (default `[]`), `parameters` (default `[]`) and `code` (default null). Nonnull code is `{runtime:"javascript",version:1,source:"..."}`, defining synchronous global execute(context), returning text or {text:string}; ordinary nodes use `actionId:"plugin.run"` and string arguments.pluginId. A member is `{"kind":"PACKAGE","id":"stable-package-id"}`; member kinds are the uppercase enums `CHARACTER`, `MAIN`, `PACKAGE`, `GROUP`, unlike command/manifest kinds. A public parameter has optional generated `id`, required `title` and `target`; the target requires `kind`, `resourceId`, `field`, plus applicable `entryId`/`workflowId`/`nodeId`/`blockId`/`argDescriptorKey`. The target must exist in a member resource and be offered by `ExtensionFieldRegistry`; readonly prompt slots do not offer BLOCK_TEMPLATE. Values remain in original resource fields. GROUP_STATE_DEFAULT points to an existing stateDefaults key; literal defaults fill absent session keys only. Editing shared source fields is not rolled back by closing a plugin.

Create ordinary member resources in their original editors or import them together with the collection. Enabling the extension in the current chat loads GROUP/PACKAGE members alongside actual bindings. Packages still only store prompts; explicit consuming references and ordinary actions provide injections, models and cards. JavaScript only returns pure text and cannot directly mutate the host. Independent character/main/group/session bindings remain valid when an extension closes. Pure text actions need no model; model actions retain their original model/provider configuration.

Macro admission: legacy `{{pack.namespace.key}}`, `{{state.key}}` and `{{result.outputKey}}` remain supported. Static type:pack/type:character queries support name/tag/any/not, single-entry XML and at:latest. Execution9+ also supports dynamic type:main/type:group result queries using resource/call/node slugs and existing version selectors. Directory folder/under queries and static nonlatest versions remain unsupported. Structured History has its own floor/role/source contract. Editor // completion never changes double-brace runtime syntax.

Native resource-only imports use `previewResources` and reject existing resource IDs (an identical existing tag may be reused); they do not have config's overwrite-by-ID semantics. Chat-containing native backups follow legacy merge/admission and cannot be undone as configuration. Use config for deliberate updates, and inspect before/after values: an entire resource item or `put` replaces the typed object, applying defaults to omitted fields; use `mergePatch` for partial updates.

The preview records full before/after values, changed fields and resource order for each touched item. Apply checks that those items still have the previewed before values, validates the final resource graph once, then saves the library and its local receipt together. Undo requires the touched items still to equal the values this package installed. Later changes to any touched item cause an explicit conflict; unrelated later resources and conversations remain. If a later conversation or binding now uses a resource this undo would remove, undo is refused. Local receipts are excluded from exported resource bundles.

The app keeps the latest20 receipts when committing a new configuration receipt, consistently for source edits and imports. A no-op or library load does not trim existing historical records. Typed decoding failures report authored resource/command field paths with fixed safe messages; original input bodies and serializer exception messages are not echoed.

## Editable ZIP content packages (revision 38)

Revision39 added optional native `mainCall.avatar`, using the same bounded AvatarImage as characters. Existing `$avatar` markers work in this field; absent main-call avatars default to null. Revision40 introduced connection/profile `nativeProvider`/`nativeModel` payloads at wire14; current wire19 still reads older versions. Provider reuse does not put API keys or private keys into exported resources.

The file import picker accepts JSON, ZIP and character-card PNG. Original native JSON and `companion.config.v1` remain supported. A ZIP is an editable authoring tree: keep resource structure in JSON and long prompts in ordinary UTF-8 files. Import reads the archive once, resolves attachments and generates one fixed preview candidate before any resource is published. Apply and strict touched-resource undo use the same configuration path described above. Import never runs a workflow, executes scripts, calls a model or downloads a URL.

An AI can prepare this tree, then a person can revise either its files or the imported resources in the original graphical editors:

```text
manifest.json
characters/guide.json
packages/town.json
mainCalls/dialogue.json
prompts/profile.md
prompts/town.md
images/guide.png
README.md
```

`manifest.json` is at the ZIP root. Paths are root-relative, exact and case-sensitive:

```json
{
  "format": "companion.bundle.v1",
  "label": "Town guide",
  "files": [
    { "path": "characters/guide.json", "kind": "character", "id": "guide" },
    { "path": "packages/town.json", "kind": "package", "id": "town" },
    { "path": "mainCalls/dialogue.json", "kind": "mainCall", "id": "dialogue" },
    { "path": "prompts/profile.md", "kind": "text" },
    { "path": "prompts/town.md", "kind": "text" },
    { "path": "images/guide.png", "kind": "avatar" },
    { "path": "README.md", "kind": "documentation" }
  ]
}
```

`characters/guide.json`:

```json
{
  "id": "guide",
  "namespace": "guide",
  "name": "Guide",
  "entries": [
    { "id": "guide.profile", "key": "profile", "text": { "$text": "prompts/profile.md" }, "isTemplate": false }
  ],
  "boundPackageIds": ["town"],
  "avatar": { "$avatar": "images/guide.png" },
  "greeting": "Welcome."
}
```

`packages/town.json`:

```json
{
  "id": "town",
  "namespace": "town",
  "name": "Town",
  "entries": [
    { "id": "town.setting", "key": "setting", "text": { "$text": "prompts/town.md" }, "isTemplate": false }
  ]
}
```

`mainCalls/dialogue.json` can start with `{"id":"dialogue","name":"Dialogue","boundPackageIds":["town"]}`. Configure its actual provider/model and prompt references in the original editor before use. A bound package makes its entries available; it does not promise automatic prompt injection. To author calls, workflows, blocks and macro settings, use the existing typed native resource fields and stable references, preferably starting from an export. No new interpreter or alternate variable store is involved.

### File kinds and assembly

| `kind` | Content |
| --- | --- |
| `character`, `mainCall`, `package`, `group`, `persona`, `tag`, `profile`, `connection`, `extension` | One existing native resource object. Optional manifest `id` inserts an omitted top-level ID or must match it exactly. |
| `config` | A complete `companion.config.v1` document with resources and/or commands. |
| `native` | One native library/resource/backup JSON; retain its original schema and chat fields. |
| `character-card` | Common v1, v2 or v3 card JSON, or PNG with standard embedded card metadata. |
| `text` | UTF-8 content referenced by `$text`. Merely listing it does not create an entry. |
| `avatar` | PNG/JPEG referenced by `$avatar`. Android produces the same bounded thumbnail as the existing avatar editor. |
| `documentation` | UTF-8 explanatory text, intentionally not imported and reported in the preview. |

With a manifest, every primary resource/config/card must be listed. Attachments may be explicitly listed or directly referenced by a marker. Listing a `text` or `avatar` member does not consume or import it: it must actually be referenced by the corresponding marker. Unused files fail with their location, except explicitly classified UTF-8 documentation. Unknown manifest fields/kinds fail. A primary document cannot also serve as an attachment. Manifest `id` is allowed only for individual native resources or character cards, never config/native/text/avatar/documentation entries.

Without a manifest, the importer recognizes config/native/card JSON by its shape, or a single native resource under `characters/`, `mainCalls/`, `packages/`, `groups/`, `personas/`, `tags/`, `profiles/`, `connections/`, `extensions/`. Remaining PNG files must be embedded character cards; ordinary images must be referenced as avatars. Root `README.md`/`README.txt` and `.md`/`.txt` files under `docs/` are documentation-only with a preview warning. Ambiguous JSON, unreferenced prompt files and unknown binary assets are rejected. Use a manifest for dependable AI-authored packages.

Config and individual resource/card files can be combined. All resource lists are assembled first, then commands run in manifest file order and each document's command order. Duplicate resource IDs within one resource kind fail; use explicit commands for intended repeated edits. Existing IDs retain the normal create/update semantics. Give every shared resource and referenced nested object an explicit stable ID. An omitted ID is generated once for the prepared candidate, never on each Apply click. A native backup must be the only primary document in its ZIP; it can still use attachments/documentation. This preserves its existing import semantics, including the warning that chat-containing backups cannot be undone as configuration.

### File markers and limits

A marker is an object with exactly one property: `{"$text":"prompts/scene.md"}` or `{"$avatar":"images/face.png"}`. Markers can occur where the typed native schema expects a string or avatar respectively, including nested prompt blocks and config commands. Expansion happens before ordinary validation; a marker in an incompatible field still fails its normal type check. A string merely containing marker-like text is literal. Markers cannot include defaults, network URLs, interpolation or relative `../` paths.

`$text` preserves the complete decoded UTF-8 body, including BOM, spaces and CR/LF. Nothing is trimmed or silently truncated. JSON files may have a UTF-8 BOM before their root object. JSON nesting is limited to 64 levels. Avatar markers accept PNG/JPEG; PNG card metadata must be imported as a card rather than silently discarded through an avatar marker. Large images require the Android thumbnail encoder; a pure-core caller without an encoder must supply an already valid image no larger than 32 KiB.

Default limits per import:

| Limit | Value |
| --- | --- |
| ZIP members, including directory entries | 1,024 |
| Actual total decompressed input | 256 MiB |
| One JSON or decoded PNG card JSON | 8 MiB |
| One prompt/documentation file | 32 MiB |
| One source PNG/JPEG | 32 MiB |
| Charged JSON/text/retained-source/thumbnail materialization | 64 MiB |
| Stored avatar thumbnail | 32 KiB |

Repeated text expansions each count against materialization; compression ratios and declared ZIP sizes do not bypass the actual byte limits. The materialization budget is a content budget, not a peak-heap guarantee: JSON trees, Java strings and the existing preview/receipt images also have overhead. ZIP packaging does not change the current string-based prompt/runtime/receipt storage. Split large packages when limits are reached.

Paths must use `/`, be NFC Unicode relative paths, and contain no empty component, `.`/`..`, backslash, colon, control character or trailing dot/space. Duplicate names, including case-only aliases and file/directory conflicts, fail. Members are streamed to generated private filenames, never extracted using supplied paths. ZIP/PNG CRC and PNG chunk lengths are checked. Failure/cancellation removes only this import's temporary members and directory without publishing a candidate. Encrypted archives, unsupported compression, native executables and arbitrary binary attachments are unsupported. The bounded javascript/version1 plugin source is supported as a referenced UTF-8 text attachment; saving it never executes it.

### Common character cards

The importer accepts flat v1 fields and the `data` envelopes of `chara_card_v2`/`chara_card_v3`. PNG uses base64 UTF-8 card JSON in `tEXt` chunks named `chara` or `ccv3`; when both exist, `ccv3` wins and the preview states this. Nonstandard compressed `zTXt`/`iTXt` card payloads and extra PNG embedded assets are rejected. The format references are the [v1 specification](https://github.com/malfoyslastname/character-card-spec-v2/blob/main/spec_v1.md), [v2 specification](https://github.com/malfoyslastname/character-card-spec-v2/blob/main/spec_v2.md), [v3 specification](https://github.com/kwaroran/character-card-spec-v3/blob/main/SPEC_V3.md), and [SillyTavern's PNG parser](https://github.com/SillyTavern/SillyTavern/blob/release/src/character-card-parser.js).

Description, personality, scenario and examples become normal character fields. First/alternate greetings become native openings where their macros can be converted. `{{char}}`/`<BOT>`/`<char>` and `{{user}}`/`<USER>` map to the existing character/user name macros. Other dynamic syntax is retained as literal text with a warning; an unsafe opening is preserved in a disabled entry until edited. Creator notes and system/post-history overrides remain disabled entries. Embedded worldbook content becomes a bound prompt package with disabled entries: keyword scanning, recursion and insertion positions need explicit native configuration. Full source JSON, including unconverted metadata/extensions, is retained as a disabled, non-template entry. No hidden settings or global prompt overrides are installed.

The PNG image becomes the character avatar with textual metadata stripped before thumbnail preparation. External assets are not fetched; attachment metadata remains in the source entry and raises a warning. This is common-card content interoperability, not full SillyTavern execution or a full CHARX asset implementation. ZIPs named `.charx` can only succeed if all their actual members satisfy the same supported resource/attachment rules; embedded audio, video or other unsupported assets fail explicitly.

