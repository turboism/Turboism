---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.psd-import
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: workflow
tags: psd, import, clip-mask
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: false
interface: none
---

# PSD Import Plugin

> **Official Turboism development module** · **Status: Development**

An SDK-only migration shell for the legacy PSD Import feature. It is not a functional PSD importer.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.psd-import` |
| Category | `workflow` |
| Tags | psd, import, clip-mask |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | No |
| Interface | `none` |
| License | Project License |

## What it does

- Maintains an internal inventory of legacy PSD migration candidates and their parameter defaults.
- Provides internal lifecycle and parameter-parsing domain behavior for migration tests.
- Deliberately does not register a host action, panel, dialog, menu, file chooser, or Cubism operation.
- Does not import PSD files, repair layer bindings, expand a canvas, create ArtMeshes, or modify a model.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Not required; this module does not access Cubism.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Install and enable

This is a **development-only** migration shell. It is not a marketplace listing, release plugin, or supported end-user installation. Do not install it expecting PSD import functionality. The separate PSD Clip Mask Import module is the current development-only workflow for importing confirmed PSD clipping relationships.

## How to use

1. Use this module only while developing or testing legacy PSD Import migration boundaries.
2. Enable it to exercise the internal lifecycle shell in automated or internal tests.
3. Do not expect a user interface, import command, file picker, model change, or visible output.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

The manifest declares no permissions.

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not read PSD files, access the filesystem through a plugin permission, persist plugin data, or modify Cubism data. Its legacy-action inventory and parameter parsing are in-memory internal behavior only.

### Telemetry

No telemetry is sent by this module.

Lifecycle records can appear in Turboism's session log with the plugin ID attached.

## Status and limitations

- **Status:** Development.
- This is a non-functional compatibility placeholder, not a general PSD importer.
- It declares no permissions, capabilities, host UI, file import, network activity, model access, or persistent storage.
- Internal descriptors for clip-mask import, layer-binding repair, and canvas expansion are migration inventory only; none is registered or implemented by this module.
- Its lifecycle is internal state bookkeeping and reaches a terminal shutdown state; it does not expose an end-user workflow.

## Troubleshooting

| Symptom | What to check |
|---|---|
| No PSD import command appears | Expected: the module intentionally registers no command or UI. |
| A PSD file cannot be selected | Expected: it provides no file chooser or file import implementation. |
| No model changes occur | Expected: it has no Cubism model access or write capability. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.psd-import`
