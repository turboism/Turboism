---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.parameter-lock
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: parameter, lock, palette, safety
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: embedded
---

# Parameter Lock Plugin

> **Official Turboism plugin** · **Status: Preview**

Protects Cubism parameter values from accidental editing, with cross-session persistence and native per-row/group lock icon integration.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.parameter-lock` |
| Category | `workflow` |
| Tags | parameter, lock, palette, safety |
| Requires Cubism | Yes |
| Interface | `embedded` |
| License | Project License |

## What it does

- Intercepts parameter mutation requests before host execution and returns the current value for locked parameters. Coverage of native slider dragging and text input is tracked separately in the host acceptance record.
- Integrates native lock icons directly into each parameter row and parameter group row in the Parameters palette (matching Parts/Deformer row behavior).
- Clicking a parameter row lock icon toggles its individual lock state without altering editor selection.
- Clicking a parameter group folder row lock icon recursively affects all descendant parameters across nested sub-groups (with cycle prevention): partially locked (mixed) groups lock all children, fully locked groups unlock all children, and empty groups remain safely unlocked without false locking.
- Single-parameter lock changes immediately reflect in the visual state of ancestor parameter groups (unlocked, mixed, or locked).
- Provides context-menu actions on the Parameter Tab (Lock Selected, Unlock Selected, Toggle Parameter Lock) as secondary entry points. The main toolbar toggle button has been removed in favor of independent per-row locking.
- Returns the existing value through the write hook without directly modifying the Editor history stack. Native Undo/Redo behavior still needs live-host acceptance.
- Persists locks across sessions using the model's native serialized GUID (`CModelSource.guid`).
- Cleans up state when the plugin is disabled or uninstalled, while safely retaining stored configuration files for future re-enabling.

## Requirements and compatibility

- **SDK API range:** `[0.67.0,1.0.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.0.00` through `5.3.04`, including `5.3.03`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `embedded`.
- **Plugin dependencies:** None declared.

- Unlocked rows continuously display the native ring placeholder from Parts/Deformer tabs; locked rows display the native lock, and mixed groups display the disabled lock.

- The lock occupies a separate slot beside the chain icon, preserving native name alignment and preventing long names from overlapping it. Locked names use the native theme’s disabled text color and restore their original color when unlocked.
- Locked parameters disable and gray their native drag and value editors; the lock remains clickable. In combined two-axis rows, a locked axis blocks the shared drag surface, while the other axis retains numeric input.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

Model observation and parameter interception are included in the version calculation above. Run `:runtime:parameterLockArtifactTest` to check parameter hooks and row UI transformations against each original reviewed JAR; see [version compatibility verification](../../validation/parameter-lock/VERSION-COMPATIBILITY.md).

**Host acceptance:** Native per-parameter row clicks and SDK/native-controller write blocking passed on Cubism 5.2.03 and 5.3.02. On both versions, a real flat group passed MIXED → all locked → all unlocked checks for every descendant; the isolated fixture temporarily gained a second member and its membership was restored without saving. Cross-process row-lock persistence passed on 5.3.02. Deep nested groups are covered offline; live nested groups, physical slider dragging, text keystrokes, Undo/Redo, and multi-model switching remain untested.

See [host acceptance](../../validation/parameter-lock/ROW-UI-ACCEPTANCE.md).

## Install and enable

This preview plugin is included in the release roster for the next Turboism bundle and Plugin Market publication. Build a JAR with `./gradlew :plugins:parameter-lock:jar` or install it from a release that includes it, then enable it in **Plugin Management**. Disable it there to release write interception while retaining saved locks.

## How to use

1. In the **Parameters** palette, click the lock icon on any parameter row to toggle its individual lock state.
2. Click the lock icon on any parameter folder row to recursively toggle or lock all descendant parameters in that folder.
3. Alternatively, right-click one or more parameters or folders to access **Lock Selected Parameters**, **Unlock Selected Parameters**, or **Toggle Parameter Lock**.
4. Try modifying a locked parameter using sliders or input fields: the value will not change.
5. Reopen the model in a new session to confirm that locked parameters remain protected.

## Capabilities

| Declared capability | User effect |
|---|---|
| `action.register` | Registers parameter lock, unlock, and toggle actions. |
| `ui.context-menu.contribute` | Contributes lock and unlock actions to the parameter palette context menu. |
| `ui.appearance.modify` | Overrides parameter and parameter group palette entry leading actions with native lock icons. |
| `cubism.model.read` | Reads active model parameters and native model GUID. |
| `cubism.model.observe` | Receives model lifecycle events to load and manage lock persistence. |
| `cubism.model.intercept` | Intercepts parameter mutation writes to block modifications to locked parameters. |
| `file.read` | Reads persisted parameter lock states from plugin storage. |
| `file.write` | Persists parameter lock states to plugin storage. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.action.register` | `application` | Register parameter lock, unlock, and toggle actions. |
| `turboism.ui.context-menu.contribute` | `application` | Contribute lock and unlock actions to parameter palette context menu. |
| `turboism.ui.appearance.modify` | `application` | Override parameter and parameter group palette entry leading actions with native lock icons. |
| `turboism.cubism.model.read` | `application` | Read active model parameter IDs and native model GUID for locking. |
| `turboism.cubism.model.observe` | `application` | Receive model open/create/close lifecycle callbacks to manage lock persistence. |
| `turboism.cubism.model.intercept` | `application` | Intercept parameter value writes before they reach the host to prevent modification of locked parameters. |
| `turboism.file.read` | `application` | Read persisted parameter lock states from plugin storage. |
| `turboism.file.write` | `application` | Persist parameter lock states to plugin storage. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Persists lock lists in sandboxed plugin storage as `locks/<sha256(guid)>.json`. Contains only model GUIDs and parameter ID strings.

### Telemetry

No telemetry is sent by this plugin.

## Status and limitations

- **Status:** Preview.
- Offline unit and integration tests verify hook mutation blocking, per-row leading actions, recursive group hierarchy handling, persistence, and cross-model isolation; live row-UI acceptance and its explicit limitations are recorded in the linked validation document.
- The new per-row UI is distinct from and not equivalent to old toolbar live-host test results.
- The mutation suppression mechanism returns the parameter's current value before host write; native host history interaction depends on the installed Cubism version's exact write pipeline.
- Requires an active modeling document with an accessible native model GUID.
- Parameter writes are temporarily held at their current value while that model’s saved lock list loads.
- This module is not included in the official release bundle. Build it with `./gradlew :plugins:parameter-lock:jar` for development validation.
- Locks are keyed by the native `CModelSource.guid` stored in the `.cmo3` model file. In Live2D Cubism Editor, files created using "Save As" or direct file copies retain the original `CModelSource.guid`. Therefore, copies share the same lock configuration by host design.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Parameter value still modifies | Verify that the plugin is enabled in Plugin Management and that the parameter appears in the locked list. |
| Lock entries missing from context menu | Verify that a model is currently active and that the context-menu contribution capability is granted. |
| Row lock icons not displaying | Verify that the active model has loaded and that palette appearance modification permission is granted. |
| Locks not restored after reopening | Ensure the model was saved and that the `.cmo3` file's native GUID has not been manually altered. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.parameter-lock`
