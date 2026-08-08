"""Mutable state shared by ordered SDK API tier scenarios."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass
class TierTestContext:
    root: Path
    historical: Path
    current: Path
    baseline_path: Path
    baseline: dict[str, Any]
    policy: dict[str, Any]
    policy_path: Path
    state: dict[str, Any] = field(default_factory=dict)
