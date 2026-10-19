"""Deterministic Turboism release planning and orchestration."""

import sys
from pathlib import Path

# Release scripts loaded by path (importlib) import the shared turboism_dev package,
# which lives in scripts/ and is not on sys.path when an entry point runs from scripts/release.
_SCRIPTS_DIR = str(Path(__file__).resolve().parents[2])
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

from .contracts import ReleaseError, canonical_bytes, plan_id, read_document, write_document

__all__ = [
    "ReleaseError",
    "canonical_bytes",
    "plan_id",
    "read_document",
    "write_document",
]
