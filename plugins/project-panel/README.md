---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.project-panel
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: workflow
tags: project, navigation, panel
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: false
interface: none
---

# Project Panel Plugin

> **Official Turboism development module** · **Status: Development**

An SDK-only migration shell for Project Panel lifecycle-state work. It currently contributes no Project Panel UI or Cubism host feature.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.project-panel` |
| Category | `workflow` |
| Tags | project, navigation, panel |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | No |
| Interface | `none` |
| License | Project License |

## What it does

- Registers a versioned plugin configuration schema for internal Project Panel lifecycle state.
- Hydrates the last recorded phase and bounded `opening`, `opened`, `closing`, and `closed` counters when enabled.
- Provides internal state-reduction and conditional-persistence support for the migration work.
- Deliberately registers no host action, panel, dialog, menu item, toolbar control, or Cubism operation.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Not required; this module has no Cubism integration.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Install and enable

This is a **development-only** migration module. It is not a marketplace listing, release plugin, or end-user Project Panel. It may be included in a development runtime to exercise its internal lifecycle/configuration boundary, but enabling it does not display a panel or add a command.

## How to use

1. Include the module only in a development runtime that is testing Project Panel migration state.
2. Enable it to register and hydrate its internal configuration state.
3. Use the module's automated tests or an internal consumer to exercise lifecycle transitions; there is no user-facing control or panel to open.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.config.plugin.read` | `application` | Restores validated B1 project lifecycle counters. |
| `turboism.config.plugin.write` | `application` | Persists validated B1 project lifecycle counters. |

## Privacy and data

### Network

Makes no network connections.

### Local data

The internal configuration schema uses `project-panel/state.cfg` and defines a last phase plus four bounded counters (each from 0 to 1,000,000). The entrypoint currently hydrates this state but does not itself generate lifecycle transitions or write counter updates.

### Telemetry

No telemetry is sent by this module.

Lifecycle records can appear in Turboism's session log with the plugin ID attached.

## Status and limitations

- **Status:** Development.
- This is a migration shell, not a working Project Panel, project navigator, or workspace UI.
- It has no Cubism dependency and no host UI contribution.
- Its internal state machine accepts only defined lifecycle-phase transitions and rejects invalid, duplicate, disabled, stale-revision, invalid-value, or permission-denied operations.
- Configuration persistence is conditional on revision; a partial write is reported as partial persistence rather than silently treated as success.

## Troubleshooting

| Symptom | What to check |
|---|---|
| No Project Panel appears | Expected: the module intentionally declares `interface: none` and contributes no UI. |
| No command is available | Expected: it registers no actions, menus, dialogs, or toolbar entries. |
| Internal state is unavailable | Confirm the development runtime granted the declared plugin-config read/write permissions and that the config registry is available. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.project-panel`
