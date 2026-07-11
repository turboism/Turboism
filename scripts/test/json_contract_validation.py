#!/usr/bin/env python3
"""Stdlib-only validation helpers for Phase 4 JSON governance contracts."""

from __future__ import annotations

import json
import re
from datetime import datetime
from pathlib import Path, PurePosixPath
from typing import Any, Callable

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]*$")
WORKTREE_ID_RE = re.compile(r"^[a-z][a-z0-9-]{2,63}$")
UTC_TIMESTAMP_RE = re.compile(
    r"^(?:[0-9]{4})-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])"
    r"T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]+)?Z$"
)


def error(code: str, message: str, path: str = "$", severity: str = "ERROR") -> dict[str, str]:
    return {"code": code, "message": message, "path": path, "severity": severity}


def load_json(path: Path) -> tuple[Any | None, list[dict[str, str]]]:
    try:
        data = path.read_bytes()
    except OSError as exc:
        return None, [error("FILE_READ_ERROR", str(exc))]
    if data.startswith(b"\xef\xbb\xbf"):
        return None, [error("BOM_FORBIDDEN", "UTF-8 BOM is not allowed")]
    try:
        text = data.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        return None, [error("INVALID_UTF8", str(exc))]
    try:
        return json.loads(text), []
    except json.JSONDecodeError as exc:
        return None, [error("INVALID_JSON", exc.msg, f"$:{exc.lineno}:{exc.colno}")]


def require_object(value: Any, path: str, errors: list[dict[str, str]]) -> bool:
    if not isinstance(value, dict):
        errors.append(error("TYPE_MISMATCH", "expected object", path))
        return False
    return True


def strict_fields(
    value: dict[str, Any], required: set[str], allowed: set[str], path: str,
    errors: list[dict[str, str]],
) -> None:
    for field in sorted(required - value.keys()):
        errors.append(error("MISSING_FIELD", f"missing required field: {field}", f"{path}.{field}"))
    for field in sorted(value.keys() - allowed):
        errors.append(error("UNKNOWN_FIELD", f"unknown field: {field}", f"{path}.{field}"))


def require_string(
    value: Any, path: str, errors: list[dict[str, str]], pattern: re.Pattern[str] | None = None,
) -> bool:
    if not isinstance(value, str) or not value:
        errors.append(error("TYPE_MISMATCH", "expected non-empty string", path))
        return False
    if pattern is not None and pattern.fullmatch(value) is None:
        errors.append(error("INVALID_VALUE", "string does not match the required pattern", path))
        return False
    return True


def require_integer(value: Any, path: str, errors: list[dict[str, str]], minimum: int = 0) -> bool:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        errors.append(error("TYPE_MISMATCH", f"expected integer >= {minimum}", path))
        return False
    return True


def require_relative_path(value: Any, path: str, errors: list[dict[str, str]]) -> bool:
    if not require_string(value, path, errors):
        return False
    assert isinstance(value, str)
    pure = PurePosixPath(value)
    # PurePosixPath normalizes duplicate separators and dot segments. Requiring
    # exact round-trip equality makes the serialized path canonical as well as safe.
    if (
        "\\" in value
        or pure.is_absolute()
        or value != pure.as_posix()
        or any(part in {".", ".."} for part in value.split("/"))
    ):
        errors.append(error("INVALID_PATH", "expected canonical relative POSIX path", path))
        return False
    return True


def require_utc_time(value: Any, path: str, errors: list[dict[str, str]]) -> bool:
    if not require_string(value, path, errors):
        return False
    assert isinstance(value, str)
    if UTC_TIMESTAMP_RE.fullmatch(value) is None:
        errors.append(error("INVALID_TIME", "expected complete UTC ISO-8601 timestamp ending in Z", path))
        return False
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError:
        errors.append(error("INVALID_TIME", "expected valid UTC ISO-8601 timestamp", path))
        return False
    if parsed.tzinfo is None or parsed.utcoffset() is None or parsed.utcoffset().total_seconds() != 0:
        errors.append(error("INVALID_TIME", "expected UTC ISO-8601 timestamp", path))
        return False
    return True


def require_string_list(
    value: Any, path: str, errors: list[dict[str, str]], *, minimum: int = 1,
    item_validator: Callable[[Any, str, list[dict[str, str]]], bool] | None = None,
) -> bool:
    if not isinstance(value, list) or len(value) < minimum:
        errors.append(error("TYPE_MISMATCH", f"expected array with at least {minimum} item(s)", path))
        return False
    valid = True
    for index, item in enumerate(value):
        item_path = f"{path}[{index}]"
        if item_validator is not None:
            valid = item_validator(item, item_path, errors) and valid
        else:
            valid = require_string(item, item_path, errors) and valid
    if len(value) != len(set(item for item in value if isinstance(item, str))):
        errors.append(error("DUPLICATE_VALUE", "array items must be unique", path))
        valid = False
    return valid


def validate_file(path: Path, validator: Callable[[Any], list[dict[str, str]]]) -> list[dict[str, str]]:
    document, errors = load_json(path)
    if errors:
        return errors
    return validator(document)
