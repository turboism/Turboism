"""Shared JSON, digest, and target primitives for SDK API tiers."""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Any, Callable, Iterable

from sdk_api_baseline_common import BaselineError

TIER_POLICY_FORMAT = "turboism.sdk.api-tier-policy"
INITIAL_PREVIEW_LEDGER_FORMAT = "turboism.sdk.api-initial-preview-ledger"
SCHEMA_VERSION = 1
GENERATOR_VERSION = 1
SHA_RE = re.compile(r"[0-9a-f]{64}")
COMMIT_RE = re.compile(r"[0-9a-f]{40}")


@dataclass(frozen=True)
class Digest:
    line_count: int
    sha256: str

    def as_json(self) -> dict[str, object]:
        return {"lineCount": self.line_count, "sha256": self.sha256}


@dataclass(frozen=True)
class NewPreviewAdmission:
    identity: str
    root: dict[str, str]
    admitted_owned_records: Digest


def canonical_json(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def canonical_record_digest(records: Iterable[str]) -> Digest:
    ordered = sorted(records)
    payload = "".join(record + "\n" for record in ordered).encode("utf-8")
    return Digest(len(ordered), hashlib.sha256(payload).hexdigest())


def strict_json(path, label: str) -> Any:
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
        return json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicates(label))
    except BaselineError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BaselineError(f"{label} is malformed: {exc}") from exc


def _reject_duplicates(label: str):
    def reject(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise BaselineError(f"{label} contains duplicate JSON object key {key!r}")
            result[key] = value
        return result
    return reject


def closed_object(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise BaselineError(f"{label} has an invalid closed shape")
    return value


def digest(value: Any, label: str) -> Digest:
    value = closed_object(value, {"lineCount", "sha256"}, label)
    line_count = value["lineCount"]
    sha256 = value["sha256"]
    if type(line_count) is not int or line_count < 0:
        raise BaselineError(f"{label} lineCount is invalid")
    if not isinstance(sha256, str) or not SHA_RE.fullmatch(sha256):
        raise BaselineError(f"{label} SHA-256 is invalid")
    return Digest(line_count, sha256)


def unique_list(values: Any, label: str, identity: Callable[[Any, str], str]) -> list[Any]:
    if not isinstance(values, list):
        raise BaselineError(f"{label} must be a list")
    result, seen = [], set()
    for index, value in enumerate(values):
        item_identity = identity(value, f"{label}[{index}]")
        if item_identity in seen:
            raise BaselineError(f"{label} contains duplicate entry {item_identity}")
        seen.add(item_identity)
        result.append(value)
    return result


def target_identity(value: Any, label: str = "tier target") -> str:
    if not isinstance(value, dict):
        raise BaselineError(f"{label} is invalid")
    if value.get("target") == "type":
        return _type_identity(value, label)
    if value.get("target") == "method":
        return _method_identity(value, label)
    raise BaselineError(f"{label} is invalid")


def _type_identity(value: dict[str, Any], label: str) -> str:
    name = value.get("name")
    if set(value) != {"target", "name"} or not isinstance(name, str) or not valid_internal_name(name) or "." in name:
        raise BaselineError(f"{label} type name is invalid")
    return f"class:{name}"


def _method_identity(value: dict[str, Any], label: str) -> str:
    owner, name, descriptor = value.get("owner"), value.get("name"), value.get("descriptor")
    valid = valid_internal_name(owner) and isinstance(name, str) and name not in ("", "<init>", "<clinit>")
    if set(value) != {"target", "owner", "name", "descriptor"} or not valid or not isinstance(descriptor, str) or not descriptor.startswith("("):
        raise BaselineError(f"{label} method target is invalid")
    return f"method:{owner}#{name}{descriptor}"


def valid_internal_name(value: Any) -> bool:
    return isinstance(value, str) and bool(value) and not value.startswith("/") and not value.endswith("/") and "//" not in value and ".." not in value


def target_object(value: Any, label: str) -> dict[str, str]:
    target_identity(value, label)
    return dict(value)
