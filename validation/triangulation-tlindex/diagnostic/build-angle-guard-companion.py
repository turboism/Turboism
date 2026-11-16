"""T057 diagnostic companion builder; retired, never a production build.

The companion rewrote one expected builder SHA-256 pin so an instrumented
onReturn observer body could be admitted. Production now admits the builder by
live shape, so no pin table exists to rewrite and this variant cannot be built.

The script keeps its argument contract so an archived invocation fails with a
clear reason instead of an opaque assertion. Its recorded evidence stays valid
for the revision that produced it; no current claim depends on this script.
"""
import sys

RETIREMENT_REASON = (
    "T057 companion is retired: production admits the local builder by live shape, "
    "so the exact onReturn observation variant has no dependency pin table to replace.")


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: build-angle-guard-companion.py <frozen-agent.jar> <new-output-directory>")
    raise SystemExit(RETIREMENT_REASON)


if __name__ == "__main__":
    main()
