#!/usr/bin/env python3
"""Generate verification manifests and selector contracts from records + authoring files.

The ``*VerificationManifest`` classes and ``selector/*Contract`` classes under
``dev.turboism.mapping.verification`` are generated artifacts. Their source of
truth is:

- the byte-hashed verification records under
  ``compatibility/cubism/verification/`` (record sha256, verificationId,
  cubismVersion, profileId, adapterSliceId and artifact identity are substituted
  from record bytes at render time, so a Java pin can never drift from the
  audited record), and
- per-family authoring files under ``scripts/verification-sources/`` that carry
  the data records do not model: class Javadoc, capability/alias grouping,
  method bodies and every other authored line, expressed as Java templates with
  ``${record:<record-file>:<field>}`` placeholders.

Modes:

- ``render --out DIR`` writes generated sources below DIR and validates them;
  unresolved placeholders, unbound literals and coverage gaps fail closed.
- ``--check`` renders everything, applies the same validation, then compares
  each rendered file byte-for-byte with its checked-in counterpart under
  ``runtime/src/main/java``. Byte-level equivalence is used because templates
  preserve all formatting: rendering back identical bytes is the strongest
  possible equivalence proof, and a normalized-AST comparison would only be
  needed if templates re-rendered a different surface syntax. Once checked-in
  sources are removed, ``--check`` instead enforces that none of the generated
  targets reappear under ``src/main/java`` (migration completeness) while still
  running render+validation.
- ``extract`` (maintenance) rewrites the authoring templates and family files
  from the current checked-in sources; use it when intentionally migrating a
  new file into generation.

Usage: generate_verification_sources.py [repo-root] render --out DIR
       generate_verification_sources.py [repo-root] --check
       generate_verification_sources.py [repo-root] extract
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AUTHORING_ROOT = Path("scripts/verification-sources")
FAMILY_DIR = AUTHORING_ROOT / "families"
TEMPLATE_ROOT = AUTHORING_ROOT / "templates"
VERIFICATION_DIR = Path("compatibility/cubism/verification")
PACK_DIR = Path("compatibility/cubism/mapping-packs/draft")
SOURCE_ROOT = Path("runtime/src/main/java")
VERIFICATION_PACKAGE = "dev/turboism/mapping/verification"

FAMILY_FORMAT = "turboism.verification-source-family/v1"
GENERATED_TARGETS = re.compile(
    r"^" + VERIFICATION_PACKAGE + r"/(?:[^/]*VerificationManifest|selector/[^/]+)\.java$"
)

PLACEHOLDER = re.compile(
    r"\$\{record:([^}:]+):((?:artifact\.)?[a-zA-Z0-9]+)(?::([a-z]+))?\}"
)
RECORD_FILE_NAME = re.compile(r"^cubism-\d+\.\d+\.\d+-.+\.json$")
STRING_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
EVIDENCE_SET = re.compile(r"(?:Set|List|Map)\.of\s*\(|union\s*\(|Set\.copyOf\s*\(")
EVIDENCE_CONSTANT = re.compile(
    r"\bString\s+([A-Z][A-Z0-9_]*)\s*=\s*\"((?:[^\"\\]|\\.)*)\""
)
EVIDENCE_NAME = re.compile(
    r"^(REQUIRED_ALIASES|[A-Z0-9_]*_ALIASES|[A-Z0-9_]*_ALIAS|[A-Z0-9_]*_CAPABILITY_ID"
    r"|CAPABILITY_ID|ADAPTER_SLICE_ID|VERIFICATION_ID[A-Z0-9_]*"
    r"|RECORD_SHA256[A-Z0-9_]*|PROFILE_ID[A-Z0-9_]*|CUBISM_VERSION[A-Z0-9_]*)$"
)
SCALAR_FIELDS = (
    "sha256",
    "verificationId",
    "cubismVersion",
    "profileId",
    "adapterSliceId",
    "artifact.name",
    "artifact.sha256",
)


class GenerationError(Exception):
    """Raised for unreadable, unparseable or inconsistent authoring inputs."""


def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        raise GenerationError(f"unable to read {path}: {exc}") from exc


def mask_comments(text: str) -> str:
    """Returns ``text`` with comment contents blanked to spaces.

    String literals are skipped so ``//`` inside a literal is never treated as a
    comment start. Newlines are preserved so offsets still map to ``text``.
    """
    out = list(text)
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        if char == '"':
            index += 1
            while index < length and text[index] != '"':
                index += 2 if text[index] == "\\" else 1
            index += 1
            continue
        if char == "'":
            index += 1
            while index < length and text[index] != "'":
                index += 2 if text[index] == "\\" else 1
            index += 1
            continue
        if char == "/" and index + 1 < length and text[index + 1] == "/":
            while index < length and text[index] != "\n":
                out[index] = " "
                index += 1
            continue
        if char == "/" and index + 1 < length and text[index + 1] == "*":
            out[index] = out[index + 1] = " "
            index += 2
            while index + 1 < length and not (
                text[index] == "*" and text[index + 1] == "/"
            ):
                if text[index] != "\n":
                    out[index] = " "
                index += 1
            if index + 1 < length:
                out[index] = out[index + 1] = " "
                index += 2
            continue
        index += 1
    return "".join(out)


def load_records(root: Path) -> dict[str, dict]:
    """Returns record filename -> parsed record with byte sha256 and sets."""
    records: dict[str, dict] = {}
    base = root / VERIFICATION_DIR
    for path in sorted(base.glob("*.json")):
        raw = path.read_bytes()
        try:
            document = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise GenerationError(f"{path.name}: invalid JSON: {exc}") from exc
        selectors = document.get("selectors") or []
        records[path.name] = {
            "document": document,
            "sha256": hashlib.sha256(raw).hexdigest(),
            "aliases": {s.get("alias") for s in selectors},
            "capabilityIds": set(document.get("capabilityIds") or ()),
        }
    return records


def load_packs(root: Path) -> dict[str, set[str]]:
    """Returns pack filename -> entry names for alias validation."""
    packs: dict[str, set[str]] = {}
    base = root / PACK_DIR
    for path in sorted(base.glob("*.json")):
        try:
            document = json.loads(_read(path))
        except json.JSONDecodeError as exc:
            raise GenerationError(f"{path.name}: invalid JSON: {exc}") from exc
        packs[path.name] = {
            entry.get("name") for entry in document.get("entries") or ()
        }
    return packs


def load_families(root: Path) -> list[dict]:
    families = []
    base = root / FAMILY_DIR
    if not base.is_dir():
        raise GenerationError(f"missing family authoring directory {FAMILY_DIR}")
    for path in sorted(base.glob("*.json")):
        try:
            family = json.loads(_read(path))
        except json.JSONDecodeError as exc:
            raise GenerationError(f"{path.name}: invalid JSON: {exc}") from exc
        if family.get("format") != FAMILY_FORMAT:
            raise GenerationError(
                f"{path.name}: format must be {FAMILY_FORMAT!r}"
            )
        for key in ("family", "files"):
            if key not in family:
                raise GenerationError(f"{path.name}: missing key {key!r}")
        family.setdefault("records", [])
        family.setdefault("packs", [])
        family.setdefault("extraLiterals", [])
        family.setdefault("coverageSources", [])
        family.setdefault("coverageSourceGlobs", [])
        family.setdefault("coverage", False)
        family["path"] = path.name
        families.append(family)
    return families


def record_field(record: dict, field: str, name: str) -> str:
    document = record["document"]
    if field == "sha256":
        return record["sha256"]
    if field in ("verificationId", "cubismVersion", "profileId", "adapterSliceId"):
        value = document.get(field)
        if not isinstance(value, str):
            raise GenerationError(f"{name}: record field {field!r} is not a string")
        return value
    if field.startswith("artifact."):
        artifact = document.get("artifact") or {}
        value = artifact.get(field.split(".", 1)[1])
        if value is None:
            raise GenerationError(f"{name}: record artifact lacks {field!r}")
        return str(value)
    raise GenerationError(f"{name}: unsupported record field {field!r}")


def render_template(template: str, family: dict, records: dict[str, dict]) -> str:
    """Substitutes every ``${record:...}`` placeholder; fails on unknown input."""
    bound = set(family["records"])

    def replace(match: re.Match) -> str:
        name, field, _modifier = match.group(1), match.group(2), match.group(3)
        if name not in bound:
            raise GenerationError(
                f"{family['path']}: placeholder binds {name} which is not a "
                "declared family record"
            )
        if name not in records:
            raise GenerationError(
                f"{family['path']}: placeholder references missing record {name}"
            )
        return record_field(records[name], field, name)

    return PLACEHOLDER.sub(replace, template)


def literal_spans(masked: str):
    """Yields (start, end, raw_content) for each string literal outside comments."""
    for match in STRING_LITERAL.finditer(masked):
        yield match.start(1), match.end(1), match.group(1)


def evidence_literals(masked: str) -> set[str]:
    """Collects literals in evidence positions: collection-expression arguments
    and constants whose names claim record evidence (aliases, capability ids,
    verification ids, digests, versions)."""
    literals: set[str] = set()
    for match in EVIDENCE_SET.finditer(masked):
        depth = 0
        index = match.end() - 1
        while index < len(masked):
            char = masked[index]
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    break
            index += 1
        for _s, _e, content in literal_spans(masked[match.end():index]):
            literals.add(content)
    for match in EVIDENCE_CONSTANT.finditer(masked):
        if EVIDENCE_NAME.match(match.group(1)):
            literals.add(match.group(2))
    return literals


def family_universe(family: dict, records: dict, packs: dict) -> set[str]:
    """All literals a bound family may legitimately state as record evidence."""
    universe: set[str] = set()
    for name in family["records"]:
        record = records[name]
        universe |= record["aliases"]
        universe |= record["capabilityIds"]
        for field in SCALAR_FIELDS:
            universe.add(record_field(record, field, name))
    for name in family["packs"]:
        universe |= packs[name]
    universe |= set(family["extraLiterals"])
    return universe


def validate_family(
    root: Path, family: dict, records: dict, packs: dict, rendered: dict[str, str]
) -> list[str]:
    violations = []
    for name in family["records"]:
        if name not in records:
            violations.append(f"{family['path']}: binds missing record {name}")
        elif not RECORD_FILE_NAME.match(name):
            violations.append(f"{family['path']}: {name} is not a record filename")
    for name in family["packs"]:
        if name not in packs:
            violations.append(f"{family['path']}: binds missing pack {name}")
    if violations:
        return violations
    universe = family_universe(family, records, packs)
    for relpath in family["files"]:
        text = rendered.get(relpath)
        if text is None:
            continue
        unbound = sorted(evidence_literals(mask_comments(text)) - universe)
        if unbound:
            violations.append(
                f"{relpath}: {len(unbound)} literal(s) claim evidence not in "
                f"the {family['family']} universe, e.g. {unbound[:3]}"
            )
    if family["coverage"]:
        covered: set[str] = set()
        for relpath in family["files"]:
            if relpath not in rendered:
                continue
            for _s, _e, content in literal_spans(mask_comments(rendered[relpath])):
                covered.add(content)
        for extra in family["coverageSources"]:
            for _s, _e, content in literal_spans(mask_comments(_read(root / extra))):
                covered.add(content)
        for pattern in family["coverageSourceGlobs"]:
            matched = sorted(root.glob(pattern))
            if not matched:
                violations.append(
                    f"{family['path']}: coverageSourceGlobs {pattern!r} "
                    "matched no files"
                )
            for extra in matched:
                if extra.suffix != ".java" or not extra.is_file():
                    continue
                for _s, _e, content in literal_spans(
                    mask_comments(_read(extra))
                ):
                    covered.add(content)
        for name in family["records"]:
            missing = records[name]["aliases"] - covered
            if missing:
                violations.append(
                    f"{family['path']}: record {name} has {len(missing)} "
                    f"selector alias(es) absent from generated sources, "
                    f"e.g. {sorted(missing)[:3]}"
                )
    return violations


def target_files(families: list[dict]) -> dict[str, dict]:
    """Maps package-relative target path -> owning family; rejects duplicates."""
    targets: dict[str, dict] = {}
    for family in families:
        for relpath in family["files"]:
            if not GENERATED_TARGETS.match(relpath):
                raise GenerationError(
                    f"{family['path']}: {relpath} is not a generated target; "
                    "only *VerificationManifest.java and selector/*.java may be "
                    "generated under dev.turboism.mapping.verification"
                )
            if relpath in targets:
                raise GenerationError(
                    f"{relpath}: claimed by both {targets[relpath]['path']} "
                    f"and {family['path']}"
                )
            targets[relpath] = family
    return targets


def render_all(root: Path) -> dict[str, str]:
    """Renders and validates every declared target; returns relpath -> text."""
    records = load_records(root)
    packs = load_packs(root)
    families = load_families(root)
    targets = target_files(families)
    rendered: dict[str, str] = {}
    violations: list[str] = []
    for relpath, family in sorted(targets.items()):
        template_path = root / TEMPLATE_ROOT / relpath
        if not template_path.is_file():
            violations.append(f"{relpath}: no authoring template")
            continue
        rendered[relpath] = render_template(
            _read(template_path), family, records
        )
    for family in families:
        owned = {k: v for k, v in rendered.items() if k in family["files"]}
        violations.extend(validate_family(root, family, records, packs, owned))
    if violations:
        raise GenerationError("; ".join(violations))
    return rendered


def checked_in_targets(root: Path) -> set[str]:
    base = root / SOURCE_ROOT / VERIFICATION_PACKAGE
    if not base.is_dir():
        return set()
    found = set()
    for path in sorted(base.rglob("*.java")):
        relative = path.relative_to(root / SOURCE_ROOT).as_posix()
        if GENERATED_TARGETS.match(relative):
            found.add(relative)
    return found


def check(root: Path) -> tuple[list[str], int]:
    """Returns (violations, equivalent_checked_in_count)."""
    violations: list[str] = []
    try:
        rendered = render_all(root)
    except GenerationError as exc:
        return [f"render: {exc}"], 0
    on_disk = checked_in_targets(root)
    declared = set(rendered)
    if not declared:
        return ["no generated targets declared; the gate would be vacuous"], 0
    present = declared & on_disk
    absent = declared - on_disk
    extra = on_disk - declared
    if present and absent:
        violations.append(
            "mixed migration state: only "
            f"{len(present)}/{len(declared)} generated targets remain checked in"
        )
    equivalent = 0
    for relpath in sorted(present):
        source = _read(root / SOURCE_ROOT / relpath)
        if source != rendered[relpath]:
            violations.append(
                f"{relpath}: rendered output differs from the checked-in "
                "source; regenerate the authoring template or revert the "
                "record/template change"
            )
        else:
            equivalent += 1
    for relpath in sorted(extra):
        violations.append(
            f"{relpath}: checked-in source has no authoring family; either "
            "bind it to a family or remove it"
        )
    if not violations and present and equivalent != len(present):
        violations.append("equivalence accounting mismatch")
    return violations, equivalent


def extract(root: Path) -> int:
    """Rewrites templates from checked-in sources (migration/maintenance)."""
    records = load_records(root)
    families = load_families(root)
    targets = target_files(families)
    written = 0
    for relpath, family in sorted(targets.items()):
        source_path = root / SOURCE_ROOT / relpath
        if not source_path.is_file():
            print(f"SKIP {relpath}: no checked-in source")
            continue
        text = _read(source_path)
        masked = mask_comments(text)
        # literal value -> placeholder, first bound record wins per value
        value_map: dict[str, str] = {}
        for name in sorted(family["records"]):
            record = records[name]
            for field in SCALAR_FIELDS:
                value = record_field(record, field, name)
                value_map.setdefault(value, "${record:%s:%s}" % (name, field))
        edits = []
        for start, end, content in literal_spans(masked):
            if content in value_map:
                edits.append((start, end, value_map[content]))
        template = text
        for start, end, replacement in sorted(edits, reverse=True):
            template = template[:start] + replacement + template[end:]
        # sanity: rendering must reproduce the source byte-for-byte
        if render_template(template, family, records) != text:
            raise GenerationError(
                f"{relpath}: extraction did not reproduce the source; refusing "
                "to write a lossy template"
            )
        out = root / TEMPLATE_ROOT / relpath
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(template, encoding="utf-8")
        written += 1
    print(f"extracted {written} template(s)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=str(ROOT))
    sub = parser.add_subparsers(dest="command")
    render_parser = sub.add_parser("render", help="write generated sources")
    render_parser.add_argument("--out", required=True, help="output root")
    sub.add_parser("check", help="verify equivalence with checked-in sources")
    sub.add_parser("extract", help="rewrite templates from checked-in sources")
    args = parser.parse_args()
    root = Path(args.root).resolve()

    if args.command == "render":
        try:
            rendered = render_all(root)
        except GenerationError as exc:
            print(f"FAIL: {exc}", file=sys.stderr)
            return 2
        base = Path(args.out)
        package_dir = base / VERIFICATION_PACKAGE
        if package_dir.is_dir():
            for stale in package_dir.rglob("*.java"):
                stale.unlink()
        for relpath, text in sorted(rendered.items()):
            out = base / relpath
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(text, encoding="utf-8")
        print(f"rendered {len(rendered)} generated verification sources")
        return 0

    if args.command == "extract":
        try:
            return extract(root)
        except GenerationError as exc:
            print(f"FAIL: {exc}", file=sys.stderr)
            return 2

    violations, equivalent = check(root)
    for violation in violations:
        print(f"FAIL: {violation}")
    if violations:
        print(f"\n{len(violations)} generated-source finding(s)")
        return 1
    suffix = (
        f"; byte-level equivalence holds for all {equivalent} checked-in "
        "targets" if equivalent else ""
    )
    print("PASS: generated verification sources match records and authoring "
          f"files{suffix}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
