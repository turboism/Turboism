"""Reviewed production trust anchors for SDK API tier policy."""
from __future__ import annotations

from sdk_api_baseline_common import BaselineError
from sdk_api_tiers_common import Digest

PRODUCTION_TIER_POLICY_V3_SHA256 = "88eec47764329b21e399d85d53832f13430af0613092b501aecb5522629dc467"
PRODUCTION_TIER_POLICY_V3_LINE_COUNT = 56
PRODUCTION_INITIAL_PREVIEW_LEDGER_V3_SHA256 = "21d2d371881bf46201e1e028272909f99ea86cfe1e1cf3f0d147806db83c7f95"
PRODUCTION_INITIAL_PREVIEW_LEDGER_V3_LINE_COUNT = 1158

PRODUCTION_TIER_POLICY_V4_SHA256 = "06e2168956be95e173494bc9becb78a2c761635a1b55bb49b6dc2d8491fb53fa"
PRODUCTION_TIER_POLICY_V4_LINE_COUNT = 56
PRODUCTION_INITIAL_PREVIEW_LEDGER_V4_SHA256 = "1312e0b1deac9f9f43bd0d5384c1dedd5b011f8c8b9e2245d21a18d35aebe1da"
PRODUCTION_INITIAL_PREVIEW_LEDGER_V4_LINE_COUNT = 1206
PRODUCTION_INITIAL_PREVIEW_LEDGER_V3_ROOT_COUNT = 274
PRODUCTION_INITIAL_PREVIEW_LEDGER_V4_ROOT_COUNT = 285

# Preserve the existing unversioned names for callers that verify the historical v3 policy.
PRODUCTION_TIER_POLICY_SHA256 = PRODUCTION_TIER_POLICY_V3_SHA256
PRODUCTION_TIER_POLICY_LINE_COUNT = PRODUCTION_TIER_POLICY_V3_LINE_COUNT


def production_tier_trust(version: str) -> tuple[Digest, Digest, int]:
    """Returns the reviewed policy Digest, ledger Digest, and the ledger's
    reviewed initial-preview root count for exactly that trust version."""
    if version == "v3":
        return (
            Digest(PRODUCTION_TIER_POLICY_V3_LINE_COUNT, PRODUCTION_TIER_POLICY_V3_SHA256),
            Digest(PRODUCTION_INITIAL_PREVIEW_LEDGER_V3_LINE_COUNT, PRODUCTION_INITIAL_PREVIEW_LEDGER_V3_SHA256),
            PRODUCTION_INITIAL_PREVIEW_LEDGER_V3_ROOT_COUNT,
        )
    if version == "v4":
        return (
            Digest(PRODUCTION_TIER_POLICY_V4_LINE_COUNT, PRODUCTION_TIER_POLICY_V4_SHA256),
            Digest(PRODUCTION_INITIAL_PREVIEW_LEDGER_V4_LINE_COUNT, PRODUCTION_INITIAL_PREVIEW_LEDGER_V4_SHA256),
            PRODUCTION_INITIAL_PREVIEW_LEDGER_V4_ROOT_COUNT,
        )
    raise BaselineError(f"unsupported production tier trust version: {version}")
