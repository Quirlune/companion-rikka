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

Extensions are linked configuration compositions over existing resource IDs and typed public fields. Updating a definition recalculates enabled package/group association ownership in the preview, so any affected main calls join that plan and its strict undo receipt. Definition removal is rejected while still installed; removing composition resources is rejected while referenced. Installation metadata travels with main calls; extensions never contain duplicated parameter values, runtime state or credentials. Wire11 adds backward-defaulted metadata; old bundles remain readable.

The preview records full before/after values, changed fields and resource order for each touched item. Apply checks that those items still have the previewed before values, validates the final resource graph once, then saves the library and its local receipt together. Undo requires the touched items still to equal the values this package installed. Later changes to any touched item cause an explicit conflict; unrelated later resources and conversations remain. If a later conversation or binding now uses a resource this undo would remove, undo is refused. Local receipts are excluded from exported resource bundles.
