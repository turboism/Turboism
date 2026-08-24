"""Stable compatibility façade for deterministic SDK API canonicalization."""
from __future__ import annotations

from sdk_api_baseline_common import (
    GENERATOR_VERSION,
    HEADER,
    SCHEMA_VERSION,
    BaselineError,
    sha256_bytes,
)
from sdk_api_baseline_identity import canonical_identity
from sdk_api_baseline_records import canonical_dump, canonical_records
