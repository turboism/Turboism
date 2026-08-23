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
  * only known ``:plugins:*`` modules; ``:plugins:core`` and retired plugin
    ids are rejected; duplicate descriptor ids are rejected;
  * descriptor version is authoritative strict MAJOR.MINOR.PATCH;
  * schemaVersion 3 or 4 with a category and ordered non-empty tags is required;
  * schema-v4 public event exports/imports are normalized into store metadata;
  * ``cubismVersions`` is non-empty strict MAJOR.MINOR.PATCH only when the
    descriptor requires Cubism, and must be empty otherwise;
  * selected plugins need complete nonblank ``plugin.name`` and
    ``plugin.description`` in the declared en, zh-Hans and ja catalogs;
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
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import urllib.parse
import zipfile
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from plugin_event_metadata import EventMetadataError, normalize_event_metadata, validate_event_routes

DESCRIPTOR_ENTRY = "META-INF/turboism/plugin.json"
SIDECAR_NAME = "market-release.json"
MAX_JAR_BYTES = 16 * 1024 * 1024  # 16 MiB contract ceiling
STRICT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
SOURCE_SHA = re.compile(r"^[0-9a-f]{40}$")
REQUIRED_LOCALES = ("en", "zh-Hans", "ja")
REQUIRED_KEYS = ("plugin.name", "plugin.description")
DEFAULT_REPO_ROOT = Path(__file__).resolve().parents[2]

# Mirrors runtime/src/main/java/dev/turboism/core/plugin/PluginJarContract
# RETIRED_PLUGIN_IDS. The Gradle gates are authoritative; this set only keeps
# retired ids from being selected for publication in the first place.
RETIRED_PLUGIN_IDS = frozenset({
    "dev.turboism.plugin.logfilter",
    "dev.turboism.plugin.clipmask",
    "dev.turboism.plugin.perfopt",
    "dev.turboism.plugin.renderopt",
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
    unknown = sorted(set(document) - {"schemaVersion", "plugins"})
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
    if schema_version not in (3, 4):
        raise MarketError(f"plugins/{module}: descriptor schemaVersion must be 3 or 4")
    try:
        event_exports, event_imports = normalize_event_metadata(document, f"plugins/{module}")
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
        "i18n": {"baseName": base_name, "locales": list(locales)},
    }


def catalog_name(base_name: str, locale: str) -> str:
    """Java resource naming: zh-Hans -> base_zh_Hans.properties."""
    return base_name + "_" + locale.replace("-", "_") + ".properties"


def required_localizations(read_catalog, base_name: str, locales: list) -> dict:
    """Require nonblank plugin.name/plugin.description in en/zh-Hans/ja."""
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


def validate_selection(repo_root: Path, manifest_path: Path) -> list:
    """Validate the manifest plus source descriptors; return prepared entries."""
    entries = load_manifest(manifest_path)
    if not entries:
        return []
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


def jar_artifact(build_root: Path, module: str, tracked_raw: bytes,
                 base_name: str, locales: list) -> tuple:
    """Verify one built JAR; return (jar path, descriptor sha256, localizations)."""
    jar = find_jar(build_root, module)
    try:
        with zipfile.ZipFile(jar) as archive:
            try:
                embedded = archive.read(DESCRIPTOR_ENTRY)
            except KeyError as failure:
                raise MarketError(f"{jar}: missing {DESCRIPTOR_ENTRY}") from failure
            if embedded != tracked_raw:
                raise MarketError(
                    f"{jar}: embedded descriptor does not match tracked descriptor "
                    f"({sha256(tracked_raw)[:12]} != {sha256(embedded)[:12]})")

            def read_catalog(name: str):
                try:
                    return archive.read(name).decode("utf-8")
                except KeyError:
                    return None

            localizations = required_localizations(read_catalog, base_name, locales)
    except zipfile.BadZipFile as failure:
        raise MarketError(f"{jar}: not a valid ZIP/JAR") from failure
    return jar, sha256(tracked_raw), localizations


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


def stage(repo_root: Path, manifest_path: Path, build_root: Path, output: Path,
          revision: str) -> int:
    prepared = validate_selection(repo_root, manifest_path)
    if not prepared:
        return 0
    if revision is None or not SOURCE_SHA.match(revision):
        raise MarketError("--source-revision must be a 40-character hex commit SHA")
    if build_root is None:
        raise MarketError("--build-root is required when the selection is non-empty")
    if output is None:
        raise MarketError("--output is required when the selection is non-empty")
    build_root = Path(os.path.abspath(build_root))
    output = Path(os.path.abspath(output))
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=output.name + ".tmp-", dir=output.parent))
    try:
        for item in prepared:
            module = item["module"]
            descriptor = item["descriptor"]
            _, tracked_raw = load_descriptor(repo_root, module)
            jar, descriptor_sha256, localizations = jar_artifact(
                build_root, module, tracked_raw,
                descriptor["i18n"]["baseName"], descriptor["i18n"]["locales"])
            data = jar.read_bytes()
            if len(data) > MAX_JAR_BYTES:
                raise MarketError(f"{jar}: JAR exceeds {MAX_JAR_BYTES} bytes")
            item["jar_sha256"] = sha256(data)
            item["size"] = len(data)
            item["descriptor_sha256"] = descriptor_sha256
            item["localizations"] = localizations
            canonical_name = f"{module}-{descriptor['version']}.jar"
            (temporary / canonical_name).write_bytes(data)
        (temporary / SIDECAR_NAME).write_bytes(
            canonical_json(build_sidecar(revision, prepared)) + b"\n")
        commit_directory(temporary, output)
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    return len(prepared)


def plan(repo_root: Path, manifest_path: Path) -> int:
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
    parser.add_argument("--manifest", required=True, type=Path,
                        help="market selection manifest (packaging/market-plugins.json)")
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
    parser.add_argument("--plan", action="store_true",
                        help="validate manifest and source descriptors/i18n only; "
                             "print selected=N and exit before any build or staging")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    try:
        if args.plan:
            count = plan(args.repo_root, args.manifest)
        else:
            count = stage(args.repo_root, args.manifest, args.build_root, args.output,
                          args.source_revision)
    except (MarketError, OSError) as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 1
    print(f"selected={count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
