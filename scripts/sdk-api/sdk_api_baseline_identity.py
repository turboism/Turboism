"""Canonical SDK API record identity helpers."""
from __future__ import annotations

from sdk_api_baseline_common import BaselineError


def split_canonical_record(record: str) -> tuple[str, dict[str, str]]:
    fields = record.split("\t")
    if not fields or not fields[0]:
        raise BaselineError("invalid canonical API record")
    values: dict[str, str] = {}
    for field_value in fields[1:]:
        key, separator, value = field_value.partition("=")
        if not separator:
            key, separator, value = field_value.partition(":")
        if not separator or not key or key in values:
            raise BaselineError("invalid canonical API record")
        values[key] = value
    return fields[0], values


def canonical_identity(record: str) -> str:
    kind, values = split_canonical_record(record)
    if kind == "package":
        return f"package:{values['name']}"
    if kind == "class":
        return f"class:{values['name']}"
    if kind in {"field", "record-component"}:
        return f"{kind}:{values['owner']}#{values['name']}:{values['descriptor']}"
    if kind == "method":
        return f"method:{values['owner']}#{values['name']}{values['descriptor']}"
    raise BaselineError(f"unsupported canonical API record kind {kind}")
