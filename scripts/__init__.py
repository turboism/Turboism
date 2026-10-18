"""Turboism tooling scripts.

Importing ``scripts`` as a package (``python3 -m unittest scripts.test.<module>``
from the repository root, as Gradle and CI do) puts this directory on
``sys.path`` so sibling modules and ``turboism_dev`` resolve exactly as they do
when a script is run directly as ``python3 scripts/<script>.py``.
"""
from __future__ import annotations

import sys
from pathlib import Path

_SCRIPTS_DIR = str(Path(__file__).resolve().parent)
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)
