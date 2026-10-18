"""Standardized JSON read/write operations for Turboism tooling."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable


def read_json(
    path: Path | str,
    *,
    encoding: str = "utf-8",
    object_pairs_hook: Callable[[list[tuple[Any, Any]]], Any] | None = None,
) -> Any:
    """Read and parse a JSON file."""
    p = Path(path)
    text = p.read_text(encoding=encoding)
    if object_pairs_hook is not None:
        return json.loads(text, object_pairs_hook=object_pairs_hook)
    return json.loads(text)


def dump_json(
    data: Any,
    *,
    indent: int = 2,
    ensure_ascii: bool = False,
    sort_keys: bool = False,
    trailing_newline: bool = True,
) -> str:
    """Serialize data to a formatted JSON string."""
    rendered = json.dumps(
        data,
        indent=indent,
        ensure_ascii=ensure_ascii,
        sort_keys=sort_keys,
    )
    if trailing_newline and not rendered.endswith("\n"):
        rendered += "\n"
    return rendered


def write_json(
    path: Path | str,
    data: Any,
    *,
    indent: int = 2,
    ensure_ascii: bool = False,
    sort_keys: bool = False,
    trailing_newline: bool = True,
    encoding: str = "utf-8",
) -> None:
    """Write data as formatted JSON to a file."""
    p = Path(path)
    rendered = dump_json(
        data,
        indent=indent,
        ensure_ascii=ensure_ascii,
        sort_keys=sort_keys,
        trailing_newline=trailing_newline,
    )
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(rendered, encoding=encoding)
