"""CLI baseline envelope parsing and output helpers."""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from sdk_api_baseline import BaselineError, GENERATOR_VERSION, SCHEMA_VERSION

FORMAT = "turboism.sdk.api-baseline"
COMMIT_RE = re.compile(r"[0-9a-f]{40}")
SHA_RE = re.compile(r"[0-9a-f]{64}")


def die(message: str) -> None:
    raise SystemExit(f"SDK API baseline: {message}")


def write_output(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def strict_json(path: Path, label: str) -> Any:
    if not path.is_file():
        raise BaselineError(f"{label} is missing: {path}")
    try:
        raw = path.read_bytes()
    except OSError as exc:
        raise BaselineError(f"{label} cannot be read: {path}") from exc
    if raw.startswith(b"\xef\xbb\xbf"):
        raise BaselineError(f"{label} must be UTF-8 without BOM")
    return _decode_json(raw, label)


def _decode_json(raw: bytes, label: str) -> Any:
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=_duplicate_rejector(label))
    except BaselineError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BaselineError(f"{label} is malformed: {exc}") from exc


def _duplicate_rejector(label: str):
    def reject(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise BaselineError(f"{label} contains duplicate JSON object key {key!r}")
            result[key] = value
        return result
    return reject


def load_baseline(path: Path) -> dict[str, Any]:
    value = strict_json(path, "baseline")
    _verify_baseline_shape(value)
    _verify_baseline_versions(value)
    _verify_baseline_metadata(value)
    return value


def _verify_baseline_shape(value: Any) -> None:
    expected = {"artifact", "canonicalDump", "commit", "format", "generatorVersion", "role", "schemaVersion"}
    if not isinstance(value, dict) or set(value) != expected:
        raise BaselineError("baseline has an invalid top-level shape")


def _verify_baseline_versions(value: dict[str, Any]) -> None:
    valid_schema = value["format"] == FORMAT and type(value["schemaVersion"]) is int and value["schemaVersion"] == SCHEMA_VERSION
    if not valid_schema:
        raise BaselineError("baseline format/schema is unsupported")
    if type(value["generatorVersion"]) is not int or value["generatorVersion"] != GENERATOR_VERSION:
        raise BaselineError("baseline generator version is unsupported")
    if value["role"] not in ("pre-phase", "exact"):
        raise BaselineError("baseline role is invalid")
    if not isinstance(value["commit"], str) or not COMMIT_RE.fullmatch(value["commit"]):
        raise BaselineError("baseline commit is invalid")


def _verify_baseline_metadata(value: dict[str, Any]) -> None:
    _verify_artifact(value["artifact"])
    _verify_canonical_dump(value["canonicalDump"])


def _verify_artifact(artifact: Any) -> None:
    if not isinstance(artifact, dict) or set(artifact) != {"sha256", "size"}:
        raise BaselineError("baseline artifact metadata is invalid")
    if not isinstance(artifact["sha256"], str) or not SHA_RE.fullmatch(artifact["sha256"]):
        raise BaselineError("baseline artifact SHA-256 is invalid")
    if type(artifact["size"]) is not int or artifact["size"] <= 0:
        raise BaselineError("baseline artifact size is invalid")


def _verify_canonical_dump(canonical: Any) -> None:
    if not isinstance(canonical, dict) or set(canonical) != {"lineCount", "sha256"}:
        raise BaselineError("baseline canonical dump metadata is invalid")
    if not isinstance(canonical["sha256"], str) or not SHA_RE.fullmatch(canonical["sha256"]):
        raise BaselineError("baseline canonical dump SHA-256 is invalid")
    if type(canonical["lineCount"]) is not int or canonical["lineCount"] < 3:
        raise BaselineError("baseline canonical dump line count is invalid")
