#!/usr/bin/env python3
"""Render a canonical turboism-typed-config-v1 document for the WebDAV backup
plugin (config id ``backup.webdav``, document ``backup/webdav.cfg``).

The on-disk format is defined by TypedConfigDocumentStore#encode: a header,
``schemaVersion``, ``revision``, ``count``, then ``count`` lines of
``base64url(key):base64url(value)`` with keys sorted by their decoded name and
a trailing newline. Values are the codec-encoded forms: booleans as
``true``/``false``, integers as decimal, strings verbatim.

Usage: render-webdav-config.py <output-file> <port>
"""

import base64
import sys
from pathlib import Path


def b64(value: str) -> str:
    return base64.urlsafe_b64encode(value.encode("utf-8")).decode("ascii").rstrip("=")


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: render-webdav-config.py <output-file> <port>", file=sys.stderr)
        return 2
    output = Path(sys.argv[1])
    port = int(sys.argv[2])
    if not 1 <= port <= 65535:
        print("port out of range", file=sys.stderr)
        return 2
    entries = {
        "enabled": "true",
        "password": "probe-pass",
        "remote.path": "/turboism-backup",
        "remote.trigger": "SAVE_TRIGGERED",
        "retry.base-delay-ms": "50",
        "retry.max": "3",
        "timeout.seconds": "30",
        "url": f"http://127.0.0.1:{port}",
        "username": "probe-user",
        "verify.tls": "true",
    }
    keys = sorted(entries)
    lines = [
        "turboism-typed-config-v1",
        "schemaVersion=1",
        "revision=0",
        f"count={len(keys)}",
        *(f"{b64(key)}:{b64(entries[key])}" for key in keys),
        "",
    ]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
