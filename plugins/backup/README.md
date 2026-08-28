---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.backup
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: backup, webdav, automation
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# WebDAV Auto-Backup Sync Plugin

> **Official Turboism plugin** · **Status: Preview**

Uploads Cubism backup artifacts to a WebDAV endpoint configured by the user.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.backup` |
| Category | `workflow` |
| Tags | backup, webdav, automation |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Adds a Turboism menu entry for WebDAV backup settings and endpoint testing.
- Can upload save-triggered or host auto-backup `.cmo3` artifacts with bounded retries.
- Uses JDK `HttpClient`; credentials are kept out of rendered configuration and diagnostics.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03`, `5.3.02`, and `5.3.03`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Open **Turboism → WebDAV 备份设置**.
2. Enter the HTTP(S) endpoint, remote path, optional username and password, trigger mode, timeout, TLS, and retry settings; test the endpoint before saving.
3. Enable synchronization. Matching backup artifacts are uploaded when the configured trigger fires.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.config.plugin.read` | `application` | Reads the backup/webdav.cfg endpoint configuration. |
| `turboism.event.subscribe` | `application` | Subscribes to BackupCompletedEvent to upload new backup artifacts. |
| `turboism.cubism.backup.observe` | `application` | Observes privacy-safe Runtime backup completion facts; exact artifacts remain in the initiating command result. |
| `turboism.config.plugin.write` | `application` | Persists the WebDAV endpoint settings through the backup/webdav.cfg write path with readback confirmation. |
| `turboism.action.register` | `application` | Registers the backup.webdav.settings.open action behind the Turboism menu item. |
| `turboism.ui.menu.contribute` | `application` | Exposes the WebDAV 备份设置 settings dialog through the Turboism menu. |
| `turboism.cubism.model.observe` | `application` | Observes model and animation save lifecycle to trigger save-triggered backups. |

## Privacy and data

### Network

Connects to the user-configured WebDAV HTTP(S) endpoint and uses `MKCOL`, `PROPFIND`, and `PUT`. Optional Basic authentication sends the configured username and password to that endpoint. Transfers can run automatically after the configured save or auto-backup trigger. Disabling TLS verification weakens transport security and is intended only for trusted private endpoints.

### Local data

Stores endpoint settings and credentials in plugin configuration at `backup/webdav.cfg`. Password values are masked in the dialog and redacted from logs and object rendering. The plugin reads eligible backup files in order to upload them.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Preview.
- Requires a reachable WebDAV server and Cubism save/backup lifecycle events.
- Invalid files, endpoint failures, interrupted requests, and exhausted retries fail closed; successful remote backup is not assumed until the server accepts the upload.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Endpoint test fails | Verify the URL, remote path, credentials, TLS setting, and server WebDAV support. |
| Backups are not uploaded | Confirm synchronization is enabled, the trigger mode matches the event, and inspect the plugin-scoped log record. |
| Repeated server errors | Check HTTP status and retry settings; the plugin retries only bounded transient failures. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.backup`
