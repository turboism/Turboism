"""Compatibility façade for the B2 SDK stable/preview tier verifier."""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from sdk_api_baseline_common import BaselineError
from sdk_api_baseline_identity import canonical_identity, split_canonical_record
from sdk_api_tiers_classify import (
    historical_identity_is_promoted,
    identity_is_owned_by_root,
    owner_from_identity,
    package_from_owner,
    package_is_owned_by_promotions,
    record_tiers,
    root_owned_records,
)
from sdk_api_tiers_common import (
    COMMIT_RE,
    GENERATOR_VERSION,
    INITIAL_PREVIEW_LEDGER_FORMAT,
    SCHEMA_VERSION,
    SHA_RE,
    TIER_POLICY_FORMAT,
    Digest,
    NewPreviewAdmission,
    canonical_json,
    canonical_record_digest,
    target_identity,
)
from sdk_api_tiers_policy import (
    INITIAL_PREVIEW_LEDGER_LINE_COUNT,
    INITIAL_PREVIEW_LEDGER_SHA256,
    load_initial_preview_ledger,
    load_initial_preview_ledger_for_test,
    load_tier_policy,
)
from sdk_api_tiers_trust import PRODUCTION_TIER_POLICY_LINE_COUNT as _PRODUCTION_TIER_POLICY_LINE_COUNT, PRODUCTION_TIER_POLICY_SHA256 as _PRODUCTION_TIER_POLICY_SHA256
from sdk_api_tiers_verify import verify_tier_compatible, verify_tier_compatible_for_test

INITIAL_PREVIEW_LEDGER_SHA256 = INITIAL_PREVIEW_LEDGER_SHA256
INITIAL_PREVIEW_LEDGER_LINE_COUNT = INITIAL_PREVIEW_LEDGER_LINE_COUNT
PRODUCTION_TIER_POLICY_SHA256 = _PRODUCTION_TIER_POLICY_SHA256
PRODUCTION_TIER_POLICY_LINE_COUNT = _PRODUCTION_TIER_POLICY_LINE_COUNT
