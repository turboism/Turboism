"""Reviewed production trust anchors for SDK API tier policy."""
from __future__ import annotations

from sdk_api_baseline_common import BaselineError
from sdk_api_tiers_common import Digest

PRODUCTION_TIER_POLICY_V3_SHA256 = "88eec47764329b21e399d85d53832f13430af0613092b501aecb5522629dc467"
PRODUCTION_TIER_POLICY_V3_LINE_COUNT = 56
PRODUCTION_INITIAL_PREVIEW_LEDGER_V3_SHA256 = "21d2d371881bf46201e1e028272909f99ea86cfe1e1cf3f0d147806db83c7f95"
PRODUCTION_INITIAL_PREVIEW_LEDGER_V3_LINE_COUNT = 1158

PRODUCTION_TIER_POLICY_V4_SHA256 = "caddfb6c95e047b250a2f0b2db1886a89ddc34960ec870fb78d0f9042528e692"
PRODUCTION_TIER_POLICY_V4_LINE_COUNT = 56
PRODUCTION_INITIAL_PREVIEW_LEDGER_V4_SHA256 = "cd74e428feb2932298514ef0f35db82eeb4bca06817a61e1034bc0bb1ea2219d"
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
