"""Build canonical framework and plugin candidate manifests."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import zipfile
from pathlib import Path
from typing import Any

from .contracts import ReleaseError, canonical_bytes
from .versions import assert_version_binding, changelog_entry, framework_version, git_source


EXPECTED_PRIMARY = (
    "turboism-{version}-lite.zip",
    "turboism-{version}-full.zip",
    "TurboismInstaller-{version}.exe",
    "TurboismInstaller-{version}.jar",
)
MEDIA_TYPES = {
    ".zip": "application/zip",
    ".exe": "application/octet-stream",
    ".jar": "application/java-archive",
    ".sha256": "text/plain",
}


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def framework_artifacts(
    repo_root: Path,
    dist: Path,
    version: str,
) -> list[dict[str, Any]]:
    verifier = _load_script("verify_release_candidate", Path(__file__).parents[1] / "verify-release.py")
    try:
        verifier.verify(
            dist.resolve(),
            version,
            (repo_root / "packaging/release-plugins.txt").resolve(),
            (dist.resolve().parent / "staging").resolve(),
        )
    except (OSError, ValueError, zipfile.BadZipFile, KeyError) as failure:
        raise ReleaseError(f"release payload verification failed: {failure}") from failure
    names = []
    for pattern in EXPECTED_PRIMARY:
        name = pattern.format(version=version)
        names.extend((name, name + ".sha256"))
    artifacts = []
    for name in sorted(names):
        path = dist / name
        suffix = ".sha256" if name.endswith(".sha256") else path.suffix.lower()
        artifacts.append({
            "name": name,
            "relativePath": f"framework/{name}",
            "mediaType": MEDIA_TYPES[suffix],
            "size": path.stat().st_size,
            "sha256": file_sha256(path),
        })
    return artifacts


def bundled_plugins(dist: Path, version: str) -> list[dict[str, str]]:
    full = dist / f"turboism-{version}-full.zip"
    result = []
    try:
        with zipfile.ZipFile(full) as archive:
            for name in sorted(archive.namelist()):
                if not name.startswith("plugins/") or not name.endswith(".jar"):
                    continue
                data = archive.read(name)
                plugin_id, plugin_version = _jar_identity(data, name)
                result.append({
                    "id": plugin_id,
                    "version": plugin_version,
                    "sha256": hashlib.sha256(data).hexdigest(),
                })
    except (OSError, zipfile.BadZipFile, KeyError, ValueError) as failure:
        raise ReleaseError(f"cannot inspect bundled plugins in {full}: {failure}") from failure
    if len({item["id"] for item in result}) != len(result):
        raise ReleaseError("full distribution contains duplicate plugin ids")
    return sorted(result, key=lambda item: item["id"])


def plugin_candidates(repo_root: Path, market_dir: Path | None) -> dict[str, Any]:
    manifest = repo_root / "packaging/market-plugins.json"
    policy_hash = file_sha256(manifest)
    if market_dir is None:
        preparer = _load_script("prepare_market_candidate", repo_root / "scripts/release/prepare-market-release.py")
        try:
            prepared = preparer.validate_selection(repo_root, manifest)
        except (OSError, preparer.MarketError) as failure:
            raise ReleaseError(f"market policy validation failed: {failure}") from failure
        candidates = []
        for item in prepared:
            descriptor_path = preparer.descriptor_path(repo_root, item["module"])
            candidates.append({
                "project": item["entry"]["project"],
                "module": item["module"],
                "id": item["descriptor"]["id"],
                "version": item["descriptor"]["version"],
                "descriptorSha256": file_sha256(descriptor_path),
                "policy": item["entry"],
                "policySha256": hashlib.sha256(canonical_bytes(item["entry"])).hexdigest(),
                "built": False,
            })
        return {"policySha256": policy_hash, "candidates": candidates}

    sidecar = market_dir / "market-release.json"
    try:
        document = json.loads(sidecar.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        raise ReleaseError(f"cannot read staged market sidecar {sidecar}: {failure}") from failure
    if document.get("format") != "turboism.market-release" or document.get("schemaVersion") != 1:
        raise ReleaseError("staged market sidecar has an unsupported contract")
    candidates = []
    for artifact in document.get("artifacts", []):
        if not isinstance(artifact, dict):
            raise ReleaseError("staged market artifact must be an object")
        entry = {
            "project": artifact.get("project"),
            "module": artifact.get("module"),
            "id": artifact.get("descriptor", {}).get("id"),
            "version": artifact.get("descriptor", {}).get("version"),
            "jarRelativePath": f"plugins/{artifact.get('asset')}",
            "jarSha256": artifact.get("sha256"),
            "jarSize": artifact.get("size"),
            "descriptorSha256": artifact.get("descriptorSha256"),
            "policy": artifact.get("policy"),
            "policySha256": hashlib.sha256(canonical_bytes(artifact.get("policy"))).hexdigest(),
            "built": True,
        }
        if not all(isinstance(entry[key], str) and entry[key] for key in (
            "project", "module", "id", "version", "jarSha256", "descriptorSha256"
        )) or not isinstance(entry["jarSize"], int) or entry["jarSize"] < 1:
            raise ReleaseError("staged market artifact is missing identity, size, or hash fields")
        jar = market_dir / artifact.get("asset", "")
        if not jar.is_file() or file_sha256(jar) != entry["jarSha256"]:
            raise ReleaseError(f"staged market JAR does not match its sidecar: {jar}")
        candidates.append(entry)
    return {"policySha256": policy_hash, "candidates": sorted(candidates, key=lambda item: item["id"])}


def build_candidate(
    repo_root: Path,
    *,
    dist: Path | None,
    market_dir: Path | None,
    require_tag: bool,
) -> dict[str, Any]:
    repo_root = repo_root.resolve()
    version = framework_version(repo_root)
    source = git_source(repo_root, require_tag=require_tag)
    assert_version_binding(source, version)
    changelog = changelog_entry(repo_root, version)
    if require_tag and dist is None:
        raise ReleaseError("--require-tag also requires a verified --dist payload")
    framework: dict[str, Any] = {
        "eligible": source["tag"] is not None and dist is not None,
        "version": version,
        "changelog": changelog,
        "artifacts": [],
        "bundledPlugins": [],
    }
    if dist is not None:
        framework["artifacts"] = framework_artifacts(repo_root, dist.resolve(), version)
        framework["bundledPlugins"] = bundled_plugins(dist.resolve(), version)
    return {
        "format": "turboism.release-candidate",
        "schemaVersion": 1,
        "source": source,
        "framework": framework,
        "plugins": plugin_candidates(repo_root, market_dir.resolve() if market_dir else None),
    }


def _jar_identity(data: bytes, label: str) -> tuple[str, str]:
    import io

    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        descriptor = json.loads(archive.read("META-INF/turboism/plugin.json").decode("utf-8"))
    plugin_id = descriptor.get("id")
    version = descriptor.get("version")
    if not isinstance(plugin_id, str) or not plugin_id or not isinstance(version, str) or not version:
        raise ValueError(f"{label}: plugin descriptor lacks id/version")
    return plugin_id, version


def _load_script(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ReleaseError(f"cannot load release helper {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
