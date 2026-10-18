"""JSON manifest and sidecar serialization for compatibility matrix."""
from __future__ import annotations

import json
from pathlib import Path

def render_market_plugins(
    root: Path, release_plugins_path: Path, matrix_plugins: list[dict]
) -> str:
    by_dir = {plugin["directory"]: plugin for plugin in matrix_plugins}
    projects = [
        line.strip()
        for line in release_plugins_path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#") and line.strip() != ":plugins:core"
    ]
    entries = []
    for project in sorted(projects):
        module = project.removeprefix(":plugins:")
        plugin_info = by_dir.get(module)
        if plugin_info is None:
            raise ValueError(f"release plugin {project} is missing from compatibility matrix")
        descriptor_path = (
            root / "plugins" / module / "src/main/resources/META-INF/turboism/plugin.json"
        )
        descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
        requires_cubism = bool(descriptor.get("environment", {}).get("requiresCubism"))
        cubism_versions = list(plugin_info["supportedVersions"]) if requires_cubism else []
        entries.append(
            {
                "project": project,
                "channel": "stable",
                "cubismVersions": cubism_versions,
                "repository": "https://github.com/turboism/Turboism",
                "support": "https://github.com/turboism/Turboism/issues",
            }
        )
    document = {
        "_comment": (
            "Git-reviewed manifest selecting first-party :plugins:* modules eligible for "
            "independent Plugin Directory publication. Derived from packaging/release-plugins.txt "
            "and each plugin's cubism-versions.json; validated by scripts/release/prepare-market-release.py "
            "(project/channel/cubismVersions/repository/support, ASCII-sorted, unique)."
        ),
        "schemaVersion": 1,
        "plugins": entries,
    }
    return json.dumps(document, ensure_ascii=False, indent=2) + "\n"


