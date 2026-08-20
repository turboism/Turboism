"""Reviewed production trust anchors for SDK API tier policy."""
from __future__ import annotations

from sdk_api_baseline_common import BaselineError
from sdk_api_tiers_common import Digest

PRODUCTION_TIER_POLICY_V3_SHA256 = "88eec47764329b21e399d85d53832f13430af0613092b501aecb5522629dc467"
PRODUCTION_TIER_POLICY_V3_LINE_COUNT = 56
PRODUCTION_INITIAL_PREVIEW_LEDGER_V3_SHA256 = "21d2d371881bf46201e1e028272909f99ea86cfe1e1cf3f0d147806db83c7f95"
PRODUCTION_INITIAL_PREVIEW_LEDGER_V3_LINE_COUNT = 1158

PRODUCTION_TIER_POLICY_V4_SHA256 = "0ca4b03c2d7c258930f3b8feeb1ec13efa0572bda674286e45baf7055fb03455"
PRODUCTION_TIER_POLICY_V4_LINE_COUNT = 56
PRODUCTION_INITIAL_PREVIEW_LEDGER_V4_SHA256 = "574b863720493ab5986a246f4883a83d4933085fefa0bafa12cdc3f3c57fcb0a"
PRODUCTION_INITIAL_PREVIEW_LEDGER_V4_LINE_COUNT = 1268
PRODUCTION_INITIAL_PREVIEW_LEDGER_V3_ROOT_COUNT = 274
PRODUCTION_INITIAL_PREVIEW_LEDGER_V4_ROOT_COUNT = 299

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
