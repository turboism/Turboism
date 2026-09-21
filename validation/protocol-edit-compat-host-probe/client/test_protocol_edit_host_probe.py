#!/usr/bin/env python3
"""Unit tests for protocol_edit_host_probe against an in-process mock host.

The mock implements the server half of RFC 6455 on the standard library and a
scriptable Cubism-shaped responder, so the client matrix can be exercised
end-to-end without an exact host.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import socket
import struct
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import protocol_edit_host_probe as probe


WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class MockConnection(threading.Thread):
    """Server-side connection: parses masked client frames, calls the host
    script for each decoded request and writes the scripted replies."""

    def __init__(self, sock: socket.socket, host: "MockHost", index: int) -> None:
        super().__init__(daemon=True)
        self.sock = sock
        self.host = host
        self.index = index
        self.received: list[dict] = []
        self.closed = False

    def run(self) -> None:
        try:
            self._handshake()
            while not self.closed:
                message = self._recv_message()
                if message is None:
                    break
                request = json.loads(message)
                self.received.append(request)
                for reply in self.host.respond(self, request):
                    self._send_text(json.dumps(reply, separators=(",", ":")))
        except (OSError, ConnectionError, ValueError):
            pass
        finally:
            self.closed = True
            try:
                self.sock.close()
            except OSError:
                pass

    def _handshake(self) -> None:
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("client closed during handshake")
            data += chunk
        head = data.decode("latin-1")
        key = None
        for line in head.split("\r\n"):
            if line.lower().startswith("sec-websocket-key:"):
                key = line.split(":", 1)[1].strip()
        if key is None:
            raise ValueError("missing Sec-WebSocket-Key")
        accept = base64.b64encode(
            hashlib.sha1((key + WS_GUID).encode("ascii")).digest()
        ).decode("ascii")
        self.sock.sendall(
            (
                "HTTP/1.1 101 Switching Protocols\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Accept: {accept}\r\n"
                "\r\n"
            ).encode("ascii")
        )

    def _read_exact(self, count: int) -> bytes:
        data = b""
        while len(data) < count:
            chunk = self.sock.recv(count - len(data))
            if not chunk:
                raise ConnectionError("client closed")
            data += chunk
        return data

    def _recv_message(self):
        fragments = []
        while True:
            first = self._read_exact(2)
            fin = bool(first[0] & 0x80)
            opcode = first[0] & 0x0F
            masked = bool(first[1] & 0x80)
            length = first[1] & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._read_exact(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._read_exact(8))[0]
            if not masked:
                # RFC 6455 requires client frames to be masked; the probe must
                # comply, so the mock treats unmasked input as a defect.
                raise ValueError("client frame was not masked")
            mask = self._read_exact(4)
            payload = self._read_exact(length) if length else b""
            payload = bytes(
                byte ^ mask[i % 4] for i, byte in enumerate(payload))
            if opcode == 0x8:
                self._send_frame(0x8)
                return None
            if opcode == 0x9:
                self._send_frame(0xA, payload)
                continue
            if opcode in (0x0, 0x1):
                fragments.append(payload)
                if fin:
                    return b"".join(fragments).decode("utf-8")

    def _send_frame(self, opcode: int, payload: bytes = b"") -> None:
        header = bytearray([0x80 | opcode])
        length = len(payload)
        if length < 126:
            header.append(length)
        elif length <= 0xFFFF:
            header.append(126)
            header += struct.pack(">H", length)
        else:
            header.append(127)
            header += struct.pack(">Q", length)
        self.sock.sendall(bytes(header) + payload)

    def _send_text(self, text: str) -> None:
        self._send_frame(0x1, text.encode("utf-8"))

    def push_text(self, text: str) -> None:
        self._send_text(text)


class MockHost:
    """Scriptable Cubism-shaped responder.

    respond() receives the decoded request dict and returns a list of reply
    envelopes. Override/subclass for scripted scenarios; the default script
    replays a small but complete bridge session for the full-matrix test.
    """

    def __init__(self, script=None) -> None:
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(8)
        self.port = self.listener.getsockname()[1]
        self.connections: list[MockConnection] = []
        self._accept = threading.Thread(target=self._accept_loop, daemon=True)
        self._accept.start()
        self._script = script or self.default_respond
        # simulated bridge state
        self.registered: dict[int, bool] = {}
        self.session_owner: int | None = None
        self.parameter_name = "ParamEyeLOpen"
        self.denied_conns: set[int] = set()

    def _accept_loop(self) -> None:
        while True:
            try:
                sock, _ = self.listener.accept()
            except OSError:
                return
            conn = MockConnection(sock, self, len(self.connections))
            self.connections.append(conn)
            conn.start()

    def respond(self, conn: MockConnection, request: dict) -> list[dict]:
        return self._script(conn, request)

    def envelope(self, request: dict, type_: str, data: dict) -> dict:
        return {
            "Version": request.get("Version", "1.1.0"),
            "Timestamp": 1700000000000,
            "RequestId": request.get("RequestId"),
            "Type": type_,
            "Method": request.get("Method"),
            "Data": data,
        }

    def response(self, request: dict, data: dict) -> dict:
        return self.envelope(request, "Response", data)

    def error(self, request: dict, code: str) -> dict:
        return self.envelope(request, "Error", {"ErrorType": code})

    def default_respond(self, conn: MockConnection, request: dict) -> list[dict]:
        method = request.get("Method")
        data = request.get("Data")
        version = request.get("Version")
        index = conn.index

        if method == "RegisterPlugin":
            self.registered[index] = True
            return [self.response(request, {"Token": "mock-token"})]
        if method == "GetIsApproval":
            if index not in self.registered:
                return [self.response(request, {"Result": False})]
            return [self.response(request, {"Result": True})]

        # edit methods: version gate, then registration, then semantics
        edit_methods = {
            "GetIsEditApproval", "EditBegin", "EditEnd", "EditSendLog",
            "EditSendProgress", "NotifyUndoCancel", "AddParameterKey",
            "DeleteParameterKey", "MoveParameterKey", "GetParameterKeys",
            "GetObjectsByParameterKeys", "GetParameterStructure",
            "AddParameter", "AddParameterGroup", "EditParameter",
            "EditParameterGroup", "DeleteParameter", "DeleteParameterGroup",
            "MoveParameter", "MoveParameterGroup", "GetSelectedObjects",
            "AddSelectedObjects", "ClearSelectedObjects", "GetPartStructure",
            "GetObject", "DeleteObject", "MoveObjectOnPartsPalette", "AddPart",
            "EditPart", "EditArtMesh", "EditGlue", "GetDeformerStructure",
            "AddRotationDeformer", "AddWarpDeformer", "EditRotationDeformer",
            "EditWarpDeformer",
        }
        if method in edit_methods:
            if version != "1.1.0":
                return [self.error(request, "UnsupportedVersion")]
            if request.get("Type") != "Request":
                return [self.error(request, "InvalidType")]
            if index not in self.registered:
                return [self.error(request, "PluginNotRegistered")]
            if index in self.denied_conns:
                return [self.error(request, "InvalidEditOperation")]
            if index == 3 and method in ("EditBegin", "EditParameter"):
                self.denied_conns.add(index)
                return [self.error(request, "InvalidEditOperation")]
            if data is not None and not isinstance(data, dict):
                return [self.error(request, "InvalidData")]
            return [self.edit_respond(conn, request, data or {})]

        # native methods
        if method == "GetCurrentModelUID":
            return [self.response(request, {"ModelUID": "uid-model-1"})]
        if method == "GetCurrentDocumentUID":
            return [self.response(request, {"DocumentUID": "doc-1"})]
        if method == "GetAPIVersion":
            return [self.response(request, {"APIVersion": "1.0.1"})]
        if method == "GetParameters":
            return [self.response(request, {
                "Parameters": [{"Id": "ParamAngleX", "Value": 12.5}]})]
        if method == "SetParameterValues":
            return [self.response(request, {})]
        if method == "GetParameterGroups":
            return [self.response(request, {"ParameterGroups": []})]
        if method == "NotifyChangeEditMode":
            if not isinstance(data.get("Enabled"), bool):
                return [self.error(request, "InvalidData")]
            return [self.response(request, {})]
        return [self.error(request, "MethodNotFound")]

    def edit_respond(self, conn: MockConnection, request: dict,
                     data: dict) -> dict:
        method = request.get("Method")
        index = conn.index
        uid = "uid-model-1"
        if method == "GetIsEditApproval":
            return self.response(request, {"Result": index != 3})
        if method == "EditBegin":
            if self.session_owner is None:
                self.session_owner = index
                return self.response(request, {"Result": True})
            if self.session_owner == index:
                return self.response(request, {"Result": True})
            return self.response(request, {"Result": False})
        if method == "EditEnd":
            if self.session_owner is None:
                return self.response(request, {"Result": False})
            self.session_owner = None
            if data.get("Cancel") is True:
                self.parameter_name = "ParamEyeLOpen"
            return self.response(request, {"Result": True})
        if method == "NotifyUndoCancel":
            return self.response(request, {"Accepted": True})
        if method == "EditParameter":
            if self.session_owner != index:
                return self.error(request, "InvalidEditOperation")
            if not isinstance(data.get("Id"), str):
                return self.error(request, "InvalidData")
            return self.response(request, {"Result": True})
        if method == "EditParameterGroup":
            if self.session_owner != index:
                return self.error(request, "InvalidEditOperation")
            self.parameter_name = data.get("Name", self.parameter_name)
            return self.response(request, {"Result": True})
        if method in ("DeleteParameter", "DeleteParameterGroup",
                      "DeleteParameterKey"):
            if self.session_owner != index:
                return self.error(request, "InvalidEditOperation")
            return self.error(request, "InvalidParameter")
        if method == "MoveParameterKey":
            if self.session_owner != index:
                return self.error(request, "InvalidEditOperation")
            if not isinstance(data.get("FromValue"), (int, float)):
                return self.error(request, "InvalidData")
            return self.error(request, "InvalidParameter")
        if method == "GetParameterStructure":
            if not data.get("ModelUID"):
                return self.error(request, "InvalidData")
            return self.response(request, {"ParameterStructure": {
                "Name": "root", "Id": "root", "Entries": [
                    {"EntryType": "ParameterGroup", "Name": self.parameter_name,
                     "Id": "grp-1", "Entries": []},
                    {"EntryType": "Parameter", "Name": "ParamAngleX",
                     "Id": "ParamAngleX", "Min": -30, "Default": 0,
                     "Max": 30, "IsRepeat": False, "IsBlendShape": False},
                ]}})
        if method == "GetPartStructure":
            return self.response(request, {"PartStructure": {
                "Name": "root", "Id": "part-root", "Type": "Part",
                "Children": [
                    {"Name": "mesh", "Id": "mesh-1", "Type": "ArtMesh",
                     "Children": []},
                ]}})
        if method == "GetDeformerStructure":
            return self.response(request, {"DeformerStructure": {
                "Name": "root", "Id": "def-root", "Type": "WarpDeformer",
                "Children": []}})
        if method == "GetObject":
            if data.get("Id") == "mesh-1":
                return self.response(request, {
                    "Result": True, "Type": "ArtMesh",
                    "Data": {"Name": "mesh", "Id": "mesh-1", "Vertices": 4}})
            return self.error(request, "InvalidModel")
        if method == "GetParameterKeys":
            return self.response(request, {"Parameters": [
                {"Id": "ParamAngleX", "KeyValues": [0.0, 1.0]}]})
        if method == "GetSelectedObjects":
            return self.response(request, {"Ids": []})
        if method == "GetObjectsByParameterKeys":
            return self.response(request, {"Ids": ["mesh-1"]})
        return self.response(request, {"Result": True})

    def close(self) -> None:
        try:
            self.listener.close()
        except OSError:
            pass
        for conn in self.connections:
            try:
                conn.sock.close()
            except OSError:
                pass


def make_probe(home: Path, host: MockHost) -> probe.Probe:
    os.environ["TURBOISM_EDIT_PROTOCOL_PORT"] = str(host.port)
    os.environ["TURBOISM_EDIT_PROTOCOL_CONNECT_TIMEOUT"] = "5"
    os.environ["TURBOISM_EDIT_PROTOCOL_CALL_TIMEOUT"] = "5"
    os.environ["TURBOISM_EDIT_PROTOCOL_APPROVAL_TIMEOUT"] = "5"
    os.environ["TURBOISM_EDIT_PROTOCOL_SEED_DELAY"] = "0"
    p = probe.Probe(home, "task-test")
    return p


class EnvelopeTest(unittest.TestCase):
    def test_parse_response(self):
        frame = probe.parse_frame(
            '{"Version":"1.1.0","Timestamp":1,"RequestId":7,"Type":"Response",'
            '"Method":"EditBegin","Data":{"Result":true}}')
        self.assertTrue(frame.is_response)
        self.assertEqual(frame.request_id, 7)
        self.assertTrue(frame.result_flag())

    def test_parse_error(self):
        frame = probe.parse_frame(
            '{"Type":"Error","RequestId":2,"Data":{"ErrorType":"InvalidData"}}')
        self.assertTrue(frame.is_error)
        self.assertEqual(frame.error, "InvalidData")

    def test_parse_rejects_garbage(self):
        with self.assertRaises(probe.ValidationFailure):
            probe.parse_frame("not json")
        with self.assertRaises(probe.ValidationFailure):
            probe.parse_frame('["array"]')
        with self.assertRaises(probe.ValidationFailure):
            probe.parse_frame('{"Type":"Weird","Data":{}}')
        with self.assertRaises(probe.ValidationFailure):
            probe.parse_frame('{"Type":"Error","Data":{}}')

    def test_sanitize(self):
        self.assertEqual(probe.sanitize('a=b\nc"d\\e'), "a_b_c_d_e")


class TransportTest(unittest.TestCase):
    def test_request_envelope_shape_and_masking(self):
        host = MockHost(script=lambda conn, req: [host.response(req, {"Result": True})])
        self.addCleanup(host.close)
        with tempfile.TemporaryDirectory() as tmp:
            p = make_probe(Path(tmp), host)
            conn = probe.connect("127.0.0.1", host.port, 5.0, "t")
            frame = p.call(conn, "GetIsEditApproval", {})
            self.assertTrue(frame.is_response)
            request = host.connections[0].received[0]
            self.assertEqual(request["Type"], "Request")
            self.assertEqual(request["Method"], "GetIsEditApproval")
            self.assertEqual(request["Version"], "1.1.0")
            self.assertIsInstance(request["Timestamp"], int)
            self.assertIsInstance(request["RequestId"], int)
            self.assertEqual(request["Data"], {})
            conn.close()

    def test_stray_and_event_drainage(self):
        def script(conn, req):
            if req.get("Method") == "Ping":
                # unsolicited event + foreign response before the real one
                conn.push_text(json.dumps({
                    "Version": "1.1.0", "Timestamp": 1, "Type": "Event",
                    "Method": "NotifyUndoCancel", "Data": {"Result": True}}))
                conn.push_text(json.dumps({
                    "Version": "1.1.0", "Timestamp": 1, "RequestId": 99,
                    "Type": "Response", "Method": "RegisterPlugin",
                    "Data": {"Token": "dup"}}))
                return [host.response(req, {"Result": True})]
            return [host.response(req, {})]

        host = MockHost(script=script)
        self.addCleanup(host.close)
        with tempfile.TemporaryDirectory() as tmp:
            p = make_probe(Path(tmp), host)
            conn = probe.connect("127.0.0.1", host.port, 5.0, "t")
            frame = p.call(conn, "Ping", {})
            self.assertTrue(frame.is_response)
            self.assertEqual(len(conn.events), 1)
            self.assertEqual(len(conn.stray_frames), 1)
            conn.close()

    def test_close_frame_raises(self):
        def script(conn, req):
            conn.sock.sendall(b"\x88\x00")  # unmasked close frame
            return []

        host = MockHost(script=script)
        self.addCleanup(host.close)
        conn = probe.connect("127.0.0.1", host.port, 5.0, "t")
        with self.assertRaises(probe.WsClosed):
            conn.call("X", {}, "1.1.0", 5.0)

    def test_malformed_host_frame_fails_closed(self):
        host = MockHost(script=lambda conn, req: [])
        self.addCleanup(host.close)
        conn = probe.connect("127.0.0.1", host.port, 5.0, "t")
        host.connections[0].push_text("not json")
        with self.assertRaises(probe.ValidationFailure):
            conn.call("X", {}, "1.1.0", 5.0)

    def test_handshake_failure(self):
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        port = server.getsockname()[1]

        def refuse():
            sock, _ = server.accept()
            sock.recv(4096)
            sock.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
            sock.close()

        threading.Thread(target=refuse, daemon=True).start()
        try:
            with self.assertRaises(probe.WsHandshakeError):
                probe.WebSocket("127.0.0.1", port, 5.0)
        finally:
            server.close()


class MatrixTest(unittest.TestCase):
    def test_full_matrix_pass(self):
        host = MockHost()
        self.addCleanup(host.close)
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            p = make_probe(home, host)
            rc = p.run()
            result = (home / "state" / probe.RESULT_FILE).read_text()
            self.assertEqual(rc, 0, result)
            self.assertIn("status=PASS", result)
            self.assertIn("runId=task-test", result)
            self.assertIn("assertion.s1.edit-begin.result.status=PASS", result)
            self.assertIn("assertion.s6.edit-approval.denied.status=PASS", result)
            self.assertIn("assertion.r.get-parameters.status=PASS", result)
            # every assertion line carries a status
            for line in result.splitlines():
                if line.startswith("assertion."):
                    self.assertIn(".status=", line)

    def test_connect_failure_writes_fail(self):
        os.environ["TURBOISM_EDIT_PROTOCOL_PORT"] = "1"
        os.environ["TURBOISM_EDIT_PROTOCOL_CONNECT_TIMEOUT"] = "1"
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            p = probe.Probe(home, "task-down")
            rc = p.run()
            result = (home / "state" / probe.RESULT_FILE).read_text()
            self.assertEqual(rc, 1)
            self.assertIn("status=FAIL", result)
            self.assertIn("error.", result)


if __name__ == "__main__":
    unittest.main()
