"""Argument parser construction for the SDK API baseline CLI."""
from __future__ import annotations

import argparse
from pathlib import Path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    _add_dump_parser(sub)
    _add_capture_parser(sub)
    _add_verify_parsers(sub)
    return parser


def _add_dump_parser(sub) -> None:
    parser = sub.add_parser("dump")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--package-prefix")


def _add_capture_parser(sub) -> None:
    parser = sub.add_parser("capture")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--package-prefix")
    parser.add_argument("--role", choices=("pre-phase", "exact"), required=True)
    parser.add_argument("--commit", required=True)


def _add_verify_parsers(sub) -> None:
    for command in ("verify-compatible", "verify-exact"):
        parser = sub.add_parser(command)
        _add_verify_arguments(parser)


def _add_verify_arguments(parser) -> None:
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--reference-input", required=True, type=Path)
    parser.add_argument(
        "--reference-binding",
        choices=("artifact", "canonical"),
        default="artifact",
        help="bind the reviewed reference by exact artifact bytes or canonical API dump",
    )
    parser.add_argument("--package-prefix")
    parser.add_argument("--expected-commit")
    parser.add_argument("--tier-policy", type=Path)
    parser.add_argument("--initial-preview-ledger", type=Path)
    parser.add_argument("--tier-trust-version", choices=("v3", "v4", "v5"), default="v3")
    parser.add_argument("--tier-report", type=Path)
