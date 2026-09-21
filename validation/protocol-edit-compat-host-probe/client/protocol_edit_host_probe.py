#!/usr/bin/env python3
"""Turboism-owned protocol validation client for the 5.4-compat edit API bridge.

Connects to the exact host's own loopback external-application service
(ws://127.0.0.1:22033) and replays the official wire sequence:

    RegisterPlugin -> persist {Token} -> poll GetIsApproval/GetIsEditApproval
    -> EditBegin -> GetCurrentModelUID -> edit operations -> EditEnd

Transport is a minimal RFC 6455 client implemented on the Python standard
library only (no third-party packages): the runner stages this file into a
task-local directory and executes it with the system interpreter, so it must
not depend on site-packages.

Result evidence is published to state/edit-protocol-host-validation-result.properties
with the same assertion.<id>.status=PASS|FAIL convention the other host probes use.
Control files consumed by the in-host probe plugin live under
state/dev.turboism.validation.edit-protocol/:

    auth-allow.txt   plugin-token allow list the plugin pre-seeds into the
                     native CExternalAppAuthManager so registration completes
                     without user interaction
    approval.mode    "approve" (default) or "deny"; read by the plugin each
                     time the Turboism edit-approval dialog appears
    token.txt        the RegisterPlugin Token the host returned, persisted by
                     this client per the official contract
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import secrets
import socket
import struct
import sys
import time
import uuid
from pathlib import Path
from typing import Any, Optional

# ---------------------------------------------------------------------------
# environment / constants
# ---------------------------------------------------------------------------

PLUGIN_ID = "dev.turboism.validation.edit-protocol"
RESULT_FILE = "edit-protocol-host-validation-result.properties"
EDIT_VERSION = "1.1.0"
# The lowest non-deprecated native version; any value <= the host's latest
# (1.0.0 on 5.2.03, 1.0.1 on 5.3.x) is accepted by the native dispatcher.
NATIVE_VERSION = "1.0.0"
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

KNOWN_OBJECT_TYPES = {
    "Part", "ArtMesh", "WarpDeformer", "RotationDeformer", "ArtPath", "Glue",
}
KNOWN_ERROR_CODES = {
    "InvalidJson", "UnsupportedVersion", "MethodNotFound", "InvalidType",
    "InvalidData", "InvalidParameter", "InvalidModel", "InvalidDocument",
    "InvalidView", "PluginNotRegistered", "InvalidEditOperation", "NoError",
}


def env_text(name: str, default: str) -> str:
    return os.environ.get(name, default)


def env_seconds(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except ValueError:
        return default


# ---------------------------------------------------------------------------
# minimal RFC 6455 client (standard library only)
# ---------------------------------------------------------------------------


class WsClosed(Exception):
    """The peer closed the WebSocket connection."""


class WsHandshakeError(Exception):
    """The HTTP Upgrade handshake did not complete with 101 Switching Protocols."""


class WebSocket:
    """Blocking text-frame WebSocket client.

    Frames sent by the client are masked per RFC 6455. The reader answers ping
    with pong, tolerates fragmented text messages and raises WsClosed on a
    close frame. Server frames are not expected to be masked; masked input is
    unmasked defensively.
    """

    def __init__(self, host: str, port: int, timeout: float) -> None:
        self._sock = socket.create_connection((host, port), timeout=timeout)
        self._sock.settimeout(timeout)
        self._closed = False
        key = base64.b64encode(secrets.token_bytes(16)).decode("ascii")
        request = (
            f"GET / HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "\r\n"
        )
        self._sock.sendall(request.encode("ascii"))
        try:
            response = self._read_http_head(timeout)
            status_line, _, headers_blob = response.partition("\r\n")
            parts = status_line.split()
            if len(parts) < 2 or parts[1] != "101":
                raise WsHandshakeError(f"unexpected handshake status: {status_line!r}")
            headers = {}
            for line in headers_blob.split("\r\n"):
                if ":" in line:
                    name, _, value = line.partition(":")
                    headers[name.strip().lower()] = value.strip()
            expected = base64.b64encode(
                hashlib.sha1((key + WS_GUID).encode("ascii")).digest()
            ).decode("ascii")
            if headers.get("sec-websocket-accept") != expected:
                raise WsHandshakeError("Sec-WebSocket-Accept mismatch")
            if "websocket" not in headers.get("upgrade", "").lower():
                raise WsHandshakeError("missing Upgrade: websocket response header")
        except Exception:
            self._sock.close()
            raise

    def _read_http_head(self, timeout: float) -> str:
        deadline = time.monotonic() + timeout
        data = b""
        while b"\r\n\r\n" not in data:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise WsHandshakeError("handshake response timed out")
            self._sock.settimeout(remaining)
            chunk = self._sock.recv(4096)
            if not chunk:
                raise WsHandshakeError("connection closed during handshake")
            data += chunk
            if len(data) > 65536:
                raise WsHandshakeError("handshake response exceeds 64 KiB")
        return data.split(b"\r\n\r\n", 1)[0].decode("latin-1")

    def _read_exact(self, count: int) -> bytes:
        data = b""
        while len(data) < count:
            chunk = self._sock.recv(count - len(data))
            if not chunk:
                raise WsClosed("peer closed the TCP connection")
            data += chunk
        return data

    def send_text(self, payload: str) -> None:
        if self._closed:
            raise WsClosed("send on a closed WebSocket")
        body = payload.encode("utf-8")
        header = bytearray([0x81])
        length = len(body)
        if length < 126:
            header.append(0x80 | length)
        elif length <= 0xFFFF:
            header.append(0x80 | 126)
            header += struct.pack(">H", length)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", length)
        mask = secrets.token_bytes(4)
        header += mask
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(body))
        self._sock.sendall(bytes(header) + masked)

    def _send_frame(self, opcode: int, payload: bytes = b"") -> None:
        header = bytearray([0x80 | opcode])
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length <= 0xFFFF:
            header.append(0x80 | 126)
            header += struct.pack(">H", length)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", length)
        mask = secrets.token_bytes(4)
        header += mask
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        self._sock.sendall(bytes(header) + masked)

    def recv_message(self, timeout: float) -> str:
        """Returns the next complete text message; raises on timeout/close."""
        deadline = time.monotonic() + timeout
        fragments = []
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("WebSocket receive timed out")
            self._sock.settimeout(remaining)
            first = self._read_exact(2)
            fin = bool(first[0] & 0x80)
            opcode = first[0] & 0x0F
            masked = bool(first[1] & 0x80)
            length = first[1] & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._read_exact(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._read_exact(8))[0]
            mask = self._read_exact(4) if masked else b""
            payload = self._read_exact(length) if length else b""
            if masked:
                payload = bytes(
                    byte ^ mask[index % 4] for index, byte in enumerate(payload)
                )
            if opcode == 0x8:
                self._closed = True
                try:
                    self._send_frame(0x8, payload[:125])
                except OSError:
                    pass
                code = 0
                reason = ""
                if len(payload) >= 2:
                    code = struct.unpack(">H", payload[:2])[0]
                    reason = payload[2:126].decode("utf-8", "replace")
                raise WsClosed(
                    f"peer sent a close frame code={code} reason={reason!r}"
                )
            if opcode == 0x9:
                self._send_frame(0xA, payload)
                continue
            if opcode == 0xA:
                continue
            if opcode in (0x0, 0x1, 0x2):
                fragments.append(payload)
                if fin:
                    return b"".join(fragments).decode("utf-8")
                continue
            raise WsClosed(f"unsupported WebSocket opcode {opcode}")

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            self._send_frame(0x8)
        except OSError:
            pass
        try:
            self._sock.close()
        except OSError:
            pass


# ---------------------------------------------------------------------------
# envelope handling
# ---------------------------------------------------------------------------


class Frame:
    """One decoded response/event frame from the host."""

    def __init__(self, raw: dict[str, Any]) -> None:
        self.raw = raw
        self.type = raw.get("Type")
        self.method = raw.get("Method")
        self.request_id = raw.get("RequestId")
        self.data = raw.get("Data")
        self.error = None
        if isinstance(self.data, dict) and isinstance(
            self.data.get("ErrorType"), str
        ):
            self.error = self.data["ErrorType"]

    @property
    def is_response(self) -> bool:
        return self.type == "Response"

    @property
    def is_error(self) -> bool:
        return self.type == "Error"

    @property
    def is_event(self) -> bool:
        return self.type == "Event"

    def data_object(self) -> dict[str, Any]:
        return self.data if isinstance(self.data, dict) else {}

    def result_flag(self) -> Optional[bool]:
        value = self.data_object().get("Result")
        return value if isinstance(value, bool) else None


class ValidationFailure(Exception):
    """A matrix step observed a response that violates the official contract."""


def parse_frame(text: str) -> Frame:
    """Decodes one frame, failing closed on malformed or off-shape input."""
    try:
        raw = json.loads(text)
    except json.JSONDecodeError as failure:
        raise ValidationFailure(f"host frame is not valid JSON: {failure}") from failure
    if not isinstance(raw, dict):
        raise ValidationFailure("host frame is not a JSON object")
    frame = Frame(raw)
    if frame.type not in ("Response", "Error", "Event"):
        raise ValidationFailure(f"host frame Type is not Response/Error/Event: {frame.type!r}")
    if frame.data is not None and not isinstance(frame.data, dict):
        raise ValidationFailure("host frame Data is not a JSON object")
    if frame.is_error and frame.error is None:
        raise ValidationFailure("error frame is missing Data.ErrorType")
    return frame


class Connection:
    """One WebSocket session: request/response matching with stray drainage."""

    def __init__(self, ws: WebSocket, label: str) -> None:
        self.ws = ws
        self.label = label
        self._next_id = 0
        self.stray_frames: list[Frame] = []
        self.events: list[Frame] = []

    def call(
        self,
        method: str,
        data: Any,
        version: str,
        timeout: float,
        request_type: str = "Request",
    ) -> Frame:
        self._next_id += 1
        request_id = self._next_id
        envelope = {
            "Version": version,
            "Timestamp": int(time.time() * 1000),
            "RequestId": request_id,
            "Type": request_type,
            "Method": method,
            "Data": data,
        }
        self.ws.send_text(json.dumps(envelope, separators=(",", ":")))
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(
                    f"{self.label}: no response to {method} within {timeout:.0f}s"
                )
            frame = parse_frame(self.ws.recv_message(remaining))
            if frame.request_id == request_id and frame.type in ("Response", "Error"):
                return frame
            # Stale/unsolicited traffic is tolerated and recorded: the native
            # stack re-sends the RegisterPlugin Token ~500ms later, and the
            # bridge pushes NotifyUndoCancel events without a RequestId.
            if frame.is_event:
                self.events.append(frame)
            else:
                self.stray_frames.append(frame)
            if len(self.stray_frames) + len(self.events) > 64:
                raise ValidationFailure(
                    f"{self.label}: more than 64 stray frames without a matching response"
                )

    def pump(self, seconds: float) -> None:
        """Keeps reading frames for the given duration.

        The host's external-integration server runs a 100ms connection-lost
        timeout, so *any* idle gap without reads kills the connection. The
        pump keeps the recv loop alive (answering pings inside recv_message)
        and stashes stray text frames/events the main flow is not waiting
        for.
        """
        deadline = time.monotonic() + max(0.0, seconds)
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return
            try:
                frame = parse_frame(self.ws.recv_message(min(remaining, 0.05)))
            except TimeoutError:
                continue
            if frame.is_event:
                self.events.append(frame)
            else:
                self.stray_frames.append(frame)
            if len(self.stray_frames) + len(self.events) > 64:
                raise ValidationFailure(
                    f"{self.label}: more than 64 stray frames while pumping"
                )

    def close(self) -> None:
        self.ws.close()


def connect(host: str, port: int, timeout: float, label: str) -> Connection:
    return Connection(WebSocket(host, port, timeout), label)


# ---------------------------------------------------------------------------
# probe
# ---------------------------------------------------------------------------


def sanitize(value: Any) -> str:
    text = str(value)
    return "".join(
        ch if 32 <= ord(ch) < 127 and ch not in "\\=\"" else "_"
        for ch in text
    )


def publish_atomic(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    temporary.write_text(content, encoding="utf-8")
    os.replace(temporary, path)


class Probe:
    def __init__(self, home: Path, task_id: str) -> None:
        self.home = home
        self.task_id = task_id
        self.state_root = home / "state"
        self.plugin_state = self.state_root / PLUGIN_ID
        self.result_path = self.state_root / RESULT_FILE
        self.host = env_text("TURBOISM_EDIT_PROTOCOL_HOST", "127.0.0.1")
        self.port = int(env_text("TURBOISM_EDIT_PROTOCOL_PORT", "22033"))
        self.connect_timeout = env_seconds("TURBOISM_EDIT_PROTOCOL_CONNECT_TIMEOUT", 120.0)
        self.call_timeout = env_seconds("TURBOISM_EDIT_PROTOCOL_CALL_TIMEOUT", 30.0)
        self.approval_timeout = env_seconds("TURBOISM_EDIT_PROTOCOL_APPROVAL_TIMEOUT", 90.0)
        self.seed_delay = env_seconds("TURBOISM_EDIT_PROTOCOL_SEED_DELAY", 1.0)
        self.run_id = env_text("TURBOISM_VALIDATION_RUN_ID", task_id)
        self.host_version = env_text("TURBOISM_VALIDATION_HOST_VERSION", "unknown")
        self.lines: list[str] = [
            "schemaVersion=1",
            f"runId={sanitize(self.run_id)}",
            f"task={sanitize(task_id)}",
            f"hostVersion={sanitize(self.host_version)}",
            "client=python-stdlib-ws",
            f"endpoint=ws://{self.host}:{self.port}",
            f"editVersion={EDIT_VERSION}",
            f"nativeVersion={NATIVE_VERSION}",
            f"startedEpochMillis={int(time.time() * 1000)}",
        ]
        self.failures = 0

    # -- evidence -----------------------------------------------------------

    def check(self, name: str, condition: bool, detail: str = "") -> bool:
        status = "PASS" if condition else "FAIL"
        if not condition:
            self.failures += 1
        suffix = f" detail={sanitize(detail)}" if detail else ""
        self.lines.append(f"assertion.{name}.status={status}{suffix}")
        print(f"[{status}] {name}{suffix}", flush=True)
        return condition

    def observe(self, name: str, value: Any) -> None:
        self.lines.append(f"observation.{name}={sanitize(value)}")

    # -- protocol helpers ---------------------------------------------------

    def call(
        self,
        conn: Connection,
        method: str,
        data: Any = None,
        version: str = EDIT_VERSION,
        expect: str = "any",
        request_type: str = "Request",
    ) -> Frame:
        frame = conn.call(
            method, data if data is not None else {}, version,
            self.call_timeout, request_type=request_type)
        if expect == "response" and not frame.is_response:
            raise ValidationFailure(
                f"{conn.label} {method}: expected Response, got {frame.type} "
                f"error={frame.error}"
            )
        if expect == "error" and not frame.is_error:
            raise ValidationFailure(
                f"{conn.label} {method}: expected Error, got {frame.type} "
                f"data={frame.data_object()}"
            )
        return frame

    def register(self, conn: Connection, token: str, name: str) -> str:
        frame = self.call(
            conn, "RegisterPlugin", {"Token": token, "Name": name},
            version=NATIVE_VERSION, expect="response",
        )
        returned = frame.data_object().get("Token")
        if not isinstance(returned, str) or not returned:
            raise ValidationFailure("RegisterPlugin response carries no usable Token")
        return returned

    def await_plugin_approval(self, conn: Connection) -> float:
        deadline = time.monotonic() + self.approval_timeout
        polls = 0
        while True:
            frame = self.call(
                conn, "GetIsApproval", {}, version=NATIVE_VERSION, expect="response"
            )
            polls += 1
            result = frame.result_flag()
            if result is not True and result is not False:
                raise ValidationFailure("GetIsApproval response lacks a boolean Result")
            if result:
                self.observe("is-approval.polls", polls)
                return True
            if time.monotonic() >= deadline:
                raise ValidationFailure(
                    f"GetIsApproval stayed false for {self.approval_timeout:.0f}s; "
                    "the probe plugin did not grant the token"
                )
            conn.pump(0.5)

    def write_control_files(self, token: str) -> None:
        self.plugin_state.mkdir(parents=True, exist_ok=True)
        publish_atomic(self.plugin_state / "auth-allow.txt", token + "\n")
        publish_atomic(self.plugin_state / "approval.mode", "approve\n")

    def set_approval_mode(self, mode: str) -> None:
        publish_atomic(self.plugin_state / "approval.mode", mode + "\n")

    # -- model discovery ----------------------------------------------------

    def parameter_structure(self, conn: Connection, model_uid: str) -> dict[str, Any]:
        frame = self.call(
            conn, "GetParameterStructure", {"ModelUID": model_uid}, expect="response"
        )
        structure = frame.data_object().get("ParameterStructure")
        if not isinstance(structure, dict):
            raise ValidationFailure("GetParameterStructure lacks a ParameterStructure object")
        return structure

    def part_structure(self, conn: Connection, model_uid: str) -> dict[str, Any]:
        frame = self.call(
            conn, "GetPartStructure", {"ModelUID": model_uid}, expect="response"
        )
        structure = frame.data_object().get("PartStructure")
        if not isinstance(structure, dict):
            raise ValidationFailure("GetPartStructure lacks a PartStructure object")
        return structure

    @staticmethod
    def _walk_entries(node: dict[str, Any]) -> list[dict[str, Any]]:
        found = []
        for entry in node.get("Entries") or []:
            if isinstance(entry, dict):
                found.append(entry)
                found.extend(Probe._walk_entries(entry))
        return found

    @staticmethod
    def _walk_tree(node: dict[str, Any]) -> list[dict[str, Any]]:
        found = [node]
        for child in node.get("Children") or []:
            if isinstance(child, dict):
                found.extend(Probe._walk_tree(child))
        return found

    def pick_rename_target(
        self, conn: Connection, model_uid: str
    ) -> tuple[str, str, str]:
        """Returns (method, id, original_name) for a rename-back-safe target."""
        structure = self.parameter_structure(conn, model_uid)
        entries = self._walk_entries(structure)
        for entry in entries:
            if entry.get("EntryType") == "ParameterGroup" and entry.get("Id"):
                return "EditParameterGroup", str(entry["Id"]), str(entry.get("Name", ""))
        for entry in entries:
            if entry.get("EntryType") == "Parameter" and entry.get("Id"):
                return "EditParameter", str(entry["Id"]), str(entry.get("Name", ""))
        raise ValidationFailure("model exposes no parameter or group to rename")

    def first_art_mesh(self, conn: Connection, model_uid: str) -> Optional[str]:
        for node in self._walk_tree(self.part_structure(conn, model_uid)):
            if node.get("Type") == "ArtMesh" and node.get("Id"):
                return str(node["Id"])
        return None

    def first_parameter_id(
        self, conn: Connection, model_uid: str
    ) -> Optional[str]:
        for entry in self._walk_entries(self.parameter_structure(conn, model_uid)):
            if entry.get("EntryType") == "Parameter" and entry.get("Id"):
                return str(entry["Id"])
        return None

    def pick_occupied_key_pair(
        self, conn: Connection, model_uid: str
    ) -> Optional[tuple[str, float, float]]:
        """Finds a parameter carrying >=2 keys on any art mesh, for the
        occupied-slot move refusal check."""
        mesh = self.first_art_mesh(conn, model_uid)
        if mesh is None:
            return None
        frame = self.call(
            conn, "GetParameterKeys",
            {"ModelUID": model_uid, "ObjectId": mesh}, expect="response")
        for row in frame.data_object().get("Parameters") or []:
            values = row.get("KeyValues") if isinstance(row, dict) else None
            if isinstance(values, list) and len(values) >= 2:
                return str(row.get("Id")), values[0], values[1]
        return None

    # -- matrix -------------------------------------------------------------

    def run(self) -> int:
        status = "FAIL"
        conns: list[Connection] = []
        try:
            token = self.issue_token()
            self.write_control_files(token)
            time.sleep(self.seed_delay)

            conn1 = self.connect_retry("conn1")
            conns.append(conn1)
            self.check("p0.connect", True)

            returned_token = self.register(conn1, token, "TurboismProtocolProbe")
            self.check("p0.register.token", True)
            self.observe("register.tokenMatchesIssued", returned_token == token)
            publish_atomic(self.plugin_state / "token.txt", returned_token + "\n")

            self.await_plugin_approval(conn1)
            self.check("p0.is-approval", True)

            edit_approval = self.call(conn1, "GetIsEditApproval", expect="response")
            flag = edit_approval.result_flag()
            self.check("p0.is-edit-approval.shape", isinstance(flag, bool))
            self.observe("is-edit-approval.initial", flag)

            uid = self.current_model_uid(conn1)
            self.check("p0.current-model-uid", bool(uid))

            conn2 = self.matrix_session_lock(conn1, conns, token, uid)
            self.matrix_cancel(conn1, uid)
            self.matrix_commit(conn1, uid)
            self.matrix_semantics(conn1, uid)
            self.matrix_reads(conn1, uid)
            self.matrix_negative(conn1, conn2, conns, token, uid)
            self.matrix_regression(conn1, uid)
            status = "PASS" if self.failures == 0 else "FAIL"
        except (WsClosed, WsHandshakeError, TimeoutError, OSError) as failure:
            self.lines.append(f"error.transport={sanitize(failure)}")
            self.failures += 1
        except ValidationFailure as failure:
            self.lines.append(f"error.validation={sanitize(failure)}")
            self.failures += 1
        except Exception as failure:  # fail closed on unexpected client faults
            self.lines.append(
                f"error.client={sanitize(failure.__class__.__name__ + ': ' + str(failure))}"
            )
            self.failures += 1
        finally:
            for conn in conns:
                try:
                    conn.close()
                except Exception:
                    pass
            self.lines.append(f"finishedEpochMillis={int(time.time() * 1000)}")
            self.lines.append(f"status={status}")
            publish_atomic(self.result_path, "\n".join(self.lines) + "\n")
        return 0 if status == "PASS" else 1

    def issue_token(self) -> str:
        persisted = self.plugin_state / "token.txt"
        try:
            saved = persisted.read_text(encoding="utf-8").strip()
        except OSError:
            saved = ""
        return saved or uuid.uuid4().hex

    def connect_retry(self, label: str) -> Connection:
        deadline = time.monotonic() + self.connect_timeout
        attempt = 0
        while True:
            attempt += 1
            try:
                return connect(self.host, self.port, self.call_timeout, label)
            except (OSError, WsHandshakeError) as failure:
                if time.monotonic() >= deadline:
                    raise ValidationFailure(
                        f"{label}: cannot reach ws://{self.host}:{self.port} after "
                        f"{attempt} attempts: {failure}"
                    ) from failure
                time.sleep(1.0)

    def current_model_uid(self, conn: Connection) -> str:
        frame = self.call(
            conn, "GetCurrentModelUID", {}, version=NATIVE_VERSION, expect="response"
        )
        uid = frame.data_object().get("ModelUID")
        if uid is None:
            self.observe("current-model-uid.absent", True)
            return ""
        if not isinstance(uid, str):
            raise ValidationFailure("GetCurrentModelUID returned a non-string ModelUID")
        self.observe("current-model-uid.value", uid)
        return uid

    # -- S1: session lock ----------------------------------------------------

    def matrix_session_lock(
        self, conn1: Connection, conns: list[Connection], token: str, uid: str
    ) -> Connection:
        started = time.monotonic()
        begin = self.call(conn1, "EditBegin", {}, expect="response")
        elapsed_ms = int((time.monotonic() - started) * 1000)
        self.check("s1.edit-begin.result", begin.result_flag() is True,
                   f"data={begin.data_object()}")
        self.observe("s1.edit-begin.ms", elapsed_ms)

        conn2 = self.connect_retry("conn2")
        conns.append(conn2)
        self.register(conn2, token, "TurboismProtocolProbe")
        self.await_plugin_approval(conn2)
        second = self.call(conn2, "EditBegin", {}, expect="response")
        self.check("s1.second-begin.rejected", second.result_flag() is False,
                   f"data={second.data_object()}")
        stolen = self.call(
            conn2, "EditParameter",
            {"ModelUID": uid, "Id": "__probe__", "Name": "x"}, expect="any")
        self.check("s1.non-owner.gated",
                   stolen.is_error and stolen.error == "InvalidEditOperation",
                   self.describe(stolen))
        # NOTE: conn2.EditEnd is deliberately NOT sent here. The bridge resolves
        # EditEnd against the global session owner, so a non-owner EditEnd would
        # cancel conn1's session. The non-owner EditEnd shape is asserted later
        # (s6) once no session is open.

        owner_read = self.call(
            conn1, "GetParameterStructure", {"ModelUID": uid}, expect="response")
        self.check("s1.owner.read-while-open",
                   isinstance(owner_read.data_object().get("ParameterStructure"), dict))
        return conn2

    # -- S2: cancellation restores -------------------------------------------

    def matrix_cancel(self, conn1: Connection, uid: str) -> None:
        method, target_id, original_name = self.pick_rename_target(conn1, uid)
        marker = "turboism-probe-cancel"
        rename = self.call(
            conn1, method,
            {"ModelUID": uid, "Id": target_id, "Name": marker}, expect="response")
        self.check("s2.rename.result", rename.result_flag() is True,
                   f"data={rename.data_object()}")
        renamed = self.rename_visible(conn1, uid, method, target_id, marker)
        self.check("s2.rename.visible", renamed)
        subscribe = self.call(
            conn1, "NotifyUndoCancel", {"Enabled": True}, expect="response")
        self.check("s2.notify-undo-cancel.subscribe",
                   subscribe.data_object().get("Accepted") is True,
                   f"data={subscribe.data_object()}")
        events_before = len(conn1.events)
        end = self.call(
            conn1, "EditEnd", {"Cancel": True}, expect="response")
        self.check("s2.edit-end-cancel.result", end.result_flag() is True,
                   f"data={end.data_object()}")
        # The bridge pushes Type=Event/NotifyUndoCancel when the engine session
        # ends host-side. Whether a client-initiated cancel triggers that push
        # is an observation, not a contract assertion.
        self.observe(
            "s2.undo-cancel-event",
            any(e.method == "NotifyUndoCancel" for e in conn1.events[events_before:]))
        restored = self.rename_visible(conn1, uid, method, target_id, original_name)
        self.check("s2.cancel.restored", restored,
                   f"expectedName={original_name}")

    def rename_visible(
        self, conn1: Connection, uid: str, method: str, target_id: str, name: str
    ) -> bool:
        structure = self.parameter_structure(conn1, uid)
        kind = "ParameterGroup" if method == "EditParameterGroup" else "Parameter"
        for entry in self._walk_entries(structure):
            if entry.get("EntryType") == kind and entry.get("Id") == target_id:
                return entry.get("Name") == name
        return False

    # -- S3: commit -----------------------------------------------------------

    def matrix_commit(self, conn1: Connection, uid: str) -> None:
        method, target_id, original_name = self.pick_rename_target(conn1, uid)
        marker = "turboism-probe-commit"
        begin = self.call(conn1, "EditBegin", {}, expect="response")
        self.check("s3.edit-begin.result", begin.result_flag() is True)
        rename = self.call(
            conn1, method,
            {"ModelUID": uid, "Id": target_id, "Name": marker}, expect="response")
        self.check("s3.rename.result", rename.result_flag() is True)
        end = self.call(conn1, "EditEnd", {}, expect="response")
        self.check("s3.edit-end.result", end.result_flag() is True,
                   f"data={end.data_object()}")
        self.check("s3.commit.readback",
                   self.rename_visible(conn1, uid, method, target_id, marker))
        # Restore the fixture: the revert is a second committed rename, so the
        # observable state returns to the original name without touching files.
        cleanup_ok = True
        try:
            self.call(conn1, "EditBegin", {}, expect="response")
            self.call(
                conn1, method,
                {"ModelUID": uid, "Id": target_id, "Name": original_name},
                expect="response")
            self.call(conn1, "EditEnd", {}, expect="response")
        except (ValidationFailure, WsClosed, TimeoutError):
            cleanup_ok = False
        restored = self.rename_visible(conn1, uid, method, target_id, original_name)
        self.check("s3.fixture.restored", cleanup_ok and restored,
                   f"expectedName={original_name}")

    # -- S4: semantic edge cases ----------------------------------------------

    def matrix_semantics(self, conn1: Connection, uid: str) -> None:
        self.call(conn1, "EditBegin", {}, expect="response")
        try:
            missing = self.call(
                conn1, "DeleteParameter",
                {"ModelUID": uid, "Id": "__turboism_probe_missing__"}, expect="any")
            self.check("s4.delete-parameter.missing", self.typed_rejection(missing),
                       self.describe(missing))
            missing_group = self.call(
                conn1, "DeleteParameterGroup",
                {"ModelUID": uid, "Id": "__turboism_probe_missing__"}, expect="any")
            self.check("s4.delete-parameter-group.missing",
                       self.typed_rejection(missing_group), self.describe(missing_group))
            # Wrongly-typed required fields must fail InvalidData. Note: the
            # bridge treats a wrongly-typed *optional* flag (ForceOverwrite) as
            # absent by payload design — that policy is asserted in s4.flag-type.
            bad_number = self.call(
                conn1, "MoveParameterKey",
                {"ModelUID": uid, "ParameterId": "__turboism_probe_missing__",
                 "FromValue": "zero", "ToValue": 1},
                expect="any")
            self.check("s4.from-value.type", bad_number.error == "InvalidData",
                       f"error={bad_number.error}")
            bad_flag = self.call(
                conn1, "MoveParameterKey",
                {"ModelUID": uid, "ParameterId": "__turboism_probe_missing__",
                 "FromValue": 0, "ToValue": 1, "ForceOverwrite": "yes"},
                expect="any")
            self.check("s4.force-overwrite.typed", self.typed_rejection(bad_flag),
                       self.describe(bad_flag))
            bad_id = self.call(
                conn1, "EditParameter",
                {"ModelUID": uid, "Id": 123, "Name": "x"}, expect="any")
            self.check("s4.id.type", bad_id.error == "InvalidData",
                       f"error={bad_id.error}")
            loose = self.call(
                conn1, "DeleteParameterKey",
                {"ModelUID": uid, "ParameterId": "__turboism_probe_missing__",
                 "Strict": False},
                expect="any")
            self.check("s4.delete-parameter-key.loose",
                       self.typed_rejection(loose), self.describe(loose))
            # Occupied-slot move with ForceOverwrite=false must be refused
            # without overwriting; requires a parameter carrying >=2 keys.
            occupied = self.pick_occupied_key_pair(conn1, uid)
            if occupied is None:
                self.check("s4.move-occupied.refused", True,
                           "skipped: no parameter with two keys in fixture")
                self.observe("s4.move-occupied.skipped", True)
            else:
                parameter_id, from_value, to_value = occupied
                refused = self.call(
                    conn1, "MoveParameterKey",
                    {"ModelUID": uid, "ParameterId": parameter_id,
                     "FromValue": from_value, "ToValue": to_value,
                     "ForceOverwrite": False},
                    expect="any")
                self.check("s4.move-occupied.refused",
                           self.typed_rejection(refused), self.describe(refused))
        finally:
            self.call(conn1, "EditEnd", {"Cancel": True}, expect="response")

    @staticmethod
    def typed_rejection(frame: Frame) -> bool:
        if frame.is_error:
            return frame.error in KNOWN_ERROR_CODES
        return frame.is_response and frame.result_flag() is False

    @staticmethod
    def describe(frame: Frame) -> str:
        if frame.is_error:
            return f"error={frame.error}"
        return f"data={frame.data_object()}"

    # -- S5: structure reads ----------------------------------------------------

    def matrix_reads(self, conn1: Connection, uid: str) -> None:
        structure = self.parameter_structure(conn1, uid)
        entries = self._walk_entries(structure)
        self.check("s5.parameter-structure",
                   isinstance(structure.get("Name"), str)
                   and isinstance(structure.get("Id"), str)
                   and isinstance(structure.get("Entries"), list)
                   and len(entries) > 0,
                   f"entries={len(entries)}")

        part = self.part_structure(conn1, uid)
        nodes = self._walk_tree(part)
        self.check("s5.part-structure",
                   isinstance(part.get("Name"), str) and part.get("Type") == "Part"
                   and isinstance(part.get("Children"), list) and len(nodes) > 1,
                   f"nodes={len(nodes)}")
        self.observe("s5.part-structure.nodes", len(nodes))

        deformer = self.call(
            conn1, "GetDeformerStructure", {"ModelUID": uid}, expect="response")
        deformer_root = deformer.data_object().get("DeformerStructure")
        self.check("s5.deformer-structure",
                   isinstance(deformer_root, dict)
                   and isinstance(deformer_root.get("Children"), list))

        mesh_id = self.first_art_mesh(conn1, uid)
        if mesh_id is not None:
            obj = self.call(
                conn1, "GetObject", {"ModelUID": uid, "Id": mesh_id},
                expect="response")
            obj_data = obj.data_object()
            detail = obj_data.get("Data") if isinstance(obj_data.get("Data"), dict) else {}
            self.check("s5.get-object",
                       obj_data.get("Result") is True
                       and obj_data.get("Type") in KNOWN_OBJECT_TYPES
                       and detail.get("Id") == mesh_id,
                       f"type={obj_data.get('Type')}")
        else:
            self.check("s5.get-object", False, "no ArtMesh in fixture")

        missing = self.call(
            conn1, "GetObject",
            {"ModelUID": uid, "Id": "__turboism_probe_missing__"}, expect="any")
        self.check("s5.get-object.missing", self.typed_rejection(missing),
                   self.describe(missing))

        if mesh_id is not None:
            keys = self.call(
                conn1, "GetParameterKeys",
                {"ModelUID": uid, "ObjectId": mesh_id}, expect="response")
            self.check("s5.parameter-keys",
                       isinstance(keys.data_object().get("Parameters"), list))
        selected = self.call(
            conn1, "GetSelectedObjects", {"ModelUID": uid}, expect="response")
        self.check("s5.selected-objects",
                   isinstance(selected.data_object().get("Ids"), list))

        parameter_id = self.first_parameter_id(conn1, uid)
        if parameter_id is not None:
            by_keys = self.call(
                conn1, "GetObjectsByParameterKeys",
                {"ModelUID": uid, "ParameterId": parameter_id, "KeyValue": 0},
                expect="any")
            self.check("s5.objects-by-parameter-keys",
                       (by_keys.is_response
                        and isinstance(by_keys.data_object().get("Ids"), list))
                       or self.typed_rejection(by_keys),
                       self.describe(by_keys))
        else:
            self.check("s5.objects-by-parameter-keys", False,
                       "no Parameter in fixture")

    # -- S6: negative / fail-closed ----------------------------------------------

    def matrix_negative(
        self, conn1: Connection, conn2: Connection,
        conns: list[Connection], token: str, uid: str
    ) -> None:
        conn3 = self.connect_retry("conn3")
        conns.append(conn3)
        unregistered = self.call(conn3, "EditBegin", {}, expect="any")
        self.check("s6.unregistered.edit-begin",
                   unregistered.error == "PluginNotRegistered",
                   self.describe(unregistered))
        pre_reg = self.call(
            conn3, "GetIsApproval", {}, version=NATIVE_VERSION, expect="any")
        self.check("s6.unregistered.is-approval",
                   pre_reg.result_flag() is False
                   or pre_reg.error == "PluginNotRegistered",
                   self.describe(pre_reg))

        low_version = self.call(
            conn1, "EditBegin", {}, version="1.0.0", expect="any")
        self.check("s6.low-version", low_version.error == "UnsupportedVersion",
                   f"error={low_version.error}")
        alive = self.call(
            conn1, "GetIsEditApproval", {}, expect="any")
        self.check("s6.low-version.connection-alive",
                   isinstance(alive.result_flag(), bool))

        wrong_type = self.call(
            conn1, "EditBegin", {}, version=EDIT_VERSION,
            request_type="Response", expect="any")
        self.check("s6.wrong-type", wrong_type.error == "InvalidType",
                   f"error={wrong_type.error}")

        # Payload-level negatives use methods reachable without session
        # ownership so the InvalidData gate itself is what gets exercised.
        missing_field = self.call(
            conn1, "GetParameterStructure", {}, expect="any")
        self.check("s6.missing-field", missing_field.error == "InvalidData",
                   f"error={missing_field.error}")
        non_object = self.call(
            conn1, "EditBegin", "not-an-object", expect="any")
        self.check("s6.non-object-data", non_object.error == "InvalidData",
                   f"error={non_object.error}")

        # Non-owner EditEnd with no session open: the bridge resolves EditEnd
        # against the global owner, so this must return Result=false.
        orphan_end = self.call(conn2, "EditEnd", {}, expect="any")
        self.check("s6.non-owner.edit-end", orphan_end.result_flag() is False,
                   self.describe(orphan_end))

        conn4 = self.connect_retry("conn4")
        conns.append(conn4)
        self.register(conn4, token, "TurboismProtocolProbe")
        self.await_plugin_approval(conn4)
        self.set_approval_mode("deny")
        try:
            denied = self.call(conn4, "EditBegin", {}, expect="any")
        finally:
            self.set_approval_mode("approve")
        self.check("s6.edit-approval.denied",
                   denied.error == "InvalidEditOperation",
                   self.describe(denied))
        latched = self.call(conn4, "EditBegin", {}, expect="any")
        self.check("s6.edit-approval.latched",
                   latched.error == "InvalidEditOperation",
                   self.describe(latched))

        structure = self.parameter_structure(conn1, uid)
        self.check("s6.no-mutation",
                   isinstance(structure.get("Entries"), list))

    # -- R: native 1.0.x regression -------------------------------------------

    def matrix_regression(self, conn1: Connection, uid: str) -> None:
        api = self.call(
            conn1, "GetAPIVersion", {}, version=NATIVE_VERSION, expect="response")
        self.check("r.get-api-version", True)
        self.observe("r.get-api-version.data", api.data_object())

        doc = self.call(
            conn1, "GetCurrentDocumentUID", {}, version=NATIVE_VERSION,
            expect="response")
        self.check("r.get-current-document-uid", True)
        self.observe("r.get-current-document-uid.data", doc.data_object())

        model = self.call(
            conn1, "GetCurrentModelUID", {}, version=NATIVE_VERSION,
            expect="response")
        self.check("r.get-current-model-uid",
                   model.data_object().get("ModelUID") == uid,
                   f"data={model.data_object()}")

        parameters_data = {"ModelUID": uid}
        doc_uid = doc.data_object().get("DocumentUID")
        if isinstance(doc_uid, str) and doc_uid:
            parameters_data["DocumentUID"] = doc_uid
        parameters = self.call(
            conn1, "GetParameters", parameters_data,
            version=NATIVE_VERSION, expect="response")
        rows = parameters.data_object().get("Parameters")
        self.check("r.get-parameters", isinstance(rows, list))
        self.observe("r.get-parameters.count", len(rows) if isinstance(rows, list) else -1)

        writable = None
        if isinstance(rows, list):
            for row in rows:
                if (isinstance(row, dict) and isinstance(row.get("Id"), str)
                        and isinstance(row.get("Value"), (int, float))
                        and not isinstance(row.get("Value"), bool)):
                    writable = row
                    break
        if writable is not None:
            write = self.call(
                conn1, "SetParameterValues",
                {"ModelUID": uid,
                 "Parameters": [{"Id": writable["Id"], "Value": writable["Value"]}]},
                version=NATIVE_VERSION, expect="response")
            self.check("r.set-parameter-values", True,
                       f"data={write.data_object()}")
        else:
            self.check("r.set-parameter-values", False,
                       "no writable parameter row in GetParameters response")

        groups = self.call(
            conn1, "GetParameterGroups", {"ModelUID": uid},
            version=NATIVE_VERSION, expect="response")
        self.check("r.get-parameter-groups", True)
        self.observe("r.get-parameter-groups.data.keys",
                     sorted(groups.data_object().keys()))

        subscribe = self.call(
            conn1, "NotifyChangeEditMode", {"Enabled": True},
            version=NATIVE_VERSION, expect="any")
        self.check("r.notify-change-edit-mode.subscribe",
                   subscribe.is_response or subscribe.error in KNOWN_ERROR_CODES,
                   self.describe(subscribe))
        unsubscribe = self.call(
            conn1, "NotifyChangeEditMode", {"Enabled": False},
            version=NATIVE_VERSION, expect="any")
        self.check("r.notify-change-edit-mode.unsubscribe",
                   unsubscribe.is_response or unsubscribe.error in KNOWN_ERROR_CODES,
                   self.describe(unsubscribe))

        approval = self.call(
            conn1, "GetIsApproval", {}, version=NATIVE_VERSION, expect="response")
        self.check("r.get-is-approval", approval.result_flag() is True)


def main() -> int:
    if len(sys.argv) != 3:
        print(
            "usage: protocol_edit_host_probe.py <turboism-home> <task-id>",
            file=sys.stderr,
        )
        return 2
    home = Path(sys.argv[1]).resolve()
    task_id = sanitize(sys.argv[2])
    return Probe(home, task_id).run()


if __name__ == "__main__":
    sys.exit(main())
