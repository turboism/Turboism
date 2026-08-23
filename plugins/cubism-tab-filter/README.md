---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.cubism-tab-filter
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: tab-filter, workspace
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# Cubism Tab Filter

> **Official Turboism plugin** · **Status: Preview**

Adds keyword filter boxes to Cubism's Parameter, Deformer, Scene, and Log palette tabs.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.cubism-tab-filter` |
| Category | `workflow` |
| Tags | tab-filter, workspace |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `none` |
| License | Project License |

## What it does

- Declares four localized filter-box contributions for common Cubism palettes.
- Lets the runtime own native widget attachment and row filtering rather than manipulating host widgets directly.
- Removes every contribution when the plugin is disabled.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03` and `5.3.02`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Open the Parameter, Deformer, Scene, or Log palette tab.
2. Enter a keyword in the filter box added to that tab.
3. Clear the field to restore the unfiltered row set.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.ui.toolbar.palette.contribute` | `application` | Adds keyword filter boxes to palette tab toolbars. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not persist filter text or other plugin data.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Requires the runtime palette-filter registry and a supported palette surface.
- If the registry is unavailable, the plugin logs a warning and installs no filter boxes.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Filter box is missing | Confirm the plugin is enabled and the current palette is Parameter, Deformer, Scene, or Log. |
| Rows do not change | Clear and re-enter the keyword, then inspect the log for palette-filter attachment warnings. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.cubism-tab-filter`
