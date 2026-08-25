---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.psd-clip-mask-import
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: workflow
tags: psd, import, clip-mask
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: embedded
---

# PSD Clip Mask Import Plugin

> **Official Turboism development module** · **Status: Development**

Imports ordered PSD clipping relationships into ArtMesh clip-mask assignments after an explicit preview and overwrite confirmation.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.psd-clip-mask-import` |
| Category | `workflow` |
| Tags | psd, import, clip-mask |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `embedded` |
| License | Project License |

## What it does

- Adds a **PSD Clip Mask Import** section with an **Import Clip Masks from PSD** button to the Turboism panel.
- Reads ordered clipping relationships from the active Cubism model's PSD document and resolves the involved ArtMeshes against the active model.
- Previews each proposed target, ordered source ArtMeshes, PSD `documentId/layerId` references, overwrite conflicts, and skipped relationships before any change.
- Applies confirmed changes as one conditional clip-mask replacement batch; the SDK host contract provides all-or-nothing application and one Undo/Redo step.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires the active-model PSD relationship, clip-mask read, ordered replacement, transaction/Undo, embedded-panel, dialog, and status-notification services. This is a development-only module, not a released compatibility commitment.
- **Interface mode:** `embedded`.
- **Plugin dependencies:** None declared.

## Install and enable

This is a **development-only** module. It is not a marketplace listing, release plugin, or supported end-user installation. Use it only from a development build deliberately configured with the required Cubism services. Enable it through the development runtime's plugin controls; its action and panel section are registered in the runtime disposable scope.

## How to use

1. Open the target model and ensure its active PSD document contains clipping relationships that resolve to ArtMeshes in the model.
2. In the Turboism panel, open **PSD Clip Mask Import** and select **Import Clip Masks from PSD**.
3. Review proposed targets, ordered masks, source layers, skips, and every overwrite conflict.
4. Cancel to make no change, or explicitly confirm to replace the shown clip-mask assignments.
5. Use Cubism Undo/Redo to reverse or restore the single confirmed batch.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.psd.layer-relationship.read` | Reads ordered PSD clipping relationships from the active document. |
| `cubism.clipmask.read` | Reads current ArtMesh clip-mask lists and inversion state for planning and conflicts. |
| `ui.dialog.contribute` | Shows the preview and overwrite-confirmation dialog. |
| `ui.status.notify` | Reports imported, skipped, no-write, and safe-failure outcomes. |
| `cubism.clipmask.replace-ordered-sources` | Replaces confirmed ArtMesh clip-mask source lists in their planned order. |
| `cubism.transaction.real-write-undo` | Applies the confirmed batch through Cubism's real write/Undo path. |
| `ui.embedded-panel.contribute` | Adds the import section to the Turboism panel. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Reads ordered PSD layer relationships and resolves them against existing model Drawable identities. |
| `turboism.cubism.model.write` | `application` | Commits confirmed clip-mask assignments atomically through one conditional model batch. |
| `turboism.action.register` | `application` | Registers the PSD clip-mask import action. |
| `turboism.ui.panel.contribute` | `application` | Contributes the PSD clip-mask import button to the Turboism UI. |
| `turboism.ui.dialog.contribute` | `application` | Shows the import preview dialog that lists targets, ordered masks, conflicts, and skips before any write. |
| `turboism.ui.status.notify` | `application` | Reports imported, skipped, failure, and fail-closed diagnostic counts. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not request filesystem or plugin-storage access and does not persist plugin data. It reads active-document PSD relationship metadata, ArtMesh IDs, current ordered clip-mask lists, and inversion state. It writes only explicitly confirmed ArtMesh clip-mask assignments through one conditional batch. The preview displays ArtMesh IDs and PSD source references; cancellation performs zero writes.

### Telemetry

No telemetry is sent by this module.

Lifecycle and safe-failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached. User-visible failure notifications use localized safe messages rather than host exception details.

## Status and limitations

- **Status:** Development.
- This imports clipping relationships only; it does not import PSD image files or textures, create ArtMeshes, repair layer bindings, expand the canvas, or provide general PSD reimport.
- Confirmation is required for every replacement. A current nonempty list or inverted state is shown as an overwrite conflict; replacement sets the planned result to non-inverted.
- Relationships with unresolved targets, missing base/mask ArtMeshes, no usable masks, self-only masks, duplicate identities, cross-document ambiguity, or an already matching desired state are skipped or fail closed rather than written.
- Before commit, the module reacquires the model and verifies that document/model identity and the generated plan still match the preview. Changes between preview and confirmation produce zero writes.
- The host must supply the declared Cubism/UI services; the module is not usable outside Cubism.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Import section or button is missing | Confirm the development module is enabled and the embedded-panel/action services are available. |
| Preview has only skipped rows | Confirm the active PSD clipping bases, masks, and targets all resolve to unique ArtMeshes in the active model. |
| Confirmation makes no change | Review the preview for already-matching rows, cancellation, ambiguity, or a document/model/plan change that caused a safe failure. |
| Import fails safely | Check the localized status summary and the plugin log; confirm the required PSD, clip-mask, dialog, write/Undo, and status services are present. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.psd-clip-mask-import`
