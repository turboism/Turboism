"""Classify candidate state against canonical published observations."""
from __future__ import annotations

import hashlib
import json
import re
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from .contracts import ReleaseError, plan_id, validate_document
from .versions import compare_versions


SHA256 = re.compile(r"^[0-9a-f]{64}$")


def read_json_source(source: str | None, label: str, *, required: bool) -> dict[str, Any] | None:
    if source is None:
        if required:
            raise ReleaseError(f"{label} observation is required")
        return None
    try:
        if re.match(r"^https://", source):
            request = urllib.request.Request(
                source,
                headers={
                    "Accept": "application/json",
                    "Accept-Encoding": "identity",
                    "User-Agent": "turboism-release-orchestrator/1",
                },
            )
            with urllib.request.urlopen(request, timeout=30) as response:
                data = response.read(16 * 1024 * 1024 + 1)
        else:
            data = Path(source).read_bytes()
    except (OSError, urllib.error.URLError) as failure:
        raise ReleaseError(f"cannot read {label} observation {source}: {failure}") from failure
    if len(data) > 16 * 1024 * 1024:
        raise ReleaseError(f"{label} observation exceeds 16 MiB")
    try:
        document = json.loads(data.decode("utf-8", errors="strict"))
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise ReleaseError(f"cannot parse {label} observation {source}: {failure}") from failure
    if not isinstance(document, dict):
        raise ReleaseError(f"{label} observation must be a JSON object")
    return document


def classify_framework(
    framework: dict[str, Any],
    github: dict[str, Any] | None,
    updates: dict[str, Any] | None,
) -> dict[str, Any]:
    eligible = framework.get("eligible") is True
    artifacts = framework.get("artifacts")
    if not eligible:
        return {"action": "none", "reason": "source has no exact release tag"}
    if not isinstance(artifacts, list) or len(artifacts) != 8:
        raise ReleaseError("tagged framework candidate must contain exactly eight verified artifacts")
    expected = _artifact_map(artifacts, "framework candidate")
    github_result = _classify_artifact_host(expected, github, "GitHub Release")
    updates_result = _classify_artifact_host(expected, updates, "updates service")
    actions = {github_result["action"], updates_result["action"]}
    if "publish" in actions:
        action = "publish"
    elif "resume" in actions:
        action = "resume"
    else:
        action = "none"
    version = framework.get("version")
    return {
        "action": action,
        "version": version,
        "github": github_result,
        "updates": updates_result,
        "expectedAssetCount": 8,
        "updatesContract": {
            "releaseManifestKey": f"turboism/releases/{version}/release.json",
            "channelManifestKey": "turboism/channels/stable/latest.json",
            "pluginStoreManifestKey": "turboism/plugin-store/latest.json",
        },
    }


def classify_plugins(
    plugins: dict[str, Any],
    catalog: dict[str, Any] | None,
) -> dict[str, Any]:
    candidates = plugins.get("candidates")
    if not isinstance(candidates, list):
        raise ReleaseError("candidate plugins.candidates must be an array")
    if catalog is None:
        if candidates:
            raise ReleaseError("signed plugin catalog observation is required for store-eligible plugins")
        return {"action": "none", "catalogBefore": None, "actions": [], "expectedCandidates": []}
    if catalog.get("format") != "turboism.plugin.catalog" or catalog.get("schemaVersion") != 2:
        raise ReleaseError("plugin catalog observation is not a v2 catalog")
    catalog_plugins = catalog.get("plugins")
    if not isinstance(catalog_plugins, list):
        raise ReleaseError("plugin catalog plugins must be an array")
    by_id: dict[str, dict[str, Any]] = {}
    for plugin in catalog_plugins:
        if not isinstance(plugin, dict) or not isinstance(plugin.get("id"), str):
            raise ReleaseError("plugin catalog contains a malformed plugin")
        if plugin["id"] in by_id:
            raise ReleaseError(f"plugin catalog contains duplicate id {plugin['id']}")
        by_id[plugin["id"]] = plugin

    actions = []
    for candidate in sorted(candidates, key=lambda item: item.get("id", "")):
        action = _classify_plugin(candidate, by_id.get(candidate.get("id")))
        if action is not None:
            actions.append(action)
    fingerprint = {
        "catalogVersion": catalog.get("catalogVersion"),
        "catalogSha256": catalog.get("catalogSha256"),
        "keyId": catalog.get("keyId"),
        "generation": catalog.get("generation"),
    }
    return {
        "action": "publish" if actions else "none",
        "catalogBefore": fingerprint,
        "actions": actions,
        "expectedCandidates": [
            {
                "id": candidate["id"],
                "version": candidate["version"],
                "jarSha256": candidate.get("jarSha256"),
                "jarSize": candidate.get("jarSize"),
                "descriptorSha256": candidate.get("descriptorSha256"),
                "policySha256": candidate.get("policySha256"),
            }
            for candidate in sorted(candidates, key=lambda item: item.get("id", ""))
        ],
    }


def make_plan(
    candidate: dict[str, Any],
    *,
    github: dict[str, Any] | None,
    updates: dict[str, Any] | None,
    catalog: dict[str, Any] | None,
    channel: str,
) -> dict[str, Any]:
    validate_document(candidate, "candidate")
    if channel not in ("stable", "beta", "nightly"):
        raise ReleaseError(f"unsupported release channel: {channel}")
    framework = classify_framework(candidate["framework"], github, updates)
    plugins = classify_plugins(candidate["plugins"], catalog)
    framework_changed = framework["action"] != "none"
    plugins_changed = plugins["action"] != "none"
    if framework_changed and plugins_changed:
        intent = "combined"
    elif framework_changed:
        intent = "framework"
    elif plugins_changed:
        intent = "plugins"
    else:
        intent = "none"
    version = candidate["framework"].get("version")
    steps = _steps(intent, framework, plugins, version, channel)
    payload = {
        "format": "turboism.release-plan",
        "schemaVersion": 1,
        "source": candidate["source"],
        "intent": intent,
        "channel": channel,
        "framework": framework,
        "plugins": plugins,
        "steps": steps,
    }
    return {**payload, "planId": plan_id(payload)}


def initial_state(plan: dict[str, Any]) -> dict[str, Any]:
    validate_document(plan, "plan")
    return {
        "format": "turboism.release-state",
        "schemaVersion": 1,
        "planId": plan["planId"],
        "steps": {
            step["id"]: {"status": "pending", "attempts": 0}
            for step in plan.get("steps", [])
        },
    }


def _classify_artifact_host(
    expected: dict[str, dict[str, Any]],
    observation: dict[str, Any] | None,
    label: str,
) -> dict[str, Any]:
    if observation is None:
        return {"action": "publish", "observed": False, "missing": sorted(expected)}
    remote_version = observation.get("version")
    expected_version = _version_from_assets(expected)
    if isinstance(remote_version, str) and remote_version != expected_version:
        if compare_versions(remote_version, expected_version) > 0:
            raise ReleaseError(f"{label} already publishes newer version {remote_version}")
        return {"action": "publish", "observed": True, "observedVersion": remote_version,
                "missing": sorted(expected)}
    draft = observation.get("draft")
    if draft is not None and not isinstance(draft, bool):
        raise ReleaseError(f"{label} observation draft state must be boolean")
    assets = observation.get("assets")
    if not isinstance(assets, list):
        raise ReleaseError(f"{label} observation assets must be an array")
    remote = _artifact_map(assets, label)
    unexpected = sorted(set(remote) - set(expected))
    if unexpected:
        raise ReleaseError(f"{label} contains unexpected immutable assets: {unexpected}")
    missing = []
    for name, artifact in expected.items():
        actual = remote.get(name)
        if actual is None:
            missing.append(name)
            continue
        if actual.get("size") != artifact["size"] or actual.get("sha256") != artifact["sha256"]:
            raise ReleaseError(f"{label} asset {name} differs for the same version (VERSION_NOT_BUMPED)")
    if not missing:
        if draft is True:
            return {"action": "resume", "observed": True, "draft": True, "missing": []}
        return {"action": "none", "observed": True, "missing": []}
    if draft is False:
        raise ReleaseError(f"{label} is published but missing immutable assets: {sorted(missing)}")
    return {"action": "resume", "observed": True, "draft": draft is True, "missing": sorted(missing)}


def _artifact_map(artifacts: list[Any], label: str) -> dict[str, dict[str, Any]]:
    result = {}
    for index, artifact in enumerate(artifacts):
        if not isinstance(artifact, dict):
            raise ReleaseError(f"{label} asset {index} must be an object")
        name = artifact.get("name")
        size = artifact.get("size")
        digest = artifact.get("sha256") or _github_digest(artifact.get("digest"))
        if not isinstance(name, str) or not name:
            raise ReleaseError(f"{label} asset {index} lacks a name")
        if name in result:
            raise ReleaseError(f"{label} contains duplicate asset {name}")
        if not isinstance(size, int) or size < 1:
            raise ReleaseError(f"{label} asset {name} has invalid size")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest):
            raise ReleaseError(f"{label} asset {name} has invalid SHA-256")
        result[name] = {**artifact, "name": name, "size": size, "sha256": digest}
    return result


def _github_digest(value: Any) -> str | None:
    if isinstance(value, str) and value.startswith("sha256:"):
        return value[len("sha256:"):]
    return None


def _version_from_assets(assets: dict[str, dict[str, Any]]) -> str:
    versions = set()
    for name in assets:
        match = re.search(r"(?:turboism-|TurboismInstaller-)((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))", name)
        if match:
            versions.add(match.group(1))
    if len(versions) != 1:
        raise ReleaseError(f"cannot derive one framework version from asset names: {sorted(versions)}")
    return versions.pop()


def _classify_plugin(candidate: dict[str, Any], plugin: dict[str, Any] | None) -> dict[str, Any] | None:
    plugin_id = candidate.get("id")
    module = candidate.get("module")
    version = candidate.get("version")
    if not all(isinstance(value, str) and value for value in (plugin_id, module, version)):
        raise ReleaseError("plugin candidate lacks id/module/version")
    if candidate.get("built") is not True:
        raise ReleaseError(f"plugin {plugin_id} is store-eligible but has not been built")
    if plugin is None:
        return {"id": plugin_id, "module": module, "version": version, "action": "publish"}
    releases = plugin.get("releases")
    if not isinstance(releases, list):
        raise ReleaseError(f"catalog plugin {plugin_id} releases must be an array")
    same = None
    greatest = None
    for release in releases:
        if not isinstance(release, dict) or not isinstance(release.get("version"), str):
            raise ReleaseError(f"catalog plugin {plugin_id} contains a malformed release")
        if same is None and release["version"] == version:
            same = release
        if greatest is None or compare_versions(release["version"], greatest["version"]) > 0:
            greatest = release
    if same is not None:
        artifact = same.get("artifact")
        if not isinstance(artifact, dict):
            raise ReleaseError(f"catalog release {plugin_id}@{version} lacks artifact metadata")
        if artifact.get("sha256") != candidate.get("jarSha256") or artifact.get("descriptorSha256") != candidate.get("descriptorSha256"):
            raise ReleaseError(f"plugin {plugin_id}@{version} differs from the catalog (VERSION_NOT_BUMPED)")
        if _policy_equal(candidate.get("policy"), plugin, same):
            return None
        return {"id": plugin_id, "module": module, "version": version, "action": "metadata-update"}
    if greatest is not None and compare_versions(version, greatest["version"]) <= 0:
        raise ReleaseError(
            f"plugin {plugin_id}@{version} is not higher than cataloged {greatest['version']}"
        )
    return {"id": plugin_id, "module": module, "version": version, "action": "publish"}


def _policy_equal(policy: Any, plugin: dict[str, Any], release: dict[str, Any]) -> bool:
    if not isinstance(policy, dict):
        return False
    return (
        policy.get("channel") == release.get("channel")
        and policy.get("cubismVersions") == release.get("cubismVersions")
        and policy.get("repository") == plugin.get("repository")
        and policy.get("support") == plugin.get("support")
    )


def _steps(
    intent: str,
    framework: dict[str, Any],
    plugins: dict[str, Any],
    version: str,
    channel: str,
) -> list[dict[str, Any]]:
    plan_steps: list[tuple[str, list[str]]] = []
    if intent in ("plugins", "combined"):
        plan_steps.extend([
            ("plugin-directory.publish", []),
            ("plugin-directory.verify", ["plugin-directory.publish"]),
            ("plugin-directory.deploy", ["plugin-directory.verify"]),
        ])
    if intent in ("framework", "combined"):
        dependencies = ["plugin-directory.deploy"] if intent == "combined" else []
        plan_steps.extend([
            ("framework.github", dependencies),
            ("updates.assets", ["framework.github"]),
            ("updates.release-manifest", ["updates.assets"]),
            ("framework.github-publish", ["updates.release-manifest"]),
            ("updates.channel-pointer", ["framework.github-publish"]),
        ])
    if intent in ("plugins", "combined"):
        dependencies = ["updates.channel-pointer"] if intent == "combined" else ["plugin-directory.deploy"]
        plan_steps.append(("updates.plugin-store-pointer", dependencies))
    if intent != "none":
        terminal = plan_steps[-1][0]
        plan_steps.extend([
            ("website.verify", [terminal]),
            ("release.verify", ["website.verify"]),
        ])
    seed = hashlib.sha256(f"{version}:{channel}:{intent}".encode("utf-8")).hexdigest()
    return [
        {
            "id": step_id,
            "requires": requires,
            "idempotencyKey": f"{seed}:{step_id}",
        }
        for step_id, requires in plan_steps
    ]
