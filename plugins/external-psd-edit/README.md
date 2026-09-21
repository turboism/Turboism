---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.external-psd-edit
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: modeling
tags: psd, texture, external-edit
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: embedded
---

# External PSD Edit Plugin

> **Official Turboism plugin** · **Status: Development**

Right-click ArtMeshes to edit their complete raw PSD in the operating system's default PSD application; each stable save applies a Cubism native explicit-target raw-image replacement.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.external-psd-edit` |
| Category | `modeling` |
| Tags | psd, texture, external-edit |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `embedded` |
| License | Project License |

## What it does

- Adds an "Edit Externally (PSD)" entry to ArtMesh-capable context menus (Parts, Deformer, and workspace object views).
- Resolves each selected ArtMesh to its current raw image through the typed texture-relation graph and opens one editing session per distinct raw image; duplicated selections and already-open raw images are deduplicated.
- Exports the complete raw PSD through Cubism's native layered-image export into a runtime-owned temporary file, then launches the operating system's default PSD application.
- Watches the temporary file and applies Cubism's native explicit-target raw-image replacement for every stable save.
- While a session is live, performs a bounded read-only relation refresh so Undo/Redo raw-image
  changes are observed without queuing a replacement or replaying a save.
- If that automatic Undo/Redo tracking is unavailable, the target is checked again before the
  next explicit save.
- Reports session, import, and pause status as non-blocking notifications; an uncertain native outcome pauses the session instead of guessing.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Required. Export and replacement use Cubism native layered-image and raw-image replacement behavior; only the reviewed Cubism 5.3.02 record admits this feature today. Other Cubism versions require their own reviewed verification record before the entry resolves.
- **Interface mode:** `embedded`.
- **Plugin dependencies:** None declared.
- The temporary PSD is a rebuilt document, not a byte copy of the source asset.

## Install and enable

This is a **development-only** module, not a published store listing or release-delivery plugin. Load it only through the repository's development runtime and enable it in **Plugin Management** while validating the feature.

## How to use

1. Open a model document and select one or more ArtMeshes.
2. Right-click and choose the external PSD edit entry.
3. Confirm once when the selection resolves to more than one new raw image.
4. Edit and save the file in the external application; each stable save is imported into the originating raw image.
5. Disable the plugin or close the document to end its sessions; temporary files and the external application are left running/untouched.

## Capabilities

The manifest declares `cubism.psd.external-edit` for the ArtMesh-to-PSD editing pipeline, `ui.context-menu.contribute` for the menu entry, `ui.status.notify` for session notifications, and `ui.dialog.contribute` for the multi-file confirmation.

## Permissions

- `turboism.cubism.model.read` — read texture relations to resolve ArtMeshes to raw images and to validate session bindings.
- `turboism.cubism.model.write` — apply the native explicit-target raw-image replacement on each stable save.
- `turboism.file.read` — read the runtime-issued temporary PSD revision for replacement.
- `turboism.file.write` — export the complete raw PSD into the runtime-owned temporary file.
- `turboism.process.run` — open the temporary PSD with the operating system's default PSD application.
- `turboism.ui.status.notify` — surface session, import, and pause/failure notifications.
- `turboism.ui.dialog.contribute` — ask once before opening multiple temporary PSDs.
- `turboism.action.register` — register the action invoked from the context menus.
- `turboism.ui.context-menu.contribute` — contribute the menu entry to ArtMesh-capable context menus.
- `turboism.ui.context-source.read` — read the captured context-menu selection that triggered editing.

## Privacy and data

### Network

Makes no network connections.

### Local data

Writes runtime-owned temporary PSD files under the system temporary area and reads them back for replacement. Temporary files are retained after sessions stop; the plugin never deletes them and never closes the external application. No plugin data is persisted between sessions.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log with the plugin ID attached.

## Status and limitations

- **Status:** Development; host evidence for the full edit/save/replace loop is still being gathered.
- The export is Cubism's native layered-image rebuild. PSD-only constructs that Cubism does not model are not guaranteed to round-trip.
- Only saves to the issued temporary file are imported. "Save As" to another path, closing without saving, or editing a copy does not trigger replacement.
- A raw image shared by several model images or ArtMeshes is edited once; the replacement affects every dependent object.
- If a save task is rejected or canceled before native replacement starts, or the current raw-image
  relation cannot be determined, the session pauses without claiming a replacement outcome.
- An uncertain or partially observed native outcome pauses the session rather than risking an unverified mutation.
- A native replacement that started but cannot be verified is reported as an uncertain/partial
  outcome; it is not treated like a save that never started.
- A paused session can be opened again from its menu entry, but that only reopens its existing
  file; it does not resume automatic imports. Disable and enable the plugin, then invoke the
  entry again, to create a fresh session. Uncertain native outcomes are not retried automatically.
- The plugin never deletes the temporary PSD and never changes system file associations.
- The test-only validation probes used for host evidence are not packaged into release artifacts.

## Troubleshooting

| Symptom | What to check |
|---|---|
| The menu entry does nothing | The selection must contain ArtMeshes, and the captured document binding must still match the active model. |
| Replacement stopped after a warning | The session paused on an uncertain native outcome; invoking the entry only reopens the existing file. Disable and enable the plugin before creating a fresh session. |
| Edits never import | Confirm the external application saved to the same temporary file; saving under a new name is not watched. |
| A second right-click shows "already open" | That raw image already has a live session; the existing editor window remains the active one. |
| Nothing opened after confirming | Export can fail closed when permissions are revoked or the native export reports the raw image unavailable. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.external-psd-edit`
