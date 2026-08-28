---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.perf-stats
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: performance
tags: metrics, diagnostics, fps
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# Performance Statistics

> **Official Turboism plugin** · **Status: Preview**

Displays live Cubism CPU, FPS, JVM memory, and garbage-collection statistics.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.perf-stats` |
| Category | `performance` |
| Tags | metrics, diagnostics, fps |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Adds an embedded Performance panel and a standalone Performance Monitor window.
- Samples a shared runtime statistics source once per second and retains a bounded 120-point chart history.
- Shows a compact CPU percentage in the Cubism status area.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03`, `5.3.02`, and `5.3.03`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Open the embedded Performance panel for always-available compact charts.
2. Use **Turboism → Performance Monitor** to open the standalone window.
3. Observe viewport FPS, CPU, heap/non-heap memory, and garbage-collection pause trends.

## Capabilities

| Declared capability | User effect |
|---|---|
| `performance.stats.read` | Reads local Cubism process and JVM performance samples. |
| `ui.embedded-panel.contribute` | Adds the live chart panel to the Cubism UI. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.performance.stats.read` | `application` | Reads Cubism process CPU, FPS, and JVM memory statistics for live charts. |
| `turboism.ui.status.notify` | `application` | Shows the resident compact CPU percentage label in the Cubism status bar. |
| `turboism.ui.panel.contribute` | `application` | Adds the embedded performance chart panel to the Cubism palette area. |
| `turboism.action.register` | `application` | Registers the Performance Monitor window action. |
| `turboism.ui.menu.contribute` | `application` | Adds the Performance Monitor entry to the Turboism top-level menu. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not persist samples. Metrics and chart history remain in memory and are cleared with the plugin lifecycle.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Requires the runtime performance statistics service and supported panel/status UI integration.
- Charts are diagnostic samples, not a benchmark guarantee; unsupported metrics may appear unavailable.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Panel or menu is missing | Confirm the plugin is enabled and panel/menu contribution services are available. |
| Charts show no data | Wait for the one-second sampler and check performance-probe availability in the log. |
| CPU label disappears | Check status-notification availability and whether the plugin was disabled or reloaded. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.perf-stats`
