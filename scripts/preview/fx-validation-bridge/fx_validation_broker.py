#!/usr/bin/env python3
"""Validation-only authenticated loopback broker for the reviewed fx ACP process."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import os
from pathlib import Path
import secrets
import selectors
import signal
import socket
import subprocess
import sys
import time

MAGIC = b"TURBOISM_FX_BRIDGE/1"
CHALLENGE = MAGIC + b" CHALLENGE\n"
AUTH = MAGIC + b" AUTH"
OK = MAGIC + b" OK\n"
CLIENT_DOMAIN = b"turboism-fx-bridge-client-v1"
BROKER_DOMAIN = b"turboism-fx-bridge-broker-v1"
FX_ARCHIVE_SHA256 = "d5639d173267774aa8228a474baf619a7076ac41a91023915007c865143429b1"
FX_EXECUTABLE_SHA256 = "27a5e9474fd749d6ca2503ab93765176a93ffbd0f0e7173e8f2e3e4c6b51876f"
FX_VERSION = "0.0.5"
MAX_HANDSHAKE_LINE = 256
MAX_STDERR_BYTES = 512 * 1024
DEFAULT_ACCEPT_TIMEOUT_SECONDS = 480.0
MIN_ACCEPT_TIMEOUT_SECONDS = 30.0
MAX_ACCEPT_TIMEOUT_SECONDS = 24 * 60 * 60.0
HANDSHAKE_TIMEOUT_SECONDS = 15.0
SESSION_TIMEOUT_SECONDS = 900.0
MAX_PENDING_STREAM_BYTES = 1024 * 1024


def fail(message: str) -> "None":
    raise RuntimeError(message)


def bounded_timeout(value: str) -> float:
    try:
        parsed = float(value)
    except ValueError as failure:
        raise argparse.ArgumentTypeError("timeout must be numeric") from failure
    if not MIN_ACCEPT_TIMEOUT_SECONDS <= parsed <= MAX_ACCEPT_TIMEOUT_SECONDS:
        raise argparse.ArgumentTypeError("timeout is outside the permitted range")
    return parsed


def require_private_directory(path: Path) -> Path:
    resolved = path.resolve(strict=True)
    if not resolved.is_dir() or path.is_symlink():
        fail("private directory is invalid")
    if resolved.stat().st_uid != os.getuid() or resolved.stat().st_mode & 0o077:
        fail("private directory permissions are invalid")
    return resolved


def read_private_file(path: Path, maximum: int) -> bytes:
    if path.is_symlink():
        fail("private file is a symbolic link")
    resolved = path.resolve(strict=True)
    stat = resolved.stat()
    if not resolved.is_file() or stat.st_uid != os.getuid() or stat.st_mode & 0o077:
        fail("private file permissions are invalid")
    data = resolved.read_bytes()
    if not 0 < len(data) <= maximum:
        fail("private file size is invalid")
    return data


def verify_fx(path: Path) -> Path:
    if path.is_symlink():
        fail("fx executable is a symbolic link")
    resolved = path.resolve(strict=True)
    if not resolved.is_file() or not os.access(resolved, os.X_OK):
        fail("fx executable is invalid")
    digest = hashlib.sha256(resolved.read_bytes()).hexdigest()
    if not hmac.compare_digest(digest, FX_EXECUTABLE_SHA256):
        fail("fx executable hash is not reviewed")
    version = subprocess.run(
        [str(resolved), "--version"],
        check=True,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        timeout=10,
        env=minimal_environment(Path.home()),
    ).stdout.strip()
    if version != FX_VERSION:
        fail("fx executable version is not reviewed")
    return resolved


def minimal_environment(home: Path) -> dict[str, str]:
    environment = {
        "HOME": str(home),
        "PATH": "/usr/bin:/bin",
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "NO_COLOR": "1",
        "TERM": "dumb",
    }
    for name in ("SSL_CERT_FILE", "SSL_CERT_DIR", "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY"):
        value = os.environ.get(name)
        if value:
            environment[name] = value
    return environment


def receive_line(connection: socket.socket, maximum: int) -> bytes:
    data = bytearray()
    while True:
        value = connection.recv(1)
        if not value:
            fail("bridge handshake ended")
        if value == b"\n":
            return bytes(data)
        if value == b"\r":
            continue
        if value[0] < 0x20 or value[0] > 0x7E or len(data) >= maximum:
            fail("bridge handshake is invalid")
        data.extend(value)


def authentication_mac(
    secret: bytes,
    domain: bytes,
    session_id: bytes,
    client_nonce: bytes,
    broker_nonce: bytes,
) -> bytes:
    mac = hmac.new(secret, digestmod=hashlib.sha256)
    for value in (domain, session_id, client_nonce, broker_nonce):
        mac.update(len(value).to_bytes(4, "big"))
        mac.update(value)
    return mac.digest()


def terminate_process_group(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + 3.0
    while process.poll() is None and time.monotonic() < deadline:
        time.sleep(0.05)
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        pass


def append_stderr(path: Path, data: bytes, retained: int) -> int:
    if retained >= MAX_STDERR_BYTES:
        return retained
    allowed = min(len(data), MAX_STDERR_BYTES - retained)
    if allowed:
        with path.open("ab", buffering=0) as output:
            output.write(data[:allowed])
        retained += allowed
    return retained


def proxy(connection: socket.socket, fx: subprocess.Popen[bytes], stderr_path: Path) -> None:
    if fx.stdin is None or fx.stdout is None or fx.stderr is None:
        fail("fx pipes are unavailable")
    selector = selectors.DefaultSelector()
    connection.setblocking(False)
    os.set_blocking(fx.stdin.fileno(), False)
    os.set_blocking(fx.stdout.fileno(), False)
    os.set_blocking(fx.stderr.fileno(), False)
    selector.register(connection, selectors.EVENT_READ, "bridge")
    selector.register(fx.stderr, selectors.EVENT_READ, "stderr")
    deadline = time.monotonic() + SESSION_TIMEOUT_SECONDS
    bridge_read_open = True
    bridge_write_open = True
    stdin_open = True
    stdout_open = True
    stdout_idle_deadline: float | None = None
    stderr_retained = 0
    pending_stdin = bytearray()
    pending_stdout = bytearray()
    try:
        while (bridge_read_open or bridge_write_open or stdout_open
                or pending_stdout or pending_stdin) \
            and time.monotonic() < deadline:
            bridge_events = 0
            if bridge_read_open and not pending_stdin:
                bridge_events |= selectors.EVENT_READ
            if bridge_write_open and pending_stdout:
                bridge_events |= selectors.EVENT_WRITE
            try:
                selector.get_key(connection)
            except KeyError:
                if bridge_events:
                    selector.register(connection, bridge_events, "bridge")
            else:
                if bridge_events:
                    selector.modify(connection, bridge_events, "bridge")
                else:
                    selector.unregister(connection)

            if stdin_open:
                try:
                    selector.get_key(fx.stdin)
                except KeyError:
                    if pending_stdin:
                        selector.register(fx.stdin, selectors.EVENT_WRITE, "stdin")
                else:
                    if pending_stdin:
                        selector.modify(fx.stdin, selectors.EVENT_WRITE, "stdin")
                    else:
                        selector.unregister(fx.stdin)

            stdout_readable = stdout_open \
                and len(pending_stdout) < MAX_PENDING_STREAM_BYTES
            try:
                selector.get_key(fx.stdout)
            except KeyError:
                if stdout_readable:
                    selector.register(fx.stdout, selectors.EVENT_READ, "stdout")
            else:
                if stdout_readable:
                    selector.modify(fx.stdout, selectors.EVENT_READ, "stdout")
                else:
                    selector.unregister(fx.stdout)

            if not selector.get_map():
                break
            events = selector.select(timeout=0.25)
            for key, mask in events:
                if key.data == "bridge":
                    if mask & selectors.EVENT_WRITE and pending_stdout:
                        try:
                            sent = connection.send(pending_stdout)
                        except BlockingIOError:
                            sent = 0
                        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                            bridge_read_open = False
                            bridge_write_open = False
                            pending_stdout.clear()
                            stdout_idle_deadline = time.monotonic() + 0.5
                            sent = 0
                        del pending_stdout[:sent]
                    if mask & selectors.EVENT_READ and bridge_read_open:
                        try:
                            data = connection.recv(64 * 1024)
                        except (ConnectionResetError, ConnectionAbortedError):
                            data = b""
                            bridge_write_open = False
                            pending_stdout.clear()
                            stdout_idle_deadline = time.monotonic() + 0.5
                        if data:
                            pending_stdin.extend(data)
                        else:
                            bridge_read_open = False
                            stdout_idle_deadline = time.monotonic() + 5.0
                            if not pending_stdin and stdin_open:
                                try:
                                    selector.unregister(fx.stdin)
                                except KeyError:
                                    pass
                                fx.stdin.close()
                                stdin_open = False
                elif key.data == "stdin":
                    try:
                        written = os.write(fx.stdin.fileno(), pending_stdin)
                    except BlockingIOError:
                        written = 0
                    except BrokenPipeError:
                        pending_stdin.clear()
                        stdin_open = False
                        fx.stdin.close()
                        bridge_read_open = False
                        written = 0
                    del pending_stdin[:written]
                    if not pending_stdin and not bridge_read_open and stdin_open:
                        try:
                            selector.unregister(fx.stdin)
                        except KeyError:
                            pass
                        fx.stdin.close()
                        stdin_open = False
                elif key.data == "stdout":
                    remaining = MAX_PENDING_STREAM_BYTES - len(pending_stdout)
                    if remaining <= 0:
                        continue
                    data = os.read(fx.stdout.fileno(), min(64 * 1024, remaining))
                    if data:
                        if bridge_write_open or pending_stdout:
                            pending_stdout.extend(data)
                    else:
                        stdout_open = False
                        selector.unregister(fx.stdout)
                else:
                    data = os.read(fx.stderr.fileno(), 16 * 1024)
                    if data:
                        stderr_retained = append_stderr(stderr_path, data, stderr_retained)
                    else:
                        selector.unregister(fx.stderr)
            if fx.poll() is not None and not stdout_open \
                and not pending_stdout and not pending_stdin:
                break
            if not bridge_read_open and stdout_idle_deadline is not None \
                and time.monotonic() >= stdout_idle_deadline:
                break
        if time.monotonic() >= deadline:
            fail("broker session deadline exceeded")
    finally:
        selector.close()

def write_ready(path: Path, session_id: str, port: int, token_file: Path) -> None:
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        "schemaVersion=1\n"
        f"sessionId={session_id}\n"
        "host=127.0.0.1\n"
        f"port={port}\n"
        f"tokenFile=Z:{token_file.as_posix()}\n",
        encoding="ascii",
    )
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)


def run(arguments: argparse.Namespace) -> None:
    if not 32 <= len(arguments.session_id) <= 128 or not all(
        character.isalnum() or character in "_-" for character in arguments.session_id
    ):
        fail("bridge session id is invalid")
    runtime_dir = require_private_directory(arguments.runtime_dir)
    fx_home = require_private_directory(arguments.fx_home)
    token_file = arguments.token_file
    token = read_private_file(token_file, MAX_HANDSHAKE_LINE).strip()
    token_file = token_file.resolve(strict=True)
    session_id = arguments.session_id.encode("ascii")
    if not 32 <= len(token) <= 128 or any(value < 0x21 or value > 0x7E for value in token):
        fail("bridge token is invalid")
    fx_executable = verify_fx(arguments.fx_executable)
    ready_file = runtime_dir / "fx-validation-bridge.properties"
    connected_file = runtime_dir / "fx-validation-bridge.connected"
    stderr_path = runtime_dir / "fx-stderr.log"
    ready_file.unlink(missing_ok=True)
    connected_file.unlink(missing_ok=True)
    stderr_path.unlink(missing_ok=True)

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    listener.settimeout(arguments.accept_timeout_seconds)
    try:
        write_ready(ready_file, arguments.session_id, listener.getsockname()[1], token_file)
        connection, peer = listener.accept()
        listener.close()
        if peer[0] != "127.0.0.1":
            fail("bridge peer is not loopback")
        with connection:
            connection.settimeout(HANDSHAKE_TIMEOUT_SECONDS)
            supplied_magic = receive_line(connection, MAX_HANDSHAKE_LINE)
            encoded_client_nonce = receive_line(connection, MAX_HANDSHAKE_LINE)
            try:
                client_nonce = base64.urlsafe_b64decode(encoded_client_nonce + b"===")
            except ValueError:
                fail("bridge authentication failed")
            if supplied_magic != MAGIC or len(client_nonce) != 32:
                fail("bridge authentication failed")
            broker_nonce = secrets.token_bytes(32)
            connection.sendall(CHALLENGE)
            connection.sendall(base64.urlsafe_b64encode(broker_nonce).rstrip(b"=") + b"\n")
            connection.sendall(authentication_mac(
                token, BROKER_DOMAIN, session_id, client_nonce, broker_nonce
            ).hex().encode("ascii") + b"\n")
            if receive_line(connection, MAX_HANDSHAKE_LINE) != AUTH:
                fail("bridge authentication failed")
            try:
                supplied_mac = bytes.fromhex(receive_line(connection, MAX_HANDSHAKE_LINE).decode("ascii"))
            except ValueError:
                fail("bridge authentication failed")
            expected_mac = authentication_mac(
                token, CLIENT_DOMAIN, session_id, client_nonce, broker_nonce
            )
            if not hmac.compare_digest(supplied_mac, expected_mac):
                fail("bridge authentication failed")
            connection.sendall(OK)
            connection.settimeout(None)
            connected_file.write_text("status=AUTHENTICATED\n", encoding="ascii")
            os.chmod(connected_file, 0o600)
            fx = subprocess.Popen(
                [str(fx_executable), "acp"],
                cwd=runtime_dir,
                env=minimal_environment(fx_home),
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                start_new_session=True,
            )
            try:
                proxy(connection, fx, stderr_path)
            finally:
                terminate_process_group(fx)
    finally:
        listener.close()
        ready_file.unlink(missing_ok=True)
        connected_file.unlink(missing_ok=True)


def expected_shutdown(arguments: argparse.Namespace) -> bool:
    runtime_dir = arguments.runtime_dir.resolve()
    return (runtime_dir / "shutdown-requested").is_file()


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--runtime-dir", required=True, type=Path)
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--token-file", required=True, type=Path)
    parser.add_argument("--fx-home", required=True, type=Path)
    parser.add_argument("--fx-executable", required=True, type=Path)
    parser.add_argument(
        "--accept-timeout-seconds",
        type=bounded_timeout,
        default=DEFAULT_ACCEPT_TIMEOUT_SECONDS,
    )
    return parser.parse_args()


if __name__ == "__main__":
    parsed_arguments = parse_arguments()
    try:
        run(parsed_arguments)
    except Exception:
        if not expected_shutdown(parsed_arguments):
            print("Turboism fx validation broker failed", file=sys.stderr)
        raise SystemExit(1)
