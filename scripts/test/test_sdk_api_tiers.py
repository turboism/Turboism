#!/usr/bin/env python3
"""Black-box mutation matrix for B2 SDK stable/preview API tiers.

The matrix intentionally exercises the command-line gate with only reviewed
inputs. In particular, it does not mutate the verifier's embedded initial
preview-ledger trust anchor.
"""
from __future__ import annotations

import json
import subprocess
import tempfile
from pathlib import Path

from sdk_api_tiers_test_commands import command
from sdk_api_tiers_test_constants import COMMIT, PREFIX
from sdk_api_tiers_test_context import TierTestContext
from sdk_api_tiers_test_fixtures import compile_fixture
from sdk_api_tiers_test_policy import make_policy, write_policy
from sdk_api_tiers_test_scenario_additions import run as run_additions
from sdk_api_tiers_test_scenario_baseline import run as run_baseline
from sdk_api_tiers_test_scenario_promotions import run as run_promotions
from sdk_api_tiers_test_scenario_validation import run as run_validation


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="turboism-sdk-api-tiers-") as temporary:
        context = create_context(Path(temporary))
        run_baseline(context)
        run_additions(context)
        run_promotions(context)
        run_validation(context)
    print("SDK API tier selftest passed.")


def create_context(root: Path) -> TierTestContext:
    historical = compile_fixture(root, "historical", False, "historical")
    current = compile_fixture(root, "current", True, "normal")
    baseline_path = root / "baseline.json"
    command(
        "capture", "--input", str(historical), "--package-prefix", PREFIX,
        "--role", "pre-phase", "--commit", COMMIT, "--output", str(baseline_path),
    )
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    policy = make_policy(baseline, current, historical)
    policy_path = root / "policy.json"
    write_policy(policy_path, policy)
    return TierTestContext(root, historical, current, baseline_path, baseline, policy, policy_path)


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, subprocess.CalledProcessError, OSError, json.JSONDecodeError) as exc:
        print(exc)
        raise SystemExit(1) from exc
