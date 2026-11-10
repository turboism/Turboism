"""JSON manifest and sidecar serialization for compatibility matrix."""
from __future__ import annotations

import json
from pathlib import Path

MANIFEST_RELATIVE_PATH = "packaging/market-plugins.json"
APPROVED = "approved"
WITHHELD = "withheld"


def _existing_publication(root: Path) -> dict[str, str]:
    """Read per-plugin publication decisions from the committed manifest.

    Regeneration must never approve a plugin on its own: a developer adds the plugin to the
    roster, and a reviewer flips the decision in a separate commit.
    """
    path = root / MANIFEST_RELATIVE_PATH
    if not path.is_file():
        return {}
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    decisions = {}
    for entry in document.get("plugins", []):
        if isinstance(entry, dict) and isinstance(entry.get("project"), str):
            decisions[entry["project"]] = entry.get("publication", WITHHELD)
    return decisions


def render_market_plugins(
    root: Path, roster_plugins_path: Path, matrix_plugins: list[dict]
) -> str:
    by_dir = {plugin["directory"]: plugin for plugin in matrix_plugins}
    decisions = _existing_publication(root)
    projects = [
        line.strip()
        for line in roster_plugins_path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#") and line.strip() != ":plugins:core"
    ]
    entries = []
    for project in sorted(projects):
        module = project.removeprefix(":plugins:")
        plugin_info = by_dir.get(module)
        if plugin_info is None:
            raise ValueError(f"plugin {project} in {roster_plugins_path.name} is missing from compatibility matrix")
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
                "publication": decisions.get(project, WITHHELD),
                "cubismVersions": cubism_versions,
                "repository": "https://github.com/turboism/Turboism",
                "support": "https://github.com/turboism/Turboism/issues",
            }
        )
    document = {
        "_comment": (
            "Git-reviewed manifest selecting first-party :plugins:* modules eligible for "
            "independent Plugin Directory publication. Derived from packaging/market-plugins.txt "
            "(or packaging/release-plugins.txt) and each plugin's cubism-versions.json; "
            "validated by scripts/release/prepare-market-release.py "
            "(project/channel/publication/cubismVersions/repository/support, ASCII-sorted, unique). "
            "New roster entries default to publication 'withheld'; a reviewer must set "
            "'approved' before the plugin can reach the Plugin Directory."
        ),
        "schemaVersion": 1,
        "plugins": entries,
    }
    return json.dumps(document, ensure_ascii=False, indent=2) + "\n"
