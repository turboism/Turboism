---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.context-menu
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: workflow
tags: context-menu, productivity
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# Context Menu Plugin

> **Official Turboism plugin** · **Status: Development**

Provides an SDK migration shell and lifecycle inventory for the legacy Context Menu feature.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.context-menu` |
| Category | `workflow` |
| Tags | context-menu, productivity |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Maintains an internal inventory of four planned context-menu dispatch contributions: Parts, Deformer, Parameter, and workspace object.
- Provides an idempotent enabled/disabled/shutdown lifecycle for that inventory.
- Logs initialization through the SDK plugin context.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Declared as required, but the current migration shell does not access Cubism services.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

## Install and enable

This is a **development-only** module, not a published store listing or release-delivery plugin. Load it only through the repository's development runtime and enable it in **Plugin Management** while validating the legacy feature's lifecycle model.

## How to use

1. Enable the plugin in a development runtime.
2. Use the B1 application lifecycle from development or test code to inspect its planned contribution inventory.
3. Disable it to return to the disabled state, or shut it down to make subsequent enable/disable calls reject safely.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

The manifest declares no permissions.

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not read, write, or persist plugin data. The contribution inventory and lifecycle state remain in memory.

### Telemetry

No telemetry is sent by this plugin.

The plugin logs lifecycle initialization. Plugin lifecycle and failure records can also appear in Turboism's session log with the plugin ID attached.

## Status and limitations

- **Status:** Development.
- This is explicitly an SDK-only migration shell: it intentionally contributes no actual host capability or context-menu UI.
- The four inventory entries are metadata only; they do not register handlers, actions, or visible menu items.
- Once shut down, its internal lifecycle rejects later enable and disable operations.

## Troubleshooting

| Symptom | What to check |
|---|---|
| No context-menu entries appear | This is expected. The current module does not contribute host UI. |
| Inventory is not visible to users | The inventory is an internal B1 application API intended for development and tests. |
| Enable or disable is rejected | Check whether the lifecycle was already shut down; shutdown is terminal. |
| A repeated lifecycle call changes nothing | Repeated enable, disable, or shutdown calls are intentionally idempotent. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.context-menu`
