# Bundled toolbar icon sources

The mesh-toolbar icons are original artwork drawn in code by this plugin, not a third-party icon
set. The drawing lives in `plugins/geometric-mesh-layout/artwork/generate_icons.py`; it uses only
Pillow primitives and the shared Turboism corner badge in `scripts/artwork/plugin_icon_badge.py`.

The plugin ships six states in a light and a `-dark` variant. Each state ships five rasters, so the
editor can pick the sharpest image for the display scale:

| Scale | Raster | File name |
|---|---|---|
| 100% | 32×32 | `geometric-mesh-layout.png` |
| 125% | 40×40 | `geometric-mesh-layout.scale-125.png` |
| 150% | 48×48 | `geometric-mesh-layout.scale-150.png` |
| 175% | 56×56 | `geometric-mesh-layout.scale-175.png` |
| 200% | 64×64 | `geometric-mesh-layout.scale-200.png` |

`runtime/src/main/java/dev/turboism/ui/toolbar/ToolbarIconLoader.java` resolves these names. Under a
dark look and feel it prefers the `-dark` sibling first, for example
`geometric-mesh-layout-dark.scale-150.png`.

Derivation, run from the repository root (requires Python 3 and Pillow):

```sh
python3 plugins/geometric-mesh-layout/artwork/generate_icons.py
```

The command renders every size from one 8× working raster and keeps an existing 32×32 base raster.
Pass `--base` only when the artwork itself changes, because the committed 32px rasters are the
reviewed light/dark selection artifacts. The corner badge keeps its proportional size at every
scale. The icons use the project license; they carry no third-party notice.
