---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.frame-animation-import
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: animation, frame-animation, sprite-sheet, parameter
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---

# Frame Animation Import

> **Official Turboism plugin** · **Status: Preview**

Imports sequence frame animations from sprite sheets or image lists and automatically binds each frame mesh to a parameter with keyframe visibility/opacity.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.frame-animation-import` |
| Category | `modeling` |
| Tags | animation, frame-animation, sprite-sheet, parameter |
| Requires Cubism | Yes |
| Interface | `swing` |
| License | Project License |

## What it does

- **Sprite sheet slicing:** Import a sprite sheet image and slice it by rows and columns with interactive visual grid preview and frame count limits.
- **Image sequence import:** Select multiple discrete frame images with automatic natural numerical sorting (e.g. `frame_1.png` to `frame_10.png`).
- **Automatic ArtMesh & container generation:** Automatically creates a dedicated Part container, constructs quadrilateral ArtMeshes, and binds textures with adaptive RGBA budget scaling.
- **Parameter creation & keyframe binding:** Creates or reuses a parameter (with optional looping/repeat) and sets keyframe opacities (Step Flipbook or Smooth Crossfade) using clean frame bindings.
- **Atomic Undo/Redo:** The entire import executes in a single authoring transaction, allowing one-key complete rollback (`Ctrl+Z`).

## Requirements and compatibility

- **SDK API range:** `[0.67.0,1.0.0)`.
- **Cubism:** Requires Cubism. Supports all reviewed Editor versions `5.0.00` through `5.3.04`.
- **Interface mode:** `swing`.
- **Plugin dependencies:** None declared.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.3.00–5.3.04.
- Part opacity writes only on 5.3.00–5.3.04（[Cubism 5.3.00 added Part offscreen drawing (part opacity and clipping masks)](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/)）
- Top menu integration not on 5.0.00–5.0.06（earlier Cubism versions lack the feature entirely）
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

This official plugin is bundled with Turboism and is available through **Plugin Market**. Enable, disable or uninstall the installed plugin in **Plugin Management**.

## How to use

1. Open the top-level **Turboism → Plugins** menu or right-click in the Part tab and choose **Import Frame Animation...**.
2. Choose either **Sprite Sheet** mode (specify rows, columns, frame limit) or **Image Sequence** mode (add images).
3. Configure target parameter (name, ID, repeat) and display transition strategy (Step Flipbook vs Crossfade).
4. Click **Import**. The frames, meshes, parameter, and keyframe opacities are created and bound automatically.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.model.read` | Reads model structure, parts, and existing parameters. |
| `cubism.model.write` | Creates Part containers, ArtMeshes, textures, and parameter bindings. |
| `cubism.parameter.write` | Creates new animation parameters and writes keyframe values. |
| `ui.context-menu.contribute` | Adds the frame animation import launcher to the Part tab context menu. |
| `ui.menu.contribute` | Adds the frame animation import launcher to the Turboism top-level menu. |
| `ui.status.notify` | Reports import outcomes and precondition notifications. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Read model parts, drawables, and parameter definitions. |
| `turboism.cubism.model.write` | `application` | Create frame animation parts, art meshes, parameters, and bind keyframe opacities. |
| `turboism.file.read` | `application` | Read selected frame images and sprite sheet files. |
| `turboism.action.register` | `application` | Register frame animation import action. |
| `turboism.ui.context-menu.contribute` | `application` | Expose frame animation import in the Part tab context menu. |
| `turboism.ui.menu.contribute` | `application` | Adds the frame animation import launcher to the Turboism top-level menu. |
| `turboism.ui.status.notify` | `application` | Notify import outcomes and validation errors. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Reads user-selected sprite sheets and frame images from disk during the import session. Does not persist private data.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and diagnostic records appear in Turboism's local session log with the plugin ID attached.

## Status and limitations

- **Status:** Development.
- Requires an active Cubism model document.
- Single-frame image resolution exceeding the 4MB RGBA budget is smoothly downscaled to fit memory constraints.
- The modal Swing dialog is unavailable in a headless JVM.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Context command is missing | Ensure an active model document is opened in Cubism Editor. |
| Image loading fails | Verify the chosen image file is a valid PNG or supported raster format. |
| Import is aborted | Check the status notification and runtime log for validation details. |

## Changelog

### 0.1.0

- Initial release.

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.frame-animation-import`
