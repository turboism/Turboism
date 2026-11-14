---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.project-work-time
version: 0.68.1
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: time, project, status-bar
turboismApi: "[0.70.0,)"
requiresCubism: true
interface: swing
---

# Project Work Time

> **Official Turboism plugin** · **Status: Preview**

Shows Editor session time and the active project's cumulative work time in Cubism's bottom status bar.

| Detail | Value |
|---|---|
| Version | `0.68.1` |
| Plugin ID | `dev.turboism.plugin.project-work-time` |
| Category | `workflow` |
| Tags | time, project, status-bar |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Adds two compact resident metrics to Cubism's bottom status bar: total Editor foreground time since this process started and the active project's cumulative foreground time.
- Consumes the runtime-owned work-time tracker shared by every plugin instead of running a second timer.
- Accounts for the active project tab only while the Cubism process owns the desktop foreground, and pauses when another application is focused.
- Updates once per second and removes its status labels when disabled.

## Requirements and compatibility

- **SDK API range:** `[0.70.0,)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.0.00` through `5.3.04`; the status bar requires a host surface that supports compact status metrics.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

This official plugin is bundled with Turboism and is available through **Plugin Market**. Enable, disable or uninstall the installed plugin in **Plugin Management**.

## How to use

1. Enable the plugin and open one or more Cubism project tabs.
2. Read the two metrics at the bottom of the Cubism window:
   - **Session** is the total time Cubism has been foreground since this Editor process started.
   - **Project** is the active project's cumulative foreground time across Editor sessions.
3. Switch project tabs, or switch to another application, to pause one accounting segment and start the next.
4. Disable the plugin to remove both metrics. Recorded totals remain in the runtime work-time store and are shown again when the plugin is re-enabled.

## Capabilities

- `cubism.project.read` identifies the active project tab and supplies its display name.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.project.read` | `application` | Reads the runtime-owned project work-time statistics for the active project. |
| `turboism.ui.status.notify` | `application` | Shows session and active-project work time in the Cubism status bar. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Reads the runtime work-time tracker's persisted totals. The tracker stores project display names and cumulative foreground durations in Turboism's runtime state directory; the plugin itself writes no files.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Foreground detection is process-level: a Cubism-owned dialog counts as foreground, and a plugin-owned Swing window can count while it is active.
- Time is sampled once per second, so a switch can shift at most about one second between project totals.
- A project is identified by its display name and reported project directory. Two open projects with the same name and no distinguishing directory share one total.
- The runtime tracker caps a single sample at three intervals, so sleep or a frozen runtime cannot inflate a project by hours.
- If the status-bar or work-time service is unavailable, the plugin logs a warning and shows no labels rather than failing host startup.

## Troubleshooting

| Symptom | What to check |
|---|---|
| No labels appear | Confirm the plugin is enabled, the host supports compact status metrics, and the plugin log does not report an unavailable service. |
| Time does not advance | Make sure Cubism owns the desktop foreground and a project tab is active. |
| A project is missing from a consumer view | The runtime tracker only records a project after it has been observed in the foreground. |
| Totals look lower than expected | The first sample establishes the baseline; the first foreground second is not retroactively counted. |

## Changelog

### 0.68.1

- Initial release.

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.project-work-time`
