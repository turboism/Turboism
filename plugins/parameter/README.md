---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.parameter
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: modeling
tags: parameter, binding, editing
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: embedded
---

# Parameter Tools Plugin

> **Official Turboism plugin** · **Status: Development**

Provides parameter CSV import/export plus typed parameter-binding inversion and transfer workflows.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.parameter` |
| Category | `modeling` |
| Tags | parameter, binding, editing |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `embedded` |
| License | Project License |

## What it does

- Registers Export Parameters CSV, Import Parameters CSV, Invert Bindings, and Transfer Bindings actions.
- Exposes inversion and transfer through the Parameter Tools menu; transfer is also available in parameter, deformer, part, and workspace-object context menus.
- Validates CSV rows against active-model parameter IDs and ranges before writing values, then applies typed binding inversion or transfer plans.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires an active supported Cubism model for parameter and binding workflows.
- **Interface mode:** `embedded`.
- **Plugin dependencies:** None declared.

## Install and enable

This is a **development-only** module, not a published store listing or release-delivery plugin. Load it through the repository's development runtime and enable it in **Plugin Management** when validating parameter import/export and binding integrations.

## How to use

1. Open an active Cubism model and invoke a Parameter Tools action.
2. Use **Import Parameters CSV** to choose a CSV with `id,value` rows; all rows are validated before writes begin.
3. Use **Export Parameters CSV** to generate the active model's parameter table.
4. Select supported parameter and object targets for binding inversion or transfer. Context-menu transfer requests source/destination and inversion confirmations.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.parameter.read` | Reads parameters, selection, and binding state. |
| `cubism.parameter.write` | Applies validated parameter values and binding changes. |
| `cubism.parameter.bindings.invert` | Inverts bindings through the typed model API. |
| `cubism.parameter.bindings.transfer` | Transfers bindings through a typed transfer plan. |
| `ui.context-menu.contribute` | Adds supported context-menu workflows. |
| `ui.file-chooser.request` | Requests a CSV file for import. |
| `ui.status.notify` | Reports import/export results and fallbacks. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Reads parameter snapshots, selection, and binding state. |
| `turboism.cubism.model.write` | `application` | Applies parameter values and confirmed binding changes. |
| `turboism.ui.file-chooser.request` | `application` | Chooses CSV files for import. |
| `turboism.ui.status.notify` | `application` | Reports import/export results and fallbacks. |
| `turboism.action.register` | `application` | Registers CSV and binding actions. |
| `turboism.ui.menu.contribute` | `application` | Adds Parameter Tools menu entries. |
| `turboism.ui.context-menu.contribute` | `application` | Adds supported context-menu workflows. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Reads selected CSV content supplied by the host and active-model parameter data. Exported CSV is kept only in memory; the plugin has no settings, cache, database, or other persistent storage.

### Telemetry

No telemetry is sent by this plugin.

The plugin logs lifecycle messages and safe setter-failure diagnostics. Plugin lifecycle and failure records can also appear in Turboism's session log and host log with the plugin ID attached.

## Status and limitations

- **Status:** Development.
- Export currently produces CSV only in the service's in-memory result; it has no save dialog, file writer, clipboard flow, or other user-facing delivery path.
- The default production CSV content provider fails closed, so import succeeds only when a content provider is explicitly injected.
- The live parser accepts blank lines and comments, allows an optional `id,value` header, rejects duplicate/non-finite values, and limits input to 1,000,000 characters and 10,000 rows; it does not support quoted or multiline fields.
- Import validates all rows before writes, but writes are not transactional. A setter failure can leave earlier values applied and may require Cubism Undo.
- Binding actions require unambiguous eligible selections; precondition failures can be reported as selection-related exceptions.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Export says no parameters are available | Confirm an active supported model with parameters is open. |
| No exported file appears | This is expected: current export retains generated CSV only in memory. |
| Import is unavailable | The default provider supplies no file content; use a runtime integration that injects a CSV content provider. |
| Import fails before changes | Check the CSV shape, duplicate IDs, finite values, row/size limits, parameter existence, and valid parameter range. |
| Import partially changes values | A setter failed after earlier writes. Use Cubism Undo as needed and inspect the status warning. |
| Transfer does nothing | Confirm source, distinct destination, and eligible ArtMesh or deformer targets; a context-menu confirmation may have been cancelled. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.parameter`
