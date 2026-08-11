#!/usr/bin/env python3
"""Stable command-line façade for the SDK API baseline gate."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

from sdk_api_baseline import BaselineError, GENERATOR_VERSION, HEADER, SCHEMA_VERSION, canonical_dump, canonical_identity, canonical_records_for_tiers, sha256_bytes
from sdk_api_baseline_cli_commands import capture, verify
from sdk_api_baseline_cli_io import COMMIT_RE, FORMAT, SHA_RE, die, load_baseline, write_output, write_tier_report as _write_tier_report
from sdk_api_baseline_cli_parser import build_parser
from sdk_api_tiers import canonical_json, verify_tier_compatible


def tier_report(path: Path, records: list[str], tiers: dict[str, str]) -> None:
    _write_tier_report(path, records, tiers)


def main() -> None:
    args = build_parser().parse_args()
    try:
        _run(args)
    except BaselineError as exc:
        die(str(exc))


def _run(args: argparse.Namespace) -> None:
    if args.command == "dump":
        dump, _sha, _size = canonical_dump(args.input, args.package_prefix)
        write_output(args.output, dump)
    elif args.command == "capture":
        capture(args)
    else:
        verify(args, args.command == "verify-exact")


if __name__ == "__main__":
    main()
