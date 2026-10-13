#!/usr/bin/env python3
"""Resolve verified candidate identity or promote it inside the protected workflow."""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from turboism_release.promotion import (
    GitHub,
    REPOSITORY,
    ReleaseError,
    build_plugin_plan,
    prepare_candidate_tag,
    promote,
    require,
    validate_run,
)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    local = commands.add_parser("candidate-tag", help="create a local-only tag after payload verification")
    local.add_argument("--source-root", type=Path, required=True)
    local.add_argument("--source-sha", required=True)
    plan_cmd = commands.add_parser("plugin-plan", help="validate market bundle and write a canonical plugin verification plan")
    plan_cmd.add_argument("--source-root", type=Path, required=True)
    plan_cmd.add_argument("--source-sha", required=True)
    plan_cmd.add_argument("--market-dir", type=Path, required=True)
    plan_cmd.add_argument("--output", type=Path, required=True)
    for name in ("resolve", "promote"):
        command = commands.add_parser(name)
        command.add_argument("--run-id", required=True)
        command.add_argument("--source-sha", required=True)
        command.add_argument("--attempt", default="")
        if name == "promote":
            command.add_argument("--source-root", type=Path, required=True)
            command.add_argument("--bundle-root", type=Path, required=True)
            command.add_argument("--confirmation", required=True)
            command.add_argument("--verify-only", action="store_true",
                                 help="verify candidate and remote preconditions without creating tags or releases")
    args = parser.parse_args(argv)
    try:
        if args.command == "candidate-tag":
            print(prepare_candidate_tag(args.source_root, args.source_sha))
            return 0
        if args.command == "plugin-plan":
            plan = build_plugin_plan(args.source_root, args.source_sha, args.market_dir, args.output)
            print(f"plan_id={plan['planId']}")
            return 0
        import re
        require(re.fullmatch(r"[1-9][0-9]{0,19}", args.run_id), "invalid candidate run id")
        attempt = args.attempt or None
        github = GitHub()
        if args.command == "resolve":
            identity = validate_run(github.api(f"actions/runs/{args.run_id}"), args.run_id, args.source_sha, attempt)
            for key, value in identity.items():
                print(f"{key}={value}")
        else:
            require(os.environ.get("GITHUB_ACTIONS") == "true"
                    and os.environ.get("GITHUB_REPOSITORY", "").lower() == REPOSITORY.lower()
                    and os.environ.get("GITHUB_REF") == "refs/heads/main"
                    and os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch",
                    "promotion must run in the protected main GitHub workflow; dispatch it instead")
            tag = promote(github, args.source_root, args.bundle_root, args.run_id, args.source_sha,
                          attempt, args.confirmation, verify_only=args.verify_only)
            if args.verify_only:
                print(f"Verified candidate for {tag}")
            elif tag == "plugins":
                print("Verified plugin-only release candidate")
            else:
                print(f"Published https://github.com/{REPOSITORY}/releases/tag/{tag}")
        return 0
    except (ReleaseError, OSError, ValueError) as failure:
        print(f"Release promotion refused: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
