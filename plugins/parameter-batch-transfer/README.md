---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.parameter-batch-transfer
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: parameter, batch-edit, transfer
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# Parameter Batch Transfer

> **Official Turboism plugin** · **Status: Preview**

Transfers parameter bindings from one selected ArtMesh or Deformer to multiple target parameters.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.parameter-batch-transfer` |
| Category | `modeling` |
| Tags | parameter, batch-edit, transfer |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Adds batch-transfer entries to Deformer, Part, and workspace-object context menus.
- Builds a modal target-selection session from one selected ArtMesh, Warp Deformer, or Rotation Deformer.
- Applies confirmed transfers with optional inversion and reports each outcome.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03`, `5.3.02`, and `5.3.03`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Select exactly one supported ArtMesh or Deformer that already has parameter bindings.
2. Open its context menu and choose the batch-transfer command.
3. Select destination parameters, choose inversion where needed, and confirm the dialog to apply the transfers.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.parameter.read` | Reads source bindings and available target parameters. |
| `cubism.parameter.write` | Writes confirmed binding changes. |
| `cubism.parameter.bindings.transfer` | Executes typed binding transfers with optional inversion. |
| `ui.context-menu.contribute` | Adds the batch-transfer launcher to supported contexts. |
| `ui.status.notify` | Reports precondition and transfer outcomes. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Read the selected object's parameter bindings and the model parameter list for the transfer session. |
| `turboism.cubism.model.write` | `application` | Apply confirmed parameter-binding transfers through the typed Editor authoring API. |
| `turboism.action.register` | `application` | Register the batch-transfer open action behind the context-menu entries. |
| `turboism.ui.context-menu.contribute` | `application` | Expose the batch-transfer entry in Deformer tab, Part tab, and workspace object context menus. |
| `turboism.ui.status.notify` | `application` | Notify transfer results and precondition skips. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not persist plugin data. It reads the selected object's binding state and writes confirmed target bindings.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Requires exactly one supported source object and at least one existing source binding.
- Confirmed target rows are transferred individually; the plugin does not claim one combined undo group.
- The modal Swing dialog is unavailable in a headless JVM.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Context command is missing | Select one supported object in the Deformer, Part, or workspace-object context. |
| Dialog has no source bindings | Choose an object with existing parameter bindings. |
| A transfer is skipped | Check destination validity, selection state, and the status notification for the rejected row. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.parameter-batch-transfer`
