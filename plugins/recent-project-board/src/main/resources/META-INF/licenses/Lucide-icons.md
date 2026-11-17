# Bundled icon sources

The board header and card controls use [Lucide v0.468.0](https://github.com/lucide-icons/lucide/tree/0.468.0/icons).
Bundled icons: `plus`, `clapperboard`, `layout-grid` and `trash-2`.

Lucide is licensed under the ISC license, including the upstream Feather notice. The full text is
in [Lucide-LICENSE.txt](Lucide-LICENSE.txt).

The shipped SVG files keep the upstream geometry and drop only the upstream `class` attribute and
license comment. Each PNG is an 80×80 black alpha mask, so the runtime tints the glyph with the
active theme foreground and a control never needs its own colour.

| Glyph | Control |
|---|---|
| `plus` | Header New Model project |
| `clapperboard` | Header New Animation project |
| `layout-grid` | Header layout cycle |
| `trash-2` | Per-card preview deletion |

Each PNG is drawn at 20 logical pixels by the runtime's theme-masked icon node.

`plus` and `clapperboard` are rasterized from their committed SVG, run from the plugin resource
directory:

```sh
magick -background none -density 320 plus.svg -channel RGB -evaluate set 0 +channel PNG32:plus.png
magick -background none -density 320 clapperboard.svg -channel RGB -evaluate set 0 +channel PNG32:clapperboard.png
```

`layout-grid` and `trash-2` ship as committed masks and stay unchanged by this derivation.
