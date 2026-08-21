---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.uitheme
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: appearance
tags: theme, colors, user-interface
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: false
interface: none
---

# UI Theme Plugin

> **Official Turboism plugin** · **Status: Preview**

Manages built-in and user theme packages and applies reviewed semantic appearance changes to Cubism.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.uitheme` |
| Category | `appearance` |
| Tags | theme, colors, user-interface |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | No |
| Interface | `none` |
| License | Project License |

## What it does

- Adds theme status, manager, built-in apply, import, export, edit, delete, and native-restore workflows.
- Validates bounded ZIP theme packages and stores user packages atomically in plugin data.
- Persists the selected theme only after a successful semantic appearance apply and restores owned appearance on disable.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** The plugin can initialize without Cubism. Applying a theme to Cubism still requires an exact reviewed Editor artifact (`5.2.03` or `5.3.02`) and the semantic appearance service.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Open **Turboism → Theme Manager** or the contributed workspace context-menu command.
2. Choose a built-in theme, create or edit a user theme, or import a validated theme ZIP.
3. Apply the selection; use the native theme option to restore Cubism's original appearance. Export or delete user packages from the manager when needed.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.action.register` | `application` | Registers theme manager, package import/export, delete, and status actions. |
| `turboism.ui.menu.contribute` | `application` | Adds theme management commands under the Turboism top-level menu. |
| `turboism.config.plugin.read` | `application` | Reads the selected theme package from plugin-owned typed configuration. |
| `turboism.config.plugin.write` | `application` | Persists the selected theme package after a successful host appearance apply. |
| `turboism.ui.context-menu.contribute` | `application` | Adds theme management context-menu items. |
| `turboism.cubism.project.read` | `application` | Reads the SDK theme status snapshot through the project-scoped Cubism read capability. |
| `turboism.ui.dialog.contribute` | `application` | Shows the unified theme selection window and bounded package workflow dialogs. |
| `turboism.ui.file-chooser.request` | `application` | Requests opaque ZIP theme package handles for import and export. |
| `turboism.file.read` | `application` | Reads bounded theme archives from plugin storage and granted import handles. |
| `turboism.file.write` | `application` | Atomically stores, deletes, and exports bounded theme archives. |
| `turboism.ui.status.notify` | `application` | Shows theme package status, import progress, and appearance apply results. |
| `turboism.ui.appearance.modify` | `application` | Applies or restores a reviewed built-in theme through the semantic appearance service. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Stores the selected theme in plugin configuration at `ui-theme/selection.cfg`. User packages are bounded ZIP archives stored under plugin data as `themes/<theme-id>.zip`; import/export uses user-approved opaque file handles. Bundled themes are read from the plugin's `themes/` resources.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Theme archives, IDs, entry counts, entry sizes, and total size are bounded and validated; invalid packages are unavailable.
- Applying Cubism appearance requires a reviewed semantic appearance service. The manager can exist without Cubism, but host appearance changes fail closed when unsupported.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Theme cannot be applied | Check whether the current host exposes the reviewed appearance service and inspect the status notification. |
| Imported package is rejected | Verify it is a bounded valid Turboism theme ZIP with a valid theme ID and supported entries. |
| Theme is missing after restart | Check `ui-theme/selection.cfg` and confirm the selected user package still exists in plugin data. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.uitheme`
