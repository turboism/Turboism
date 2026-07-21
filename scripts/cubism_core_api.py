#!/usr/bin/env python3
"""Extract, validate, and render governed Cubism Core public-API inventories.

Only public class-file declarations, JVM descriptors, and public constants are
recorded. An inventory is design evidence for one exact artifact; it does not
authorize runtime resolution.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Any, Iterable, Sequence

FORMAT = "turboism.cubism-core.public-api"
SCHEMA_VERSION = 1
STATUS = "OBSERVED"
PACKAGE_PREFIX = "com.live2d.sdk.cubism.core."
EXTRACTION_INCLUDES = [
    "JVM_DESCRIPTORS",
    "PUBLIC_CONSTANTS",
    "PUBLIC_DECLARATIONS",
]
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
VERSION_RE = re.compile(r"^[0-9]+(?:\.[0-9]+)*$")
INTERNAL_NAME_RE = re.compile(
    r"^[A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)*$"
)
ACCESS_ORDER = (
    "public",
    "protected",
    "private",
    "abstract",
    "static",
    "final",
    "transient",
    "volatile",
    "synchronized",
    "native",
    "strictfp",
    "default",
)
MEMBER_KIND_ORDER = {"field": 0, "constructor": 1, "method": 2}


class InventoryError(ValueError):
    """Raised when an inventory or javap observation is not canonical."""


class DuplicateKeyError(ValueError):
    """Raised while decoding JSON containing duplicate object keys."""


def _fail(path: str, message: str) -> None:
    raise InventoryError(f"{path}: {message}")


def _require_object(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        _fail(path, "expected object")
    return value


def _require_list(value: Any, path: str) -> list[Any]:
    if not isinstance(value, list):
        _fail(path, "expected array")
    return value


def _require_string(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value:
        _fail(path, "expected non-empty string")
    return value


def _require_integer(value: Any, path: str, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        _fail(path, f"expected integer >= {minimum}")
    return value


def _strict_fields(
    value: dict[str, Any],
    *,
    required: set[str],
    allowed: set[str],
    path: str,
) -> None:
    missing = sorted(required - value.keys())
    if missing:
        _fail(path, f"missing required field(s): {', '.join(missing)}")
    unknown = sorted(value.keys() - allowed)
    if unknown:
        _fail(path, f"unknown field(s): {', '.join(unknown)}")


def _pairs_without_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def decode_json(text: str, source: str = "<memory>") -> Any:
    try:
        return json.loads(text, object_pairs_hook=_pairs_without_duplicates)
    except (json.JSONDecodeError, DuplicateKeyError) as exc:
        raise InventoryError(f"{source}: invalid JSON: {exc}") from exc


def load_json(path: Path) -> Any:
    try:
        raw = path.read_bytes()
    except OSError as exc:
        raise InventoryError(f"{path}: unable to read: {exc}") from exc
    if raw.startswith(b"\xef\xbb\xbf"):
        raise InventoryError(f"{path}: UTF-8 BOM is forbidden")
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise InventoryError(f"{path}: invalid UTF-8: {exc}") from exc
    return decode_json(text, str(path))


def canonical_json(document: Any) -> str:
    return json.dumps(
        document,
        ensure_ascii=False,
        indent=2,
        sort_keys=True,
    ) + "\n"


def _consume_descriptor_type(
    descriptor: str,
    index: int,
    *,
    allow_void: bool,
) -> int:
    if index >= len(descriptor):
        raise ValueError("missing type")
    marker = descriptor[index]
    if marker in "BCDFIJSZ":
        return index + 1
    if marker == "V":
        if allow_void:
            return index + 1
        raise ValueError("void is not a field/parameter type")
    if marker == "[":
        return _consume_descriptor_type(descriptor, index + 1, allow_void=False)
    if marker == "L":
        end = descriptor.find(";", index + 1)
        if end < 0:
            raise ValueError("unterminated object type")
        internal_name = descriptor[index + 1 : end]
        if INTERNAL_NAME_RE.fullmatch(internal_name) is None:
            raise ValueError("invalid internal class name")
        return end + 1
    raise ValueError(f"unknown type marker {marker!r}")


def is_valid_descriptor(descriptor: str, *, callable_member: bool) -> bool:
    try:
        if callable_member:
            if not descriptor.startswith("("):
                return False
            index = 1
            while index < len(descriptor) and descriptor[index] != ")":
                index = _consume_descriptor_type(
                    descriptor,
                    index,
                    allow_void=False,
                )
            if index >= len(descriptor) or descriptor[index] != ")":
                return False
            index = _consume_descriptor_type(
                descriptor,
                index + 1,
                allow_void=True,
            )
            return index == len(descriptor)
        return (
            _consume_descriptor_type(descriptor, 0, allow_void=False)
            == len(descriptor)
        )
    except ValueError:
        return False


def member_sort_key(member: dict[str, Any]) -> tuple[Any, ...]:
    return (
        MEMBER_KIND_ORDER[member["kind"]],
        member["name"],
        member["descriptor"],
        member["declaration"],
    )


def _validate_access(value: Any, path: str) -> list[str]:
    access = _require_list(value, path)
    if not access:
        _fail(path, "must contain at least public")
    if any(not isinstance(flag, str) for flag in access):
        _fail(path, "all access flags must be strings")
    if len(access) != len(set(access)):
        _fail(path, "access flags must be unique")
    unknown = [flag for flag in access if flag not in ACCESS_ORDER]
    if unknown:
        _fail(path, f"unknown access flag(s): {', '.join(unknown)}")
    expected = sorted(access, key=ACCESS_ORDER.index)
    if access != expected:
        _fail(path, "access flags are not in canonical order")
    if "public" not in access:
        _fail(path, "member is not public")
    return access


def _validate_member(
    value: Any,
    path: str,
    class_name: str,
) -> dict[str, Any]:
    member = _require_object(value, path)
    kind = member.get("kind")
    allowed = {
        "kind",
        "name",
        "descriptor",
        "access",
        "declaration",
        "constantValue",
    }
    required = {"kind", "name", "descriptor", "access", "declaration"}
    _strict_fields(member, required=required, allowed=allowed, path=path)
    if kind not in MEMBER_KIND_ORDER:
        _fail(f"{path}.kind", "expected field, constructor, or method")

    name = _require_string(member["name"], f"{path}.name")
    descriptor = _require_string(member["descriptor"], f"{path}.descriptor")
    declaration = _require_string(member["declaration"], f"{path}.declaration")
    _validate_access(member["access"], f"{path}.access")

    callable_member = kind in {"constructor", "method"}
    if not is_valid_descriptor(descriptor, callable_member=callable_member):
        _fail(f"{path}.descriptor", "invalid JVM descriptor")

    if not declaration.startswith("public "):
        _fail(f"{path}.declaration", "must be a public javap declaration")
    if not declaration.endswith(";"):
        _fail(f"{path}.declaration", "must end with ';'")

    if kind == "constructor":
        if name != "<init>":
            _fail(f"{path}.name", "constructor name must be <init>")
        if not descriptor.endswith(")V"):
            _fail(f"{path}.descriptor", "constructor must return void")
        if f"{class_name}(" not in declaration:
            _fail(f"{path}.declaration", "constructor owner mismatch")
    elif kind == "method":
        if name == "<init>":
            _fail(f"{path}.name", "method cannot be named <init>")
        if f" {name}(" not in declaration:
            _fail(f"{path}.declaration", "method name mismatch")
    else:
        if "(" in declaration:
            _fail(f"{path}.declaration", "field declaration contains '('")
        if name not in declaration:
            _fail(f"{path}.declaration", "field name mismatch")

    if "constantValue" in member:
        if kind != "field":
            _fail(f"{path}.constantValue", "only fields may carry constants")
        _require_string(member["constantValue"], f"{path}.constantValue")
        if "static" not in member["access"] or "final" not in member["access"]:
            _fail(
                f"{path}.constantValue",
                "constant field must be public static final",
            )
    return member


def validate_document(document: Any) -> dict[str, Any]:
    root = _require_object(document, "$")
    required = {
        "format",
        "schemaVersion",
        "status",
        "authorizesRuntime",
        "cubismVersion",
        "artifact",
        "extraction",
        "summary",
        "classes",
    }
    _strict_fields(root, required=required, allowed=required, path="$")

    if root["format"] != FORMAT:
        _fail("$.format", f"expected {FORMAT}")
    if root["schemaVersion"] != SCHEMA_VERSION:
        _fail("$.schemaVersion", f"expected {SCHEMA_VERSION}")
    if root["status"] != STATUS:
        _fail("$.status", f"expected {STATUS}")
    if root["authorizesRuntime"] is not False:
        _fail("$.authorizesRuntime", "must be false")
    version = _require_string(root["cubismVersion"], "$.cubismVersion")
    if VERSION_RE.fullmatch(version) is None:
        _fail("$.cubismVersion", "expected dotted numeric version")

    artifact = _require_object(root["artifact"], "$.artifact")
    artifact_fields = {"fileName", "sha256", "sizeBytes"}
    _strict_fields(
        artifact,
        required=artifact_fields,
        allowed=artifact_fields,
        path="$.artifact",
    )
    file_name = _require_string(artifact["fileName"], "$.artifact.fileName")
    if Path(file_name).name != file_name or not file_name.endswith(".jar"):
        _fail("$.artifact.fileName", "expected a basename ending in .jar")
    digest = _require_string(artifact["sha256"], "$.artifact.sha256")
    if SHA256_RE.fullmatch(digest) is None:
        _fail("$.artifact.sha256", "expected lowercase SHA-256")
    _require_integer(artifact["sizeBytes"], "$.artifact.sizeBytes", 1)

    extraction = _require_object(root["extraction"], "$.extraction")
    extraction_fields = {"tool", "scope", "packagePrefix", "includes"}
    _strict_fields(
        extraction,
        required=extraction_fields,
        allowed=extraction_fields,
        path="$.extraction",
    )
    if extraction["tool"] != "javap -public -s -constants":
        _fail("$.extraction.tool", "unexpected extractor")
    if extraction["scope"] != "EXACT_CLASSFILE_PUBLIC_SURFACE":
        _fail("$.extraction.scope", "unexpected scope")
    if extraction["packagePrefix"] != PACKAGE_PREFIX:
        _fail("$.extraction.packagePrefix", "unexpected package prefix")
    if extraction["includes"] != EXTRACTION_INCLUDES:
        _fail("$.extraction.includes", "unexpected or noncanonical evidence list")

    summary = _require_object(root["summary"], "$.summary")
    summary_fields = {
        "classCount",
        "publicCallableCount",
        "publicFieldCount",
    }
    _strict_fields(
        summary,
        required=summary_fields,
        allowed=summary_fields,
        path="$.summary",
    )
    for field in sorted(summary_fields):
        _require_integer(summary[field], f"$.summary.{field}")

    classes = _require_list(root["classes"], "$.classes")
    if not classes:
        _fail("$.classes", "must contain at least one public class")
    seen_classes: set[str] = set()
    class_names: list[str] = []
    callable_count = 0
    field_count = 0
    for class_index, class_value in enumerate(classes):
        class_path = f"$.classes[{class_index}]"
        class_entry = _require_object(class_value, class_path)
        class_fields = {"name", "declaration", "members"}
        _strict_fields(
            class_entry,
            required=class_fields,
            allowed=class_fields,
            path=class_path,
        )
        class_name = _require_string(class_entry["name"], f"{class_path}.name")
        if not class_name.startswith(PACKAGE_PREFIX):
            _fail(f"{class_path}.name", "outside the governed package")
        if class_name in seen_classes:
            _fail(f"{class_path}.name", "duplicate class")
        seen_classes.add(class_name)
        class_names.append(class_name)

        declaration = _require_string(
            class_entry["declaration"],
            f"{class_path}.declaration",
        )
        if not declaration.startswith("public ") or class_name not in declaration:
            _fail(
                f"{class_path}.declaration",
                "must be the public declaration for this class",
            )

        members = _require_list(class_entry["members"], f"{class_path}.members")
        seen_members: set[tuple[str, str, str]] = set()
        validated_members: list[dict[str, Any]] = []
        for member_index, member_value in enumerate(members):
            member_path = f"{class_path}.members[{member_index}]"
            member = _validate_member(member_value, member_path, class_name)
            identity = (member["kind"], member["name"], member["descriptor"])
            if identity in seen_members:
                _fail(member_path, "duplicate member identity")
            seen_members.add(identity)
            validated_members.append(member)
            if member["kind"] == "field":
                field_count += 1
            else:
                callable_count += 1
        if validated_members != sorted(validated_members, key=member_sort_key):
            _fail(f"{class_path}.members", "members are not canonically sorted")

    if class_names != sorted(class_names):
        _fail("$.classes", "classes are not sorted by binary name")
    if summary["classCount"] != len(classes):
        _fail("$.summary.classCount", "does not match classes")
    if summary["publicCallableCount"] != callable_count:
        _fail("$.summary.publicCallableCount", "does not match members")
    if summary["publicFieldCount"] != field_count:
        _fail("$.summary.publicFieldCount", "does not match members")
    return root


def load_inventory(path: Path) -> dict[str, Any]:
    return validate_document(load_json(path))


def _access_from_declaration(declaration: str) -> list[str]:
    tokens = set(declaration.replace("(", " ").split())
    return [flag for flag in ACCESS_ORDER if flag in tokens]


def _parse_member_declaration(
    declaration: str,
    descriptor: str,
    class_name: str,
) -> dict[str, Any]:
    access = _access_from_declaration(declaration)
    if "public" not in access:
        raise InventoryError(
            f"{class_name}: javap -public emitted non-public declaration: "
            f"{declaration}"
        )

    if "(" in declaration:
        before_parameters = declaration.split("(", 1)[0].strip()
        raw_name = before_parameters.split()[-1]
        if raw_name == class_name:
            kind = "constructor"
            name = "<init>"
        else:
            kind = "method"
            name = raw_name
    else:
        kind = "field"
        left = declaration[:-1].split(" = ", 1)[0].strip()
        name = left.split()[-1]

    member: dict[str, Any] = {
        "kind": kind,
        "name": name,
        "descriptor": descriptor,
        "access": access,
        "declaration": declaration,
    }
    if kind == "field" and " = " in declaration:
        constant = declaration[:-1].split(" = ", 1)[1].strip()
        if constant:
            member["constantValue"] = constant
    return member


def parse_javap_class(
    class_name: str,
    output: str,
) -> dict[str, Any] | None:
    lines = output.splitlines()
    header_index: int | None = None
    declaration: str | None = None
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("public ") and stripped.endswith(" {"):
            header_index = index
            declaration = stripped[:-2]
            break
    if header_index is None or declaration is None:
        return None
    if class_name not in declaration:
        raise InventoryError(
            f"{class_name}: javap public declaration owner mismatch: "
            f"{declaration}"
        )

    members: list[dict[str, Any]] = []
    index = header_index + 1
    while index < len(lines):
        stripped = lines[index].strip()
        if stripped == "}":
            break
        if not stripped:
            index += 1
            continue
        if not stripped.startswith("public ") or not stripped.endswith(";"):
            index += 1
            continue
        declaration_line = stripped
        descriptor_index = index + 1
        while descriptor_index < len(lines) and not lines[descriptor_index].strip():
            descriptor_index += 1
        if descriptor_index >= len(lines):
            raise InventoryError(
                f"{class_name}: descriptor missing after {declaration_line}"
            )
        descriptor_line = lines[descriptor_index].strip()
        prefix = "descriptor:"
        if not descriptor_line.startswith(prefix):
            raise InventoryError(
                f"{class_name}: descriptor missing after {declaration_line}"
            )
        descriptor = descriptor_line[len(prefix) :].strip()
        members.append(
            _parse_member_declaration(
                declaration_line,
                descriptor,
                class_name,
            )
        )
        index = descriptor_index + 1

    members.sort(key=member_sort_key)
    return {
        "name": class_name,
        "declaration": declaration,
        "members": members,
    }


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                return digest.hexdigest()
            digest.update(chunk)


def _class_names_from_jar(jar_path: Path) -> list[str]:
    prefix_path = PACKAGE_PREFIX.replace(".", "/")
    try:
        with zipfile.ZipFile(jar_path) as archive:
            names = [
                info.filename
                for info in archive.infolist()
                if (
                    info.filename.startswith(prefix_path)
                    and info.filename.endswith(".class")
                    and not info.is_dir()
                )
            ]
    except (OSError, zipfile.BadZipFile) as exc:
        raise InventoryError(f"{jar_path}: invalid JAR: {exc}") from exc
    if len(names) != len(set(names)):
        raise InventoryError(f"{jar_path}: duplicate class-file entry")
    return sorted(name[:-6].replace("/", ".") for name in names)


def extract_inventory(
    jar_path: Path,
    cubism_version: str,
    *,
    javap: str = "javap",
) -> dict[str, Any]:
    if VERSION_RE.fullmatch(cubism_version) is None:
        raise InventoryError(
            f"cubism version must be dotted numeric: {cubism_version}"
        )
    if not jar_path.is_file():
        raise InventoryError(f"Core JAR does not exist: {jar_path}")

    classes: list[dict[str, Any]] = []
    environment = dict(os.environ)
    environment["LC_ALL"] = "C"
    for class_name in _class_names_from_jar(jar_path):
        command = [
            javap,
            "-classpath",
            str(jar_path),
            "-public",
            "-s",
            "-constants",
            class_name,
        ]
        try:
            completed = subprocess.run(
                command,
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="strict",
                env=environment,
            )
        except OSError as exc:
            raise InventoryError(f"unable to run {javap}: {exc}") from exc
        if completed.returncode != 0:
            raise InventoryError(
                f"{class_name}: javap failed ({completed.returncode}): "
                f"{completed.stderr.strip()}"
            )
        parsed = parse_javap_class(class_name, completed.stdout)
        if parsed is not None:
            classes.append(parsed)

    classes.sort(key=lambda item: item["name"])
    callable_count = sum(
        1
        for class_entry in classes
        for member in class_entry["members"]
        if member["kind"] != "field"
    )
    field_count = sum(
        1
        for class_entry in classes
        for member in class_entry["members"]
        if member["kind"] == "field"
    )
    document = {
        "format": FORMAT,
        "schemaVersion": SCHEMA_VERSION,
        "status": STATUS,
        "authorizesRuntime": False,
        "cubismVersion": cubism_version,
        "artifact": {
            "fileName": jar_path.name,
            "sha256": _sha256(jar_path),
            "sizeBytes": jar_path.stat().st_size,
        },
        "extraction": {
            "tool": "javap -public -s -constants",
            "scope": "EXACT_CLASSFILE_PUBLIC_SURFACE",
            "packagePrefix": PACKAGE_PREFIX,
            "includes": EXTRACTION_INCLUDES,
        },
        "summary": {
            "classCount": len(classes),
            "publicCallableCount": callable_count,
            "publicFieldCount": field_count,
        },
        "classes": classes,
    }
    return validate_document(document)


def _version_key(version: str) -> tuple[int, ...]:
    return tuple(int(component) for component in version.split("."))


def _member_identity(member: dict[str, Any]) -> tuple[str, str, str]:
    return (member["kind"], member["name"], member["descriptor"])


def _member_display(member: dict[str, Any]) -> str:
    tick = chr(96)
    return (
        f"{tick}{member['declaration']}{tick} "
        f"({tick}{member['descriptor']}{tick})"
    )


def _class_map(document: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {entry["name"]: entry for entry in document["classes"]}


def _append_class_list(lines: list[str], names: Iterable[str]) -> None:
    tick = chr(96)
    materialized = list(names)
    if not materialized:
        lines.extend(["None.", ""])
        return
    lines.extend(f"- {tick}{name}{tick}" for name in materialized)
    lines.append("")


def render_reference(documents: Sequence[dict[str, Any]]) -> str:
    if len(documents) < 2:
        raise InventoryError("reference rendering requires at least two versions")
    inventories = sorted(
        (validate_document(document) for document in documents),
        key=lambda item: _version_key(item["cubismVersion"]),
    )
    tick = chr(96)
    lines = [
        "# Cubism Core Public API Reference",
        "",
        "> Generated by "
        f"{tick}scripts/cubism_core_api.py render{tick}; do not edit by hand.",
        "> These inventories are exact-artifact public-surface observations. "
        "They do not authorize runtime binding.",
        "",
        "## Evidence boundary",
        "",
        "- Included: public class declarations, public field/callable "
        "declarations, JVM descriptors, and compile-time public constants.",
        "- Excluded: binaries, bytecode/method bodies, private/package-private "
        "members, runtime semantics, thread-safety, array ownership, and "
        "Editor model-acquisition selectors.",
        "- Runtime use still requires a minimal reviewed mapping pack plus "
        "exact CodeSource/classloader verification.",
        "",
        "## Source inventories",
        "",
        "| Cubism | Artifact | SHA-256 | Classes | Public callables | "
        "Public fields | Status |",
        "| --- | --- | --- | ---: | ---: | ---: | --- |",
    ]
    for inventory in inventories:
        artifact = inventory["artifact"]
        summary = inventory["summary"]
        lines.append(
            f"| {inventory['cubismVersion']} | {tick}{artifact['fileName']}{tick} "
            f"| {tick}{artifact['sha256']}{tick} | {summary['classCount']} "
            f"| {summary['publicCallableCount']} | "
            f"{summary['publicFieldCount']} | {tick}{inventory['status']}{tick} |"
        )
    lines.append("")

    baseline = inventories[0]
    latest = inventories[-1]
    baseline_classes = set(_class_map(baseline))
    latest_classes = set(_class_map(latest))
    lines.extend(
        [
            f"## Public class delta: {baseline['cubismVersion']} → "
            f"{latest['cubismVersion']}",
            "",
            f"### Shared classes ({len(baseline_classes & latest_classes)})",
            "",
        ]
    )
    _append_class_list(lines, sorted(baseline_classes & latest_classes))
    lines.extend(
        [
            f"### Only {baseline['cubismVersion']} "
            f"({len(baseline_classes - latest_classes)})",
            "",
        ]
    )
    _append_class_list(lines, sorted(baseline_classes - latest_classes))
    lines.extend(
        [
            f"### Only {latest['cubismVersion']} "
            f"({len(latest_classes - baseline_classes)})",
            "",
        ]
    )
    _append_class_list(lines, sorted(latest_classes - baseline_classes))

    baseline_map = _class_map(baseline)
    latest_map = _class_map(latest)
    changed: list[
        tuple[str, list[dict[str, Any]], list[dict[str, Any]]]
    ] = []
    for class_name in sorted(baseline_classes & latest_classes):
        old_members = {
            _member_identity(member): member
            for member in baseline_map[class_name]["members"]
        }
        new_members = {
            _member_identity(member): member
            for member in latest_map[class_name]["members"]
        }
        removed = [
            old_members[key]
            for key in sorted(old_members.keys() - new_members.keys())
        ]
        added = [
            new_members[key]
            for key in sorted(new_members.keys() - old_members.keys())
        ]
        if removed or added:
            changed.append((class_name, added, removed))

    lines.extend(["## Changed public members", ""])
    if not changed:
        lines.extend(["None.", ""])
    for class_name, added, removed in changed:
        lines.extend([f"### {tick}{class_name}{tick}", ""])
        if added:
            lines.append("Added:")
            lines.append("")
            lines.extend(f"- {_member_display(member)}" for member in added)
            lines.append("")
        if removed:
            lines.append("Removed:")
            lines.append("")
            lines.extend(f"- {_member_display(member)}" for member in removed)
            lines.append("")

    lines.extend(
        [
            "## Descriptor observations requiring semantic follow-up",
            "",
            "- "
            f"{tick}CubismPartView#getOffscreenIndices{tick} is exactly "
            f"{tick}()I{tick} in the observed 5.3.02 artifact.",
            "- "
            f"{tick}CubismOffscreenRenderingView#getReferenceObjectIndices{tick} "
            f"is exactly {tick}()I{tick} in the observed 5.3.02 artifact.",
            "- The plural names do not prove collection semantics. Neither method "
            "may be admitted to a runtime selector set until behavior is verified.",
            "",
        ]
    )

    for inventory in inventories:
        version = inventory["cubismVersion"]
        lines.extend([f"## Cubism {version} public API", ""])
        for class_entry in inventory["classes"]:
            lines.extend(
                [
                    f"### {tick}{class_entry['name']}{tick}",
                    "",
                    f"Declaration: {tick}{class_entry['declaration']}{tick}",
                    "",
                    "| Kind | Declaration | JVM descriptor |",
                    "| --- | --- | --- |",
                ]
            )
            for member in class_entry["members"]:
                declaration = member["declaration"].replace("|", r"\|")
                lines.append(
                    f"| {member['kind']} | {tick}{declaration}{tick} "
                    f"| {tick}{member['descriptor']}{tick} |"
                )
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def _write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        newline="\n",
        dir=path.parent,
        prefix=f".{path.name}.",
        delete=False,
    ) as stream:
        temporary = Path(stream.name)
        stream.write(content)
    try:
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def _command_extract(arguments: argparse.Namespace) -> int:
    document = extract_inventory(
        arguments.jar.resolve(),
        arguments.cubism_version,
        javap=arguments.javap,
    )
    _write_text(arguments.output, canonical_json(document))
    summary = document["summary"]
    print(
        f"wrote {arguments.output}: "
        f"{summary['classCount']} classes, "
        f"{summary['publicCallableCount']} public callables, "
        f"{summary['publicFieldCount']} public fields"
    )
    return 0


def _command_validate(arguments: argparse.Namespace) -> int:
    for path in arguments.inventory:
        document = load_inventory(path)
        summary = document["summary"]
        print(
            f"OK {path}: {summary['classCount']} classes, "
            f"{summary['publicCallableCount']} callables, "
            f"{summary['publicFieldCount']} fields"
        )
    return 0


def _command_render(arguments: argparse.Namespace) -> int:
    documents = [load_inventory(path) for path in arguments.inventory]
    rendered = render_reference(documents)
    if arguments.check:
        try:
            committed = arguments.output.read_text(encoding="utf-8")
        except OSError as exc:
            raise InventoryError(
                f"{arguments.output}: unable to read generated reference: {exc}"
            ) from exc
        if committed != rendered:
            diff = difflib.unified_diff(
                committed.splitlines(),
                rendered.splitlines(),
                fromfile=str(arguments.output),
                tofile="regenerated",
                lineterm="",
            )
            print("\n".join(diff), file=sys.stderr)
            return 1
        print(f"OK {arguments.output}: generated reference is current")
        return 0
    _write_text(arguments.output, rendered)
    print(f"wrote {arguments.output}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__,
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    extract = subparsers.add_parser(
        "extract",
        help="observe one exact Core JAR public surface",
    )
    extract.add_argument("--jar", type=Path, required=True)
    extract.add_argument("--cubism-version", required=True)
    extract.add_argument("--output", type=Path, required=True)
    extract.add_argument("--javap", default="javap")
    extract.set_defaults(handler=_command_extract)

    validate = subparsers.add_parser(
        "validate",
        help="validate canonical inventory contracts",
    )
    validate.add_argument("inventory", type=Path, nargs="+")
    validate.set_defaults(handler=_command_validate)

    render = subparsers.add_parser(
        "render",
        help="render the deterministic Markdown reference",
    )
    render.add_argument("--inventory", type=Path, action="append", required=True)
    render.add_argument("--output", type=Path, required=True)
    render.add_argument("--check", action="store_true")
    render.set_defaults(handler=_command_render)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    arguments = parser.parse_args(argv)
    try:
        return int(arguments.handler(arguments))
    except InventoryError as exc:
        print(f"Cubism Core API inventory error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
