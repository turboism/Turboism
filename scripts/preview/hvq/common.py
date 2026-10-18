"""Common constants, exceptions, serialization, path, and lock helpers."""
from __future__ import annotations

import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import pwd
import sqlite3
import stat
from typing import Any, Iterator
import uuid

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


def account_root() -> Path:
    # HOME/XDG/cwd must not split one user's host into independent lock domains.
    return Path(pwd.getpwuid(os.getuid()).pw_dir) / ".local/state/turboism/host-validation"


def private_directory(path: Path) -> Path:
    path = path.absolute()
    for part in reversed((path, *path.parents)):
        if part.is_symlink():
            raise QueueError(f"symlink in queue directory: {part}")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.stat()
    if info.st_uid != os.getuid() or not stat.S_ISDIR(info.st_mode):
        raise QueueError("queue directory must be owned by the current user")
    if stat.S_IMODE(info.st_mode) & 0o077:
        raise QueueError("queue directory must not be accessible to other users")
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
        directory_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        temporary.unlink(missing_ok=True)


def process_identity(pid: int) -> dict[str, Any] | None:
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


def identity_alive(identity: dict[str, Any]) -> bool:
    return process_identity(identity["pid"]) == identity


class FileLock:
    def __init__(self, path: Path):
        self.path = path
        self.fd: int | None = None

    def acquire(self) -> "FileLock":
        fd = os.open(self.path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
            os.close(fd)
            raise QueueError("unsafe admission lock")
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as failure:
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


@contextlib.contextmanager
def storage_lock(root: Path, *, exclusive: bool = False) -> Iterator[None]:
    """Readers prepare/submit concurrently; collection needs an idle storage domain."""
    fd = os.open(root / "storage.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise QueueError("unsafe storage lock")
        try:
            fcntl.flock(fd, (fcntl.LOCK_EX | fcntl.LOCK_NB) if exclusive else fcntl.LOCK_SH)
        except BlockingIOError as failure:
            raise QueueBusy("storage is busy preparing/submitting/collecting") from failure
        yield
    finally:
        os.close(fd)
