"""Private systemd/cgroup containment helper for host-validation attempts.

This module deliberately exposes one small backend seam, ``start``.  It is not
an alternate queue or a general shell runner.  A started attempt enters a
random, user-owned systemd scope, waits for the parent to bind the scope's
actual cgroup, and only then execs the requested argv.  Cleanup is safe only
when the original cgroup reports ``populated=0`` or the original cgroup has
been destroyed while its held identity remains verifiable.
"""

from __future__ import annotations

import argparse
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import stat
import struct
import subprocess
import sys
import time
from datetime import datetime, timezone
from typing import IO, Any, Mapping, Sequence

SCHEMA_VERSION = 1
_SCOPE_PREFIX = "turboism-queue-"
_SCOPE_RE = re.compile(r"^turboism-queue-[0-9a-f]{32}\.scope$")
_REQUIRED_IDENTITY = ("jobId", "attemptId", "runId", "preparedDigest")
_CGROUP_ROOT = Path("/sys/fs/cgroup")
_HANDSHAKE_TIMEOUT_SECONDS = 10.0
_MAX_HANDSHAKE_BYTES = 64 * 1024


class ContainmentError(RuntimeError):
    """Base error for unsupported or unprovable containment operations."""


class ContainmentUnsupported(ContainmentError):
    """The host cannot provide the required systemd/cgroup contract."""


class ContainmentStartError(ContainmentError):
    """Scope startup did not reach a bound ACK; evidence is retained."""

    def __init__(
        self,
        message: str,
        *,
        metadata: Mapping[str, Any] | None = None,
        process: subprocess.Popen[bytes] | None = None,
    ) -> None:
        super().__init__(message)
        self.metadata = dict(metadata or {})
        self.process = process


class _CgroupReadError(RuntimeError):
    def __init__(self, error: OSError) -> None:
        super().__init__(str(error))
        self.error = error


class ContainedProcess:
    """A bound systemd-scope process and its kernel cleanup handles."""

    def __init__(
        self,
        *,
        process: subprocess.Popen[bytes],
        metadata: dict[str, Any],
        containment_path: Path,
        cgroup_path: Path,
        cgroup_dir_fd: int,
        events_fd: int,
        kill_fd: int,
        request_path: Path,
    ) -> None:
        self.process = process
        self.metadata = metadata
        self._containment_path = containment_path
        self._cgroup_path = cgroup_path
        self._cgroup_dir_fd = cgroup_dir_fd
        self._events_fd = events_fd
        self._kill_fd = kill_fd
        self._request_path = request_path
        self._finished = False
        self._closed = False
        self._finish_result: dict[str, Any] | None = None
        self._dir_identity = _fd_identity(cgroup_dir_fd)

    @property
    def entry_identity(self) -> dict[str, Any]:
        """The entry PID identity captured during the supervisor handshake."""
        return dict(self.metadata["entryIdentity"])

    def finish(self, timeout_seconds: float = 30.0) -> dict[str, Any]:
        """Return a kernel-backed cleanup proof, never a PID-list assertion.

        The first populated observation writes the already-bound cgroup.kill FD.
        It never searches for or signals a PID.  A failed read, changed cgroup
        path, missing FD, or timeout is reported as ``cleanup=unknown``.
        """
        if self._finish_result is not None:
            return dict(self._finish_result)
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        if self._closed:
            raise ContainmentError("containment handles are already closed")

        started_at = _utc_now()
        deadline = time.monotonic() + float(timeout_seconds)
        kill_written = False
        process_timed_out = False
        errors: list[str] = []
        observations: list[dict[str, Any]] = []
        cleanup_method = "unknown"
        final_observation: dict[str, Any] | None = None

        # Polling starts immediately: finish is called after the Runner has
        # normally exited, but an interrupted Runner may still be in the scope.
        while time.monotonic() < deadline:
            final_observation = self._observe_cgroup()
            observations.append(final_observation)
            observation_kind = final_observation["kind"]

            if observation_kind == "unknown":
                error = final_observation.get("error")
                if error and error not in errors:
                    errors.append(error)
            elif observation_kind == "destroyed":
                cleanup_method = "cgroup-destroyed"
            elif final_observation.get("populated") == 1:
                if not kill_written:
                    try:
                        _write_cgroup_kill(self._kill_fd)
                        kill_written = True
                        cleanup_method = "cgroup.kill"
                    except OSError as error:
                        errors.append(f"cgroup.kill write failed: {error}")
                        final_observation = dict(final_observation)
                        final_observation["kind"] = "unknown"
                        final_observation["error"] = str(error)
                        observations[-1] = final_observation
            elif final_observation.get("populated") == 0:
                cleanup_method = "populated=0"

            if self.process.poll() is None:
                if time.monotonic() >= deadline:
                    process_timed_out = True
                else:
                    # Do not wait for systemd-run to return before inspecting
                    # the cgroup: systemd-run may intentionally wait for scope
                    # lifetime while the bound cgroup is already empty.
                    time.sleep(0.05)
                    continue

            # If the scope is still populated, one final bound cgroup.kill is
            # permitted even when the systemd-run client has already returned.
            if (
                final_observation.get("kind") == "same"
                and final_observation.get("populated") == 1
                and not kill_written
            ):
                try:
                    _write_cgroup_kill(self._kill_fd)
                    kill_written = True
                    cleanup_method = "cgroup.kill"
                except OSError as error:
                    errors.append(f"cgroup.kill write failed: {error}")

            # A cgroup proof is not enough if the systemd-run client is still
            # alive: retain UNKNOWN rather than pretending the attempt ended.
            if (
                final_observation.get("kind") in {"same", "destroyed"}
                and final_observation.get("populated") in {0, None}
                and self.process.poll() is not None
            ):
                break

            if time.monotonic() >= deadline:
                process_timed_out = self.process.poll() is None
                break
            time.sleep(0.05)

        if self.process.poll() is None:
            process_timed_out = True

        try:
            process_returncode = self.process.wait(timeout=max(0.0, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            process_timed_out = True
            process_returncode = self.process.poll()

        final_observation = self._observe_cgroup()
        observations.append(final_observation)
        if final_observation["kind"] == "destroyed":
            cleanup_method = "cgroup.kill+cgroup-destroyed" if kill_written else "cgroup-destroyed"
        elif final_observation.get("kind") == "same" and final_observation.get("populated") == 0:
            cleanup_method = "cgroup.kill+populated=0" if kill_written else "populated=0"
        elif final_observation.get("kind") == "unknown":
            error = final_observation.get("error")
            if error and error not in errors:
                errors.append(error)

        kernel_proof = {
            "originalCgroupBound": True,
            "cgroupPath": self.metadata["cgroupPath"],
            "cgroupDevice": self.metadata["cgroupDevice"],
            "cgroupInode": self.metadata["cgroupInode"],
            "bootId": self.metadata["bootId"],
            "initialReadings": self.metadata.get("kernelReadings", {}).get("initial"),
            "observations": observations[-8:],
            "finalReading": final_observation,
            "cgroupKillWritten": kill_written,
            "cleanupMethod": cleanup_method,
            "processReturncode": process_returncode,
            "processTimedOut": process_timed_out,
            "errors": errors,
        }
        safe = (
            not errors
            and not process_timed_out
            and process_returncode is not None
            and final_observation.get("kind") in {"same", "destroyed"}
            and (
                final_observation.get("kind") == "destroyed"
                or final_observation.get("populated") == 0
            )
        )
        result = {
            "schemaVersion": SCHEMA_VERSION,
            "jobId": self.metadata["jobId"],
            "attemptId": self.metadata["attemptId"],
            "runId": self.metadata["runId"],
            "preparedDigest": self.metadata["preparedDigest"],
            "cleanup": "safe" if safe else "unknown",
            "scopeUnit": self.metadata["scopeUnit"],
            "cgroupPath": self.metadata["cgroupPath"],
            "cgroupDevice": self.metadata["cgroupDevice"],
            "cgroupInode": self.metadata["cgroupInode"],
            "bootId": self.metadata["bootId"],
            "entryIdentity": self.entry_identity,
            "kernelProof": kernel_proof,
            "startedAt": started_at,
            "finishedAt": _utc_now(),
        }
        self.metadata.update(
            {
                "state": "FINISHED",
                "cleanup": result["cleanup"],
                "kernelProof": kernel_proof,
                "finishedAt": result["finishedAt"],
            }
        )
        try:
            _atomic_write_json(self._containment_path, self.metadata)
        except OSError as error:
            result["cleanup"] = "unknown"
            result["kernelProof"] = dict(kernel_proof, evidenceWriteError=str(error))
            self.metadata["cleanup"] = "unknown"
            self.metadata["kernelProof"] = result["kernelProof"]
            # Do not replace the existing evidence with a claim that could not
            # be atomically persisted.
            try:
                _atomic_write_json(self._containment_path, self.metadata)
            except OSError:
                pass
        self._finished = True
        self._finish_result = result
        return dict(result)

    def close(self) -> None:
        """Close held descriptors without claiming cleanup or deleting evidence."""
        if self._closed:
            return
        self._closed = True
        for fd in (self._events_fd, self._kill_fd, self._cgroup_dir_fd):
            try:
                os.close(fd)
            except OSError:
                pass
        try:
            self._request_path.unlink()
        except FileNotFoundError:
            pass
        except OSError:
            # The containment record is retained; request cleanup is not a
            # kernel cleanup proof.
            pass

    def _observe_cgroup(self) -> dict[str, Any]:
        path_state = _path_identity_state(self._cgroup_path, self._dir_identity)
        if path_state == "changed":
            return {"kind": "unknown", "error": "original cgroup path identity changed"}
        try:
            text = _read_fd_text(self._events_fd)
            populated = _parse_cgroup_events(text)["populated"]
        except _CgroupReadError as error:
            post_state = _path_identity_state(self._cgroup_path, self._dir_identity)
            if (
                path_state in {"same", "missing"}
                and post_state == "missing"
                and error.error.errno in {errno.ENODEV, errno.ENOENT}
            ):
                return {"kind": "destroyed", "populated": None, "error": str(error.error)}
            return {"kind": "unknown", "error": str(error.error)}
        except ValueError as error:
            return {"kind": "unknown", "error": str(error)}
        post_state = _path_identity_state(self._cgroup_path, self._dir_identity)
        if post_state == "missing" and populated == 0:
            # The original directory vanished after a valid empty reading; the
            # held directory FD and the pre/post identity check prove destroy.
            return {"kind": "destroyed", "populated": 0, "events": text}
        if path_state == "missing" or post_state == "missing":
            return {"kind": "unknown", "error": "original cgroup path missing or recreated"}
        if path_state != "same" or post_state != "same":
            return {"kind": "unknown", "error": "original cgroup path identity unreadable or changed"}
        return {"kind": "same", "populated": populated, "events": text}


def start(
    command: list[str],
    *,
    directory: Path,
    environment: dict[str, str],
    admission_fd: int,
    identity: dict[str, Any],
    output: IO[Any],
) -> ContainedProcess:
    """Start ``command`` behind a bound, delegated systemd user scope.

    The parent writes ``containment.json`` only after SO_PEERCRED and the
    child's actual cgroup have been checked.  The child waits for that ACK
    before ``execvpe``; the admission FD is passed through systemd-run and is
    checked in the child before the ACK is accepted.
    """
    _validate_command(command)
    _validate_environment(environment)
    _validate_identity(identity)
    _validate_fd(admission_fd, "admission_fd")
    if not hasattr(output, "fileno"):
        raise TypeError("output must be a file object accepted by subprocess.Popen")

    work_dir = _prepare_directory(Path(directory))
    containment_path = work_dir / "containment.json"
    if containment_path.exists():
        raise ContainmentStartError(f"containment record already exists: {containment_path}")

    systemd_run = shutil.which("systemd-run")
    if systemd_run is None:
        raise ContainmentUnsupported("systemd-run is unavailable; containment is unsupported")
    if not _CGROUP_ROOT.is_dir() or not (_CGROUP_ROOT / "cgroup.controllers").exists():
        raise ContainmentUnsupported("unified cgroup v2 root is unavailable; containment is unsupported")
    boot_id = _read_boot_id()

    token = secrets.token_hex(16)
    unit = f"{_SCOPE_PREFIX}{token}.scope"
    # The job directory is already unique/private. A UUID filename exceeds the
    # Linux sockaddr_un limit at the production account-home queue depth.
    socket_path = work_dir / ".s"
    if len(os.fsencode(socket_path)) >= 108:
        raise ContainmentUnsupported("containment socket path exceeds the kernel limit")
    request_path = work_dir / f".containment-request-{token}.json"
    metadata: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "jobId": identity["jobId"],
        "attemptId": identity["attemptId"],
        "runId": identity["runId"],
        "preparedDigest": identity["preparedDigest"],
        "cleanup": "unknown",
        "state": "STARTING",
        "scopeUnit": unit,
        "scopePath": None,
        "cgroupPath": None,
        "cgroupDevice": None,
        "cgroupInode": None,
        "bootId": boot_id,
        "entryIdentity": None,
        "directory": str(work_dir),
        "commandSha256": _sha256_json(command),
        "environmentKeys": sorted(environment),
        "createdAt": _utc_now(),
    }
    _atomic_write_json(containment_path, metadata)

    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_PASSCRED, 1)
    connection: socket.socket | None = None
    process: subprocess.Popen[bytes] | None = None
    open_fds: list[int] = []
    try:
        listener.bind(str(socket_path))
        os.chmod(socket_path, 0o600)
        listener.listen(1)

        request = {
            "schemaVersion": SCHEMA_VERSION,
            "token": token,
            "socketPath": str(socket_path),
            "directory": str(work_dir),
            "command": list(command),
            "environment": dict(environment),
            "admissionFd": admission_fd,
            "identity": dict(identity),
            "scopeUnit": unit,
        }
        _atomic_write_json(request_path, request)
        login_keys = {"HOME", "USER", "LOGNAME", "PATH", "LANG", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS"}
        launcher_env = {k: v for k, v in os.environ.items() if k in login_keys or k.startswith("LC_")}
        launcher_env.update(environment)
        for key in ("LD_PRELOAD", "LD_LIBRARY_PATH", "PYTHONPATH", "PYTHONHOME", "BASH_ENV", "ENV"):
            launcher_env.pop(key, None)  # Bootstrap must not execute ambient code before ACK.
        process = subprocess.Popen(
            [
                systemd_run,
                "--user",
                "--scope",
                "--quiet",
                f"--unit={unit}",
                "--property=Delegate=yes",
                "--",
                sys.executable,
                "-I",
                str(Path(__file__).resolve()),
                "--_enter",
                str(request_path),
            ],
            cwd=str(work_dir),
            env=launcher_env,
            stdin=subprocess.DEVNULL,
            stdout=output,
            stderr=subprocess.STDOUT,
            pass_fds=(admission_fd,),
            close_fds=True,
        )

        listener.settimeout(_HANDSHAKE_TIMEOUT_SECONDS)
        connection, _ = listener.accept()
        connection.settimeout(_HANDSHAKE_TIMEOUT_SECONDS)
        peer_pid, peer_uid, peer_gid = _peer_credentials(connection)
        payload = _recv_json(connection)
        _validate_handshake(payload, token=token, unit=unit, peer_pid=peer_pid, peer_uid=peer_uid, boot_id=boot_id)
        if not payload.get("admissionFdOpen", False):
            raise ContainmentStartError("admission FD did not survive systemd scope entry", metadata=metadata, process=process)

        cgroup_path = _read_process_cgroup(peer_pid)
        _validate_cgroup_path(cgroup_path, unit)
        cgroup_dir = _CGROUP_ROOT / cgroup_path.lstrip("/")
        dir_fd, events_fd, kill_fd = _open_cgroup_handles(cgroup_dir)
        open_fds.extend((dir_fd, events_fd, kill_fd))
        dir_identity = _fd_identity(dir_fd)
        initial_events = _parse_cgroup_events(_read_fd_text(events_fd))
        entry_identity = {
            "pid": peer_pid,
            "startTicks": payload["startTicks"],
            "bootId": payload["bootId"],
            "uid": peer_uid,
            "gid": peer_gid,
        }
        if _read_proc_start_ticks(peer_pid) != payload["startTicks"]:
            raise ContainmentStartError("entry PID changed during containment bind", metadata=metadata, process=process)
        if _read_process_cgroup(peer_pid) != cgroup_path:
            raise ContainmentStartError("entry cgroup changed during containment bind", metadata=metadata, process=process)
        metadata.update(
            {
                "state": "BOUND",
                "scopePath": cgroup_path,
                "cgroupPath": cgroup_path,
                "cgroupDevice": dir_identity["dev"],
                "cgroupInode": dir_identity["inode"],
                "entryIdentity": entry_identity,
                "kernelReadings": {"initial": {"populated": initial_events["populated"], "events": initial_events["raw"]}},
                "boundAt": _utc_now(),
            }
        )
        _atomic_write_json(containment_path, metadata)
        _send_json(
            connection,
            {
                "schemaVersion": SCHEMA_VERSION,
                "ok": True,
                "scopePath": cgroup_path,
                "cgroupDevice": dir_identity["dev"],
                "cgroupInode": dir_identity["inode"],
            },
        )
        try:
            request_path.unlink()
        except FileNotFoundError:
            pass
        listener.close()
        connection.close()
        try:
            socket_path.unlink()
        except FileNotFoundError:
            pass
        return ContainedProcess(
            process=process,
            metadata=metadata,
            containment_path=containment_path,
            cgroup_path=cgroup_dir,
            cgroup_dir_fd=dir_fd,
            events_fd=events_fd,
            kill_fd=kill_fd,
            request_path=request_path,
        )
    except ContainmentStartError as error:
        _mark_start_failure(containment_path, metadata, str(error))
        _send_nack(connection, str(error))
        raise
    except Exception as error:
        _mark_start_failure(containment_path, metadata, str(error))
        _send_nack(connection, str(error))
        raise ContainmentStartError(str(error), metadata=metadata, process=process) from error
    finally:
        if connection is not None:
            try:
                connection.close()
            except OSError:
                pass
        try:
            listener.close()
        except OSError:
            pass
        try:
            socket_path.unlink()
        except (FileNotFoundError, OSError):
            pass
        if process is None or metadata.get("state") != "BOUND":
            for fd in open_fds:
                try:
                    os.close(fd)
                except OSError:
                    pass


def _enter_main(request_path: Path) -> int:
    request = json.loads(request_path.read_text(encoding="utf-8"))
    connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    connection.settimeout(_HANDSHAKE_TIMEOUT_SECONDS)
    try:
        connection.connect(request["socketPath"])
        admission_fd = int(request["admissionFd"])
        admission_fd_open = True
        try:
            fcntl.fcntl(admission_fd, fcntl.F_GETFD)
        except OSError:
            admission_fd_open = False
        payload = {
            "schemaVersion": SCHEMA_VERSION,
            "token": request["token"],
            "scopeUnit": request["scopeUnit"],
            "pid": os.getpid(),
            "uid": os.getuid(),
            "startTicks": _read_proc_start_ticks(os.getpid()),
            "bootId": _read_boot_id(),
            "admissionFdOpen": admission_fd_open,
        }
        _send_json(connection, payload)
        response = _recv_json(connection)
        if not response.get("ok", False):
            return 125
        if not admission_fd_open:
            return 126
        os.chdir(request["directory"])
        os.execvpe(request["command"][0], request["command"], request["environment"])
    except Exception as error:
        print(f"containment entry failed: {error}", file=sys.stderr)
        return 127
    finally:
        connection.close()
    return 127


def _validate_command(command: Sequence[str]) -> None:
    if not isinstance(command, list) or not command:
        raise ValueError("command must be a non-empty list[str]")
    if not command[0] or any(not isinstance(item, str) or "\x00" in item for item in command):
        raise ValueError("command executable must be nonempty; arguments must be strings without NUL")


def _validate_environment(environment: Mapping[str, str]) -> None:
    if not isinstance(environment, dict):
        raise ValueError("environment must be dict[str, str]")
    for key, value in environment.items():
        if not isinstance(key, str) or not key or "=" in key or "\x00" in key:
            raise ValueError("environment keys must be valid strings")
        if not isinstance(value, str) or "\x00" in value:
            raise ValueError("environment values must be strings without NUL")


def _validate_identity(identity: Mapping[str, Any]) -> None:
    if not isinstance(identity, dict):
        raise ValueError("identity must be a dict")
    missing = [key for key in _REQUIRED_IDENTITY if key not in identity]
    if missing:
        raise ValueError(f"identity missing required fields: {', '.join(missing)}")
    for key in _REQUIRED_IDENTITY:
        value = identity[key]
        if not isinstance(value, str) or not value or "\x00" in value:
            raise ValueError(f"identity.{key} must be a non-empty string")


def _validate_fd(fd: int, name: str) -> None:
    if not isinstance(fd, int) or fd < 0:
        raise ValueError(f"{name} must be an open non-negative file descriptor")
    try:
        os.fstat(fd)
    except OSError as error:
        raise ValueError(f"{name} is not open: {error}") from error


def _prepare_directory(directory: Path) -> Path:
    directory = directory.absolute()
    if ".." in directory.parts or any(parent.is_symlink() for parent in (directory, *directory.parents)):
        raise ValueError("containment directory must not traverse symlinks or parent components")
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    resolved = directory.resolve(strict=True)
    info = resolved.stat()
    if not stat.S_ISDIR(info.st_mode):
        raise ValueError(f"containment directory is not a directory: {directory}")
    if info.st_uid != os.getuid():
        raise PermissionError(f"containment directory is not owned by current uid: {directory}")
    if info.st_mode & 0o077:
        raise PermissionError(f"containment directory must be private to current uid: {directory}")
    return resolved


def _read_boot_id() -> str:
    value = Path("/proc/sys/kernel/random/boot_id").read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[0-9a-fA-F-]{8,}", value):
        raise ContainmentUnsupported("kernel boot ID is unavailable or malformed")
    return value


def _read_proc_start_ticks(pid: int) -> str:
    raw = (Path("/proc") / str(pid) / "stat").read_bytes()
    closing = raw.rfind(b")")
    if closing < 0:
        raise ContainmentError(f"malformed /proc/{pid}/stat")
    fields = raw[closing + 2 :].split()
    if len(fields) < 20:
        raise ContainmentError(f"short /proc/{pid}/stat")
    return fields[19].decode("ascii")


def _read_process_cgroup(pid: int) -> str:
    lines = (Path("/proc") / str(pid) / "cgroup").read_text(encoding="utf-8").splitlines()
    matches = [line.split(":", 2)[2] for line in lines if line.startswith("0::")]
    if len(matches) != 1:
        raise ContainmentError(f"process {pid} does not expose one unified cgroup")
    return matches[0]


def _validate_cgroup_path(cgroup_path: str, unit: str) -> None:
    if not _SCOPE_RE.fullmatch(unit):
        raise ContainmentError("scope unit is not an internally generated name")
    if not cgroup_path.startswith("/") or ".." in cgroup_path.split("/"):
        raise ContainmentError("kernel returned an unsafe cgroup path")
    if not cgroup_path.rstrip("/").endswith("/" + unit):
        raise ContainmentError(f"entry is not in the requested scope: {cgroup_path}")


def _open_cgroup_handles(cgroup_dir: Path) -> tuple[int, int, int]:
    no_follow = getattr(os, "O_NOFOLLOW", 0)
    dir_fd = events_fd = kill_fd = -1
    try:
        dir_fd = os.open(str(cgroup_dir), os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | no_follow)
        events_fd = os.open(
            "cgroup.events", os.O_RDONLY | os.O_CLOEXEC | no_follow, dir_fd=dir_fd
        )
        kill_fd = os.open(
            "cgroup.kill", os.O_WRONLY | os.O_CLOEXEC | no_follow, dir_fd=dir_fd
        )
        return dir_fd, events_fd, kill_fd
    except OSError:
        for fd in (events_fd, kill_fd, dir_fd):
            if fd >= 0:
                try:
                    os.close(fd)
                except OSError:
                    pass
        raise


def _peer_credentials(connection: socket.socket) -> tuple[int, int, int]:
    option = getattr(socket, "SO_PEERCRED", None)
    if option is None:
        raise ContainmentUnsupported("SO_PEERCRED is unavailable")
    raw = connection.getsockopt(socket.SOL_SOCKET, option, struct.calcsize("3i"))
    return struct.unpack("3i", raw)


def _recv_json(connection: socket.socket) -> dict[str, Any]:
    data = bytearray()
    while len(data) < _MAX_HANDSHAKE_BYTES:
        chunk = connection.recv(min(4096, _MAX_HANDSHAKE_BYTES - len(data)))
        if not chunk:
            break
        data.extend(chunk)
        if b"\n" in chunk:
            break
    if not data or b"\n" not in data:
        raise ContainmentError("containment handshake ended without a JSON line")
    line = bytes(data).split(b"\n", 1)[0]
    value = json.loads(line.decode("utf-8"))
    if not isinstance(value, dict):
        raise ContainmentError("containment handshake JSON is not an object")
    return value


def _send_json(connection: socket.socket, value: Mapping[str, Any]) -> None:
    connection.sendall(json.dumps(value, sort_keys=True).encode("utf-8") + b"\n")


def _send_nack(connection: socket.socket | None, reason: str) -> None:
    if connection is None:
        return
    try:
        _send_json(connection, {"schemaVersion": SCHEMA_VERSION, "ok": False, "reason": reason})
    except OSError:
        pass


def _validate_handshake(
    payload: Mapping[str, Any],
    *,
    token: str,
    unit: str,
    peer_pid: int,
    peer_uid: int,
    boot_id: str,
) -> None:
    if peer_uid != os.getuid():
        raise ContainmentError("containment peer is not owned by the current UID")
    if payload.get("schemaVersion") != SCHEMA_VERSION:
        raise ContainmentError("containment handshake schema mismatch")
    if payload.get("token") != token or payload.get("scopeUnit") != unit:
        raise ContainmentError("containment handshake token/scope mismatch")
    if payload.get("pid") != peer_pid or payload.get("uid") != peer_uid:
        raise ContainmentError("SO_PEERCRED does not match entry identity")
    if payload.get("bootId") != boot_id:
        raise ContainmentError("entry boot ID mismatch")
    if not isinstance(payload.get("startTicks"), str) or not payload["startTicks"]:
        raise ContainmentError("entry startTicks is missing")


def _fd_identity(fd: int) -> dict[str, int]:
    info = os.fstat(fd)
    return {"dev": int(info.st_dev), "inode": int(info.st_ino)}


def _path_identity_state(path: Path, identity: Mapping[str, int]) -> str:
    try:
        info = path.stat()
    except FileNotFoundError:
        return "missing"
    except OSError:
        return "unknown"
    if int(info.st_dev) != int(identity["dev"]) or int(info.st_ino) != int(identity["inode"]):
        return "changed"
    return "same"


def _read_fd_text(fd: int) -> str:
    try:
        os.lseek(fd, 0, os.SEEK_SET)
        raw = os.read(fd, 64 * 1024)
    except OSError as error:
        raise _CgroupReadError(error) from error
    return raw.decode("utf-8")


def _parse_cgroup_events(text: str) -> dict[str, Any]:
    values: dict[str, str] = {}
    for line in text.splitlines():
        if not line:
            continue
        if "=" in line:
            key, value = line.split("=", 1)
        else:
            fields = line.split(None, 1)
            if len(fields) != 2:
                raise ValueError("malformed cgroup.events reading")
            key, value = fields
        if key in values:
            raise ValueError("duplicate cgroup.events field")
        values[key] = value
    if values.get("populated") not in {"0", "1"}:
        raise ValueError("cgroup.events lacks a valid populated=0/1 reading")
    return {"populated": int(values["populated"]), "raw": text}


def _write_cgroup_kill(fd: int) -> None:
    written = os.write(fd, b"1")
    if written != 1:
        raise OSError(errno.EIO, "short cgroup.kill write")


def _atomic_write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.{secrets.token_hex(6)}.tmp")
    fd = os.open(
        str(temporary),
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC,
        0o600,
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            fd = -1
            json.dump(value, stream, ensure_ascii=False, sort_keys=True, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory_fd = os.open(str(path.parent), os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if fd >= 0:
            os.close(fd)
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def _mark_start_failure(path: Path, metadata: dict[str, Any], reason: str) -> None:
    metadata.update({"state": "UNKNOWN", "cleanup": "unknown", "failureReason": reason, "failedAt": _utc_now()})
    try:
        _atomic_write_json(path, metadata)
    except OSError:
        pass


def _sha256_json(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _main() -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--_enter", type=Path)
    args, unknown = parser.parse_known_args()
    if unknown or args._enter is None:
        return 2
    return _enter_main(args._enter)


if __name__ == "__main__":
    raise SystemExit(_main())
