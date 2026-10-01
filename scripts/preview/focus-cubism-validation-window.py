#!/usr/bin/env python3
"""Task-owned niri focus, coordinate receipts and selection-brush window capture."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time


def niri_json(niri: str, kind: str):
    result = subprocess.run([niri, "msg", "-j", kind], check=True, capture_output=True,
                            text=True, timeout=5)
    return json.loads(result.stdout)


def task_window(windows: list[dict], run_id: str) -> dict | None:
    matches = [window for window in windows if run_id + ".cmo3" in (window.get("title") or "")]
    if len(matches) > 1:
        raise ValueError("ambiguous task editor window")
    return matches[0] if matches else None


def window_geometry(window: dict, workspaces: list[dict], outputs: dict) -> tuple[int, ...]:
    workspace = next(item for item in workspaces if item["id"] == window["workspace_id"])
    logical = outputs[workspace["output"]]["logical"]
    if logical["scale"] != 1 or logical["transform"] != "Normal":
        raise ValueError("Robot coordinates require an unscaled, unrotated output")
    layout = window["layout"]
    position = layout.get("tile_pos_in_workspace_view")
    if not isinstance(position, list) or len(position) != 2:
        raise ValueError("task editor position is not visible yet")
    x, y = position
    dx, dy = layout["window_offset_in_tile"]
    width, height = layout["window_size"]
    return (round(logical["x"] + x + dx), round(logical["y"] + y + dy), width, height)


def write_properties(path: Path, values: dict) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text("".join(f"{key}={value}\n" for key, value in values.items()), encoding="ascii")
    temporary.replace(path)


def read_properties(path: Path) -> dict[str, str]:
    return dict(line.split("=", 1) for line in path.read_text(encoding="ascii").splitlines() if "=" in line)


def run(task_dir: Path, run_id: str, niri: str) -> None:
    if task_dir.name != run_id or not run_id.startswith("queue-"):
        raise ValueError("window helper requires its queue run directory")
    state = task_dir / "turboism-home/state/dev.turboism.validation.mesh-edit"
    state.mkdir(parents=True, exist_ok=True)
    ready = state / "editor-window-ready.properties"
    request = state / "selection-brush-preview.request"
    receipt = state / "selection-brush-preview.receipt"
    positioned_id = None
    previous = None
    stable_since = time.monotonic()
    deadline = time.monotonic() + 1200
    while time.monotonic() < deadline:
        try:
            window = task_window(niri_json(niri, "windows"), run_id)
            if window is None:
                ready.unlink(missing_ok=True)
                previous = None
                time.sleep(0.2)
                continue
            window_id = window["id"]
            if not window["is_focused"] or positioned_id != window_id:
                ready.unlink(missing_ok=True)
                subprocess.run([niri, "msg", "action", "focus-window", "--id", str(window_id)],
                               check=True, capture_output=True, timeout=5)
                if positioned_id != window_id:
                    subprocess.run([niri, "msg", "action", "move-window-to-floating", "--id", str(window_id)],
                                   check=True, capture_output=True, timeout=5)
                    positioned_id = window_id
                previous = None
                time.sleep(0.2)
                continue
            geometry = window_geometry(window, niri_json(niri, "workspaces"), niri_json(niri, "outputs"))
            identity = (window_id, *geometry)
            if identity != previous:
                ready.unlink(missing_ok=True)
                previous, stable_since = identity, time.monotonic()
            elif time.monotonic() - stable_since >= 0.4:
                values = {"schemaVersion": 1, "runId": run_id, "windowId": window_id,
                          "originX": geometry[0], "originY": geometry[1],
                          "width": geometry[2], "height": geometry[3], "scale": 1}
                write_properties(ready, values)
                if request.is_file() and not receipt.exists():
                    if read_properties(request).get("runId") != run_id:
                        raise ValueError("preview request belongs to another run")
                    image = state / "selection-brush-preview.png"
                    temporary = state / "selection-brush-preview.tmp.png"
                    temporary.unlink(missing_ok=True)
                    subprocess.run([niri, "msg", "action", "screenshot-window", "--id", str(window_id),
                                    "--path", str(temporary)], check=True, capture_output=True, timeout=5)
                    capture_deadline = time.monotonic() + 5
                    last_size = None
                    while time.monotonic() < capture_deadline:
                        size = temporary.stat().st_size if temporary.is_file() else 0
                        if size > 0 and size == last_size:
                            break
                        last_size = size
                        time.sleep(0.1)
                    data = temporary.read_bytes()
                    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
                        raise ValueError("window capture is not PNG")
                    observed = task_window(niri_json(niri, "windows"), run_id)
                    if observed is None or observed["id"] != window_id or not observed["is_focused"]:
                        raise ValueError("task editor changed during capture")
                    temporary.replace(image)
                    write_properties(receipt, {**values, "status": "PASS", "source": "niri",
                                               "sha256": hashlib.sha256(data).hexdigest()})
        except (OSError, ValueError, KeyError, TypeError, StopIteration, subprocess.SubprocessError) as error:
            ready.unlink(missing_ok=True)
            previous = None
            print(f"editor-window helper: {type(error).__name__}: {error}", flush=True)
        time.sleep(0.2)
    ready.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task-dir", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--niri", required=True)
    args = parser.parse_args()
    run(args.task_dir.resolve(), args.run_id, args.niri)


if __name__ == "__main__":
    main()
