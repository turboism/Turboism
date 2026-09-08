---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.historypanel
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: history, navigation, floating-window
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: false
interface: embedded
---

# History Panel Plugin

> **Official Turboism plugin** · **Status: Preview**

Projects the active document's native Undo history into a floating semantic timeline with trusted targets, before/after values, origin, bounded native decoding, and stable snapshot-bound navigation.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.historypanel` |
| Category | `workflow` |
| Tags | history, navigation, floating-window |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | No |
| Interface | `embedded` |
| License | Project License |

## What it does

- Adds a localized History button to the right-side vertical Cubism tool strip.
- Renders every reachable Undo entry as `FULL`, `PARTIAL`, or `LABEL_ONLY` semantic detail. Turboism-captured metadata is authoritative; allowlisted native decoders may supplement host entries; unknown operations stay label-only.
- Shows a semantic description without repeating the raw host label; label-only entries keep the host label once. Each group shows only its summary, never its first change or a `+N` suffix. Rows retain affected target count, detail level, and known Turboism attribution; host-unattributed origin is omitted and groups cannot expand.
- Refreshes when history identity or semantic content changes and navigates only by stable entry ID against the unchanged generation, revision, document/manager binding, and entry sequence.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Does not require Cubism for initialization. The pane needs an available native history service and host UI integration to display history entries.
- **Interface mode:** `embedded`.
- **Plugin dependencies:** None declared.

## Install and enable

This plugin is included in Turboism Full releases. Install a Full package, then enable or disable it in **Plugin Management**. Its native history actions remain subject to the exact Cubism version and active-document availability shown by the panel.

## How to use

1. Install a Turboism Full package and enable the plugin in **Plugin Management**.
2. Click the History button on the right-side vertical tool strip to open the floating pane.
3. Review the current native Undo history. Toggle an applied entry to undo back to it, or an undone entry to redo forward past it.
4. Click the History button again to close the pane.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.editor-history.read` | Reads the active document's native Undo history snapshot. |
| `cubism.editor-history.semantic-read` | Optionally decodes independently authorized native Undo families with strict depth/node/string budgets. |
| `cubism.editor-history.move` | Requests movement through native Undo history entries. |
| `ui.embedded-panel.contribute` | Contributes the History panel. |
| `ui.status.notify` | Declares history-status notification support. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Reads the active document's native Undo history snapshot. |
| `turboism.cubism.model.write` | `application` | Moves the native Undo cursor from History entry actions. |
| `turboism.ui.panel.contribute` | `application` | Contributes the History embedded panel. |
| `turboism.ui.toolbar.main.contribute` | `application` | Adds the vertical History tool-strip button. |
| `turboism.ui.status.notify` | `application` | Declares history snapshot and move-attempt notification support. |
| `turboism.action.register` | `application` | Registers the panel toggle and per-entry history actions. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not persist plugin data. It reads native history snapshots and keeps the rendered panel state, action registrations, and optional polling task in memory.

### Telemetry

No telemetry is sent by this plugin.

The plugin logs lifecycle, refresh, polling, and safe-failure diagnostics. Plugin lifecycle and failure records can also appear in Turboism's session log and host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview release plugin.
- `FULL` means targets and relevant values are complete; `PARTIAL` includes an explicit degradation code; `LABEL_ONLY` makes no semantic claim beyond the host label.
- `TURBOISM` origin is emitted only from operation-time registry metadata. Unregistered native operations remain `HOST_UNATTRIBUTED` in SDK data, but the panel does not display that unattributed origin.
- Native semantic decoding is authorized for the reviewed Cubism 5.2.03 and 5.3.02 selector records. Cubism 5.3.03 native-semantic readiness remains explicitly pending.
- Operation-time capture currently covers controlled SDK writes; native UI capture ingress is not yet implemented. Uncaptured native entries use conservative decoder or label-only fallback, never the current mutable target as an old after-value.
- Two-version SDK probes passed 19 assertions each, including retained color details after later writes and Undo/Redo. These runs required task-scoped forced cleanup, so graceful exit and complete native-semantic readiness remain unverified. The latest panel summary rendering has local test coverage but has not been redeployed for host visual validation.
- Entries without stable IDs are displayed but receive no move action. Changed snapshot binding or entry sequence rejects navigation without index fallback.
- Polling is optional: if task scheduling is unavailable, the pane remains usable with its initial refresh only.
- A failed panel refresh, stale navigation, or failed close is logged and handled fail-closed.

## Troubleshooting

| Symptom | What to check |
|---|---|
| History button is missing | Confirm the plugin is enabled and the vertical main-toolbar contribution is available. |
| Pane says history is unavailable | Open an active document and confirm the native history service is available. |
| Pane does not refresh | Check scheduler availability; without it, the pane displays only its initial snapshot. |
| Clicking an entry has no effect | The row may lack a stable entry ID, or the generation/revision/binding/entry sequence changed after rendering. Refresh and retry; no index fallback is used. |
| Pane does not close cleanly | Check the plugin log for host floating-frame teardown diagnostics. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.historypanel`
