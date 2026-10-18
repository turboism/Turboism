"""Display allocation, Xvfb process supervision, session checks and module loading."""
from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import time
from typing import TYPE_CHECKING, Any

from .common import (
    X11_SOCKET_DIR,
    QueueError,
)

if TYPE_CHECKING:
    from .store import Store


def wake(store: Store) -> None:
    with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as client:
        client.setblocking(False)
        try:
            client.sendto(b"wake", str(store.root / "wake.sock"))
        except (FileNotFoundError, ConnectionRefusedError, BlockingIOError):
            pass  # Durable state, not a datagram, is authoritative.


def xvfb_available() -> bool:
    return shutil.which("Xvfb") is not None


def display_in_use(number: int) -> bool:
    return os.path.lexists(os.path.join(X11_SOCKET_DIR, f"X{number}"))


def spawn_xvfb(display: str) -> subprocess.Popen | None:
    """Start a private worker-owned X server; returns None when it is not ready."""
    number = display.lstrip(":")
    socket_path = os.path.join(X11_SOCKET_DIR, f"X{number}")
    try:
        process = subprocess.Popen(
            ["Xvfb", display, "-screen", "0", "1920x1080x24", "-nolisten", "tcp"],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, start_new_session=True)
    except OSError:
        return None
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        if os.path.lexists(socket_path):
            return process
        if process.poll() is not None:
            return None
        time.sleep(0.05)
    terminate_process(process)
    return None


def terminate_process(process: subprocess.Popen) -> None:
    """SIGTERM then SIGKILL for a process this worker spawned itself."""
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def terminate_xvfb(pid: int, display: str | None) -> None:
    """Kill a recorded Xvfb pid only after verifying it is still an Xvfb for
    that display; never signal a recycled/unrelated pid."""
    try:
        executable = (Path("/proc") / str(pid) / "comm").read_text().strip().lower()
        command = (Path("/proc") / str(pid) / "cmdline").read_bytes()
    except (FileNotFoundError, ProcessLookupError):
        return
    except PermissionError:
        return
    if "xvfb" not in executable:
        return
    if display is not None and display.encode() not in command:
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        try:
            state = (Path("/proc") / str(pid) / "stat").read_text().rsplit(")", 1)[1].split()[0]
        except (FileNotFoundError, ProcessLookupError):
            return
        if state == "Z":
            return  # Dead; reaping belongs to its parent, never to the queue.
        time.sleep(0.05)
    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def cubism_candidate(command: bytes, executable: str) -> bool:
    """Pure candidate check: java/cubism comm plus a Cubism cmdline marker."""
    return ("java" in executable or "cubism" in executable) and any(
        text in command for text in (b"CubismEditor", b"CECubismEditorApp", b"Live2D_Cubism")
    )


def task_owned_session(command: bytes, environ: bytes, owned_markers: set[str]) -> bool:
    """A foreign-looking session is task-owned only when a live attempt's
    run_id is embedded in its cmdline or environment (task dirs and WINEPREFIX
    both embed the queue-<uuid> run_id)."""
    return any(marker.encode() in command or marker.encode() in environ
               for marker in owned_markers)


def external_sessions(owned_markers: set[str] | frozenset[str] = frozenset(),
                      proc_root: Path = Path("/proc")) -> list[int]:
    """Read-only conservative check; never kill or adopt an unknown session."""
    found = []
    for path in proc_root.iterdir():
        if not path.name.isdigit():
            continue
        try:
            command = (path / "cmdline").read_bytes().replace(b"\0", b" ")
            # Do not match tools merely discussing Cubism in their prompts.
            executable = (path / "comm").read_text().strip().lower()
            if not cubism_candidate(command, executable):
                continue
            try:
                environ = (path / "environ").read_bytes()
            except (FileNotFoundError, ProcessLookupError, PermissionError):
                environ = b""  # Unreadable environment cannot prove ownership.
            if owned_markers and task_owned_session(command, environ, set(owned_markers)):
                continue
            found.append(int(path.name))
        except (FileNotFoundError, ProcessLookupError):
            continue
        except PermissionError as failure:
            raise QueueError("cannot inspect host processes safely") from failure
    return sorted(found)


def scope_identity(raw: dict[str, Any]) -> dict[str, Any]:
    # Containment metadata also records gid and serializes kernel ticks as text.
    # Project only the queue's exact PID-lifetime identity fields.
    return {"pid": int(raw["pid"]), "startTicks": int(raw["startTicks"]),
            "bootId": raw["bootId"], "uid": int(raw["uid"])}


def snapshot_module(prepared_root: Path, name: str) -> Any:
    if name not in {"host_validation_containment", "host_validation_evidence"}:
        raise QueueError("unsupported supervisor helper")
    path = prepared_root / "tool/scripts/preview" / (name + ".py")
    specification = importlib.util.spec_from_file_location(name + "_snapshot", path)
    if specification is None or specification.loader is None:
        raise QueueError("missing prepared supervisor helper")
    module = importlib.util.module_from_spec(specification)
    # Loading a verified helper must not mutate its snapshot with __pycache__.
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    return module
