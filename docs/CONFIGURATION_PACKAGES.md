# JSON configuration packages

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
| `bind` | `kind`, `id` or `slug`, `packages` and/or `groups` | Replace package bindings on a character, main call or group; only main calls support `groups`. References in these arrays are IDs. |
| `select` | `key`, `value` | Set `selectedProfileId`, `selectedPersonaId`, `reasoningParsing` or `titleConfig`. |

The `kind` names are `character`, `mainCall`, `package`, `group`, `persona`, `tag`, `profile`, `connection`, and `extension`. `slug` locates an existing character, main call, package, group, persona or tag; profiles, connections and extensions use IDs. Unknown commands, fields and types are rejected. A package cannot edit sessions, messages, saved chats, runtime state or API keys. Credentials stay in the app's local credential store. Do not place secrets in configurable request headers or custom request bodies.

Extensions are independent collections over existing resources and typed public fields. They do not require a selected character or main call and have no installation/enabling lifecycle. Deleting a user collection removes its definition, not its constituent resources or actual package/group associations. The built-in Memory collection is identified by its system identity and cannot be deleted. Wire12 reads older installation ledgers but canonically clears that obsolete ownership metadata while retaining every existing actual binding; it does not change runtime loading. Collections contain references, not duplicate parameter values, runtime state or credentials. "Domain" in the authoring workflow means existing macros, not a separate resource kind.

The preview records full before/after values, changed fields and resource order for each touched item. Apply checks that those items still have the previewed before values, validates the final resource graph once, then saves the library and its local receipt together. Undo requires the touched items still to equal the values this package installed. Later changes to any touched item cause an explicit conflict; unrelated later resources and conversations remain. If a later conversation or binding now uses a resource this undo would remove, undo is refused. Local receipts are excluded from exported resource bundles.

## Editable ZIP content packages (revision 38)

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

With a manifest, every primary resource/config/card must be listed. Attachments may be explicitly listed or directly referenced by a marker. Unused files fail with their location, except explicitly classified documentation. Unknown manifest fields/kinds fail. A primary document cannot also serve as an attachment.

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

Paths must use `/`, be NFC Unicode relative paths, and contain no empty component, `.`/`..`, backslash, colon, control character or trailing dot/space. Duplicate names, including case-only aliases and file/directory conflicts, fail. Members are streamed to generated private filenames, never extracted using their supplied paths. ZIP/PNG CRC and PNG chunk lengths are checked. Failure or cancellation removes only this import's own temporary members and directory; cancellation does not publish a candidate. Encrypted archives, unsupported compression, scripts/executables and arbitrary binary attachments are not supported.

### Common character cards

The importer accepts flat v1 fields and the `data` envelopes of `chara_card_v2`/`chara_card_v3`. PNG uses base64 UTF-8 card JSON in `tEXt` chunks named `chara` or `ccv3`; when both exist, `ccv3` wins and the preview states this. Nonstandard compressed `zTXt`/`iTXt` card payloads and extra PNG embedded assets are rejected. The format references are the [v1 specification](https://github.com/malfoyslastname/character-card-spec-v2/blob/main/spec_v1.md), [v2 specification](https://github.com/malfoyslastname/character-card-spec-v2/blob/main/spec_v2.md), [v3 specification](https://github.com/kwaroran/character-card-spec-v3/blob/main/SPEC_V3.md), and [SillyTavern's PNG parser](https://github.com/SillyTavern/SillyTavern/blob/release/src/character-card-parser.js).

Description, personality, scenario and examples become normal character fields. First/alternate greetings become native openings where their macros can be converted. `{{char}}`/`<BOT>`/`<char>` and `{{user}}`/`<USER>` map to the existing character/user name macros. Other dynamic syntax is retained as literal text with a warning; an unsafe opening is preserved in a disabled entry until edited. Creator notes and system/post-history overrides remain disabled entries. Embedded worldbook content becomes a bound prompt package with disabled entries: keyword scanning, recursion and insertion positions need explicit native configuration. Full source JSON, including unconverted metadata/extensions, is retained as a disabled, non-template entry. No hidden settings or global prompt overrides are installed.

The PNG image becomes the character avatar with textual metadata stripped before thumbnail preparation. External assets are not fetched; attachment metadata remains in the source entry and raises a warning. This is common-card content interoperability, not full SillyTavern execution or a full CHARX asset implementation. ZIPs named `.charx` can only succeed if all their actual members satisfy the same supported resource/attachment rules; embedded audio, video or other unsupported assets fail explicitly.
