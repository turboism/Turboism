#!/usr/bin/env python3
"""Index compiled SDK members and explicit scenario mappings without assigning support."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import sys

import _bootstrap  # noqa: F401
from turboism_dev.json_io import read_json, write_json
from turboism_dev.paths import repo_root

ROOT = repo_root()
sys.path.insert(0, str(ROOT / "scripts/test"))
from sdk_api_baseline_common import ACC_BRIDGE, ACC_SYNTHETIC, API_VISIBILITY  # noqa: E402
from sdk_api_baseline_parse import api_classes, load_parsed_classes  # noqa: E402


def build_inventory(sdk_jar: Path, coverage_paths: Path | list[Path]) -> dict:
    classes, artifact_sha, artifact_size = load_parsed_classes(sdk_jar, "dev/turboism/sdk/")
    if isinstance(coverage_paths, Path):
        coverage_paths = [coverage_paths]
    mapped = {}
    indexes = []
    for coverage_path in coverage_paths:
        coverage_bytes = coverage_path.read_bytes()
        coverage = read_json(coverage_path)
        indexes.append({"path": coverage_path.name, "sha256": hashlib.sha256(coverage_bytes).hexdigest()})
        for row in coverage["methods"]:
            owner, name = row["sdkMethod"].split("(", 1)[0].rsplit(".", 1)
            package = row["sdkSource"].removeprefix("sdk/src/main/java/").removesuffix(".java")
            if package.rsplit("/", 1)[1] != owner:
                raise ValueError(f"Coverage owner/source mismatch: {row['sdkMethod']}")
            key = (package, name, row.get("sdkDescriptor"))
            if key in mapped:
                raise ValueError(f"Duplicate SDK scenario mapping: {row['sdkMethod']}")
            mapped[key] = {**row, "scenario": coverage["scenario"], "coverageIndex": coverage_path.name}
    mapped_counts = {key: 0 for key in mapped}
    indexed = []
    for parsed in sorted(api_classes(classes), key=lambda item: item.info.name):
        info = parsed.info
        source = "sdk/src/main/java/" + info.name.split("$", 1)[0] + ".java"
        members = []
        for method in sorted(info.methods, key=lambda item: (item.name, item.descriptor)):
            if not method.access & API_VISIBILITY:
                continue
            row = {
                "name": method.name, "descriptor": method.descriptor,
                "accessFlags": method.access,
                "syntheticOrBridge": bool(method.access & (ACC_SYNTHETIC | ACC_BRIDGE)),
                "scenarioMapping": "PENDING_AUDIT",
            }
            matches = [key for key in mapped if key[:2] == (info.name, method.name)
                       and key[2] in (None, method.descriptor)]
            if len(matches) > 1:
                raise ValueError(f"Overlapping SDK scenario mappings: {info.name}.{method.name}")
            if matches:
                key = matches[0]
                mapped_counts[key] += 1
                row["scenarioMapping"] = {
                    "scenario": mapped[key]["scenario"], "coverageIndex": mapped[key]["coverageIndex"],
                    "sdkMethod": mapped[key]["sdkMethod"], "evidenceKey": mapped[key]["evidenceKey"],
                    "scope": "See per-version results and limitations in coverage index; no blanket support verdict",
                }
            members.append(row)
        indexed.append({
            "owner": info.name, "source": source, "accessFlags": info.effective_access,
            "superClass": info.super_name, "interfaces": info.interfaces,
            "fields": [{"name": field.name, "descriptor": field.descriptor, "accessFlags": field.access,
                        "scenarioMapping": "PENDING_AUDIT"}
                       for field in info.fields if field.access & API_VISIBILITY],
            "methods": members,
        })
    if any(count != 1 for count in mapped_counts.values()):
        raise ValueError(f"Missing or ambiguous SDK scenario mappings: {mapped_counts}")
    return {
        "schemaVersion": 1, "completeFrameworkCoverage": False,
        "baselineRevision": "7d7de1de4",
        "sdkArtifact": {"name": sdk_jar.name, "sha256": artifact_sha, "sizeBytes": artifact_size},
        "coverageIndex": indexes[0],
        "coverageIndexes": indexes,
        "rules": [
            "Include incubating members, public/protected constructors, nested types, generated methods and fields.",
            "Descriptors distinguish overloads; each declared member is indexed once, inheritance is recorded.",
            "A scenario mapping does not assign a support verdict; check exact-host assertions and supervisor gates.",
            "Other SDK methods, callback/hook contracts, plugin behavior and field semantics remain pending audit.",
        ],
        "classCount": len(indexed),
        "methodCount": sum(len(item["methods"]) for item in indexed),
        "fieldCount": sum(len(item["fields"]) for item in indexed),
        "explicitlyMappedMethodCount": sum(mapped_counts.values()),
        "classes": indexed,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-jar", required=True, type=Path)
    parser.add_argument("--coverage", type=Path, action="append",
                        help="Repeat to include several explicit scenario indexes")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "validation/cubism-version-matrix/sdk-method-coverage.json")
    args = parser.parse_args()
    coverage = args.coverage or [ROOT / "validation/cubism-version-matrix" / name
                                for name in ("owned-moc-coverage.json", "mcp-texture-coverage.json", "mcp-authoring-coverage.json")]
    document = build_inventory(args.sdk_jar, coverage)
    write_json(args.output, document, indent=2, ensure_ascii=False)
    print(f"Indexed {document['classCount']} types, {document['methodCount']} methods, "
          f"{document['fieldCount']} fields; {document['explicitlyMappedMethodCount']} explicit scenario mappings")


if __name__ == "__main__":
    main()
