---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.demo
version: 0.1.0
kind: demo
status: development
delivery: development-only
category: development
tags: example, sdk
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: false
interface: none
---

# Demo Plugin

> **Official Turboism plugin** · **Status: Development**

Demonstrates Turboism action, menu, toolbar, context-menu, configuration, localization, and event integrations for framework validation.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.demo` |
| Category | `development` |
| Tags | example, sdk |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | No |
| Interface | `none` |
| License | Project License |

## What it does

- Registers the localized `demo.hello` action and exposes it from a Tools/Demo menu entry, main-toolbar button, palette-toolbar button, and parameter context-menu entry.
- Registers read access for `demo/config.json` without consuming or changing a configuration value.
- Subscribes to `DemoEvent`, publishes one enable-time demo event, and records received events in the plugin log.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Does not require Cubism or use Cubism services. Its UI registrations demonstrate optional framework integration when the corresponding host registries are available.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Install and enable

This is a **development-only** module, not a published store listing or release-delivery plugin. Build and load it only through this repository's development runtime, then enable it in **Plugin Management** when validating framework integrations. Disable it from the same window when the demonstration registrations are not needed.

## How to use

1. Enable the plugin in a development runtime.
2. Locate the localized **Hello Demo** command under **Tools → Demo**, on the main or Parameters palette toolbar, or in a parameter context menu.
3. Invoke the command to exercise registration and dispatch; its handler intentionally has no visible user action.
4. Inspect the plugin log to observe its enable-time `DemoEvent` publication and any received demo events.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.ui.menu` | `application` | Declares access for the demo menu integration. |
| `turboism.action.register` | `application` | Registers the `demo.hello` action. |
| `turboism.ui.menu.contribute` | `application` | Adds the Tools/Demo menu contribution. |
| `turboism.ui.toolbar.main.contribute` | `application` | Adds the main-toolbar button. |
| `turboism.ui.toolbar.palette.contribute` | `application` | Adds the Parameters palette-toolbar button. |
| `turboism.ui.context-menu.contribute` | `application` | Adds the parameter context-menu entry. |
| `turboism.config.plugin.read` | `application` | Registers read scope for `demo/config.json`. |
| `turboism.event.subscribe` | `application` | Subscribes to `DemoEvent`. |
| `turboism.event.publish` | `application` | Publishes the enable-time `DemoEvent`. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Does not read or write plugin data. It registers read scope for `demo/config.json`, but the current implementation does not consume a value. Registration handles and event state remain in memory.

### Telemetry

No telemetry is sent by this plugin.

Lifecycle messages and received demo events are written to the plugin log. Plugin lifecycle and failure records can also appear in Turboism's session log and host log with the plugin ID attached.

## Status and limitations

- **Status:** Development.
- This module validates framework integration rather than delivering a production workflow; the `demo.hello` handler intentionally does nothing visible.
- It declares `ui: none` and does not require Cubism, although it demonstrates optional menu, toolbar, palette-toolbar, and context-menu registrations.
- The source references `/demo/icon.png` and `/demo/palette-icon.png`, but no matching resources are currently packaged.
- Configuration access is read-scope registration only, and the implementation publishes only its enable-time demo event.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Demo entries are missing | Confirm the plugin is enabled in a development runtime and its declared UI permissions are granted. |
| Invoking Hello Demo has no visible result | This is expected: the action handler is intentionally empty. Inspect the plugin log to confirm enablement instead. |
| Labels show fallback text | Confirm the declared i18n catalogs are packaged and that the host supplies plugin localization. |
| Toolbar buttons have no icons | The referenced icon resources are not currently present in this module. |
| Entries remain after lifecycle cleanup | Ensure the plugin's disposable scope is closed; its registrations are enrolled in that scope. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.demo`
