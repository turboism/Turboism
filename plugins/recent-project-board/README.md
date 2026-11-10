---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.recent-project-board
version: 0.68.1
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: recent-files, preview, viewport, navigation
turboismApi: "[0.70.0,1.0.0)"
requiresCubism: true
interface: swing
---

# Recent Project Board

> **Official Turboism plugin** · **Status: Preview**

Replaces the empty Cubism modeling viewport with a board of recent project thumbnails. Double-click a card to open that project.

| Detail | Value |
|---|---|
| Version | `0.68.1` |
| Plugin ID | `dev.turboism.plugin.recent-project-board` |
| Category | `workflow` |
| Tags | recent-files, preview, viewport, navigation |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Shows up to eight recent projects in a compact, configurable layout over the native OpenGL viewport while no document is open. An icon-only Lucide layout control in the header cycles 6-column grid, 3-column grid, and a left-aligned details list, and remembers the choice. Hovering it shows the current layout. Text scales up in the 3-column and details layouts.
- Uses the official `recent-preview` plugin's public `thumbnail.captured` event for thumbnails, including its bounded startup cache replay.
- Hides the board immediately when any document opens and restores it after the last document closes.
- Keeps the header title/subtitle left-aligned and the layout button on the right.
- The details list uses a small thumbnail on the left followed by a larger, left-aligned name and timestamp vertically centered in the row, similar to Explorer's Details view.
- Deduplicates repeated recent-menu entries by display name and lists entries with cached previews first.
- Centers each card's name and timestamp, and centers the placeholder text vertically and horizontally when no thumbnail is cached.
- When there are no recent files, centers an empty-state message with blue **Create New Project** and **Create New Animation Project** links. The first invokes the host's typed `NEW_MODEL` command; the second opens Cubism's native New Animation target dialog.
- Opens a project through the host's own Recent Files action; no path or file grant is exposed to the plugin.

## Requirements and compatibility

- **SDK API range:** `[0.70.0,1.0.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.0.00` through `5.3.04`; each host-facing feature is exposed only when its declared services and capabilities are available.
- **Interface mode:** `swing`.
- **Plugin dependencies:** `dev.turboism.plugin.recent-preview` (required, ordered after the provider).

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

This official plugin is bundled with Turboism and is available through **Plugin Market**. Enable, disable or uninstall the installed plugin in **Plugin Management**.

## How to use

1. Enable both **Recent Preview** and **Recent Project Board**.
2. Open a project once so Recent Preview can create its thumbnail.
3. Close the project. The board appears over the empty viewport.
4. Use the header layout icon to cycle 6-column grid, 3-column grid, and details list; the choice is persisted.
5. Double-click a card to open that project again.
6. With no recent files, use the centered **Create New Project** link to start a new model, or **Create New Animation Project** to open Cubism's New Animation target dialog.

Projects without a cached thumbnail still appear as placeholders, so the board remains a faithful recent-project list. The first successful open or save creates the missing thumbnail.

## Capabilities

| Capability | Scope | Why it is declared |
|---|---|---|
| `ui.embedded-panel.contribute` | `application` | Contributes the recent-project board overlay to the modeling viewport. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.recent-file.read` | `application` | List recent projects and dispatch the selected recent file for opening. |
| `turboism.ui.panel.contribute` | `application` | Contribute the board to the empty viewport placement. |
| `turboism.cubism.model.observe` | `application` | Refresh the recent-project list after a project is opened, created, or closed. |
| `turboism.cubism.model.write` | `application` | Create a new model through `NEW_MODEL` and open the native New Animation flow when the empty-state links are clicked. |
| `turboism.event.subscribe` | `application` | Receive live and replayed `thumbnail.captured` events from the provider. |
| `turboism.action.register` | `application` | Register the double-click open action used by board cards. |
| `turboism.file.read` | `application` | Read the plugin-confined layout preference. |
| `turboism.file.write` | `application` | Persist the selected layout preference. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Stores the user's selected board layout preference in plugin data storage at `recent-project-board/layout.txt`. No project file paths, model content, or user credentials are stored.

### Telemetry

No telemetry is sent by this plugin.

## Status and limitations

- **Status:** Preview.
- Requires the `recent-preview` plugin for project thumbnails; placeholder cards are displayed when thumbnails have not yet been captured.
- Opens projects through native host actions without exposing local filesystem grants.

## Troubleshooting

| Symptom | What to check |
|---|---|
| The board is not visible | Confirm no Cubism model or animation document is open; the board automatically hides while any document is active. |
| Thumbnails show placeholder text | Confirm `recent-preview` is enabled and open the project once to capture its thumbnail. |
| Double-click does not open a project | Confirm the project file still exists at its original path on disk. |

## Changelog

### 0.68.1

- Initial release as an official preview plugin.
- Added viewport recent project board overlay with double-click open dispatch.
- Added 6-column, 3-column, and details layouts with persisted icon-only Lucide header control.
- Added centered empty state with Create New Project and Create New Animation Project links.

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.recent-project-board`

### Icon attribution

The layout control uses Lucide v0.468.0 `layout-grid` and `list` icons under the ISC license (including the upstream Feather notice). See `META-INF/licenses/Lucide-LICENSE.txt`.
