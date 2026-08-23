#!/usr/bin/env python3
"""Generate the deterministic first-party plugin public-event reference."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts"))

from plugin_event_metadata import EventMetadataError, normalize_event_metadata, validate_event_routes

DESCRIPTOR = Path("src/main/resources/META-INF/turboism/plugin.json")


def load_descriptors(root: Path) -> list[dict]:
    descriptors = []
    for path in sorted((root / "plugins").glob(f"*/{DESCRIPTOR}")):
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as failure:
            raise EventMetadataError(f"{path}: cannot read descriptor: {failure}") from failure
        if not isinstance(document, dict):
            raise EventMetadataError(f"{path}: descriptor must be an object")
        plugin_id = document.get("id")
        version = document.get("version")
        if not isinstance(plugin_id, str) or not isinstance(version, str):
            raise EventMetadataError(f"{path}: descriptor id/version must be strings")
        exports, imports = normalize_event_metadata(document, str(path.relative_to(root)))
        dependencies = document.get("dependencies", [])
        descriptors.append({
            "id": plugin_id,
            "version": version,
            "module": path.parents[5].name,
            "dependencies": dependencies,
            "eventExports": exports,
            "eventImports": imports,
        })
    descriptors.sort(key=lambda item: item["id"])
    validate_event_routes(descriptors, require_providers=False)
    return descriptors


def render(descriptors: list[dict]) -> str:
    exports = [
        (descriptor, event)
        for descriptor in descriptors
        for event in descriptor["eventExports"]
    ]
    imports = [
        (descriptor, event)
        for descriptor in descriptors
        for event in descriptor["eventImports"]
    ]
    lines = [
        "# Plugin Public Events",
        "",
        "This generated reference lists schema-v4 public event contracts declared by first-party plugin descriptors. Runtime-owned SDK events are documented by their SDK Javadocs and are not plugin exports.",
        "",
        f"- First-party descriptors: {len(descriptors)}",
        f"- Published event contracts: {len(exports)}",
        f"- Subscribed event contracts: {len(imports)}",
        "",
        "## Published events",
        "",
    ]
    if not exports:
        lines.append("No first-party plugin currently publishes a public plugin event contract.")
    else:
        lines.extend([
            "| Provider | Event ID | Contract | Java type | ABI SHA-256 |",
            "|---|---|---|---|---|",
        ])
        for descriptor, event in exports:
            lines.append(
                f"| `{descriptor['id']}` | `{event['id']}` | `{event['contractVersion']}` | "
                f"`{event['eventType']}` | `{event['abiSha256']}` |"
            )
    lines.extend(["", "## Subscribed events", ""])
    if not imports:
        lines.append("No first-party plugin currently imports a public plugin event contract.")
    else:
        lines.extend([
            "| Consumer | Provider | Event ID | Contract range | Java type | ABI SHA-256 | Required |",
            "|---|---|---|---|---|---|---|",
        ])
        for descriptor, event in imports:
            lines.append(
                f"| `{descriptor['id']}` | `{event['provider']}` | `{event['eventId']}` | "
                f"`{event['contractVersion']}` | `{event['eventType']}` | `{event['abiSha256']}` | "
                f"`{str(event['required']).lower()}` |"
            )
    lines.extend([
        "",
        "## Governance",
        "",
        "- `eventExports` is the authoritative declaration of a plugin-published public event contract.",
        "- `eventImports` is the authoritative declaration of a dependent plugin's public event subscription contract.",
        "- Private plugin events and Runtime-owned SDK events are intentionally absent from this descriptor inventory.",
        "- Generated output is ASCII-sorted by plugin ID and event route; `checkPluginEventReference` rejects drift.",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        content = render(load_descriptors(args.root.resolve())).encode("utf-8")
        output = args.output.resolve()
        if args.check:
            if not output.is_file() or output.read_bytes() != content:
                raise EventMetadataError(f"generated plugin event reference is stale: {output}")
        else:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(content)
    except EventMetadataError as failure:
        raise SystemExit(f"Plugin event reference: {failure}") from failure


if __name__ == "__main__":
    main()
