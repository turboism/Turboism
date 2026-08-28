---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.recent-preview
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: recent-files, preview, navigation
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# Recent Preview Plugin

> **Official Turboism plugin** · **Status: Preview**

Captures bounded thumbnails and shows them in Cubism's Recent Files hover popup.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.recent-preview` |
| Category | `workflow` |
| Tags | recent-files, preview, navigation |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Captures up to 150×150 modeling-surface previews after supported project open/save activity.
- Deduplicates in-flight and unchanged captures and contributes cached content to Recent Files hover UI.
- Uses opaque recent-file IDs and a no-path cache index.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03`, `5.3.02`, and `5.3.03`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Open or save a Cubism project while the plugin is enabled.
2. Open Cubism's Recent Files surface and hover a recent entry.
3. The popup shows the cached thumbnail and available display metadata; stale or unavailable captures are skipped safely.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.recent-file.read` | `application` | Lists the host Recent Files projection to key the preview cache by opaque file id. |
| `turboism.ui.viewport.read` | `application` | Captures bounded modeling-surface preview thumbnails for recent files. |
| `turboism.ui.recent-preview.contribute` | `application` | Contributes thumbnail popup content to the host Recent Files hover bridge. |
| `turboism.file.read` | `application` | Reads cached preview PNGs from plugin-confined cache storage. |
| `turboism.file.write` | `application` | Atomically writes preview PNGs and the no-path index into plugin-confined cache storage. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Stores bounded PNG thumbnails and no-path index entries under plugin cache paths `recent-preview/images/` and `recent-preview/index/`. Keys are SHA-256 values derived from opaque recent-file IDs; index entries intentionally contain no file path. Disk cache survives disable until cache data is cleared externally.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Requires recent-file, viewport screenshot, and recent-preview contribution services.
- Capture is asynchronous and bounded; a project change, stale ID, invalid PNG, or unavailable viewport causes the result to be skipped.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Hover popup has no thumbnail | Open or save the project, wait for capture, and confirm viewport/recent-file services are available. |
| An old thumbnail remains | Reopen or save the project to trigger reconciliation; invalid or stale cache entries are ignored. |
| Cache grows | Clear this plugin's cache through Turboism's storage management or remove the plugin cache while Turboism is stopped. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.recent-preview`
