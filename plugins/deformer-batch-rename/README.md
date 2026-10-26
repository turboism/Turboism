---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.deformer-batch-rename
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: deformer, rename, batch-edit
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---

# Batch Rename Deformers

## What it does

Generate name suggestions for Warp and Rotation Deformers in the current model from descendant names and bound parameters. Review and select changes before applying the batch.

## Requirements and compatibility

Requires Turboism 0.67.0 or later and a Cubism model. The generated block below describes SDK-derived compatibility, not real-host validation.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
- Top menu integration not on 5.0.00–5.0.06（earlier Cubism versions lack the feature entirely）
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

Build with `./gradlew :plugins:deformer-batch-rename:jar`, then install the JAR through Turboism plugin management and enable it.

## How to use

1. Open a Cubism model and choose **Turboism → Plugins → Batch Rename Deformers**.
2. Review the four columns: native Warp/Rotation icon, original name, target name, and apply checkbox.
3. Select the rows to rename. Click the fourth column header to select or clear all; unchanged rows cannot be selected.
4. Click **Confirm** to apply. **Cancel**, closing the window, and Escape discard the preview.
5. Use Cubism Undo to undo the entire batch in one operation.

Target names are generated automatically and cannot be edited in this dialog.

Names use the longest common prefix of descendant leaf names. Without a common prefix, use the shortest name; ties follow model order. Empty child deformers contribute their own names; childless deformers stay unchanged. Append own bound parameter display names, falling back to IDs. Matching X/Y/Z suffixes are compacted in binding order: `Hair1` and `Hair1 Shadow`, bound to `HeadX` and `HeadY`, produce `Hair1 HeadXY`.

### Naming examples

| Descendant leaf names | Own bound parameters | Target name |
| --- | --- | --- |
| `Hair1`, `Hair1 Shadow` | None | `Hair1` |
| `Hair1`, `Hair1 Shadow` | `HeadX`, `HeadY` | `Hair1 HeadXY` |
| `EyeL`, `RightEyeShadow` | None | `EyeL` |

Only display names change. Object IDs, hierarchy and parameter bindings stay unchanged. Duplicate names are not automatically resolved.

## Capabilities

- `cubism.model.read`
- `cubism.model.write`
- `ui.dialog.contribute`
- `ui.menu.contribute`
- `ui.status.notify`

## Permissions

- `turboism.cubism.model.read`
- `turboism.cubism.model.write`
- `turboism.action.register`
- `turboism.ui.dialog.contribute`
- `turboism.ui.menu.contribute`
- `turboism.ui.status.notify`

## Privacy and data

Works locally; no network, file export or persistent settings.

## Status and limitations

Release candidate. The preview and both native deformer icon types were verified in Cubism 5.3.03 (12 native icon cells, no text fallbacks). Full rename/Undo validation across supported versions remains pending. Duplicate display names are allowed; IDs stay unchanged. The batch uses one native Undo transaction.

## Troubleshooting

Open a model first. If you switch models or change hierarchy, names or parameter bindings while the preview is open, cancel and reopen it. Confirmation rejects a stale preview. For failed transactions, check the plugin log and the reported transaction outcome.

## Changelog

### 0.1.0

- Initial release.

## Support and license

[Report issues](https://github.com/turboism/Turboism/issues). Distributed under the repository license.
