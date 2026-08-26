#!/usr/bin/env python3
"""Build reviewed Updates service manifests from release contracts."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from turboism_release.contracts import ReleaseError, read_document

MEDIA_NAMES = {
    "turboism-{version}-lite.zip",
    "turboism-{version}-lite.zip.sha256",
    "turboism-{version}-full.zip",
    "turboism-{version}-full.zip.sha256",
    "TurboismInstaller-{version}.exe",
    "TurboismInstaller-{version}.exe.sha256",
    "TurboismInstaller-{version}.jar",
    "TurboismInstaller-{version}.jar.sha256",
}
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SOURCE_SHA = re.compile(r"^[0-9a-f]{40}$")
VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--candidate", type=Path)
    root.add_argument("--plan", type=Path)
    root.add_argument("--catalog-state", type=Path)
    root.add_argument("--source-run-id")
    root.add_argument("--source-run-attempt")
    root.add_argument("--published-at", required=True)
    root.add_argument("--output", required=True, type=Path)
    return root


def main(argv=None) -> int:
    args = parser().parse_args(argv)
    try:
        timestamp(args.published_at)
        if args.candidate is not None:
            document = framework_manifest(args)
        elif args.plan is not None and args.catalog_state is not None:
            document = plugin_pointer(args)
        else:
            raise ReleaseError("provide either --candidate or both --plan and --catalog-state")
        encoded = canonical(document)
        args.output.resolve().parent.mkdir(parents=True, exist_ok=True)
        args.output.resolve().write_bytes(encoded)
        print(f"sha256={hashlib.sha256(encoded).hexdigest()}")
        return 0
    except (OSError, json.JSONDecodeError, ReleaseError) as failure:
        print(f"Updates manifest generation failed: {failure}", file=sys.stderr)
        return 1


def framework_manifest(args) -> dict:
    if args.plan is not None or args.catalog_state is not None:
        raise ReleaseError("framework manifest does not accept --plan or --catalog-state")
    if not isinstance(args.source_run_id, str) or not re.fullmatch(r"[1-9][0-9]{0,19}", args.source_run_id):
        raise ReleaseError("--source-run-id must be a positive Actions run ID")
    if not isinstance(args.source_run_attempt, str) or not re.fullmatch(r"[1-9][0-9]*", args.source_run_attempt):
        raise ReleaseError("--source-run-attempt must be positive")
    candidate = read_document(args.candidate.resolve(), "candidate")
    source = candidate.get("source", {})
    framework = candidate.get("framework", {})
    version = framework.get("version")
    revision = source.get("revision")
    tag = source.get("tag")
    if not isinstance(version, str) or not VERSION.fullmatch(version) or tag != f"v{version}":
        raise ReleaseError("candidate framework version/tag is invalid")
    if not isinstance(revision, str) or not SOURCE_SHA.fullmatch(revision):
        raise ReleaseError("candidate source revision is invalid")
    artifacts = framework.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != 8:
        raise ReleaseError("candidate must contain exactly eight framework artifacts")
    expected_names = {name.format(version=version) for name in MEDIA_NAMES}
    assets = []
    for artifact in sorted(artifacts, key=lambda item: item.get("name", "")):
        if not isinstance(artifact, dict):
            raise ReleaseError("candidate artifact must be an object")
        name = artifact.get("name")
        size = artifact.get("size")
        digest = artifact.get("sha256")
        media_type = artifact.get("mediaType")
        if name not in expected_names or not isinstance(size, int) or size < 1:
            raise ReleaseError("candidate framework artifact identity or size is invalid")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest):
            raise ReleaseError(f"candidate framework artifact {name} has an invalid SHA-256")
        if not isinstance(media_type, str) or not media_type:
            raise ReleaseError(f"candidate framework artifact {name} has an invalid media type")
        key = f"turboism/releases/{version}/{name}"
        assets.append({
            "name": name,
            "key": key,
            "url": f"https://updates.turboism.dev/{key}",
            "mediaType": media_type,
            "size": size,
            "sha256": digest,
        })
    if {item["name"] for item in assets} != expected_names:
        raise ReleaseError("candidate framework artifact set is incomplete")
    return {
        "schemaVersion": 2,
        "product": "turboism",
        "version": version,
        "tag": tag,
        "sourceRevision": revision,
        "publishedAt": args.published_at,
        "githubReleaseUrl": f"https://github.com/turboism/Turboism/releases/tag/{tag}",
        "changelogUrl": f"https://github.com/turboism/Turboism/blob/{revision}/CHANGELOG.md",
        "provenance": {
            "repository": "turboism/Turboism",
            "workflow": ".github/workflows/release.yml",
            "runId": args.source_run_id,
            "runAttempt": int(args.source_run_attempt),
        },
        "assets": assets,
    }


def plugin_pointer(args) -> dict:
    if args.candidate is not None or args.source_run_id is not None or args.source_run_attempt is not None:
        raise ReleaseError("plugin pointer accepts only --plan and --catalog-state")
    plan = read_document(args.plan.resolve(), "plan")
    if plan.get("intent") not in ("plugins", "combined"):
        raise ReleaseError("plan does not publish plugins")
    state = json.loads(args.catalog_state.resolve().read_text(encoding="utf-8"))
    fingerprint = state.get("catalogFingerprint") if isinstance(state, dict) else None
    expected_plugins = plan.get("plugins", {}).get("expectedCandidates")
    verified_plugins = state.get("plugins") if isinstance(state, dict) else None
    if not isinstance(fingerprint, dict) or not isinstance(expected_plugins, list) or not isinstance(verified_plugins, list):
        raise ReleaseError("verified catalog state is incomplete")
    expected = sorted((item.get("id"), item.get("version"), item.get("jarSha256")) for item in expected_plugins)
    actual = sorted((item.get("id"), item.get("version"), item.get("jarSha256")) for item in verified_plugins)
    if expected != actual:
        raise ReleaseError("verified catalog state does not match the planned plugin candidates")
    catalog_version = fingerprint.get("catalogVersion")
    generation_value = fingerprint.get("generation")
    digest = fingerprint.get("sha256")
    key_id = fingerprint.get("keyId")
    url = fingerprint.get("url")
    if not isinstance(catalog_version, str) or not re.fullmatch(r"0|[1-9][0-9]*", catalog_version):
        raise ReleaseError("catalog fingerprint version is invalid")
    if not isinstance(generation_value, int) or generation_value < 0:
        raise ReleaseError("catalog fingerprint generation is invalid")
    if not isinstance(digest, str) or not SHA256.fullmatch(digest):
        raise ReleaseError("catalog fingerprint SHA-256 is invalid")
    if not isinstance(key_id, str) or not key_id or url != "https://plugin.turboism.dev/api/v2/catalog.json":
        raise ReleaseError("catalog fingerprint authority is invalid")
    return {
        "schemaVersion": 2,
        "catalogFingerprint": {
            "catalogVersion": catalog_version,
            "generation": generation_value,
            "sha256": digest,
            "keyId": key_id,
            "url": url,
        },
        "updatedAt": args.published_at,
    }


def timestamp(value: str) -> None:
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z", value):
        raise ReleaseError("--published-at must be a canonical UTC timestamp")
    try:
        parsed = dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=dt.timezone.utc)
    except ValueError as failure:
        raise ReleaseError("--published-at is not a real timestamp") from failure
    if parsed.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z" != value:
        raise ReleaseError("--published-at is not canonical")


def canonical(value: dict) -> bytes:
    return (json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")


if __name__ == "__main__":
    sys.exit(main())
