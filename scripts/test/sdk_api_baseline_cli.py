#!/usr/bin/env python3
"""Stable command-line façade for the SDK API baseline gate."""
from __future__ import annotations

import argparse

from sdk_api_baseline import BaselineError, canonical_dump
from sdk_api_baseline_cli_commands import capture, verify
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
    else:
        verify(args, args.command == "verify-exact")


if __name__ == "__main__":
    main()
