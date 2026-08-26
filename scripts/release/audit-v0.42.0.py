#!/usr/bin/env python3
"""Read-only audit of the immutable Turboism v0.42.0 GitHub Release."""
from __future__ import annotations

import json
import subprocess
import sys


REPOSITORY = "turboism/Turboism"
TAG = "v0.42.0"
EXPECTED = {
    "turboism-0.42.0-full.zip": (77303399, "583a36b2ad3a0c5196681fd914c58dadaab74b9124492977d7d35b3f2f2df160"),
    "turboism-0.42.0-full.zip.sha256": (91, "72a88b26e9954b0fc017ce1e083438e4ff1efb3ae2e48542ecd99129441f9f64"),
    "turboism-0.42.0-lite.zip": (76667834, "33462b88ed885f2d5505e909d1ba388ba44789da0bac0a94f55600ba73c97648"),
    "turboism-0.42.0-lite.zip.sha256": (91, "cb0a42d40b16a7281811ea4b9d04e9b709a856b3d9757a1ac6a11efe543c6048"),
    "TurboismInstaller-0.42.0.exe": (74116438, "33c9db4c3ee54c6389f813004925ea5cb70510b6ea1f068038a057a065b5e762"),
    "TurboismInstaller-0.42.0.exe.sha256": (95, "d6ddf260a793bc937a884fd126c9b0c2a182b75ff3c8e900ad80c86c40bc57cd"),
    "TurboismInstaller-0.42.0.jar": (80412994, "5b43ab0c1a4a78da8bd8081671b156027ced58359b9bf938aadcf516a0d29109"),
    "TurboismInstaller-0.42.0.jar.sha256": (95, "2c30240e809c49df003ff108e890e7f6bcd58d933af79dce1f9d0a74547a20ed"),
}


def audit() -> None:
    completed = subprocess.run(
        ["gh", "release", "view", TAG, "--repo", REPOSITORY,
         "--json", "assets,tagName,isDraft,isPrerelease,url"],
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        raise ValueError(completed.stderr.strip() or "GitHub release query failed")
    document = json.loads(completed.stdout)
    if document.get("tagName") != TAG:
        raise ValueError(f"release tag is not {TAG}")
    if document.get("isDraft") is not False or document.get("isPrerelease") is not False:
        raise ValueError("v0.42.0 must remain a published stable release")
    assets = document.get("assets")
    if not isinstance(assets, list):
        raise ValueError("release assets are unavailable")
    by_name = {asset.get("name"): asset for asset in assets}
    if set(by_name) != set(EXPECTED):
        raise ValueError(
            f"v0.42.0 asset names changed: expected {sorted(EXPECTED)}, got {sorted(by_name)}"
        )
    for name, (size, digest) in EXPECTED.items():
        asset = by_name[name]
        if asset.get("size") != size:
            raise ValueError(f"{name}: expected {size} bytes, got {asset.get('size')}")
        if asset.get("digest") != f"sha256:{digest}":
            raise ValueError(f"{name}: published SHA-256 changed")
    print(f"immutable release audit passed: {document.get('url')}")
    for name in sorted(EXPECTED):
        print(f"{name}: {EXPECTED[name][1]}")


def main() -> int:
    try:
        audit()
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"v0.42.0 audit failed: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
