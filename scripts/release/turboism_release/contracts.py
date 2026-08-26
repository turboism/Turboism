"""Strict JSON contracts shared by Turboism release commands."""
from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
from pathlib import Path
from typing import Any


FORMATS = {
    "candidate": ("turboism.release-candidate", 1),
    "plan": ("turboism.release-plan", 1),
    "state": ("turboism.release-state", 1),
}
SECRET_KEY = re.compile(r"(?:secret|token|password|private[_-]?key|credential)", re.IGNORECASE)
LOCAL_PATH_MARKERS = (
    "/workspace/",
    "/home/",
    "/Users/",
    "\\Users\\",
    "/.claude/",
    "\\.claude\\",
)


class ReleaseError(ValueError):
    """Fail-closed release contract error."""


def canonical_bytes(value: Any) -> bytes:
    """Serialize one JSON-compatible value in canonical repository form."""
    validate_public_value(value)
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def plan_id(plan_without_id: dict[str, Any]) -> str:
    if "planId" in plan_without_id:
        raise ReleaseError("planId must be omitted while deriving a plan id")
    return sha256_bytes(canonical_bytes(plan_without_id))


def validate_public_value(value: Any, path: str = "document") -> None:
    if value is None or isinstance(value, (bool, int, float)):
        if isinstance(value, float) and (value != value or value in (float("inf"), float("-inf"))):
            raise ReleaseError(f"{path}: non-finite numbers are not allowed")
        return
    if isinstance(value, str):
        normalized = value.replace("\\", "/")
        for marker in LOCAL_PATH_MARKERS:
            if marker.replace("\\", "/") in normalized:
                raise ReleaseError(f"{path}: local absolute paths are not publishable")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            validate_public_value(item, f"{path}[{index}]")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise ReleaseError(f"{path}: JSON object keys must be strings")
            if SECRET_KEY.search(key):
                raise ReleaseError(f"{path}.{key}: secret-bearing keys are not publishable")
            validate_public_value(item, f"{path}.{key}")
        return
    raise ReleaseError(f"{path}: unsupported JSON value {type(value).__name__}")


def validate_document(document: Any, kind: str) -> dict[str, Any]:
    expected = FORMATS.get(kind)
    if expected is None:
        raise ReleaseError(f"unknown release document kind: {kind}")
    if not isinstance(document, dict):
        raise ReleaseError(f"{kind} document must be a JSON object")
    expected_format, expected_schema = expected
    if document.get("format") != expected_format:
        raise ReleaseError(f"{kind} format must be {expected_format!r}")
    if document.get("schemaVersion") != expected_schema:
        raise ReleaseError(f"{kind} schemaVersion must be {expected_schema}")
    validate_public_value(document)
    if kind == "plan":
        supplied = document.get("planId")
        if not isinstance(supplied, str) or not re.fullmatch(r"[0-9a-f]{64}", supplied):
            raise ReleaseError("planId must be a lowercase SHA-256")
        payload = dict(document)
        del payload["planId"]
        expected_id = plan_id(payload)
        if supplied != expected_id:
            raise ReleaseError("planId does not match the canonical plan")
    if kind == "state":
        supplied = document.get("planId")
        if not isinstance(supplied, str) or not re.fullmatch(r"[0-9a-f]{64}", supplied):
            raise ReleaseError("state planId must be a lowercase SHA-256")
    return document


def read_document(path: Path, kind: str) -> dict[str, Any]:
    try:
        raw = path.read_bytes()
    except OSError as failure:
        raise ReleaseError(f"cannot read {kind} document {path}: {failure}") from failure
    if len(raw) > 16 * 1024 * 1024:
        raise ReleaseError(f"{kind} document exceeds 16 MiB")
    try:
        text = raw.decode("utf-8", errors="strict")
        document = json.loads(text, object_pairs_hook=_reject_duplicate_pairs)
    except (UnicodeDecodeError, json.JSONDecodeError, ReleaseError) as failure:
        raise ReleaseError(f"cannot parse {kind} document {path}: {failure}") from failure
    return validate_document(document, kind)


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ReleaseError(f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def write_document(path: Path, document: dict[str, Any], kind: str) -> None:
    validate_document(document, kind)
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(canonical_bytes(document) + b"\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
