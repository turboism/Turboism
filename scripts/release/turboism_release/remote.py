"""Read-only adapters for canonical release services."""
from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from .contracts import ReleaseError


CATALOG_URL = "https://plugin.turboism.dev/api/v2/catalog.json"
SIGNATURE_URL = "https://plugin.turboism.dev/api/v2/catalog.json.sig"
UPDATES_RELEASE_BASE = "https://updates.turboism.dev/turboism/releases"


def github_release(repo: str, tag: str) -> dict[str, Any] | None:
    completed = subprocess.run(
        ["gh", "release", "view", tag, "--repo", repo, "--json", "assets,tagName,isDraft,isPrerelease"],
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip()
        if "release not found" in detail.lower() or "not found" in detail.lower():
            return None
        raise ReleaseError(f"cannot inspect GitHub Release {repo}@{tag}: {detail or completed.returncode}")
    try:
        document = json.loads(completed.stdout)
    except json.JSONDecodeError as failure:
        raise ReleaseError(f"GitHub Release response is not JSON: {failure}") from failure
    is_draft = document.get("isDraft")
    if document.get("tagName") != tag or not isinstance(is_draft, bool) or document.get("isPrerelease") is not False:
        raise ReleaseError(f"GitHub Release {repo}@{tag} has conflicting tag/draft/prerelease metadata")
    assets = []
    for item in document.get("assets", []):
        digest = item.get("digest")
        assets.append({
            "name": item.get("name"),
            "size": item.get("size"),
            "digest": digest,
            "url": item.get("url"),
        })
    return {"version": tag.removeprefix("v"), "draft": is_draft, "assets": assets}


def updates_release(version: str, base_url: str = UPDATES_RELEASE_BASE) -> dict[str, Any] | None:
    url = f"{base_url.rstrip('/')}/{version}/release.json"
    try:
        document, _ = _json_url(url)
    except ReleaseError as failure:
        if "HTTP 404" in str(failure):
            return None
        raise
    if document.get("version") != version:
        raise ReleaseError(f"updates release manifest {url} does not identify version {version}")
    return document


def verified_catalog(plugin_directory_repo: Path) -> dict[str, Any]:
    verifier = plugin_directory_repo / "scripts/catalog-v2/verify.mjs"
    keys = plugin_directory_repo / "lib/catalog-v2/trusted-keys.json"
    if not verifier.is_file() or not keys.is_file():
        raise ReleaseError(f"plugin directory verifier or trusted keys are unavailable under {plugin_directory_repo}")
    with tempfile.TemporaryDirectory(prefix="turboism-catalog-") as temporary:
        root = Path(temporary)
        catalog_path = root / "catalog.json"
        signature_path = root / "catalog.json.sig"
        catalog_bytes = _bytes_url(CATALOG_URL)
        signature_bytes = _bytes_url(SIGNATURE_URL)
        catalog_path.write_bytes(catalog_bytes)
        signature_path.write_bytes(signature_bytes)
        completed = subprocess.run(
            [
                "node", str(verifier),
                "--catalog", str(catalog_path),
                "--sig", str(signature_path),
                "--keys", str(keys),
                "--require-production",
                "--quiet",
            ],
            cwd=plugin_directory_repo,
            capture_output=True,
            text=True,
        )
        if completed.returncode != 0:
            detail = completed.stderr.strip() or completed.stdout.strip()
            raise ReleaseError(f"plugin catalog signature verification failed: {detail}")
        try:
            catalog = json.loads(catalog_bytes.decode("utf-8", errors="strict"))
            signature = json.loads(signature_bytes.decode("utf-8", errors="strict"))
        except (UnicodeDecodeError, json.JSONDecodeError) as failure:
            raise ReleaseError(f"verified plugin catalog pair is not valid JSON: {failure}") from failure
        catalog["catalogSha256"] = hashlib.sha256(catalog_bytes).hexdigest()
        catalog["keyId"] = signature.get("keyId")
        return catalog


def _json_url(url: str) -> tuple[dict[str, Any], bytes]:
    data = _bytes_url(url)
    try:
        document = json.loads(data.decode("utf-8", errors="strict"))
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise ReleaseError(f"response from {url} is not strict UTF-8 JSON: {failure}") from failure
    if not isinstance(document, dict):
        raise ReleaseError(f"response from {url} must be a JSON object")
    return document, data


def _bytes_url(url: str) -> bytes:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json",
            "Accept-Encoding": "identity",
            "User-Agent": "turboism-release-orchestrator/1",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            data = response.read(16 * 1024 * 1024 + 1)
    except urllib.error.HTTPError as failure:
        raise ReleaseError(f"request to {url} failed with HTTP {failure.code}") from failure
    except (OSError, urllib.error.URLError) as failure:
        raise ReleaseError(f"request to {url} failed: {failure}") from failure
    if len(data) > 16 * 1024 * 1024:
        raise ReleaseError(f"response from {url} exceeds 16 MiB")
    return data
