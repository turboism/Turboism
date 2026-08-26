#!/usr/bin/env python3
"""Verify a signed Plugin Directory catalog against one release plan."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import zipfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from turboism_release.contracts import ReleaseError, read_document

CATALOG_URL = "https://plugin.turboism.dev/api/v2/catalog.json"
SIGNATURE_URL = "https://plugin.turboism.dev/api/v2/catalog.json.sig"
ALLOWED_ARTIFACT_HOST = "github.com"
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--plan", required=True, type=Path)
    root.add_argument("--verifier-repo", required=True, type=Path)
    root.add_argument("--catalog-url", default=CATALOG_URL)
    root.add_argument("--signature-url", default=SIGNATURE_URL)
    root.add_argument("--output", type=Path)
    return root


def main(argv=None) -> int:
    args = parser().parse_args(argv)
    try:
        plan = read_document(args.plan, "plan")
        expected = plan.get("plugins", {}).get("expectedCandidates")
        if not isinstance(expected, list) or not expected:
            raise ReleaseError("release plan has no plugin candidates to verify")
        verifier_repo = args.verifier_repo.resolve()
        catalog_bytes = fetch(args.catalog_url, "application/json")
        signature_bytes = fetch(args.signature_url, "application/json")
        verify_signature(verifier_repo, catalog_bytes, signature_bytes)
        catalog = parse_object(catalog_bytes, "plugin catalog")
        signature = parse_object(signature_bytes, "plugin catalog signature")
        result = verify_expected(catalog, expected)
        fingerprint = {
            "catalogVersion": str(catalog.get("catalogVersion")),
            "generation": generation(catalog),
            "sha256": hashlib.sha256(catalog_bytes).hexdigest(),
            "keyId": signature.get("keyId"),
            "url": args.catalog_url,
        }
        if not isinstance(fingerprint["keyId"], str) or not fingerprint["keyId"]:
            raise ReleaseError("verified signature lacks a keyId")
        output = {
            "schemaVersion": 2,
            "catalogFingerprint": fingerprint,
            "plugins": result,
        }
        encoded = json.dumps(output, ensure_ascii=False, allow_nan=False, separators=(",", ":"), sort_keys=True) + "\n"
        if args.output:
            args.output.resolve().parent.mkdir(parents=True, exist_ok=True)
            args.output.resolve().write_text(encoded, encoding="utf-8")
        else:
            sys.stdout.write(encoded)
        return 0
    except ReleaseError as failure:
        print(f"plugin publication verification failed: {failure}", file=sys.stderr)
        return 1


def fetch(url: str, accept: str) -> bytes:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname != "plugin.turboism.dev" or parsed.query or parsed.fragment:
        raise ReleaseError(f"untrusted Plugin Directory URL: {url}")
    request = urllib.request.Request(url, headers={"Accept": accept, "Accept-Encoding": "identity"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status != 200:
                raise ReleaseError(f"request to {url} returned HTTP {response.status}")
            if response.headers.get("Content-Encoding"):
                raise ReleaseError(f"request to {url} unexpectedly used content encoding")
            data = response.read(16 * 1024 * 1024 + 1)
    except urllib.error.HTTPError as failure:
        raise ReleaseError(f"request to {url} failed with HTTP {failure.code}") from failure
    except (OSError, urllib.error.URLError) as failure:
        raise ReleaseError(f"request to {url} failed: {failure}") from failure
    if len(data) > 16 * 1024 * 1024:
        raise ReleaseError(f"response from {url} exceeds 16 MiB")
    return data


def verify_signature(repo: Path, catalog: bytes, signature: bytes) -> None:
    verifier = repo / "scripts/catalog-v2/verify.mjs"
    keys = repo / "lib/catalog-v2/trusted-keys.json"
    if not verifier.is_file() or not keys.is_file():
        raise ReleaseError("Plugin Directory verifier or trusted keys are unavailable")
    with tempfile.TemporaryDirectory(prefix="turboism-plugin-verify-") as directory:
        root = Path(directory)
        catalog_path = root / "catalog.json"
        signature_path = root / "catalog.json.sig"
        catalog_path.write_bytes(catalog)
        signature_path.write_bytes(signature)
        completed = subprocess.run(
            [
                "node", str(verifier),
                "--catalog", str(catalog_path),
                "--sig", str(signature_path),
                "--keys", str(keys),
                "--require-production",
                "--quiet",
            ],
            cwd=repo,
            capture_output=True,
            text=True,
        )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip() or str(completed.returncode)
        raise ReleaseError(f"catalog signature verification failed: {detail}")


def parse_object(data: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(data.decode("utf-8", errors="strict"), object_pairs_hook=reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError, ReleaseError) as failure:
        raise ReleaseError(f"cannot parse {label}: {failure}") from failure
    if not isinstance(value, dict):
        raise ReleaseError(f"{label} must be a JSON object")
    return value


def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value = {}
    for key, item in pairs:
        if key in value:
            raise ReleaseError(f"duplicate JSON key {key!r}")
        value[key] = item
    return value


def generation(catalog: dict[str, Any]) -> int:
    value = catalog.get("generation")
    if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
        return value
    version = catalog.get("catalogVersion")
    if isinstance(version, int) and not isinstance(version, bool) and version >= 0:
        return version
    if isinstance(version, str) and re.fullmatch(r"0|[1-9][0-9]*", version):
        return int(version)
    raise ReleaseError("verified catalog lacks a non-negative generation")


def verify_expected(catalog: dict[str, Any], expected: list[Any]) -> list[dict[str, Any]]:
    plugins = catalog.get("plugins")
    if catalog.get("format") != "turboism.plugin.catalog" or catalog.get("schemaVersion") != 2 or not isinstance(plugins, list):
        raise ReleaseError("verified document is not a Plugin Directory catalog v2")
    by_id = {}
    for plugin in plugins:
        if not isinstance(plugin, dict) or not isinstance(plugin.get("id"), str) or plugin["id"] in by_id:
            raise ReleaseError("catalog contains malformed or duplicate plugin identities")
        by_id[plugin["id"]] = plugin
    verified = []
    for item in expected:
        if not isinstance(item, dict):
            raise ReleaseError("expected plugin candidate must be an object")
        plugin_id = item.get("id")
        version = item.get("version")
        jar_hash = item.get("jarSha256")
        jar_size = item.get("jarSize")
        descriptor_hash = item.get("descriptorSha256")
        if not isinstance(plugin_id, str) or not plugin_id or not isinstance(version, str) or not version:
            raise ReleaseError("expected plugin candidate lacks id/version")
        if not isinstance(jar_hash, str) or not SHA256.fullmatch(jar_hash):
            raise ReleaseError(f"expected plugin {plugin_id}@{version} lacks a valid JAR hash")
        if not isinstance(jar_size, int) or isinstance(jar_size, bool) or jar_size < 1:
            raise ReleaseError(f"expected plugin {plugin_id}@{version} lacks a valid JAR size")
        if not isinstance(descriptor_hash, str) or not SHA256.fullmatch(descriptor_hash):
            raise ReleaseError(f"expected plugin {plugin_id}@{version} lacks a valid descriptor hash")
        plugin = by_id.get(plugin_id)
        releases = plugin.get("releases") if isinstance(plugin, dict) else None
        matches = [release for release in releases or [] if isinstance(release, dict) and release.get("version") == version]
        if len(matches) != 1:
            raise ReleaseError(f"catalog must contain exactly one release for {plugin_id}@{version}")
        release = matches[0]
        artifact = release.get("artifact")
        if not isinstance(artifact, dict):
            raise ReleaseError(f"catalog release {plugin_id}@{version} lacks artifact metadata")
        if artifact.get("sha256") != jar_hash or artifact.get("descriptorSha256") != descriptor_hash or artifact.get("size") != jar_size:
            raise ReleaseError(f"catalog release {plugin_id}@{version} does not match the planned bytes")
        url = artifact.get("url")
        if not isinstance(url, str):
            raise ReleaseError(f"catalog release {plugin_id}@{version} lacks an artifact URL")
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or parsed.hostname != ALLOWED_ARTIFACT_HOST or parsed.query or parsed.fragment:
            raise ReleaseError(f"catalog release {plugin_id}@{version} has an untrusted artifact URL")
        data = download_artifact(url, jar_size)
        if hashlib.sha256(data).hexdigest() != jar_hash:
            raise ReleaseError(f"downloaded catalog release {plugin_id}@{version} has the wrong SHA-256")
        try:
            import io
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                descriptor = archive.read("META-INF/turboism/plugin.json")
            descriptor_document = parse_object(descriptor, f"plugin descriptor for {plugin_id}@{version}")
        except (KeyError, OSError, zipfile.BadZipFile) as failure:
            raise ReleaseError(f"cannot inspect downloaded plugin {plugin_id}@{version}: {failure}") from failure
        if hashlib.sha256(descriptor).hexdigest() != descriptor_hash:
            raise ReleaseError(f"downloaded plugin {plugin_id}@{version} has the wrong descriptor SHA-256")
        if descriptor_document.get("id") != plugin_id or descriptor_document.get("version") != version:
            raise ReleaseError(f"downloaded plugin {plugin_id}@{version} descriptor identity differs")
        verified.append({
            "id": plugin_id,
            "version": version,
            "jarSha256": jar_hash,
            "jarSize": jar_size,
            "descriptorSha256": descriptor_hash,
            "url": url,
        })
    return verified


def download_artifact(url: str, size: int) -> bytes:
    request = urllib.request.Request(url, headers={"Accept": "application/java-archive", "Accept-Encoding": "identity"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read(size + 1)
    except (OSError, urllib.error.URLError) as failure:
        raise ReleaseError(f"cannot download published plugin artifact {url}: {failure}") from failure
    if len(data) != size:
        raise ReleaseError(f"published plugin artifact {url} has size {len(data)}, expected {size}")
    return data


if __name__ == "__main__":
    sys.exit(main())
