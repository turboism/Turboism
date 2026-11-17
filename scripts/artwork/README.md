# Plugin Feature Icons

The self-drawn feature icons that the Turboism framework and the first-party plugins embed in the native Cubism interface carry one purple square with a white `T` at the bottom right, which marks the feature as first-party Turboism. The body outline and the button states stay unchanged, so a user never mistakes these entries for a native feature.

**Scope: this mark belongs to the Turboism framework and the first-party plugins only. A third-party plugin never uses the mark; adopting the Turboism SDK, listing in the plugin store, or being compatible with the framework grants no first-party identity.** The generator is an internal first-party asset tool and is no rule for marking a third-party plugin automatically.

- The mark width is about 28% of the asset width and at least 7px, and the right and bottom margins are about 1/32 of the asset width.
- A light outline keeps the mark legible on a light or dark button background, and the glyph also carries an identity cue beyond color.
- The default, hover, pressed, and selected states keep the same mark, and the disabled state uses gray.
- The current coverage is the selection brush, the bounding-box mirror, the Alt symmetry toggle, and the history panel. The Turboism home page, the installer, and the ACP entry already carry brand graphics and take no second mark.
- A native symbol that names an object type, such as an ArtMesh, deformer, or part, and the generic settings gear take no origin badge.

## Light and Dark Mode Adaptation

A new or changed UI icon must follow the [UI appearance and dark mode guide](../../sdk/plugin-author-guide.md#ui-appearance-and-dark-mode) and must check the asset file and the runtime paint path together.

- Prefer a transparent background so the button joins its toolbar. Never bake a fixed light plate, such as white or light gray, into a PNG, a code-generated image, or a GL state texture. When a solid background is truly needed, use the theme color of the toolbar that holds the button.
- A transparent background alone is no adaptation. The body line, the mirror axis, the node, and the text must stay legible on a dark toolbar, so avoid a black-only or very dark off-state graphic. Provide a light and a dark icon set when needed, or choose a palette that reads on both backgrounds.
- Keep the alpha of an anti-aliased edge, and check the body, the badge, and the interaction feedback at the real host display size and at every supported DPI.
- Check every supported state: default, hover, pressed, on or selected, off, and disabled. An off state stays clickable and never borrows a disabled style, and a mode never relies on color alone.
- For a cached bitmap or GL texture, check the effect of a theme switch while it is displayed. Use a transparent plate with a translucent feedback, or regenerate the asset on a theme change, so an old theme color never remains.
- Regenerate the release asset after editing a source image without the badge, so the next generation cannot overwrite the fix. A composited preview on a light and a dark background and an alpha check are an initial verification only; the final host visual effect still needs its own confirmation, and the unfinished verification must be recorded explicitly.

The surface deformer mirror button and the graphic mesh mirror button share the GL state image generation of `RuntimeViewContextMenuRegistry`. Even with a transparent source image, an opaque light plate added at run time breaks dark mode, so a check of these buttons must not read the PNG alone.

## Standalone Assets

The assets live in [`assets/plugin-badge/`](assets/plugin-badge/) and go directly to a designer or into later icon composition:

- [`turboism-plugin-badge.svg`](assets/plugin-badge/turboism-plugin-badge.svg): the normal state in 100×100 normalized coordinates, drawn as pure geometry with no font dependency.
- [`turboism-plugin-badge-disabled.svg`](assets/plugin-badge/turboism-plugin-badge-disabled.svg): the disabled state.
- The PNG of the same name covers 7, 9, 10, 13, 18, 34, and 64px in both states. The trailing number is the size of the badge itself, and the canvas holds no placement margin.

The SVG serves editing and scaling, and the PNG serves exact pixel-size composition. One set of geometry and color definitions generates both, so the edge anti-aliasing of a different renderer may differ slightly. A small size uses its matching PNG instead of repeated scaling from the 64px image.

| Color role | Normal state | Disabled state |
| --- | --- | --- |
| Background | `#6752C7` | `#BDBDBD` |
| T glyph | `#FFFFFF` | `#EEEEEE` |
| Outer edge | `#F4F3FF` | `#E6E6E6` |

## Composition Rules

The badge stays square and keeps a fixed orientation, so it is never stretched, rotated, or set in another font, and it gains no shadow and no hover color change. It marks the Turboism framework or first-party plugin identity and never an enabled, selected, or error state. The button background still carries the interaction state, and only the disabled state switches to the gray badge.

The size is `max(7, round(source width × 0.28))`, and the right and bottom margins are both `max(1, round(source width / 32))`, with the Python `round` rule.

| Source width | Badge size | Right and bottom margin |
| --- | --- | --- |
| 24px | 7px | 1px |
| 32px | 9px | 1px |
| 36px | 10px | 1px |
| 48px | 13px | 2px |
| 64px | 18px | 2px |
| 120px | 34px | 4px |

A new icon design reserves the badge size and margin at the bottom right, so the badge never covers a key graphic. This rule serves the current toolbar assets, and a smaller or unusual asset needs a check at the real host display size first. A HiDPI variant scales the body, the badge, and the margin by the same logical size, so a separate rounding never shifts the position.

Python composition entry:

```python
from plugin_icon_badge import render_plugin_badge, with_plugin_badge

badge = render_plugin_badge(9)  # standalone 9×9 RGBA asset
result = with_plugin_badge(source_image, disabled=False)
```

Outside `scripts/artwork/`, add that directory to the Python module search path. Call `with_plugin_badge` once on a source image without the badge, and never process an output image again.

## Regenerate

To update the standalone SVG and PNG assets only, after editing the shared definition:

```sh
python3 scripts/artwork/plugin_icon_badge.py
```

From the repository root, with Python 3 and Pillow:

```sh
python3 scripts/artwork/generate_plugin_icons.py
```

The selection brush also regenerates its icons alone:

```sh
python3 plugins/selection-brush/artwork/generate_icons.py
```

Code draws the brush body, and the source image without the badge of every other body lives in the `artwork/` directory of its plugin. The shared `plugin_icon_badge.py` adds the badge at output time, modifies no source image, and never stacks a second badge on an already badged asset. A new first-party feature entry puts its unbadged image into the `artwork/` directory of the plugin and registers it in the `ASSETS` table of `generate_plugin_icons.py`.

The generation keeps the asset path and size, so the icon loading logic needs no change. The final scale, theme, and DPI display in the host still needs confirmation inside Cubism.
