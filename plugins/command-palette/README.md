---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.command-palette
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: workflow
tags: launcher, commands, shortcut
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# Command Palette

> **Official Turboism plugin** · **Status: Development**

Opens a Ctrl+K launcher over the Editor's supported menu commands, searchable by command id or localized name.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.command-palette` |
| Category | `workflow` |
| Tags | launcher, commands, shortcut |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- Opens a command palette with **Ctrl+K** (also available from **Turboism → Command Palette**).
- Lists every Editor command the host currently admits, ordered by localized name.
- Filters by subsequence as you type: the query matches both the localized command name and the stable command id (for example `new.model`).
- Highlights the matched characters in green inside each result row; all other text stays in the normal foreground.
- Shows a gray inline completion hint for the top match; **Tab** accepts it.
- **Enter** executes the selected command through the safe Editor-command service and closes the palette. Typing a full command id and pressing **Enter** executes it even when the dropdown is empty.
- **Up/Down** move the selection, **Esc** or clicking away closes the palette without running anything.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03`, `5.3.02`, and `5.3.03`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is under development and not yet part of release packaging. Build it with `./gradlew :plugins:command-palette:jar`, place the jar in the Turboism `plugins/` directory, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Press **Ctrl+K** (or use **Turboism → Command Palette**).
2. Type part of a command name or id, such as `undo`, `new model`, or `new.model`.
3. Press **Tab** to accept the gray completion hint, or **Up/Down** to pick a row.
4. Press **Enter** to run the command. Press **Esc** to dismiss the palette.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.editor-commands.execute` | Runs the Editor command chosen in the palette through the host menu system. |

## Permissions

| Declared permission | Scope | User effect |
|---|---|---|
| `turboism.action.register` | `application` | Registers the Command Palette open action and its default Ctrl+K shortcut. |
| `turboism.ui.menu.contribute` | `application` | Adds the Command Palette entry to the Turboism top-level menu. |
| `turboism.cubism.model.read` | `application` | Executes read-tier Editor commands chosen in the palette. |
| `turboism.cubism.model.write` | `application` | Executes write-tier Editor commands chosen in the palette. |
| `turboism.file.write` | `application` | Allows the SAVE Editor command to be launched from the palette. |
| `turboism.network.fetch` | `application` | Allows the palette to launch Editor commands that open Live2D web pages. |
| `turboism.process.run` | `application` | Allows the palette to launch Editor commands that open local folders. |

## Privacy and data

### Network

Makes no network connections. Commands that open Live2D web pages hand the address to the host Editor, which performs the navigation.

### Local data

Does not persist data. The query and selection state live only while the palette window is open.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

- **Status:** Development.
- The palette lists the parameterless Editor command set exposed by `EditorCommandService`; file-path and parameterized commands and other plugins' actions are not included.
- Only commands the host reports as currently available (and permitted for this plugin) appear in the list.
- A user's own Ctrl+K keybinding override takes precedence over the plugin default.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Ctrl+K does nothing | Confirm the plugin is enabled and no user keybinding overrides Ctrl+K. |
| A command is missing from the list | The host reports it as unavailable in the current state, or this plugin lacks a permission the command requires. |
| A command does not run on Enter | Check the host log; the result status is logged when execution is denied or fails. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.command-palette`
