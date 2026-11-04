"""Common constants, exceptions, serialization, path, and lock helpers.

The queue runs on Linux (Proton host validation) and on native Windows x64.
POSIX-only primitives are imported defensively so importing this module never
fails on a host that cannot provide them; every guarded path keeps the exact
Linux behaviour it had before.
"""
from __future__ import annotations

import contextlib
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import stat
from typing import Any, Iterator
import uuid

try:  # POSIX advisory locks; absent on Windows.
    import fcntl
except ImportError:  # pragma: no cover - platform dependent
    fcntl = None  # type: ignore[assignment]

try:  # POSIX account database; absent on Windows.
    import pwd
except ImportError:  # pragma: no cover - platform dependent
    pwd = None  # type: ignore[assignment]

try:  # Windows byte-range locks; absent on POSIX.
    import msvcrt
except ImportError:  # pragma: no cover - platform dependent
    msvcrt = None  # type: ignore[assignment]

SCHEMA = 1
TERMINAL = frozenset({"succeeded", "failed", "cancelled", "timed_out", "blocked"})
ACTIVE = frozenset({"starting", "running", "cleaning", "recovering", "quarantined"})
RUNNING = frozenset({"starting", "running"})

# Version-scoped slots: catalogued resource names and the ones that still
# serialize the whole host. display-input is per-job (private Xvfb), host-slot
# is per-version; performance-host/interactive-desktop remain global.
KNOWN_RESOURCES = frozenset({"host-slot", "display-input", "performance-host", "interactive-desktop"})
GLOBAL_RESOURCES = frozenset({"performance-host", "interactive-desktop"})
# Descriptors prepared before per-job admission existed must run on the legacy
# admission.lock protocol; queueProtocol 2 snapshots accept admission-<job>.lock.
QUEUE_PROTOCOL = 2
CLAIM_SCAN_LIMIT = 32
MAX_SLOTS = 8
XVFB_DISPLAYS = range(10, 30)
X11_SOCKET_DIR = "/tmp/.X11-unix"

READ_ONLY_CONNECT_TIMEOUT_SECONDS = 0.0
_READ_LOCK_RESULT_CODES = frozenset({
    getattr(sqlite3, "SQLITE_BUSY", 5),
    getattr(sqlite3, "SQLITE_LOCKED", 6),
    getattr(sqlite3, "SQLITE_PROTOCOL", 15),
})
_READ_LOCK_MESSAGES = frozenset({
    "database is busy",
    "database is locked",
    "database schema is locked",
    "database table is locked",
    "locking protocol",
})
# An administrative registration is terminal for scheduling but is never a
# verification outcome; TERMINAL stays limited to validated terminal states.
ADMINISTRATIVE_STATE = "abandoned"
ADMINISTRATIVE = frozenset({ADMINISTRATIVE_STATE})


class QueueError(RuntimeError):
    """A rejected operation; never permission to bypass admission."""


class QueueBusy(QueueError):
    """Temporary ownership contention; callers may report skipped or wait."""


class NoHostSideEffects(QueueError):
    """A verified read-only preflight failed before any contained process start."""


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def digest_json(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode()).hexdigest()


def _current_uid() -> int:
    """Effective uid on POSIX; Windows has no numeric owner, so report 0."""
    getuid = getattr(os, "getuid", None)
    return int(getuid()) if getuid is not None else 0


def account_root() -> Path:
    # HOME/XDG/cwd must not split one user's host into independent lock domains.
    if pwd is not None and hasattr(os, "getuid"):
        return Path(pwd.getpwuid(os.getuid()).pw_dir) / ".local/state/turboism/host-validation"
    # Windows: the profile directory is the single per-user lock domain.
    return Path.home() / ".local/state/turboism/host-validation"


def private_directory(path: Path) -> Path:
    path = path.absolute()
    for part in reversed((path, *path.parents)):
        if part.is_symlink():
            raise QueueError(f"symlink in queue directory: {part}")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.stat()
    if hasattr(os, "getuid"):
        if info.st_uid != _current_uid() or not stat.S_ISDIR(info.st_mode):
            raise QueueError("queue directory must be owned by the current user")
        if stat.S_IMODE(info.st_mode) & 0o077:
            raise QueueError("queue directory must not be accessible to other users")
        return path
    # Windows ACLs, not POSIX mode bits, carry the privacy guarantee.
    if not stat.S_ISDIR(info.st_mode):
        raise QueueError("queue directory must be a directory")
    return path


def atomic_json(path: Path, value: Any) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(canonical_json(value) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        if hasattr(os, "O_DIRECTORY") and os.name != "nt":
            directory_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    finally:
        temporary.unlink(missing_ok=True)


def process_identity(pid: int) -> dict[str, Any] | None:
    if os.name == "nt" and not Path("/proc").is_dir():
        return _windows_process_identity(pid)
    try:
        base = Path("/proc") / str(pid)
        fields = (base / "stat").read_text().rsplit(")", 1)[1].split()
        if fields[0] == "Z":
            return None
        return {"pid": pid, "startTicks": int(fields[19]),
                "bootId": Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
                "uid": base.stat().st_uid}
    except (FileNotFoundError, ProcessLookupError):
        return None


# Windows has no /proc and no boot_id file, so a process's kernel creation time
# (FILETIME, 100ns since 1601) plus the fixed boot label fences PID reuse the
# same way startTicks plus bootId does on Linux.
_PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
_STILL_ACTIVE = 259
_windows_kernel32: Any = None


def _kernel32() -> Any:
    global _windows_kernel32
    if _windows_kernel32 is not None:
        return _windows_kernel32
    import ctypes
    import ctypes.wintypes as wintypes

    kernel32 = ctypes.windll.kernel32
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.CloseHandle.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.GetExitCodeProcess.restype = wintypes.BOOL
    kernel32.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel32.GetProcessTimes.restype = wintypes.BOOL
    kernel32.GetProcessTimes.argtypes = [
        wintypes.HANDLE,
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
    ]
    _windows_kernel32 = kernel32
    return kernel32


def _filetime_value(value: Any) -> int:
    return (int(value.dwHighDateTime) << 32) | int(value.dwLowDateTime)


def _windows_process_identity(pid: int) -> dict[str, Any] | None:
    import ctypes
    import ctypes.wintypes as wintypes

    kernel32 = _kernel32()
    handle = kernel32.OpenProcess(_PROCESS_QUERY_LIMITED_INFORMATION, False, int(pid))
    if not handle:
        return None
    try:
        exit_code = wintypes.DWORD(0)
        if not kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code)):
            return None
        if exit_code.value != _STILL_ACTIVE:
            return None
        created, exited = wintypes.FILETIME(), wintypes.FILETIME()
        kernel_time, user_time = wintypes.FILETIME(), wintypes.FILETIME()
        if not kernel32.GetProcessTimes(
            handle,
            ctypes.byref(created),
            ctypes.byref(exited),
            ctypes.byref(kernel_time),
            ctypes.byref(user_time),
        ):
            return None
        return {"pid": int(pid), "startTicks": _filetime_value(created),
                "bootId": "windows", "uid": 0}
    finally:
        kernel32.CloseHandle(handle)


def identity_alive(identity: dict[str, Any]) -> bool:
    return process_identity(identity["pid"]) == identity


class FileLock:
    def __init__(self, path: Path):
        self.path = path
        self.fd: int | None = None

    def acquire(self) -> "FileLock":
        fd = os.open(self.path, os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != _current_uid():
            os.close(fd)
            raise QueueError("unsafe admission lock")
        try:
            _lock_fd(fd, exclusive=True)
        except (BlockingIOError, PermissionError) as failure:
            os.close(fd)
            raise QueueBusy(f"already owned: {self.path.name}") from failure
        self.fd = fd
        return self

    def close(self) -> None:
        if self.fd is not None:
            # Do not LOCK_UN: the supervisor may still hold an inherited copy.
            os.close(self.fd)
            self.fd = None

    def __enter__(self) -> "FileLock":
        return self.acquire()

    def __exit__(self, *_: Any) -> None:
        self.close()


def _lock_fd(fd: int, *, exclusive: bool) -> None:
    """Take a non-blocking advisory lock on an already-open descriptor.

    POSIX keeps the exact flock calls it always used (LOCK_SH is blocking,
    LOCK_EX|LOCK_NB is not).  Windows has no flock: the CRT byte-range lock is
    used instead, and the CRT grants no reader/reader concurrency, so on that
    host a reader also excludes other readers.  Both backends raise OSError
    when the lock is already held elsewhere.
    """
    if fcntl is not None:
        fcntl.flock(fd, (fcntl.LOCK_EX | fcntl.LOCK_NB) if exclusive else fcntl.LOCK_SH)
        return
    if msvcrt is None:  # pragma: no cover - no lock backend on this host
        raise QueueError("no advisory file lock backend is available on this host")
    os.lseek(fd, 0, os.SEEK_SET)
    msvcrt.locking(fd, msvcrt.LK_NBLCK if exclusive else msvcrt.LK_NBRLCK, 1)


@contextlib.contextmanager
def storage_lock(root: Path, *, exclusive: bool = False) -> Iterator[None]:
    """Readers prepare/submit concurrently; collection needs an idle storage domain."""
    fd = os.open(root / "storage.lock", os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode):
            raise QueueError("unsafe storage lock")
        if hasattr(os, "getuid") and (info.st_uid != _current_uid() or info.st_mode & 0o077):
            raise QueueError("unsafe storage lock")
        try:
            _lock_fd(fd, exclusive=exclusive)
        except (BlockingIOError, PermissionError) as failure:
            raise QueueBusy("storage is busy preparing/submitting/collecting") from failure
        yield
    finally:
        os.close(fd)
