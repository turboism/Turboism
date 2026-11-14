#!/usr/bin/env python3
"""Stable command-line façade for the SDK API baseline gate."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

# The tool runs both directly and through Gradle; add the scripts root so the shared
# turboism_dev helpers resolve in both cases.
_SCRIPTS_DIR = str(Path(__file__).resolve().parents[1])
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

from sdk_api_baseline import BaselineError, canonical_dump
from sdk_api_baseline_cli_commands import capture, verify, verify_current
from sdk_api_baseline_cli_io import die, write_output
from sdk_api_baseline_cli_parser import build_parser


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
    elif args.command == "verify-current":
        verify_current(args)
    else:
        verify(args, args.command == "verify-exact")


if __name__ == "__main__":
    main()
