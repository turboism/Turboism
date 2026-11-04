#!/usr/bin/env python3
"""Generate a deterministic human-input baseline trace for the host probe.

The recorded-trace path (launcher -Workload record) captures a real person.
This script is the CI/reproducible baseline for the same replay engine: it
writes the exact TSV format consumed by dev.turboism.validation.modelupdate
.HumanTrace. Event pacing is absolute (nanoOffset), so replay does not depend
on how fast the generator runs.

Examples:
  python3 scripts/preview/generate-human-trace.py wheel \
      --notches 8 --interval-ms 80 --gap-ms 500 --out wheel.tsv
  python3 scripts/preview/generate-human-trace.py pan \
      --distance 240 --steps 24 --step-ms 16 --out pan.tsv
  python3 scripts/preview/generate-human-trace.py artmesh \
      --x 582 --y 413 --distance 120 --steps 24 --step-ms 16 --out artmesh.tsv
"""

from __future__ import annotations

import argparse
from pathlib import Path

CANVAS = "com.jogamp.opengl.awt.GLJPanel"
HEADER = [
    "# turboism-human-trace v1",
    "kind={kind}",
    "fixture=",
    "zoom.before=",
    "# kind\twhenMillis\tnanoOffset\tid\tx\ty\tabsX\tabsY\tscrollAmount"
    "\twheelRotation\tpreciseWheelRotation\tmodifiers\tbutton\tclickCount"
    "\tkeyCode\tkeyChar\tsourceClass",
]
MOUSE_PRESSED = 501
MOUSE_RELEASED = 502
MOUSE_MOVED = 503
MOUSE_CLICKED = 500
MOUSE_DRAGGED = 506
KEY_PRESSED = 401
KEY_RELEASED = 402
BUTTON1 = 1024


def emit(rows: list[str], kind: str, line: str) -> None:
    rows.append(line)


def mouse(rows: list[str], offset_ns: int, event_id: int, x: int, y: int,
          modifiers: int = 0, button: int = 0, clicks: int = 0) -> None:
    emit(rows, "mouse",
         f"M\t0\t{offset_ns}\t{event_id}\t{x}\t{y}\t0\t0\t0\t0\t0.0\t"
         f"{modifiers}\t{button}\t{clicks}\t0\t\t{CANVAS}")


def key(rows: list[str], offset_ns: int, event_id: int, code: int, char: str) -> None:
    emit(rows, "key",
         f"K\t0\t{offset_ns}\t{event_id}\t0\t0\t0\t0\t0\t0\t0.0\t0\t0\t0\t"
         f"{code}\t{char}\t{CANVAS}")


def wheel(rows: list[str], offset_ns: int, x: int, y: int, rotation: int) -> None:
    emit(rows, "wheel",
         f"W\t0\t{offset_ns}\t507\t{x}\t{y}\t0\t0\t3\t{rotation}\t{rotation}.0"
         f"\t0\t0\t0\t0\t\t{CANVAS}")


def wheel_trace(args: argparse.Namespace) -> list[str]:
    rows: list[str] = []
    offset = 0
    for burst in range(args.bursts):
        direction = 1 if burst % 2 == 0 else -1
        for _ in range(args.notches):
            wheel(rows, offset, args.x, args.y, direction)
            offset += args.interval_ms * 1_000_000
        offset += args.gap_ms * 1_000_000
    return rows


def pan_trace(args: argparse.Namespace) -> list[str]:
    rows: list[str] = []
    offset = 0
    mouse(rows, offset, MOUSE_MOVED, args.x, args.y)
    offset += 40_000_000
    key(rows, offset, KEY_PRESSED, 32, " ")
    offset += 40_000_000
    mouse(rows, offset, MOUSE_MOVED, args.x, args.y)
    offset += 40_000_000
    mouse(rows, offset, MOUSE_PRESSED, args.x, args.y, BUTTON1, 1, 1)
    offset += 40_000_000
    for step in range(1, args.steps + 1):
        fraction = step / args.steps
        mouse(rows, offset, MOUSE_DRAGGED,
              args.x + round(args.distance * fraction),
              args.y + round(args.distance * fraction / 2), BUTTON1)
        offset += args.step_ms * 1_000_000
    mouse(rows, offset, MOUSE_RELEASED,
          args.x + args.distance, args.y + args.distance // 2, 0, 1, 1)
    offset += 40_000_000
    key(rows, offset, KEY_RELEASED, 32, " ")
    return rows


def artmesh_trace(args: argparse.Namespace) -> list[str]:
    rows: list[str] = []
    offset = 0
    mouse(rows, offset, MOUSE_MOVED, args.x, args.y)
    offset += 40_000_000
    # Select the mesh first; keep the drag outside the platform double-click
    # interval so the host stays in object mode rather than point editing.
    mouse(rows, offset, MOUSE_PRESSED, args.x, args.y, BUTTON1, 1, 1)
    offset += 40_000_000
    mouse(rows, offset, MOUSE_RELEASED, args.x, args.y, 0, 1, 1)
    offset += 40_000_000
    mouse(rows, offset, MOUSE_CLICKED, args.x, args.y, 0, 1, 1)
    offset += args.select_hold_ms * 1_000_000
    mouse(rows, offset, MOUSE_MOVED, args.x, args.y)
    offset += 40_000_000
    mouse(rows, offset, MOUSE_PRESSED, args.x, args.y, BUTTON1, 1, 1)
    offset += 40_000_000
    for step in range(1, args.steps + 1):
        fraction = step / args.steps
        mouse(rows, offset, MOUSE_DRAGGED,
              args.x + round(args.distance * fraction),
              args.y + round(args.distance * fraction / 2), BUTTON1)
        offset += args.step_ms * 1_000_000
    mouse(rows, offset, MOUSE_RELEASED,
          args.x + args.distance, args.y + args.distance // 2, 0, 1, 1)
    offset += 40_000_000
    mouse(rows, offset, MOUSE_CLICKED,
          args.x + args.distance, args.y + args.distance // 2, 0, 1, 1)
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=["wheel", "pan", "artmesh"])
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--x", type=int, default=447)
    parser.add_argument("--y", type=int, default=410)
    parser.add_argument("--notches", type=int, default=8)
    parser.add_argument("--interval-ms", type=int, default=80)
    parser.add_argument("--gap-ms", type=int, default=500)
    parser.add_argument("--bursts", type=int, default=2)
    parser.add_argument("--distance", type=int, default=240)
    parser.add_argument("--steps", type=int, default=24)
    parser.add_argument("--step-ms", type=int, default=16)
    parser.add_argument("--select-hold-ms", type=int, default=800)
    args = parser.parse_args()

    if args.kind == "wheel":
        rows = wheel_trace(args)
    elif args.kind == "pan":
        rows = pan_trace(args)
    else:
        rows = artmesh_trace(args)
    if not rows:
        raise SystemExit("generated no events")

    text = "\n".join(HEADER).format(kind=args.kind) + "\n" + "\n".join(rows) + "\n"
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(text, encoding="utf-8")
    print(f"{args.out}: {len(rows)} events, kind={args.kind}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
