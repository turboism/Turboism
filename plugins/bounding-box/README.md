---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.bounding-box
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: analysis
tags: bounding-box, overlay, geometry
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: false
interface: none
---

# Bounding Box Plugin

> **Official Turboism plugin** · **Status: Development**

Provides the SDK migration shell and persisted B1 settings model for the retired Bounding Box feature.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.bounding-box` |
| Category | `analysis` |
| Tags | bounding-box, overlay, geometry |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | No |
| Interface | `none` |
| License | Project License |

## What it does

- Registers a versioned configuration schema for three Bounding Box feature flags.
- Restores and validates the overlay-button, workspace-button, and mirror/shrink-suppression settings when enabled.
- Provides an internal, revision-aware binding that can update all three settings and reports conflicts or partial persistence.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Does not require Cubism and contributes no Cubism integration.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Install and enable

This is a **development-only** module, not a published store listing or release-delivery plugin. Load it only through the repository's development runtime and enable it in **Plugin Management** when validating the configuration-migration seam.

## How to use

1. Enable the plugin in a development runtime to register and restore its configuration schema.
2. Use the B1 application binding from development or test code to change the three feature settings.
3. Inspect the returned binding result for permission denial, revision conflict, unavailable runtime, or partial-persistence outcomes.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.config.plugin.read` | `application` | Restores validated B1 Bounding Box feature settings. |
| `turboism.config.plugin.write` | `application` | Persists validated B1 Bounding Box feature settings. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Stores three Boolean feature settings in plugin configuration at `bounding-box/features.cfg`: overlay buttons, workspace buttons, and mirror/shrink suppression. It does not read model data or retain user content.

### Telemetry

No telemetry is sent by this plugin.

The plugin logs lifecycle initialization. Plugin lifecycle and failure records can also appear in Turboism's session log with the plugin ID attached.

## Status and limitations

- **Status:** Development.
- This is explicitly an SDK-only migration shell: it intentionally contributes no host capability, UI, action, menu, overlay, or workspace button.
- The plugin does not expose a user-facing settings screen; settings updates are currently available only through its B1 application binding.
- Concurrent configuration revisions can be retried once; a multi-key update can report partial persistence if an intermediate write succeeds before a later failure.

## Troubleshooting

| Symptom | What to check |
|---|---|
| No Bounding Box UI appears | This is expected. The module deliberately contributes no host UI. |
| Settings cannot be restored or saved | Confirm the config read/write permissions are granted and the plugin runtime is available. |
| Update reports a revision conflict | Retry after reloading the confirmed settings; another configuration write changed the expected revision. |
| Update reports partial persistence | Reload the confirmed settings before retrying because one or more setting values may already have been written. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.bounding-box`
