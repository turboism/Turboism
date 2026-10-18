"""Bootstrap Turboism tooling path so turboism_dev can be imported reliably."""
from __future__ import annotations

import sys
from pathlib import Path

SCRIPTS_DIR: Path = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from turboism_dev.paths import REPO_ROOT, repo_root  # noqa: E402

__all__ = ["SCRIPTS_DIR", "REPO_ROOT", "repo_root"]
