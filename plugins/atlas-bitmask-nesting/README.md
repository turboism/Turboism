---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas-nesting
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: texture-atlas, packing, polygon, auto-layout
turboismApi: "[0.67.4,1.0.0)"
requiresCubism: true
interface: none
---

# Bitmask Nesting

## What it does

Independent current-page texture-atlas nesting using material-local concave contours, conservative bit masks, and run-skipping placement. Small pieces can occupy open concavities and gaps between separate contour regions. Exact rectangular inputs automatically use a lightweight BSSF path; irregular inputs retain contour collision checks. No other packing plugin is required.

## Requirements and compatibility

Java 17+, Turboism API [0.67.4,1.0.0), and the reviewed Cubism 5.0–5.3 versions in the generated inventory. Cubism 5.2–5.3 supports contour nesting; 5.0–5.1 uses rectangle packing when the polygon service is unavailable. The existing AUTO backend slot is used with a concrete bitmask-nesting kernel diagnostic; no new SDK enum is required.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.2.00–5.2.03, 5.3.00–5.3.04.
- Polygon-aware atlas layout only on 5.2.00–5.3.04（earlier Cubism versions lack the feature entirely）
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

Build with `./gradlew :plugins:atlas-bitmask-nesting:jar`, then install the JAR through Turboism plugin management and enable Bitmask Nesting. This development module is not in the release bundle.

## How to use

Open the current texture page and choose **Bitmask Nesting** in the native automatic-layout algorithm selector. Set the native scale and margin before running. Rotation disabled preserves issued angles; enabled adds quarter turns and minimum-area-box candidates. Margin 3 keeps at least 6 pixels between outlines, matching the native per-side convention. Fixed scale never silently shrinks items; automatic scale searches at or below 100%.

## Capabilities

`cubism.texture-atlas.layout` registers the native algorithm. The runtime owns snapshots, validation, affine writeback, undo, and stale-target checks.

## Permissions

`turboism.cubism.model.read` reads issued contours and transforms. `turboism.cubism.model.write` applies validated plans through the runtime.

## Privacy and data

The plugin processes geometry in memory. It does not export source images, write model files, access the network, or send telemetry. Diagnostic logs contain item counts, scale, grid size and planner duration.

## Status and limitations

Development build. Native invocations pack the current page with real contours. Complete-atlas SDK requests, including Folder Atlas Layout, use available mesh contours at source size without rotation (full-image bounds when contours are unavailable); overflow creates pages within the budget or raises a typed packing failure. Unavailable contours use the SDK rectangular fallback. Closed holes are filled conservatively by the SDK; open concavities remain available. Finite grid positions and angles trade density for speed. The default mask grid has at most 512 cells per side (8px cells on 4096 pages); DENSE SDK requests use 1024 cells. Explicit padding remains independent of grid resolution. Mesh contours are not reconstructed from image alpha: a circular picture with a rectangular mesh remains a rectangle. Search is deterministic, serial and heuristic; it does not prove optimal density or maximum scale. The parallel checkbox is disabled.

## Troubleshooting

If the selector is missing, check plugin enablement, host compatibility and the runtime log. Overflow at fixed scale is left to the runtime current-page contract. Run `./gradlew :plugins:atlas-bitmask-nesting:test` for collision and geometry regressions. `:plugins:atlas-bitmask-nesting:nestingBenchmark` measures synthetic planner time; it is separate from Cubism UI time.

## Support and license

Report reproducible cases to the Turboism project at https://turboism.dev with the host version and diagnostic log. MIT license; see the repository LICENSE.
