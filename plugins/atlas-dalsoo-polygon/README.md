---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas-dalsoo
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: modeling
tags: texture-atlas, packing, polygon, auto-layout
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# Dalsoo Polygon Packing Algorithm

> **Official Turboism plugin** · **Status: Development**

Adds an irregular-polygon automatic layout algorithm to Cubism's texture-atlas
workflow, in the style of Cubism 5.4's contour packing. The kernel is an
independent port of the MIT-licensed `whitegreen/Dalsoo-Bin-Packing` project
(commit `bde2a3e`); see `LICENSE` and `NOTICE`. This plugin does **not** embed,
invoke, or claim compatibility with Cubism 5.4 - it runs on the currently
supported hosts (`5.2.03`, `5.3.02`, `5.3.03`).

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.texture-atlas-dalsoo` |
| Category | `modeling` |
| Tags | texture-atlas, packing, polygon, auto-layout |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `none` |
| License | MIT (kernel port) |

## What it does

- Registers a `dalsoo` texture-atlas layout algorithm alongside the existing
  MaxRects-BSSF backend; the rectangle backend remains unchanged.
- Packs real concave item outlines (material-local `drawDataShapes`, read
  through verified selectors), not bounding boxes and never convex hulls.
- Supports rotation modes `NONE`, `QUARTER` (90-degree steps), and `FREE`
  (18 candidate angles), fixed or automatic common scale, page margins, and
  per-item policies: participate, preserve angle, preserve scale, preserve
  position (fixed items become obstacles before movable items are placed).
- Serves the native automatic-layout callback with validated polygon plans;
  other algorithms continue through the existing rectangle path.
- Plans are validated against fresh host state (identity, overlap, margin,
  locks) and applied through the same staged affine/undo boundary as the
  rectangle service; failures roll back completely.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** exact reviewed Editor artifacts `5.2.03`, `5.3.02`, `5.3.03`.
  Cubism 5.4 is not admitted; a future official 5.4 host-native provider keeps
  a reserved `HOST_NATIVE` backend slot in the SDK contract.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Honest limits

- **Outline fallback.** When a host item exposes no usable contour, the item
  falls back to its bounding rectangle with `outlineSource=BOUNDS_FALLBACK`
  reported in diagnostics; it is not presented as contour packing.
- **Holes.** Contour holes are conservatively filled before packing and the
  fill is reported (`holesFilled` diagnostics). Complex self-intersecting
  topology may degrade to the bounds rectangle.
- **Rectangle-path degradation.** The legacy rectangle layout contract cannot
  express arbitrary angles; when plans flow through it, `FREE` rotation is
  degraded to `QUARTER` - a 45-degree placement is never rounded to a false
  quarter-turn.
- **Performance.** Concave packing costs orders of magnitude more than
  rectangle packing: 100 concave items take seconds to minutes depending on
  rotation mode and quality preset; 500-item packs take minutes. The `AUTO`
  backend routes all-near-rectangular inputs to the rectangle path. See
  [offline evidence](../../validation/texture-atlas-current-page/OFFLINE-POLYGON.md)
  and the raw CSV for measured numbers; these are synthetic offline figures,
  not host timings.
- **No native 5.4 claim.** The Cubism 5.4 alpha2 JAR was used only for
  read-only research; its artifact is not admitted, and no native comparison
  numbers are claimed.

## Install and enable

This official plugin is a **store candidate**, not yet a published store
listing. Install through Turboism's official release packaging, then enable it
in **Plugin Management**.

## How to use

1. Open Cubism's texture-atlas editor and choose the automatic layout workflow.
2. Select the `dalsoo` algorithm. While it is selected, the dialog exposes the
   packing controls described below; they are disabled (and their values
   ignored) for every other algorithm.
3. Run automatic layout; the plugin validates the complete plan before
   applying it and reports diagnostics (backend, outlines, fallbacks, holes,
   scale, overflow).

## Dialog options and persisted settings

The automatic-layout dialog contributes the following controls when `dalsoo`
is selected. Every value is bridged to the persisted plugin policy
(`texture-atlas-dalsoo/layout.cfg`) and takes effect on the next run.

| Dialog control | Setting | Cubism 5.4 counterpart | Notes |
|---|---|---|---|
| Rotation: `NONE` / `QUARTER` / `FREE` | `rotation` | rotation granularity combo | `FREE` plans with 18 candidate angles (20° steps) and writes them through the full affine path. The rectangle fallback honestly degrades `FREE` to `QUARTER`. |
| Lock preset: `NONE` / `ALL` / `ANGLE` / `SCALE` / `ANGLE_SCALE` / `POS_ANGLE` | `lock-preset` | `AutoLayoutLock` (`fixPosition`/`fixRotate`/`fixScale`) | Global default layer; an explicit per-item policy (`item-policies`) overrides it. |
| Scale mode: automatic / fixed | `auto-scale`, `fixed-scale-percent` | auto vs fixed scale entry | Fixed scale is entered in percent (1-800). Automatic scale never exceeds 1 (`s = min(1, ·)`). |
| Scale tolerance | `auto-scale-tolerance-permille` | `AUTO_SCALE_TOLERANCE` | Relative step floor for the scale search; default `0.005` (stored per-mille). |
| Scale max tries | `auto-scale-max-try` | `AUTO_SCALE_MAX_TRY` | Attempt bound; `0` derives it from the quality preset (`FAST` 8, `BALANCED` 12, `DENSE` 20). |
| Kernel: Abey / Dalalah | `use-abey` | - | Dalsoo kernel variant used by the planner. |
| Parallel search | `parallel` | - | Deterministic parallel variants, gated by the algorithm's `supportsParallel` declaration. |

The Cubism 5.4 texture-compression presets (`PRESET_COMPRESSION`,
`IMAGE_QUALITY`, `CUSTOM`) are orthogonal to packing and intentionally not
implemented.

Defaults preserve the pre-dialog behavior: rotation `QUARTER`, lock preset
`NONE`, automatic scale with tolerance `0.005`, quality-derived attempt bound,
Abey kernel, serial planning.

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.texture-atlas.layout` | Registers and applies the polygon packing algorithm when the reviewed editor service is available. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Read atlas item outlines, issued transforms and page state. |
| `turboism.cubism.model.write` | `application` | Apply validated polygon layout plans through the host affine/undo boundary. |
| `turboism.config.plugin.read` | `application` | Restore polygon layout settings and per-item policies. |
| `turboism.config.plugin.write` | `application` | Persist polygon layout settings and per-item policies. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Stores layout settings and per-item policies in plugin configuration. It reads
and writes the active texture-atlas authoring state only when the workflow
runs.

### Telemetry

No telemetry is sent by this plugin.

## Status and limitations

- **Status:** Development.
- Requires an active texture-atlas editor session and reviewed model
  read/write services.
- Planning or application failure is reported without applying a partial
  layout; the MaxRects-BSSF and native algorithms remain available.
- Real-host acceptance on all three supported versions is pending; only
  offline benchmarks and unit/integration tests have been run.

## Troubleshooting

| Symptom | What to check |
|---|---|
| `dalsoo` choice is missing | Confirm the plugin is enabled and the host exposes the texture-atlas layout capability. |
| Items pack as rectangles | The host contour source may be unavailable; check diagnostics for `BOUNDS_FALLBACK`. |
| Layout is slow | Concave packing is expensive; lower the quality preset, use `QUARTER`/`NONE`, or choose the `AUTO`/rectangle backend for near-rectangular inputs. |
| Layout is not applied | Check the Turboism log for validation failure; plans are rejected atomically. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** MIT; kernel ported from `whitegreen/Dalsoo-Bin-Packing` (see
  `LICENSE`/`NOTICE`)
- **Plugin ID:** `dev.turboism.plugin.texture-atlas-dalsoo`
