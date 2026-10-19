#!/usr/bin/env python3
"""Regenerate badged first-party plugin icons from checked-in, unbadged artwork (Pillow)."""

from pathlib import Path
import runpy

from PIL import Image

from plugin_icon_badge import export_badge_assets, with_plugin_badge


ROOT = Path(__file__).resolve().parents[2]
ASSETS = {
    "boundingbox-warp-mirror": ("META-INF/turboism/icons", ("mirror.png", "mirror_hover.png")),
    "warp-deformer-alt-symmetry": ("META-INF/turboism/icons", ("Vertical.png", "Horizon.png", "Off.png")),
    "history-panel": ("icons", ("history.png",)),
}


def main():
    export_badge_assets()
    runpy.run_path(str(ROOT / "plugins/selection-brush/artwork/generate_icons.py"), run_name="__main__")
    for plugin, (resource_dir, names) in ASSETS.items():
        plugin_dir = ROOT / "plugins" / plugin
        for name in names:
            with Image.open(plugin_dir / "artwork" / name) as source:
                image = with_plugin_badge(source)
            target = plugin_dir / "src/main/resources" / resource_dir / name
            image.save(target, optimize=True)
            print(target.relative_to(ROOT))


if __name__ == "__main__":
    main()
