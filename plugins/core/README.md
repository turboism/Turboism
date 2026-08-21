---
turboismReadmeSchema: 1
pluginId: turboism.core
version: 0.1.0
kind: core
status: built-in
delivery: bundled
category: system
tags: plugin-management, settings, diagnostics
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: false
interface: none
---

# Turboism Core

> **Official Turboism plugin** · **Status: Built-in**

Provides Turboism's built-in menu, home toolbar entry, settings, logs, About window, panel, and plugin manager.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `turboism.core` |
| Category | `system` |
| Tags | plugin-management, settings, diagnostics |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | No |
| Interface | `none` |
| License | Project License |

## What it does

- Owns the non-removable Turboism home entry and top-level Settings, Plugin Management, Logs, and About commands.
- Publishes the main Turboism embedded panel and its float/dock context operations.
- Manages installation, enable/disable state, and uninstall requests for non-core plugins.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** The plugin can initialize without a Cubism project, while host UI contributions appear only when the runtime has an available UI host.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Install and enable

Turboism Core is bundled with the runtime. It is initialized automatically and cannot be separately installed, disabled, or removed.

## How to use

1. Use the Turboism home toolbar entry or the **Turboism** menu to open built-in windows.
2. Open **Plugin Management** to inspect, enable, disable, install, or schedule removal of non-core plugins.
3. Open **Settings** or **Logs** to configure the runtime and inspect diagnostics.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.action.register` | `application` | Registers the main toolbar home entry action. |
| `turboism.ui.toolbar.main.contribute` | `application` | Adds the home entry button to the main toolbar. |
| `turboism.ui.panel.contribute` | `application` | Publishes and activates the Turboism embedded panel. |
| `turboism.ui.context-menu.contribute` | `application` | Contributes the built-in panel-tab float and dock menu operations. |
| `turboism.ui.menu.contribute` | `application` | Adds Settings and Plugin Management entries to the Turboism top-level menu. |
| `turboism.ui.dialog.contribute` | `application` | Confirms plugin uninstall requests. |

## Privacy and data

### Network

The core plugin itself makes no network connections.

### Local data

Reads and writes runtime settings, plugin-management state, logs, and file-chooser history in Turboism-owned storage. Installed plugin packages are handled through the runtime's validated package-management boundary.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Built-in.
- Built in, bundled, and non-removable. Requests to disable or uninstall the core are rejected by runtime policy.
- Some plugin package changes take effect on the next discovery or reload cycle; safe mode can intentionally restrict optional behavior.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Turboism entry is missing | Inspect startup diagnostics and confirm the built-in core completed initialization. |
| A plugin change is pending | Restart or reload Turboism when Plugin Management reports a pending install or uninstall operation. |
| A window cannot open | Check safe mode, host UI availability, and the Turboism session log. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `turboism.core`
