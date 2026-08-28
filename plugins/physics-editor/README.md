---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.physics-editor
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: physics, editing, simulation
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# Physics Editor

> **Official Turboism plugin** · **Status: Preview**

Adds select-all behavior and reopen retention to Cubism's Physics Settings group list.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.physics-editor` |
| Category | `modeling` |
| Tags | physics, editing, simulation |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Contributes a reviewed Physics Settings policy with group select-all enabled.
- Requests retention of the group-selection state when the Physics Settings window is reopened.
- Closes the contribution cleanly when disabled.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03`, `5.3.02`, and `5.3.03`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Open Cubism's Physics Settings editor after enabling the plugin.
2. Use the group-list header select-all behavior to enable or disable all groups.
3. Close and reopen the editor; the contributed retention policy preserves the supported selection state.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.write` | `application` | Atomically enables or disables Physics Settings groups through the verified Editor transaction path. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not write plugin-owned files. State retention is delegated to the reviewed Physics Editor host service.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Requires a supported Physics Editor contribution service in the active Cubism host.
- The plugin is intentionally limited to select-all and reopen retention; it does not replace Cubism's physics editor.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Select-all is missing | Confirm the plugin is enabled before opening Physics Settings and check host-service availability. |
| Selection is not retained | Inspect the plugin log for contribution attachment failure and reopen the editor after the plugin is active. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.physics-editor`
