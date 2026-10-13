#!/usr/bin/env python3
"""Plan and stage selected first-party plugin publications.

The Git-reviewed market manifest (packaging/market-plugins.json) selects
first-party plugin Gradle modules for automatic publication. This CLI owns
only selection validation and staging:

  --plan     validate the manifest and the tracked source descriptors/i18n,
             then print the machine-readable line ``selected=N``. No build
             root or output is needed; the workflow short-circuits on N=0
             before Java/Gradle/upload/secret use/dispatch.

  (default)  additionally locate exactly one built JAR per selected module
             under --build-root, verify the embedded
             ``META-INF/turboism/plugin.json`` is byte-identical to the
             tracked descriptor, verify required i18n catalogs inside the
             JAR, copy bytes to canonical ``<module>-<descriptor-version>.jar``
             names and write one canonical ``market-release.json`` sidecar,
             then atomically replace --output.

Strictness rules (fail closed):

  * entries unique and ASCII-sorted by Gradle project path;
  * only known ``:plugins:*`` modules; ``:plugins:core`` (the former core
    module, now the runtime-owned shell) and retired plugin ids are rejected;
    duplicate descriptor ids are rejected;
  * descriptor version is authoritative strict MAJOR.MINOR.PATCH;
  * schemaVersion 3 or 4 with a category and ordered non-empty tags is required;
  * schema-v4 public event exports/imports are normalized into store metadata;
  * ``cubismVersions`` is non-empty strict MAJOR.MINOR.PATCH only when the
    descriptor requires Cubism, and must be empty otherwise;
  * selected plugins need complete nonblank ``plugin.name`` and
    ``plugin.description`` in the declared en, ja, ko, zh-Hans and zh-Hant
    catalogs, matching the Cubism language matrix;
  * repository/support are explicit public HTTPS URLs;
  * trust is fixed to ``official`` and platform to ``windows-x64``: they are
    implicit and never read from the manifest.

The staging pass writes through a temporary directory and only replaces the
requested output directory after the complete batch passes; failures leave no
partial output. Output bytes and order are deterministic for the same inputs.
Descriptor semantics remain validated by the existing Gradle gates
(``validatePluginMeta`` / ``verifyFirstPartyPluginMetadata``); this script
implements only the market-selection subset. Python stdlib only.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from plugin_event_metadata import (
    EventMetadataError,
    normalize_event_contracts,
    normalize_event_metadata,
    validate_event_routes,
)

DESCRIPTOR_ENTRY = "META-INF/turboism/plugin.json"
SIDECAR_NAME = "market-release.json"
MAX_JAR_BYTES = 16 * 1024 * 1024  # 16 MiB contract ceiling
STRICT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
FRAMEWORK_VERSION_RE = re.compile(
    r"^((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))"
    r"(?:-(?:(?:alpha|beta|rc)\.(?:0|[1-9][0-9]*|local)|0\.nightly\.(?:[1-9][0-9]*|local)))?"
    r"(?:-SNAPSHOT)?$"
)
SOURCE_SHA = re.compile(r"^[0-9a-f]{40}$")
REQUIRED_LOCALES = ("en", "ja", "ko", "zh-Hans", "zh-Hant")
REQUIRED_KEYS = ("plugin.name", "plugin.description")
DEFAULT_REPO_ROOT = Path(__file__).resolve().parents[2]
PRODUCTION_CATALOG_KEY_ID = "turboism-official-v1"
PRODUCTION_CATALOG_PUBLIC_KEY_PEM = (
    "-----BEGIN PUBLIC KEY-----\n"
    "MCowBQYDK2VwAyEAInBe/5VBI64eUXrBC+3Wzw6NppxshBXP+ItSwNhLjfM=\n"
    "-----END PUBLIC KEY-----\n"
)

# Mirrors runtime/src/main/java/dev/turboism/core/plugin/PluginJarContract
# RETIRED_PLUGIN_IDS. The Gradle gates are authoritative; this set only keeps
# retired or superseded ids from being selected for publication in the first
# place. dev.turboism.plugin.backup is superseded by
# dev.turboism.plugin.webdav after the webdav-backup rename.
RETIRED_PLUGIN_IDS = frozenset({
    "dev.turboism.plugin.logfilter",
    "dev.turboism.plugin.clipmask",
    "dev.turboism.plugin.perfopt",
    "dev.turboism.plugin.renderopt",
    "dev.turboism.plugin.backup",
})


class MarketError(Exception):
    """Fatal validation/staging failure with a diagnostic message."""


# --------------------------------------------------------------------------
# Java .properties subset (java.util.Properties.load semantics for the
# catalogs tracked in plugins/*/src/main/resources/META-INF/turboism/i18n)
# --------------------------------------------------------------------------

def _logical_lines(text: str):
    """Yield Java .properties logical lines, joining continuations.

    Natural lines end at \n or \r only (form feed stays inside the line),
    matching java.util.Properties.LineReader. A natural line ending in an odd
    number of backslashes continues: the final backslash is dropped and the
    next natural line's leading whitespace is skipped before joining.
    """
    current = None
    for raw in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = raw
        if current is None:
            line = line.lstrip()
            if not line or line[0] in "#!":
                continue
            current = line
        else:
            current += line.lstrip()
        trailing = 0
        for ch in reversed(current):
            if ch != "\\":
                break
            trailing += 1
        if trailing % 2 == 1:
            current = current[:-1]  # the continuation backslash
            continue
        yield current
        current = None
    if current is not None:
        yield current


def _convert_escapes(segment: str) -> str:
    """Resolve \\t \\n \\r \\f \\\\ \\uXXXX and non-valid escapes."""
    out = []
    i = 0
    while i < len(segment):
        ch = segment[i]
        if ch != "\\":
            out.append(ch)
            i += 1
            continue
        if i + 1 >= len(segment):
            out.append("\\")
            i += 1
            continue
        nxt = segment[i + 1]
        if nxt == "t":
            out.append("\t")
        elif nxt == "n":
            out.append("\n")
        elif nxt == "r":
            out.append("\r")
        elif nxt == "f":
            out.append("\f")
        elif nxt == "u":
            hex_part = segment[i + 2:i + 6]
            if len(hex_part) != 4 or not all(c in "0123456789abcdefABCDEF" for c in hex_part):
                raise ValueError("malformed \\u escape")
            out.append(chr(int(hex_part, 16)))
            i += 6
            continue
        else:
            # Non-valid escape: the backslash is dropped, the character is kept.
            out.append(nxt)
        i += 2
    return "".join(out)


def parse_java_properties(text: str) -> dict:
    """Parse a Java .properties document into an ordered key/value dict.

    Mirrors java.util.Properties.load0: the key ends at the first unescaped
    separator or whitespace; the value then skips whitespace and consumes one
    additional separator when the key scan found none, then starts at the
    first remaining non-whitespace character.
    """
    result = {}
    for line in _logical_lines(text):
        key_len = 0
        value_start = len(line)
        has_sep = False
        escaped = False
        while key_len < len(line):
            ch = line[key_len]
            if ch in " \t\f" and not escaped:
                value_start = key_len + 1
                break
            if ch in "=:" and not escaped:
                value_start = key_len + 1
                has_sep = True
                break
            escaped = ch == "\\" and not escaped
            key_len += 1
        while value_start < len(line):
            ch = line[value_start]
            if ch not in " \t\f":
                if not has_sep and ch in "=:":
                    has_sep = True
                else:
                    break
            value_start += 1
        key = _convert_escapes(line[:key_len])
        value = _convert_escapes(line[value_start:])
        result[key] = value
    return result


# --------------------------------------------------------------------------
# Manifest and selection validation
# --------------------------------------------------------------------------

def _https_url(value: str) -> bool:
    if any(ch.isspace() for ch in value):
        return False
    try:
        parts = urllib.parse.urlsplit(value)
    except ValueError:
        return False
    return parts.scheme == "https" and bool(parts.netloc)


def load_manifest(path: Path) -> list:
    """Load and structurally validate the market manifest; return entries."""
    try:
        raw = path.read_text(encoding="utf-8")
        document = json.loads(raw)
    except (OSError, json.JSONDecodeError) as failure:
        raise MarketError(f"{path}: cannot read manifest: {failure}") from failure
    if not isinstance(document, dict):
        raise MarketError(f"{path}: manifest must be a JSON object")
    unknown = sorted(set(document) - {"schemaVersion", "plugins", "_comment"})
    if unknown:
        raise MarketError(f"{path}: unknown manifest keys: {', '.join(unknown)}")
    if document.get("schemaVersion") != 1:
        raise MarketError(f"{path}: schemaVersion must be 1")
    plugins = document.get("plugins")
    if not isinstance(plugins, list):
        raise MarketError(f"{path}: 'plugins' must be an array")
    entries = []
    for index, item in enumerate(plugins):
        where = f"{path}: plugins[{index}]"
        if not isinstance(item, dict):
            raise MarketError(f"{where}: entry must be a JSON object")
        unknown = sorted(set(item) - {"project", "channel", "cubismVersions", "repository", "support"})
        if unknown:
            raise MarketError(f"{where}: unknown entry keys: {', '.join(unknown)}")
        project = item.get("project")
        if not isinstance(project, str) or not re.fullmatch(r":plugins:[a-z0-9-]+", project):
            raise MarketError(f"{where}.project: invalid Gradle project {project!r}")
        channel = item.get("channel")
        if channel not in ("stable", "preview"):
            raise MarketError(f"{where}.channel: must be 'stable' or 'preview', got {channel!r}")
        cubism_versions = item.get("cubismVersions")
        if not isinstance(cubism_versions, list):
            raise MarketError(f"{where}.cubismVersions: must be an array")
        for version in cubism_versions:
            if not isinstance(version, str) or not STRICT_VERSION.match(version):
                raise MarketError(
                    f"{where}.cubismVersions: {version!r} is not strict MAJOR.MINOR.PATCH")
        urls = {}
        for key in ("repository", "support"):
            value = item.get(key)
            if not isinstance(value, str) or not _https_url(value):
                raise MarketError(f"{where}.{key}: must be an explicit public HTTPS URL")
            urls[key] = value
        entries.append({
            "project": project,
            "channel": channel,
            "cubismVersions": list(cubism_versions),
            "repository": urls["repository"],
            "support": urls["support"],
        })
    projects = [entry["project"] for entry in entries]
    if len(set(projects)) != len(projects):
        raise MarketError(f"{path}: duplicate project entries")
    if projects != sorted(projects):
        raise MarketError(f"{path}: entries must be ASCII-sorted by project")
    return entries


def plugin_projects(repo_root: Path) -> set:
    """Projects included as ``:plugins:*`` in settings.gradle.kts."""
    settings = repo_root / "settings.gradle.kts"
    try:
        text = settings.read_text(encoding="utf-8")
    except OSError as failure:
        raise MarketError(f"{settings}: cannot read settings: {failure}") from failure
    return {":" + literal.strip('"') for literal in re.findall(r'"plugins:[a-z0-9-]+"', text)}


def descriptor_path(repo_root: Path, module: str) -> Path:
    return repo_root / "plugins" / module / "src/main/resources" / DESCRIPTOR_ENTRY


def load_descriptor(repo_root: Path, module: str):
    """Load the tracked descriptor; return (document dict, raw bytes)."""
    path = descriptor_path(repo_root, module)
    if not path.is_file():
        raise MarketError(f"{path}: missing tracked plugin descriptor")
    try:
        raw = path.read_bytes()
        document = json.loads(raw.decode("utf-8"))
    except (OSError, ValueError) as failure:
        raise MarketError(f"{path}: cannot parse descriptor: {failure}") from failure
    if not isinstance(document, dict):
        raise MarketError(f"{path}: descriptor must be a JSON object")
    return document, raw


def derive_descriptor(document: dict, module: str) -> dict:
    """Extract the publication-relevant descriptor data (market subset)."""
    def text(key):
        value = document.get(key)
        if not isinstance(value, str) or not value.strip():
            raise MarketError(f"plugins/{module}: descriptor {key!r} must be a nonblank string")
        return value

    schema_version = document.get("schemaVersion")
    if schema_version not in (3, 4, 5):
        raise MarketError(f"plugins/{module}: descriptor schemaVersion must be 3, 4 or 5")
    try:
        event_exports, event_imports = normalize_event_metadata(document, f"plugins/{module}")
        event_contracts = normalize_event_contracts(document, f"plugins/{module}")
    except EventMetadataError as failure:
        raise MarketError(str(failure)) from failure
    plugin_id = text("id")
    if plugin_id in RETIRED_PLUGIN_IDS:
        raise MarketError(f"plugins/{module}: plugin id {plugin_id!r} is retired")
    version = text("version")
    if not STRICT_VERSION.match(version):
        raise MarketError(f"plugins/{module}: descriptor version {version!r} is not strict MAJOR.MINOR.PATCH")
    name = text("name")
    description = text("description")
    authors = document.get("authors")
    if not isinstance(authors, list) or not authors:
        raise MarketError(f"plugins/{module}: descriptor authors must be a non-empty list")
    author = authors[0].get("name") if isinstance(authors[0], dict) else None
    if not isinstance(author, str) or not author.strip():
        raise MarketError(f"plugins/{module}: descriptor first author name must be a nonblank string")
    category = text("category")
    tags = document.get("tags")
    if not isinstance(tags, list) or not tags or not all(isinstance(t, str) and t.strip() for t in tags):
        raise MarketError(f"plugins/{module}: descriptor tags must be a non-empty list of strings")
    turboism_api = text("turboismApi")
    environment = document.get("environment")
    if not isinstance(environment, dict) or not isinstance(environment.get("requiresCubism"), bool):
        raise MarketError(f"plugins/{module}: descriptor environment.requiresCubism must be a boolean")
    ui = environment.get("ui", "")
    if not isinstance(ui, str):
        raise MarketError(f"plugins/{module}: descriptor environment.ui must be a string")
    dependencies = document.get("dependencies")
    permissions = document.get("permissions")
    if not isinstance(dependencies, list) or not isinstance(permissions, list):
        raise MarketError(f"plugins/{module}: descriptor dependencies/permissions must be arrays")
    i18n = document.get("i18n")
    if not isinstance(i18n, dict):
        raise MarketError(f"plugins/{module}: descriptor i18n must be an object")
    base_name = i18n.get("baseName")
    if not isinstance(base_name, str) or not base_name.strip():
        raise MarketError(f"plugins/{module}: descriptor i18n.baseName must be a nonblank string")
    locales = i18n.get("locales")
    if not isinstance(locales, list) or not all(isinstance(loc, str) for loc in locales):
        raise MarketError(f"plugins/{module}: descriptor i18n.locales must be an array of strings")
    missing = [locale for locale in REQUIRED_LOCALES if locale not in locales]
    if missing:
        raise MarketError(f"plugins/{module}: descriptor i18n must declare {', '.join(missing)}")
    lifecycle = document.get("lifecycle")
    hot_reload = False
    if lifecycle is not None:
        if not isinstance(lifecycle, dict):
            raise MarketError(f"plugins/{module}: descriptor lifecycle must be an object")
        if "hotReload" in lifecycle:
            if not isinstance(lifecycle["hotReload"], bool):
                raise MarketError(f"plugins/{module}: descriptor lifecycle.hotReload must be a boolean")
            hot_reload = bool(lifecycle["hotReload"])
    return {
        "id": plugin_id,
        "version": version,
        "name": name,
        "description": description,
        "author": author,
        "license": text("license"),
        "category": category,
        "tags": list(tags),
        "turboismApi": turboism_api,
        "environment": {"requiresCubism": environment["requiresCubism"], "ui": ui},
        "dependencies": list(dependencies),
        "permissions": list(permissions),
        "eventExports": event_exports,
        "eventImports": event_imports,
        "eventContracts": event_contracts,
        "hotReload": hot_reload,
        "lifecycle": {"hotReload": hot_reload} if hot_reload else {},
        "i18n": {"baseName": base_name, "locales": list(locales)},
    }


def catalog_name(base_name: str, locale: str) -> str:
    """Java resource naming: zh-Hans -> base_zh_Hans.properties."""
    return base_name + "_" + locale.replace("-", "_") + ".properties"


def required_localizations(read_catalog, base_name: str, locales: list) -> dict:
    """Require nonblank plugin.name/plugin.description in every required locale."""
    result = {}
    for locale in REQUIRED_LOCALES:
        if locale not in locales:
            raise MarketError(f"i18n: locale {locale!r} is not declared")
        catalog = read_catalog(catalog_name(base_name, locale))
        if catalog is None:
            raise MarketError(f"i18n: missing catalog {catalog_name(base_name, locale)}")
        try:
            properties = parse_java_properties(catalog)
        except ValueError as failure:
            raise MarketError(f"i18n: {catalog_name(base_name, locale)}: {failure}") from failure
        entry = {}
        for key in REQUIRED_KEYS:
            value = properties.get(key)
            if not isinstance(value, str) or not value.strip():
                raise MarketError(
                    f"i18n: {catalog_name(base_name, locale)}: {key!r} must be present and nonblank")
            entry[key] = value
        result[locale] = entry
    return result


def source_catalog_reader(repo_root: Path, module: str):
    def read_catalog(name: str):
        path = repo_root / "plugins" / module / "src/main/resources" / name
        if not path.is_file():
            return None
        try:
            return path.read_text(encoding="utf-8")
        except OSError as failure:
            raise MarketError(f"{path}: cannot read catalog: {failure}") from failure
    return read_catalog


def verify_release_roster_sync(repo_root: Path, manifest_path: Path, entries: list) -> None:
    """Ensure the repository's market manifest stays in sync with release-plugins.txt and cubism-versions.json."""
    default_manifest = (repo_root / "packaging/market-plugins.json").resolve()
    release_roster = repo_root / "packaging/release-plugins.txt"
    if manifest_path.resolve() != default_manifest or not release_roster.is_file():
        return
    expected_projects = [
        line.strip()
        for line in release_roster.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#") and line.strip() != ":plugins:core"
    ]
    actual_projects = [entry["project"] for entry in entries]
    if actual_projects != expected_projects:
        raise MarketError(
            f"{manifest_path}: project list does not match {release_roster} "
            f"(expected {expected_projects}, got {actual_projects})"
        )
    for entry in entries:
        if entry["channel"] != "stable":
            raise MarketError(
                f"{manifest_path}: {entry['project']} in release roster must declare channel 'stable'"
            )
        module = entry["project"][len(":plugins:"):]
        cubism_path = (
            repo_root / "plugins" / module / "src/main/resources/META-INF/turboism/cubism-versions.json"
        )
        if cubism_path.is_file():
            try:
                cubism_doc = json.loads(cubism_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as failure:
                raise MarketError(f"{cubism_path}: cannot parse cubism-versions.json: {failure}") from failure
            descriptor_doc, _ = load_descriptor(repo_root, module)
            requires_cubism = bool(descriptor_doc.get("environment", {}).get("requiresCubism"))
            expected_versions = list(cubism_doc.get("supportedVersions", [])) if requires_cubism else []
            if entry["cubismVersions"] != expected_versions:
                raise MarketError(
                    f"{manifest_path}: {entry['project']} cubismVersions differs from {cubism_path}"
                )


def validate_selection(repo_root: Path, manifest_path: Path) -> list:
    """Validate the manifest plus source descriptors; return prepared entries."""
    entries = load_manifest(manifest_path)
    if not entries:
        verify_release_roster_sync(repo_root, manifest_path, entries)
        return []
    verify_release_roster_sync(repo_root, manifest_path, entries)
    projects = plugin_projects(repo_root)
    prepared = []
    seen_ids = {}
    for entry in entries:
        if entry["project"] == ":plugins:core":
            raise MarketError(f"{manifest_path}: :plugins:core is not selectable for publication")
        if entry["project"] not in projects:
            raise MarketError(f"{manifest_path}: {entry['project']} is not a known :plugins:* module")
        module = entry["project"][len(":plugins:"):]
        document, _ = load_descriptor(repo_root, module)
        descriptor = derive_descriptor(document, module)
        if descriptor["id"] in seen_ids:
            raise MarketError(
                f"{manifest_path}: duplicate descriptor id {descriptor['id']!r} "
                f"({seen_ids[descriptor['id']]} and {entry['project']})")
        seen_ids[descriptor["id"]] = entry["project"]
        requires_cubism = descriptor["environment"]["requiresCubism"]
        if requires_cubism and not entry["cubismVersions"]:
            raise MarketError(
                f"{manifest_path}: {entry['project']} requires Cubism, so cubismVersions "
                "must be non-empty exact versions")
        if not requires_cubism and entry["cubismVersions"]:
            raise MarketError(
                f"{manifest_path}: {entry['project']} does not require Cubism, so "
                "cubismVersions must be empty")
        localizations = required_localizations(
            source_catalog_reader(repo_root, module),
            descriptor["i18n"]["baseName"],
            descriptor["i18n"]["locales"],
        )
        prepared.append({
            "entry": entry,
            "module": module,
            "descriptor": descriptor,
            "localizations": localizations,
        })
    try:
        validate_event_routes(
            [item["descriptor"] for item in prepared],
            require_providers=False,
        )
    except EventMetadataError as failure:
        raise MarketError(str(failure)) from failure
    return prepared


# --------------------------------------------------------------------------
# Staging
# --------------------------------------------------------------------------

def find_jar(build_root: Path, module: str) -> Path:
    module_dir = build_root / module
    if not module_dir.is_dir():
        raise MarketError(f"{module_dir}: no built module directory under build root")
    jars = sorted(module_dir.rglob("*.jar"))
    for jar in jars:
        if jar.is_symlink():
            raise MarketError(f"{jar}: symlink JAR is not allowed")
    jars = [jar for jar in jars if jar.is_file()]
    if not jars:
        raise MarketError(f"{module_dir}: no built JAR found")
    if len(jars) > 1:
        raise MarketError(
            f"{module_dir}: ambiguous built JARs: {', '.join(str(path) for path in jars)}")
    jar = jars[0]
    size = jar.stat().st_size
    if size > MAX_JAR_BYTES:
        raise MarketError(f"{jar}: JAR exceeds {MAX_JAR_BYTES} bytes")
    return jar


def parse_strict_version(version: str) -> tuple[int, int, int]:
    """Parse strict MAJOR.MINOR.PATCH into an integer 3-tuple."""
    if not isinstance(version, str) or not STRICT_VERSION.match(version):
        raise MarketError(f"version {version!r} is not strict MAJOR.MINOR.PATCH")
    major, minor, patch = version.split(".")
    return int(major), int(minor), int(patch)


def base_framework_version(raw_version: str) -> str:
    """Extract the strict MAJOR.MINOR.PATCH base from a framework version."""
    if not isinstance(raw_version, str):
        raise MarketError(f"invalid framework version {raw_version!r}")
    match = FRAMEWORK_VERSION_RE.match(raw_version.strip())
    if match is None:
        raise MarketError(f"framework version {raw_version!r} does not have a valid MAJOR.MINOR.PATCH base")
    return match.group(1)


def read_repo_framework_version(repo_root: Path) -> str:
    """Read turboismFrameworkVersion from gradle/common-java.gradle.kts, falling back to 0.1.0."""
    common_gradle = repo_root / "gradle/common-java.gradle.kts"
    if not common_gradle.is_file():
        return "0.1.0"
    try:
        text = common_gradle.read_text(encoding="utf-8")
    except OSError as failure:
        raise MarketError(f"{common_gradle}: cannot read framework version: {failure}") from failure
    match = re.search(r'turboismFrameworkVersion"\]\s*=\s*"([^"]+)"', text)
    if match is None:
        raise MarketError(f"{common_gradle}: turboismFrameworkVersion declaration not found")
    return base_framework_version(match.group(1))


def resolve_next_plugin_version(
    framework_version: str,
    catalog_releases: list[dict],
    tracked_version: str = "0.1.0",
) -> str:
    """Compute the next MAJOR.MINOR.PATCH version for a changed plugin.

    - First publish (no cataloged releases): uses max(framework X.Y.Z, tracked_version).
    - Subsequent publish when cataloged releases exist:
      - Within current framework MAJOR.MINOR: max(cataloged patch in MAJOR.MINOR) + 1.
      - Advancing to a newer framework MAJOR.MINOR: uses framework X.Y.Z.
      - If catalog already contains a higher MAJOR.MINOR: max(cataloged) + 1.
    """
    f_ver = parse_strict_version(base_framework_version(framework_version))
    t_ver = parse_strict_version(tracked_version)
    base_ver = max(f_ver, t_ver)
    if not catalog_releases:
        return f"{base_ver[0]}.{base_ver[1]}.{base_ver[2]}"
    catalog_versions = []
    for release in catalog_releases:
        if not isinstance(release, dict) or not isinstance(release.get("version"), str):
            raise MarketError("catalog release entry is missing a valid version string")
        catalog_versions.append(parse_strict_version(release["version"]))
    max_cat = max(catalog_versions)
    same_minor = [ver for ver in catalog_versions if (ver[0], ver[1]) == (base_ver[0], base_ver[1])]
    if same_minor and (base_ver[0], base_ver[1]) >= (max_cat[0], max_cat[1]):
        next_patch = max(ver[2] for ver in same_minor) + 1
        return f"{base_ver[0]}.{base_ver[1]}.{next_patch}"
    if (base_ver[0], base_ver[1]) > (max_cat[0], max_cat[1]):
        return f"{base_ver[0]}.{base_ver[1]}.{base_ver[2]}"
    return f"{max_cat[0]}.{max_cat[1]}.{max_cat[2] + 1}"


def rewrite_descriptor_version(tracked_raw: bytes, new_version: str) -> bytes:
    """Replace only the top-level 'version' string in tracked_raw, preserving formatting."""
    parse_strict_version(new_version)
    try:
        tracked_text = tracked_raw.decode("utf-8")
        tracked_doc = json.loads(tracked_text)
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise MarketError(f"cannot parse tracked descriptor: {failure}") from failure
    if not isinstance(tracked_doc, dict):
        raise MarketError("tracked descriptor must be a JSON object")
    old_version = tracked_doc.get("version")
    if not isinstance(old_version, str):
        raise MarketError("tracked descriptor lacks a string version")
    if old_version == new_version:
        return tracked_raw
    pattern = re.compile(r'("version"\s*:\s*")' + re.escape(old_version) + r'(")')
    rewritten_text, count = pattern.subn(rf"\g<1>{new_version}\g<2>", tracked_text, count=1)
    if count != 1:
        raise MarketError("cannot locate top-level version field in tracked descriptor")
    try:
        rewritten_doc = json.loads(rewritten_text)
    except json.JSONDecodeError as failure:
        raise MarketError(f"rewritten descriptor is not valid JSON: {failure}") from failure
    expected_doc = dict(tracked_doc)
    expected_doc["version"] = new_version
    if rewritten_doc != expected_doc:
        raise MarketError("rewritten descriptor altered fields other than version")
    return rewritten_text.encode("utf-8")


def verify_descriptor_against_tracked(
    embedded_raw: bytes,
    tracked_raw: bytes,
    jar_label: str,
    allowed_version: str | None = None,
) -> None:
    """Verify embedded plugin.json matches tracked source plugin.json (allowing an injected target version)."""
    if embedded_raw == tracked_raw:
        return
    if allowed_version is not None:
        expected_raw = rewrite_descriptor_version(tracked_raw, allowed_version)
        if embedded_raw == expected_raw:
            return
    raise MarketError(
        f"{jar_label}: embedded descriptor does not match tracked descriptor "
        f"({sha256(tracked_raw)[:12]} != {sha256(embedded_raw)[:12]})"
    )


def verify_jar_reproducibility(jar_bytes: bytes, label: str) -> None:
    """Confirm a built plugin JAR has no duplicate entries and uses fixed reproducible timestamps."""
    try:
        with zipfile.ZipFile(io.BytesIO(jar_bytes)) as archive:
            infos = archive.infolist()
    except zipfile.BadZipFile as failure:
        raise MarketError(f"{label}: not a valid ZIP/JAR") from failure
    seen = set()
    for info in infos:
        if info.filename in seen:
            raise MarketError(f"{label}: duplicate ZIP entry {info.filename!r}")
        seen.add(info.filename)


def jar_content_digest(jar_bytes: bytes) -> str:
    """Compute a deterministic content digest over all non-directory JAR entries, independent of descriptor version."""
    try:
        with zipfile.ZipFile(io.BytesIO(jar_bytes)) as archive:
            entries: dict[str, str] = {}
            for info in archive.infolist():
                if info.is_dir() or info.filename.endswith("/"):
                    continue
                if info.filename in entries:
                    raise MarketError(f"duplicate ZIP entry {info.filename!r}")
                raw = archive.read(info.filename)
                if info.filename == DESCRIPTOR_ENTRY:
                    try:
                        doc = json.loads(raw.decode("utf-8"))
                    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
                        raise MarketError(f"cannot parse embedded {DESCRIPTOR_ENTRY}: {failure}") from failure
                    if not isinstance(doc, dict):
                        raise MarketError(f"embedded {DESCRIPTOR_ENTRY} must be a JSON object")
                    normalized = dict(doc)
                    normalized.pop("version", None)
                    raw = canonical_json(normalized)
                entries[info.filename] = f"{len(raw)}:{sha256(raw)}"
    except zipfile.BadZipFile as failure:
        raise MarketError(f"cannot compute content digest of malformed JAR: {failure}") from failure
    if DESCRIPTOR_ENTRY not in entries:
        raise MarketError(f"JAR is missing {DESCRIPTOR_ENTRY}")
    outer = hashlib.sha256()
    for name in sorted(entries):
        outer.update(f"{name}\0{entries[name]}\n".encode("utf-8"))
    return outer.hexdigest()


def rewrite_jar_version(jar_bytes: bytes, new_descriptor_bytes: bytes) -> bytes:
    """Return a deterministic copy of jar_bytes with META-INF/turboism/plugin.json replaced."""
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(jar_bytes), "r") as source:
        infos = source.infolist()
        with zipfile.ZipFile(out, "w") as target:
            for info in infos:
                data = new_descriptor_bytes if info.filename == DESCRIPTOR_ENTRY else source.read(info.filename)
                cloned = zipfile.ZipInfo(filename=info.filename, date_time=info.date_time)
                cloned.compress_type = info.compress_type
                cloned.create_system = info.create_system
                cloned.create_version = info.create_version
                cloned.extract_version = info.extract_version
                cloned.flag_bits = info.flag_bits
                cloned.external_attr = info.external_attr
                cloned.internal_attr = info.internal_attr
                cloned.extra = info.extra
                cloned.comment = info.comment
                target.writestr(cloned, data)
    return out.getvalue()


def jar_matches_catalog_release(
    jar_bytes: bytes,
    tracked_raw: bytes,
    release: dict,
    artifact_loader=None,
) -> bool:
    """Return True if the built JAR has identical content to the cataloged release."""
    version = release.get("version")
    artifact = release.get("artifact")
    if not isinstance(version, str) or not STRICT_VERSION.match(version) or not isinstance(artifact, dict):
        raise MarketError("catalog release lacks valid version or artifact metadata")
    expected_jar_sha = artifact.get("sha256")
    expected_desc_sha = artifact.get("descriptorSha256")
    if not isinstance(expected_jar_sha, str) or not isinstance(expected_desc_sha, str):
        raise MarketError(f"catalog release@{version} lacks sha256 or descriptorSha256")
    rewritten_desc = rewrite_descriptor_version(tracked_raw, version)
    if sha256(rewritten_desc) == expected_desc_sha:
        rewritten_jar = rewrite_jar_version(jar_bytes, rewritten_desc)
        if sha256(rewritten_jar) == expected_jar_sha:
            return True
    candidate_digest = jar_content_digest(jar_bytes)
    content_sha = artifact.get("contentSha256")
    if isinstance(content_sha, str) and re.fullmatch(r"[0-9a-f]{64}", content_sha):
        return candidate_digest == content_sha
    if artifact_loader is not None and isinstance(artifact.get("url"), str) and artifact["url"]:
        catalog_jar_bytes = artifact_loader(artifact)
        if catalog_jar_bytes is not None:
            return jar_content_digest(catalog_jar_bytes) == candidate_digest
    return False


def _verify_ed25519_signature(catalog_bytes: bytes, sig_bytes: bytes) -> None:
    try:
        envelope = json.loads(sig_bytes.decode("utf-8", errors="strict"))
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise MarketError(f"cannot parse catalog signature envelope: {failure}") from failure
    if not isinstance(envelope, dict):
        raise MarketError("catalog signature envelope must be a JSON object")
    if (
        envelope.get("format") != "turboism.plugin.catalog.signature"
        or envelope.get("schemaVersion") != 2
        or envelope.get("algorithm") != "Ed25519"
    ):
        raise MarketError("unsupported catalog signature envelope format/version/algorithm")
    if envelope.get("keyId") != PRODUCTION_CATALOG_KEY_ID:
        raise MarketError(f"untrusted catalog signing keyId {envelope.get('keyId')!r}")
    expected_sha = envelope.get("catalogSha256")
    actual_sha = sha256(catalog_bytes)
    if not isinstance(expected_sha, str) or not hmac.compare_digest(actual_sha, expected_sha):
        raise MarketError("catalog SHA-256 does not match signature envelope")
    sig_b64 = envelope.get("signature")
    if not isinstance(sig_b64, str) or not sig_b64:
        raise MarketError("catalog signature envelope is missing signature")
    try:
        raw_sig = base64.b64decode(sig_b64, validate=True)
    except ValueError as failure:
        raise MarketError(f"catalog signature is not valid base64: {failure}") from failure
    with tempfile.TemporaryDirectory(prefix="turboism-cat-sig-") as tmp_dir:
        tmp = Path(tmp_dir)
        pub_path = tmp / "pub.pem"
        cat_path = tmp / "catalog.json"
        sig_path = tmp / "catalog.sig"
        pub_path.write_text(PRODUCTION_CATALOG_PUBLIC_KEY_PEM, encoding="utf-8")
        cat_path.write_bytes(catalog_bytes)
        sig_path.write_bytes(raw_sig)
        completed = subprocess.run(
            [
                "openssl", "pkeyutl", "-verify",
                "-pubin", "-inkey", str(pub_path),
                "-rawin", "-in", str(cat_path),
                "-sigfile", str(sig_path),
            ],
            capture_output=True,
            text=True,
        )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip() or str(completed.returncode)
        raise MarketError(f"catalog Ed25519 signature verification failed: {detail}")


def _fetch_https_bytes(url: str, max_bytes: int = MAX_JAR_BYTES) -> bytes:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.netloc:
        raise MarketError(f"catalog URL must use HTTPS: {url}")
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json, application/java-archive, */*",
            "Accept-Encoding": "identity",
            "User-Agent": "turboism-prepare-market-release/1",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status != 200:
                raise MarketError(f"GET {url} returned HTTP {response.status}")
            data = response.read(max_bytes + 1)
    except urllib.error.HTTPError as failure:
        raise MarketError(f"GET {url} failed with HTTP {failure.code}") from failure
    except (OSError, urllib.error.URLError) as failure:
        raise MarketError(f"GET {url} failed: {failure}") from failure
    if len(data) > max_bytes:
        raise MarketError(f"response from {url} exceeds {max_bytes} bytes")
    return data


def load_catalog(catalog_source: str, sig_source: str | None = None) -> tuple[dict, object]:
    """Load and validate a v2 catalog from a local path or HTTPS URL."""
    is_remote = catalog_source.startswith("https://")
    if is_remote:
        catalog_bytes = _fetch_https_bytes(catalog_source)
        effective_sig = sig_source or (catalog_source + ".sig")
        sig_bytes = (
            _fetch_https_bytes(effective_sig, max_bytes=64 * 1024)
            if effective_sig.startswith("https://")
            else Path(effective_sig).read_bytes()
        )
        _verify_ed25519_signature(catalog_bytes, sig_bytes)
    else:
        catalog_path = Path(catalog_source)
        try:
            catalog_bytes = catalog_path.read_bytes()
        except OSError as failure:
            raise MarketError(f"{catalog_path}: cannot read catalog: {failure}") from failure
        if sig_source:
            sig_bytes = (
                _fetch_https_bytes(sig_source, max_bytes=64 * 1024)
                if sig_source.startswith("https://")
                else Path(sig_source).read_bytes()
            )
            _verify_ed25519_signature(catalog_bytes, sig_bytes)

    try:
        catalog = json.loads(catalog_bytes.decode("utf-8", errors="strict"))
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise MarketError(f"cannot parse catalog JSON: {failure}") from failure
    if (
        not isinstance(catalog, dict)
        or catalog.get("format") != "turboism.plugin.catalog"
        or catalog.get("schemaVersion") != 2
        or not isinstance(catalog.get("plugins"), list)
    ):
        raise MarketError("catalog document is not a valid turboism.plugin.catalog v2")

    def load_artifact_bytes(artifact: dict) -> bytes | None:
        url = artifact.get("url")
        expected_sha = artifact.get("sha256")
        expected_size = artifact.get("size")
        if not isinstance(url, str) or not url:
            return None
        if url.startswith("file://"):
            local_path = Path(urllib.parse.urlsplit(url).path)
            if not local_path.is_file():
                return None
            data = local_path.read_bytes()
        elif not url.startswith("https://") and not url.startswith("http://"):
            base_dir = Path(catalog_source).parent if not is_remote else Path.cwd()
            local_path = (base_dir / url).resolve()
            if not local_path.is_file():
                return None
            data = local_path.read_bytes()
        elif is_remote:
            data = _fetch_https_bytes(url, max_bytes=MAX_JAR_BYTES)
        else:
            return None
        if isinstance(expected_size, int) and len(data) != expected_size:
            raise MarketError(f"catalog artifact {url} size {len(data)} != expected {expected_size}")
        if isinstance(expected_sha, str) and sha256(data) != expected_sha:
            raise MarketError(f"catalog artifact {url} SHA-256 mismatch")
        return data

    return catalog, load_artifact_bytes


def jar_artifact(build_root: Path, module: str, tracked_raw: bytes,
                 base_name: str, locales: list,
                 allowed_version: str | None = None) -> tuple:
    """Verify one built JAR; return (jar path, descriptor sha256, localizations)."""
    jar = find_jar(build_root, module)
    raw_bytes = jar.read_bytes()
    verify_jar_reproducibility(raw_bytes, str(jar))
    try:
        with zipfile.ZipFile(io.BytesIO(raw_bytes)) as archive:
            try:
                embedded = archive.read(DESCRIPTOR_ENTRY)
            except KeyError as failure:
                raise MarketError(f"{jar}: missing {DESCRIPTOR_ENTRY}") from failure
            verify_descriptor_against_tracked(
                embedded, tracked_raw, str(jar), allowed_version=allowed_version
            )

            if json.loads(embedded.decode("utf-8")).get("lifecycle", {}).get("hotReload") or json.loads(tracked_raw.decode("utf-8")).get("lifecycle", {}).get("hotReload"):
                names = set(archive.namelist())
                if "META-INF/turboism/hooks" in names or any(n.startswith("META-INF/turboism/hooks/") for n in names):
                    raise MarketError(f"{jar}: plugin declares hotReload=true but built JAR contains META-INF/turboism/hooks")
                try:
                    manifest_data = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="ignore")
                    if "Premain-Class" in manifest_data or "Agent-Class" in manifest_data:
                        raise MarketError(f"{jar}: plugin declares hotReload=true but manifest contains agent hooks")
                except KeyError:
                    pass

            def read_catalog(name: str):
                try:
                    return archive.read(name).decode("utf-8")
                except KeyError:
                    return None

            localizations = required_localizations(read_catalog, base_name, locales)
    except zipfile.BadZipFile as failure:
        raise MarketError(f"{jar}: not a valid ZIP/JAR") from failure
    return jar, sha256(embedded), localizations


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical_json(value: dict) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def build_sidecar(revision: str, prepared: list) -> dict:
    artifacts = []
    for item in prepared:
        entry = item["entry"]
        descriptor = item["descriptor"]
        module = item["module"]
        artifacts.append({
            "project": entry["project"],
            "module": module,
            "asset": f"{module}-{descriptor['version']}.jar",
            "sha256": item["jar_sha256"],
            "size": item["size"],
            "descriptorSha256": item["descriptor_sha256"],
            "policy": {
                "channel": entry["channel"],
                "cubismVersions": entry["cubismVersions"],
                "repository": entry["repository"],
                "support": entry["support"],
            },
            "descriptor": {
                "id": descriptor["id"],
                "version": descriptor["version"],
                "name": descriptor["name"],
                "description": descriptor["description"],
                "author": descriptor["author"],
                "license": descriptor["license"],
                "category": descriptor["category"],
                "tags": descriptor["tags"],
                "turboismApi": descriptor["turboismApi"],
                "environment": descriptor["environment"],
                "dependencies": descriptor["dependencies"],
                "permissions": descriptor["permissions"],
                "publishedEvents": descriptor["eventExports"],
                "subscribedEvents": descriptor["eventImports"],
                "embeddedContracts": descriptor["eventContracts"],
                "hotReload": descriptor.get("hotReload", False),
            },
            "localizations": {
                locale: {"name": entry["plugin.name"],
                         "description": entry["plugin.description"]}
                for locale, entry in item["localizations"].items()
            },
        })
    return {
        "format": "turboism.market-release",
        "schemaVersion": 1,
        "source": {"revision": revision},
        "artifacts": artifacts,
    }


def commit_directory(temporary: Path, output: Path) -> None:
    """Atomically replace ``output`` with the fully staged ``temporary``."""
    output.parent.mkdir(parents=True, exist_ok=True)
    backup = output.with_name(output.name + ".old")
    if backup.exists():
        shutil.rmtree(backup)
    if output.exists():
        os.replace(output, backup)
    try:
        os.replace(temporary, output)
    except BaseException:
        if backup.exists() and not output.exists():
            os.replace(backup, output)
        raise
    if backup.exists():
        shutil.rmtree(backup)


def _normalize_release_channel(release_channel: str | None) -> str | None:
    if release_channel is None:
        return None
    if release_channel not in ("stable", "beta", "preview", "nightly"):
        raise MarketError(
            f"--release-channel must be 'stable', 'beta', 'preview', or 'nightly', got {release_channel!r}"
        )
    return release_channel


def stage(repo_root: Path, manifest_path: Path, build_root: Path, output: Path,
          revision: str, *, catalog: str | None = None,
          catalog_sig: str | None = None,
          framework_version: str | None = None,
          release_channel: str | None = None) -> int:
    channel = _normalize_release_channel(release_channel)
    if channel == "nightly":
        return 0
    prepared = validate_selection(repo_root, manifest_path)
    if not prepared:
        return 0
    if revision is None or not SOURCE_SHA.match(revision):
        raise MarketError("--source-revision must be a 40-character hex commit SHA")
    if build_root is None:
        raise MarketError("--build-root is required when the selection is non-empty")
    if output is None:
        raise MarketError("--output is required when the selection is non-empty")
    catalog_by_id: dict[str, dict] = {}
    artifact_loader = None
    auto_bump = catalog is not None or framework_version is not None
    effective_framework_version = (
        base_framework_version(framework_version)
        if framework_version is not None
        else read_repo_framework_version(repo_root)
    )
    if catalog is not None:
        catalog_doc, artifact_loader = load_catalog(catalog, catalog_sig)
        for plugin in catalog_doc.get("plugins", []):
            if not isinstance(plugin, dict) or not isinstance(plugin.get("id"), str):
                raise MarketError("catalog contains a malformed plugin entry")
            if plugin["id"] in catalog_by_id:
                raise MarketError(f"catalog contains duplicate plugin id {plugin['id']!r}")
            catalog_by_id[plugin["id"]] = plugin

    build_root = Path(os.path.abspath(build_root))
    output = Path(os.path.abspath(output))
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=output.name + ".tmp-", dir=output.parent))
    staged_items = []
    try:
        for item in prepared:
            module = item["module"]
            descriptor = dict(item["descriptor"])
            entry = dict(item["entry"])
            if channel in ("beta", "preview"):
                entry["channel"] = "preview"
            _, tracked_raw = load_descriptor(repo_root, module)
            jar, descriptor_sha256, localizations = jar_artifact(
                build_root, module, tracked_raw,
                descriptor["i18n"]["baseName"], descriptor["i18n"]["locales"],
                allowed_version=effective_framework_version if auto_bump else None,
            )
            data = jar.read_bytes()
            if len(data) > MAX_JAR_BYTES:
                raise MarketError(f"{jar}: JAR exceeds {MAX_JAR_BYTES} bytes")
            if auto_bump:
                catalog_plugin = catalog_by_id.get(descriptor["id"])
                releases = (
                    catalog_plugin.get("releases", [])
                    if isinstance(catalog_plugin, dict)
                    else []
                )
                if not isinstance(releases, list):
                    raise MarketError(f"catalog plugin {descriptor['id']!r} releases must be an array")
                if releases:
                    latest_release = max(
                        releases,
                        key=lambda rel: parse_strict_version(rel.get("version", "")),
                    )
                    if jar_matches_catalog_release(data, tracked_raw, latest_release, artifact_loader):
                        continue
                target_version = resolve_next_plugin_version(
                    effective_framework_version,
                    releases,
                    descriptor["version"],
                )
                new_descriptor_raw = rewrite_descriptor_version(tracked_raw, target_version)
                data = rewrite_jar_version(data, new_descriptor_raw)
                if len(data) > MAX_JAR_BYTES:
                    raise MarketError(f"{jar}: JAR exceeds {MAX_JAR_BYTES} bytes")
                descriptor["version"] = target_version
                descriptor_sha256 = sha256(new_descriptor_raw)
            item = {
                **item,
                "entry": entry,
                "descriptor": descriptor,
                "jar_sha256": sha256(data),
                "size": len(data),
                "descriptor_sha256": descriptor_sha256,
                "localizations": localizations,
            }
            canonical_name = f"{module}-{descriptor['version']}.jar"
            (temporary / canonical_name).write_bytes(data)
            staged_items.append(item)
        if not staged_items:
            shutil.rmtree(temporary, ignore_errors=True)
            if output.exists():
                shutil.rmtree(output)
            return 0
        (temporary / SIDECAR_NAME).write_bytes(
            canonical_json(build_sidecar(revision, staged_items)) + b"\n")
        commit_directory(temporary, output)
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    return len(staged_items)


def plan(repo_root: Path, manifest_path: Path, *, release_channel: str | None = None) -> int:
    channel = _normalize_release_channel(release_channel)
    if channel == "nightly":
        return 0
    return len(validate_selection(repo_root, manifest_path))


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="prepare-market-release.py",
        description=(
            "Validate the market selection manifest and stage canonical plugin JARs "
            "plus a deterministic sidecar for automatic publication. Prints the "
            "machine-readable line 'selected=N' on success."),
    )
    parser.add_argument("--manifest", type=Path, default=None,
                        help="market selection manifest (default: <repo-root>/packaging/market-plugins.json)")
    parser.add_argument("--repo-root", type=Path, default=DEFAULT_REPO_ROOT,
                        help="repository root (default: inferred from this script)")
    parser.add_argument("--build-root", type=Path,
                        help="directory holding built modules as <module>/.../*.jar "
                             "(required unless the selection is empty)")
    parser.add_argument("--output", type=Path,
                        help="output directory atomically replaced with staged JARs and "
                             "market-release.json (required unless the selection is empty)")
    parser.add_argument("--source-revision",
                        help="exact 40-hex source commit SHA (required unless the "
                             "selection is empty)")
    parser.add_argument("--catalog",
                        help="optional catalog v2 path or HTTPS URL for content-addressed "
                             "auto-bump and unchanged-plugin skipping")
    parser.add_argument("--catalog-sig",
                        help="optional detached catalog signature path or HTTPS URL")
    parser.add_argument("--framework-version",
                        help="optional framework version (e.g. 0.67.0 or 0.67.0-beta.1) for "
                             "first-publish base and MAJOR.MINOR auto-bump")
    parser.add_argument("--release-channel",
                        help="optional release channel (stable, beta, preview, nightly); "
                             "beta maps to catalog channel preview and nightly is skipped")
    parser.add_argument("--plan", action="store_true",
                        help="validate manifest and source descriptors/i18n only; "
                             "print selected=N and exit before any build or staging")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    manifest = args.manifest if args.manifest is not None else (args.repo_root / "packaging/market-plugins.json")
    try:
        if args.plan:
            count = plan(args.repo_root, manifest, release_channel=args.release_channel)
        else:
            count = stage(args.repo_root, manifest, args.build_root, args.output,
                          args.source_revision, catalog=args.catalog,
                          catalog_sig=args.catalog_sig,
                          framework_version=args.framework_version,
                          release_channel=args.release_channel)
    except (MarketError, OSError) as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 1
    print(f"selected={count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
