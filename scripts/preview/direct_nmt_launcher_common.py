"""Shared official BAT constants and digest helper for direct-NMT diagnostic hooks."""
from __future__ import annotations

import hashlib

OFFICIAL_BAT_SHA = "4da7e34e346280cdce02527fc7746cfd563d449033e018b7f3af1205e7b2ca5b"
ORIGINAL_PROXY_SHA = "c9b924644749a5e3fc8131443a8ff162278791a7e64e8dd8756625a5a9e2b9e4"
NMT_PROXY_SUFFIX = (
    b"\r\nrem T100 task-local NMT diagnostic; official BAT is unchanged\r\n"
    b"set MAXMEMORY=-XX:MaxRAMPercentage=100 -XX:NativeMemoryTracking=summary\r\n"
)


def sha256_bytes(raw: bytes) -> str:
    """Return lowercase hexadecimal SHA-256 digest of raw bytes."""
    return hashlib.sha256(raw).hexdigest()
