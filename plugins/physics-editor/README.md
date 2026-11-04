---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.physics-editor
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: physics, editing, simulation
turboismApi: "[0.68.1,1.0.0)"
requiresCubism: true
interface: swing
---

# Physics Settings Enhancer

> **Official Turboism plugin** · **Status: Preview**

Adds select-all behavior and reopen retention to Cubism's native Physics / Scene Blending Settings group list.

## GIF recording

Use the red **Record** icon in the transparent, icon-only toolbar above the right preview (1–50 FPS). Hover over an icon to see its action. It becomes **Pause** during recording; press it again to resume. **Stop** opens export settings, and **Discard** deletes the recording without exporting. There is no frame-count input. Default registered shortcuts: **F7** record/pause/resume, **Shift+F7** pause, **F8** stop/export, **F9** discard. They can be changed in the shortcut settings and only operate on the focused Physics Settings preview. Paused time adds no frames. Recording reads the model framebuffer, excluding the settings and toolbar. When recording finishes, the export dialog provides percentage scaling, pixel width/height, and an aspect-ratio lock. **Optimize to target size** is checked by default with a **20 MB** limit (1 MB = 1,000,000 bytes). Optimization may reduce colors and dimensions while preserving all frames and playback timing. Uncheck it to export at the selected dimensions without a size target.

Confirm export settings, then choose a save location. The last successful GIF export directory is remembered across sessions, independently of project saves. Export failures preserve the recording so you can adjust settings and retry. Cancellation, closing the preview, or disabling the plugin cleans up temporary recordings.

GIF export requires **Gifsicle** installed locally: make `gifsicle` available on `PATH`, enter its executable path in the export dialog, or set `-Dturboism.gifsicle.path=<path>`. No executable is downloaded automatically. Keep the preview open and its size unchanged while recording.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.physics-editor` |
| Category | `modeling` |
| Tags | physics, editing, simulation |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- **Solves native workflow bottlenecks:** In complex Live2D models with dozens of physics groups (e.g. hair strands, clothing folds, ribbons, bust sway), isolating or testing specific groups in Cubism's native Physics Settings requires clicking checkboxes one by one. Native Cubism lacks a master select-all toggle on the group table header, and reopening the dialog during an editing session resets temporary test configurations.
- **Table header select-all checkbox:** Contributes a native-styled tri-state checkbox directly to the header (`JTableHeader`) of the Physics calculation group table.
- **Aggregate state reflection:** The header checkbox dynamically reflects row states: checked (all groups enabled), unchecked (all groups disabled), or indeterminate / dash (partially enabled).
- **One-click bulk toggle:** Clicking the header checkbox toggles all physics groups simultaneously (if mixed or all disabled, enables all; if all enabled, disables all).
- **Atomic host transactions:** Executes batch changes through Cubism's verified transaction path (`checkpoint` / `commit` / `rollback`), ensuring all rows update in a single atomic operation that rolls back safely if an error occurs.
- **Selection preservation:** Preserves the active table row selection so inspecting group properties remains uninterrupted.
- **In-session reopen retention:** Remembers the enabled/disabled state of every physics group across closing and reopening the Physics Settings dialog during the active model editing session.
- **Safe lifecycle teardown:** Disabling or hot-reloading the plugin uninstalls table listeners, restores the native header renderer, and clears in-memory retention without leaving orphan hooks or altering project files.

## Requirements and compatibility

- **SDK API range:** `[0.68.1,1.0.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.0.00` through `5.3.04`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.
- **Architecture notice:** Operates strictly through native Swing table hooks on the host Physics Settings panel; it does not replace or modify Cubism's physics calculation pipeline or `.cmo3` / `.moc3` data structures.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

This official plugin is bundled with Turboism and is available through **Plugin Market**. Enable, disable or uninstall the installed plugin in **Plugin Management**.

## How to use

1. Ensure the plugin is enabled in **Plugin Management**.
2. Open Cubism's native physics window via the top menu: **Modeling** → **Open Physics/Scene Blending Settings...**.
3. In the left panel's **Physics calculation group** table, locate the column header above the group enable checkboxes.
4. Click the table header checkbox to toggle all physics groups:
   - If groups are partially enabled or all disabled, clicking enables all groups.
   - If all groups are currently enabled, clicking disables all groups.
5. Individual row changes continue to work normally, and the header checkbox updates immediately to reflect the aggregate state.
6. Close and reopen the dialog during your session; the contributed retention policy preserves your enabled and disabled group states.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.action.register` | `application` | Registers GIF recording, pause, stop and discard actions with configurable shortcuts. |
| `turboism.cubism.model.write` | `application` | Atomically enables or disables Physics Settings groups through the verified Editor transaction path. |

## Privacy and data

### Network

Makes no network connections.

### Local data

GIF recording writes temporary files and the selected output GIF, and remembers the last successful export directory in local preferences. Group state retention stays in memory during the active model session; project files are unchanged.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Requires a supported Physics Editor contribution service in the active Cubism host.
- **Workflow-only scope:** The plugin provides group table select-all, in-session reopen retention and preview GIF recording. It is not an alternative physics simulator and does not alter pendulum physical calculations, reaction times, gravity, wind forces, or motion sync parameters.
- **Session-scoped retention:** Retention operates within the active model session memory and does not serialize custom tags into the `.cmo3` model file.
- Disabling the plugin while the dialog is open safely restores the native header renderer without affecting current model state.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Select-all checkbox is missing | Confirm the plugin is enabled before opening Physics Settings. If opened prior to enabling, close and reopen the dialog. Check logs for host-service availability. |
| Clicking header does nothing | Verify the model is not locked in another modal dialog. Inspect the session log for transaction rollback or editor exceptions. |
| Selection is not retained on reopen | Retention is scoped to the active model session in memory. Verify the model was not closed and reopened, and that the plugin was not disabled between dialog openings. |
| Header displays a minus/dash | The indeterminate state indicates that some groups are enabled and others are disabled. Clicking the checkbox will enable all groups. |

## Changelog

### 0.1.0

- Initial release.

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.physics-editor`
