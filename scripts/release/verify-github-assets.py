#!/usr/bin/env python3
"""Compare a GitHub Release asset list to reviewed immutable identities."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SHA256 = re.compile(r"^[0-9a-f]{64}$")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--expected-json", required=True)
    root.add_argument("--release-json", required=True)
    root.add_argument("--allow-missing", action="store_true")
    root.add_argument("--missing-output", type=Path)
    root.add_argument("--extra-allowed", action="append", default=[])
    return root


def main(argv=None) -> int:
    args = parser().parse_args(argv)
    try:
        expected = json.loads(args.expected_json)
        release = json.loads(args.release_json)
        if not isinstance(expected, dict) or not isinstance(release, dict):
            raise ValueError("expected and release JSON must be objects")
        assets = release.get("assets")
        if not isinstance(assets, list):
            raise ValueError("release assets must be an array")
        remote = {}
        for asset in assets:
            if not isinstance(asset, dict) or not isinstance(asset.get("name"), str):
                raise ValueError("release contains a malformed asset")
            name = asset["name"]
            if name in remote:
                raise ValueError(f"release contains duplicate asset {name}")
            digest = asset.get("digest")
            digest = digest.removeprefix("sha256:") if isinstance(digest, str) else None
            remote[name] = {"size": asset.get("size"), "sha256": digest}
        allowed = set(expected) | set(args.extra_allowed)
        extra = sorted(set(remote) - allowed)
        if extra:
            raise ValueError(f"release contains unexpected immutable assets: {extra}")
        missing = []
        for name, identity in expected.items():
            if not isinstance(name, str) or not isinstance(identity, dict):
                raise ValueError("expected asset map is malformed")
            size = identity.get("size")
            digest = identity.get("sha256")
            if not isinstance(size, int) or size < 1 or not isinstance(digest, str) or not SHA256.fullmatch(digest):
                raise ValueError(f"expected identity for {name} is invalid")
            actual = remote.get(name)
            if actual is None:
                missing.append(name)
            elif actual != {"size": size, "sha256": digest}:
                raise ValueError(f"release asset {name} differs for the same version")
        if missing and not args.allow_missing:
            raise ValueError(f"release is missing immutable assets: {missing}")
        if args.missing_output:
            args.missing_output.resolve().parent.mkdir(parents=True, exist_ok=True)
            args.missing_output.resolve().write_text("".join(f"{name}\n" for name in sorted(missing)), encoding="utf-8")
        return 0
    except (json.JSONDecodeError, OSError, ValueError) as failure:
        print(f"GitHub Release verification failed: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
