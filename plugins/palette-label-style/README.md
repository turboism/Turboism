---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.palette-label-style
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: appearance
tags: palette, labels, typography
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: embedded
---

# Palette Label Style Plugin

> **Official Turboism plugin** · **Status: Preview**

Adds text and background color controls to Deformer, Part, and Parameter palette context menus.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.palette-label-style` |
| Category | `appearance` |
| Tags | palette, labels, typography |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `embedded` |
| License | Project License |

## What it does

- Provides preset, clear, and custom-color actions for supported palette entries.
- Coordinates transient palette appearance overrides with native deformer label-color writes.
- Replays per-project colors when the corresponding model or project becomes active.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03` and `5.3.02`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `embedded`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Select an entry in a supported Deformer, Part, or Parameter palette and open its context menu.
2. Choose the text-color or background-color submenu, then select a preset, clear action, or custom color.
3. Reopen the project to confirm persisted colors are replayed for the same project and object IDs.

## Capabilities

| Declared capability | User effect |
|---|---|
| `ui.context-menu.contribute` | Adds color submenus to supported palette context menus. |
| `ui.appearance.modify` | Applies transient palette text/background overrides. |
| `ui.dialog.contribute` | Opens the custom color form. |
| `cubism.model.read` | Resolves selected objects and replay targets. |
| `cubism.model.write` | Writes native deformer label colors. |
| `cubism.project.read` | Scopes persistence to the active project. |
| `file.read` | Reads persisted per-project colors. |
| `file.write` | Writes or clears persisted per-project colors. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.action.register` | `application` | Register label text/background color actions invoked from palette context menus. |
| `turboism.ui.context-menu.contribute` | `application` | Contribute text-color and background-color submenus to Deformer, Part, and Parameter palette context menus. |
| `turboism.ui.appearance.modify` | `application` | Override palette entry text and background colors and set native deformer label colors. |
| `turboism.ui.dialog.contribute` | `application` | Open the custom-color form dialog. |
| `turboism.cubism.model.write` | `application` | Write native deformer label colors for the Deformer tab background menu. |
| `turboism.cubism.model.read` | `application` | Resolve selected palette objects and replay persisted colors on model open. |
| `turboism.cubism.project.read` | `application` | Resolve the active project id for per-project label color persistence. |
| `turboism.file.read` | `application` | Read persisted per-project label colors for replay. |
| `turboism.file.write` | `application` | Persist per-project label text/background colors after each apply or clear. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Atomically stores per-project color records in plugin data as `palette-label-style/colors-<projectId>.properties`. Records contain project/object identifiers, palette family, color target, and `#RRGGBB` values.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Requires an active project/model and supported palette context-menu and appearance services.
- Invalid persisted records are ignored. Native deformer colors and transient palette overrides use separate host paths and may have different availability.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Color submenu is missing | Use a supported palette entry and confirm context-menu and appearance capabilities are available. |
| Color is not restored | Confirm the same project and object ID are active and inspect storage/read diagnostics. |
| Custom color is rejected | Enter a valid six-digit RGB color through the provided dialog. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.palette-label-style`
