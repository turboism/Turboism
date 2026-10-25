"""Full compatibility matrix generation pipeline."""
from __future__ import annotations

import json
from pathlib import Path

from .constants import DOC_SUFFIX, DOCS_DIR, MATRIX_JSON, README_HEADINGS
from .model import build_matrix
from .readme import update_readme
from .render_json import render_market_plugins
from .render_markdown import render_capabilities_doc, render_plugins_doc

def render_all(root: Path) -> dict[str, str]:
    matrix = build_matrix(root)
    outputs: dict[str, str] = {
        str(MATRIX_JSON): json.dumps(matrix, ensure_ascii=False, indent=1) + "\n"
    }
    for locale in ("en", "zh", "ja", "ko"):
        suffix = DOC_SUFFIX[locale]
        outputs[str(DOCS_DIR / f"plugins{suffix}.md")] = render_plugins_doc(
            matrix, locale
        )
        outputs[str(DOCS_DIR / f"capabilities{suffix}.md")] = (
            render_capabilities_doc(matrix, locale)
        )
    for plugin in matrix["plugins"]:
        module = root / "plugins" / plugin["directory"]
        outputs[
            str(
                module.relative_to(root)
                / "src/main/resources/META-INF/turboism/cubism-versions.json"
            )
        ] = json.dumps(
            {
                "format": "turboism.plugin-cubism-versions/v1",
                "plugin": plugin["id"],
                "supportedVersions": plugin["supportedVersions"],
                "fullFeatureVersions": plugin["fullFeatureVersions"],
            },
            ensure_ascii=False,
            indent=1,
        ) + "\n"
        for readme_name in README_HEADINGS:
            path = module / readme_name
            if not path.is_file():
                continue
            outputs[str(path.relative_to(root))] = update_readme(
                path.read_text(encoding="utf-8"),
                plugin,
                readme_name,
                matrix["reviewedVersions"],
            )
    market_plugins_path = root / "packaging/market-plugins.txt"
    release_plugins_path = root / "packaging/release-plugins.txt"
    market_source_path = (
        market_plugins_path if market_plugins_path.is_file() else release_plugins_path
    )
    if market_source_path.is_file():
        outputs["packaging/market-plugins.json"] = render_market_plugins(
            root, market_source_path, matrix["plugins"]
        )
    return outputs
