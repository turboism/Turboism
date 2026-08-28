#!/usr/bin/env python3
"""Offline contract tests for bounded fx validation-broker lifetimes."""

from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading


ROOT = Path(__file__).resolve().parents[2]
BROKER = ROOT / "scripts/preview/fx-validation-bridge/fx_validation_broker.py"
SPEC = importlib.util.spec_from_file_location("fx_validation_broker", BROKER)
if SPEC is None or SPEC.loader is None:
    raise SystemExit("fx validation broker could not be loaded")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

assert MODULE.bounded_timeout("30") == 30.0
assert MODULE.bounded_timeout("86400") == 86400.0

for invalid in ("29", "86401", "nan", "inf", "not-a-number"):
    try:
        MODULE.bounded_timeout(invalid)
    except Exception as failure:
        if failure.__class__.__name__ != "ArgumentTypeError":
            raise
    else:
        raise AssertionError(f"broker accepted invalid timeout: {invalid}")

source = BROKER.read_text(encoding="utf-8")
assert "listener.settimeout(arguments.accept_timeout_seconds)" in source
assert '"--accept-timeout-seconds"' in source
assert "selectors.EVENT_WRITE" in source
assert "connection.send(pending_stdout)" in source
assert "connection.sendall(pending_stdout)" not in source
assert "len(pending_stdout) < MAX_PENDING_STREAM_BYTES" in source
assert "MAX_PENDING_STREAM_BYTES - len(pending_stdout)" in source


class PipeFixture:
    def __init__(self, script: str = "import time; time.sleep(60)") -> None:
        self.process = subprocess.Popen(
            [sys.executable, "-c", script],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
        )

    def __enter__(self):
        return self.process

    def __exit__(self, *_):
        MODULE.terminate_process_group(self.process)


def install_stdout_pipe(fx: subprocess.Popen[bytes]):
    assert fx.stdout is not None
    read_fd, write_fd = os.pipe()
    original_stdout = fx.stdout
    fx.stdout = os.fdopen(read_fd, "rb", buffering=0)
    return original_stdout, write_fd


def write_payload(write_fd: int, payload: bytes, completed: threading.Event) -> None:
    view = memoryview(payload)
    try:
        while view:
            view = view[os.write(write_fd, view):]
    finally:
        os.close(write_fd)
        completed.set()


def assert_proxy_flushes_large_stdout() -> None:
    left, right = socket.socketpair()
    payload = os.urandom(512 * 1024)
    try:
        with tempfile.TemporaryDirectory(prefix="fx-broker-test-") as directory:
            with PipeFixture() as fx:
                original_stdout, write_fd = install_stdout_pipe(fx)
                completed = threading.Event()
                writer = threading.Thread(
                    target=write_payload,
                    args=(write_fd, payload, completed),
                    daemon=True,
                )
                thread = threading.Thread(
                    target=MODULE.proxy,
                    args=(left, fx, Path(directory) / "stderr.log"),
                    daemon=True,
                )
                thread.start()
                writer.start()
                right.settimeout(5)
                received = bytearray()
                while len(received) < len(payload):
                    chunk = right.recv(64 * 1024)
                    if not chunk:
                        break
                    received.extend(chunk)
                right.close()
                writer.join(timeout=5)
                thread.join(timeout=5)
                fx.stdout.close()
                fx.stdout = original_stdout
                assert completed.is_set(), "broker stdout writer did not finish"
                assert bytes(received) == payload, "broker proxy lost or reordered stdout"
    finally:
        left.close()
        right.close()


def assert_proxy_flushes_final_stdout_after_client_eof() -> None:
    left, right = socket.socketpair()
    try:
        with tempfile.TemporaryDirectory(prefix="fx-broker-half-close-") as directory:
            with PipeFixture(
                "import sys; sys.stdin.buffer.read(); "
                "sys.stdout.buffer.write(b'FINAL'); sys.stdout.buffer.flush()"
            ) as fx:
                thread = threading.Thread(
                    target=MODULE.proxy,
                    args=(left, fx, Path(directory) / "stderr.log"),
                    daemon=True,
                )
                thread.start()
                right.sendall(b"request")
                right.shutdown(socket.SHUT_WR)
                right.settimeout(5)
                received = bytearray()
                while len(received) < len(b"FINAL"):
                    chunk = right.recv(64 * 1024)
                    if not chunk:
                        break
                    received.extend(chunk)
                assert bytes(received) == b"FINAL", (
                    "broker lost final fx stdout after client write EOF"
                )
                right.close()
                thread.join(timeout=5)
                assert not thread.is_alive(), "half-close broker proxy did not finish"
    finally:
        left.close()
        right.close()


def assert_proxy_exits_after_child_eof_with_client_open() -> None:
    left, right = socket.socketpair()
    try:
        with tempfile.TemporaryDirectory(prefix="fx-broker-child-eof-") as directory:
            with PipeFixture("pass") as fx:
                thread = threading.Thread(
                    target=MODULE.proxy,
                    args=(left, fx, Path(directory) / "stderr.log"),
                    daemon=True,
                )
                thread.start()
                thread.join(timeout=3)
                assert not thread.is_alive(), (
                    "broker proxy did not exit after child EOF while client stayed open"
                )
    finally:
        left.close()
        right.close()


def assert_proxy_exits_after_silent_client_eof() -> None:
    left, right = socket.socketpair()
    try:
        with tempfile.TemporaryDirectory(prefix="fx-broker-silent-eof-") as directory:
            with PipeFixture() as fx:
                thread = threading.Thread(
                    target=MODULE.proxy,
                    args=(left, fx, Path(directory) / "stderr.log"),
                    daemon=True,
                )
                thread.start()
                right.shutdown(socket.SHUT_WR)
                thread.join(timeout=7)
                assert not thread.is_alive(), (
                    "broker proxy did not exit after client EOF and silent child"
                )
    finally:
        left.close()
        right.close()


def assert_proxy_exits_after_abrupt_disconnect() -> None:
    left, right = socket.socketpair()
    left.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
    try:
        with tempfile.TemporaryDirectory(prefix="fx-broker-disconnect-") as directory:
            with PipeFixture(
                "import os, sys; block = b'x' * 65536; "
                "[(os.write(sys.stdout.fileno(), block)) for _ in iter(int, 1)]"
            ) as fx:
                thread = threading.Thread(
                    target=MODULE.proxy,
                    args=(left, fx, Path(directory) / "stderr.log"),
                    daemon=True,
                )
                thread.start()
                right.close()
                thread.join(timeout=3)
                assert not thread.is_alive(), (
                    "broker proxy did not exit promptly after abrupt disconnect "
                    "from a continuously chatty child"
                )
    finally:
        left.close()
        right.close()


def assert_proxy_backpressures_stalled_stdout() -> None:
    left, right = socket.socketpair()
    left.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
    right.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    payload = os.urandom(MODULE.MAX_PENDING_STREAM_BYTES + 512 * 1024)
    try:
        with tempfile.TemporaryDirectory(prefix="fx-broker-backpressure-") as directory:
            with PipeFixture() as fx:
                original_stdout, write_fd = install_stdout_pipe(fx)
                completed = threading.Event()
                writer = threading.Thread(
                    target=write_payload,
                    args=(write_fd, payload, completed),
                    daemon=True,
                )
                thread = threading.Thread(
                    target=MODULE.proxy,
                    args=(left, fx, Path(directory) / "stderr.log"),
                    daemon=True,
                )
                thread.start()
                writer.start()

                # Do not consume the socket. Once the bounded pending buffer and
                # socket send buffer fill, the broker must stop reading fx stdout.
                assert not completed.wait(timeout=1), (
                    "broker consumed more than the bounded stdout capacity while "
                    "the remote peer was stalled"
                )

                right.settimeout(5)
                received = bytearray()
                while len(received) < len(payload):
                    chunk = right.recv(64 * 1024)
                    if not chunk:
                        break
                    received.extend(chunk)
                right.close()
                writer.join(timeout=5)
                thread.join(timeout=5)
                fx.stdout.close()
                fx.stdout = original_stdout
                assert completed.is_set(), "backpressured stdout writer did not resume"
                assert bytes(received) == payload, "backpressured proxy lost or reordered stdout"
    finally:
        left.close()
        right.close()


assert_proxy_flushes_large_stdout()
assert_proxy_flushes_final_stdout_after_client_eof()
assert_proxy_exits_after_child_eof_with_client_open()
assert_proxy_exits_after_silent_client_eof()
assert_proxy_exits_after_abrupt_disconnect()
assert_proxy_backpressures_stalled_stdout()
print(
    "PASS: fx validation broker argument bounds, half-close, disconnect, "
    "buffering, and backpressure"
)
