"""Host operating-system primitives shared by the host-validation toolchain.

Three facts differ between the supported host classes — Linux (native or Proton) and native
Windows x64 — and every caller needs exactly one of them:

* the advisory lock backend (``flock`` has no Windows equivalent, and the CRT lock grants no
  reader/reader concurrency);
* the numeric owner of a private path (Windows has no ``uid``);
* the interrupt signals that may carry a handler.

This module owns those facts. A caller never probes the platform and never imports ``fcntl``,
``msvcrt`` or ``pwd`` directly, so one import answers every question and the Windows run cannot
fail at module import time.
"""

from __future__ import annotations

import os
import shutil
import signal as _signal

try:  # POSIX advisory locks; absent on Windows.
    import fcntl as _fcntl
except ImportError:  # pragma: no cover - platform dependent
    _fcntl = None

try:  # Windows byte-range locks; absent on POSIX.
    import msvcrt as _msvcrt
except ImportError:  # pragma: no cover - platform dependent
    _msvcrt = None

try:  # POSIX account database; absent on Windows.
    import pwd as _pwd
except ImportError:  # pragma: no cover - platform dependent
    _pwd = None


class HostOs:
    """Reusable access to the host primitives the validation toolchain needs."""

    #: The POSIX advisory-lock module, or ``None`` on a host without one.
    fcntl = _fcntl

    #: The Windows byte-range lock module, or ``None`` on a host without one.
    msvcrt = _msvcrt

    #: The POSIX account database, or ``None`` on a host without one.
    pwd = _pwd

    #: Refuse to follow a final symbolic link when opening; Windows has no such flag.
    NO_FOLLOW = getattr(os, "O_NOFOLLOW", 0)

    #: True on a Windows host, where process creation does not follow ``PATH`` order.
    WINDOWS = os.name == "nt"

    def __init__(self) -> None:
        raise NotImplementedError("HostOs is a namespace; use its class methods")

    @classmethod
    def current_uid(cls) -> int:
        """Effective uid on POSIX; Windows has no numeric owner, so report 0."""
        getuid = getattr(os, "getuid", None)
        return int(getuid()) if getuid is not None else 0

    @classmethod
    def home_directory(cls) -> "os.PathLike[str] | None":
        """Account home from the POSIX account database, or ``None`` when it is unavailable."""
        if _pwd is None:
            return None
        getuid = getattr(os, "getuid", None)
        if getuid is None:
            return None
        try:
            return _pwd.getpwuid(int(getuid())).pw_dir
        except (KeyError, OSError):
            return None

    @classmethod
    def try_lock(cls, descriptor: int, *, exclusive: bool) -> None:
        """Take a non-blocking advisory lock on an already-open descriptor.

        POSIX keeps the exact ``flock`` calls it always used: ``LOCK_SH`` is blocking and
        ``LOCK_EX | LOCK_NB`` is not. Windows has no ``flock``, so the CRT byte-range lock is used
        instead; that backend grants no reader/reader concurrency, which makes a reader exclude
        other readers there. Both backends raise ``OSError`` when the lock is held elsewhere.
        """
        if _fcntl is not None:
            _fcntl.flock(descriptor, (_fcntl.LOCK_EX | _fcntl.LOCK_NB) if exclusive else _fcntl.LOCK_SH)
            return
        if _msvcrt is None:  # pragma: no cover - no lock backend on this host
            raise OSError("no advisory file lock backend is available on this host")
        os.lseek(descriptor, 0, os.SEEK_SET)
        _msvcrt.locking(descriptor, _msvcrt.LK_NBLCK if exclusive else _msvcrt.LK_NBRLCK, 1)

    @classmethod
    def shell(cls) -> str:
        """POSIX shell that runs the validation task scripts.

        Windows resolves a bare ``bash`` through CreateProcess, which searches ``System32`` before
        ``PATH`` and therefore starts the WSL launcher instead of the MSYS shell the scripts need.
        ``shutil.which`` follows ``PATH`` order, so Windows passes its answer explicitly. POSIX
        keeps the bare name: its search order is the shell's own ``PATH``, which is correct.
        """
        if not cls.WINDOWS:
            return "bash"
        found = shutil.which("bash")
        return found if found is not None else "bash"

    @classmethod
    def interrupt_signals(cls) -> tuple[int, ...]:
        """Signals that may carry a cooperative stop handler on this host."""
        signals = []
        for name in ("SIGINT", "SIGTERM", "SIGBREAK"):
            value = getattr(_signal, name, None)
            if isinstance(value, int):
                signals.append(value)
        return tuple(signals)
