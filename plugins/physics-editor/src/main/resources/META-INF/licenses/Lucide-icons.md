# Bundled icon sources

The GIF control strip uses [Lucide v0.468.0](https://github.com/lucide-icons/lucide/tree/0.468.0/icons).
Bundled icons: `circle`, `pause`, `play`, `square` and `x`.

Lucide is licensed under the ISC license, including the upstream Feather notice. The full text
is in [Lucide-LICENSE.txt](Lucide-LICENSE.txt).

The shipped SVG files stay byte-identical to upstream. Each PNG fills the upstream stroke geometry
with one reviewed control colour, so a control can signal meaning by colour as well as by shape.

| Glyph | Control | Colour |
|---|---|---|
| `circle` | Record | `#2FA36B` |
| `pause` | Pause | `#F5A524` |
| `play` | Resume | `#2FA36B` |
| `square` | Stop and export | `#E5484D` |
| `x` | Discard | `#8B8D98` |

Record and resume share one colour because they are one control.

Each PNG is an 80×80 glyph drawn at 20 logical pixels by the runtime's colour icon node. A disabled
control paints the same glyph at 35% opacity, so its size never changes.

Derivation, run from the repository root:

```sh
magick -background none -density 320 <name>.svg <name>-raw.png
```

The render step fills the stroke geometry with the control colour. `build/acceptance/070/render-lucide-masks.py`
performs the rasterization and the colouring.
