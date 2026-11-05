---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.recent-preview
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: recent-files, preview, navigation
turboismApi: "[0.67.0,1.0.0)"
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
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Captures up to 300×300 modeling-surface previews after supported project open/save activity and renders them within the existing popup bounds.
- Deduplicates in-flight and unchanged captures and contributes cached content to Recent Files hover UI.
- Uses opaque recent-file IDs and a no-path cache index.

## Requirements and compatibility

- **SDK API range:** `[0.67.0,1.0.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.0.00` through `5.3.04`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
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

1. Open or save a Cubism project while the plugin is enabled.
2. Open Cubism's Recent Files surface and hover a recent entry.
3. The popup shows the cached thumbnail and available display metadata; stale or unavailable captures are skipped safely.

## Plugin-to-plugin event contract

The plugin exports the public `thumbnail.captured` event (contract `1.0.0`). Its SDK-owned payload `RecentPreviewCapturedEvent` carries the opaque recent-file id, display name, captured `ScreenshotImage`, and a ready-to-use thumbnail `PanelView`.

A consumer declares a dependency on `dev.turboism.plugin.recent-preview` with `ordering: after`, imports the event, and declares `turboism.event.subscribe`:

```json
"dependencies": [{
  "id": "dev.turboism.plugin.recent-preview",
  "version": "[0.68.0,1.0.0)",
  "type": "required",
  "ordering": "after",
  "reason": "Consumes committed recent-file previews."
}],
"eventImports": [{
  "provider": "dev.turboism.plugin.recent-preview",
  "eventId": "thumbnail.captured",
  "contractVersion": "[1.0.0,2.0.0)",
  "eventType": "dev.turboism.sdk.cubism.recentpreview.RecentPreviewCapturedEvent",
  "abiSha256": "e4791126d565f94153f3580974ea179f1fd46fa69a638ed52cabdb359a4f4109",
  "required": true
}]
```

Subscribe with `@SubscribeEvent` or `context.eventBus().subscribe(RecentPreviewCapturedEvent.class, ...)`. Use `event.image().png()` for the byte stream or `event.thumbnail()` for a `PanelView` part. The event is live-only and is not replayed to subscribers that start after a capture.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.observe` | `application` | Receives model open/save lifecycle callbacks that trigger bounded preview captures; undeclared, the lifecycle registry filters them out. |
| `turboism.cubism.recent-file.read` | `application` | Lists the host Recent Files projection to key the preview cache by opaque file id. |
| `turboism.ui.viewport.read` | `application` | Captures bounded modeling-surface preview thumbnails for recent files. |
| `turboism.ui.recent-preview.contribute` | `application` | Contributes thumbnail popup content to the host Recent Files hover bridge. |
| `turboism.file.read` | `application` | Reads cached preview PNGs from plugin-confined cache storage. |
| `turboism.file.write` | `application` | Atomically writes preview PNGs and the no-path index into plugin-confined cache storage. |
| `turboism.event.publish` | `application` | Publishes committed thumbnails to dependent plugins that import the public `thumbnail.captured` event. |

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

## Changelog

### 0.1.0

- Initial release.

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.recent-preview`
