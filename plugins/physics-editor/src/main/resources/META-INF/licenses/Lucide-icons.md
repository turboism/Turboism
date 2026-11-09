# Bundled icon sources

The GIF control strip uses [Lucide v0.468.0](https://github.com/lucide-icons/lucide/tree/0.468.0/icons).
Bundled icons: `circle`, `pause`, `play`, `square` and `x`.

Lucide is licensed under the ISC license, including the upstream Feather notice. The full text
is in [Lucide-LICENSE.txt](Lucide-LICENSE.txt).

The shipped SVG files stay byte-identical to upstream. Two PNG masks are derived from each SVG:

| Mask | Purpose |
|---|---|
| `<name>.png` | Solid variant: the upstream stroke geometry with a filled interior |
| `<name>-dim.png` | Same silhouette at 45% alpha, for a disabled control |

Each PNG is an 80×80 white alpha mask, so the runtime tints it with the active theme foreground
and renders it at 20 logical pixels. This is the same mask format the `recent-project-board` and
`acp` plugins ship.

Derivation, run from the repository root:

```sh
magick -background none -density 320 <name>.svg <name>-raw.png
```

Filling the upstream stroke geometry gives the solid mask; scaling its alpha to 45% gives the
dimmed mask. `build/acceptance/070/render-lucide-masks.py` performs both steps.
