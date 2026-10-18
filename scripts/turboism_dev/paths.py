"""Repository and script path utilities."""
from __future__ import annotations

from pathlib import Path

# Canonical repository root resolved relative to this module's location:
# <repo_root>/scripts/turboism_dev/paths.py -> parents[2] is <repo_root>
REPO_ROOT: Path = Path(__file__).resolve().parents[2]
SCRIPTS_DIR: Path = REPO_ROOT / "scripts"


def repo_root(anchor: Path | str | None = None) -> Path:
    """Return the Turboism repository root as a resolved Path.

    If anchor is provided, resolves relative to the given file or directory
    by searching upward for the repository root markers. Otherwise returns
    the canonical REPO_ROOT derived from this package location.
    """
    if anchor is not None:
        path = Path(anchor).resolve()
        candidate = path if path.is_dir() else path.parent
        for current in [candidate, *candidate.parents]:
            if (current / "gradle/common-java.gradle.kts").is_file():
                return current
            if (current / "settings.gradle.kts").is_file() and (current / "scripts").is_dir():
                return current
    return REPO_ROOT


def scripts_dir(root: Path | str | None = None) -> Path:
    """Return the scripts/ directory Path."""
    return repo_root(root) / "scripts"
