---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.project-inspector
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: analysis
tags: project, inspection, diagnostics
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# Project Inspector

> **Official Turboism development module** · **Status: Development**

A Developer Preview Swing window that reads and displays the active Cubism project and workspace through the Turboism SDK.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.project-inspector` |
| Category | `analysis` |
| Tags | project, inspection, diagnostics |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Opens a localized Developer Preview window when the module is enabled in a non-headless JVM.
- Requests an asynchronous project/workspace snapshot with a two-second timeout and displays the active project name, document count, workspace display name, and refresh time.
- Provides a **Refresh** button; opening an existing window brings it forward and refreshes it.
- Shows an unavailable state rather than exposing a host-read failure in the window.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires an available Cubism project/workspace snapshot service. This is a developer-preview diagnostic module, not a released compatibility commitment.
- **Interface mode:** `swing`; a headless JVM cannot open the inspector.
- **Plugin dependencies:** None declared.

## Install and enable

This is a **development-only** module. It is not a marketplace listing, release plugin, or supported end-user installation. Use it only when a development build has deliberately included it, then enable it through the development runtime's plugin controls. Disabling or shutting it down cancels an in-flight read and disposes the window.

## How to use

1. Run a development build attached to Cubism and enable Project Inspector.
2. Inspect the window's host-read status, active project/document count, and workspace values.
3. Select **Refresh** to request a new snapshot. Closing the window hides it; re-enabling or reopening the module shows and refreshes it again.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.project.read` | Reads the active Cubism project snapshot for the preview window. |
| `cubism.workspace.read` | Reads the active workspace summary for the preview window. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.project.read` | `application` | Displays the active project and workspace in the Developer Preview inspector. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not persist project, workspace, or plugin data. The displayed snapshot remains in the window memory only while the module is active.

### Telemetry

No telemetry is sent by this module.

Lifecycle and safe-failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached. Refresh logging records availability and document-count summaries, not a saved project export.

## Status and limitations

- **Status:** Development.
- This is a diagnostic Developer Preview, not a production project browser or editor.
- It is read-only: it does not modify projects, documents, workspace layout, or Cubism state.
- A rejected, timed-out, unavailable, or superseded host read displays the localized unavailable state; stale results are ignored.
- The window is unavailable in a headless JVM and is not contributed to a Cubism menu, panel, or toolbar.

## Troubleshooting

| Symptom | What to check |
|---|---|
| No window appears | Confirm a development build enabled the module and that the JVM is not headless. |
| Values show unavailable | Confirm Cubism's project/workspace snapshot service is available, then refresh and inspect the plugin log. |
| Values do not change | Use **Refresh** after changing the active project or workspace; an earlier in-flight request may have been safely superseded. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.project-inspector`
