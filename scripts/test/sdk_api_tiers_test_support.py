"""Shared assertion and report helpers for SDK API tier scenarios."""
from __future__ import annotations

import json
from pathlib import Path


def fail(message: str) -> None:
    raise AssertionError(f"SDK API tier selftest: {message}")


def report_tiers(path: Path) -> dict[str, str]:
    return {
        entry["identity"]: entry["tier"]
        for entry in json.loads(path.read_text(encoding="utf-8"))["entries"]
    }
