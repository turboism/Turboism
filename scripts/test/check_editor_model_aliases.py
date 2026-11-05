#!/usr/bin/env python3
"""Check resolver-consumed host aliases against the reviewed verification records.

Which vocabulary a ``cubism.*`` string belongs to is decided by the call site, never by the
string alone. This checker owns the alias vocabulary: every string literal that sits in a
reviewed alias namespace, and every literal a ``VerifiedMemberResolver`` alias accessor receives,
has to be declared by an admitted record of a family that declares a ``vocabulary`` block in
``scripts/verification-sources/families/*.json``, or be one of the deliberately unrecorded
aliases pinned by alias, file and reason in ``scripts/verification-sources/no-record-aliases.json``.

Two directions are enforced:

* existence — an invoked alias with no reviewed record and no pin fails closed, so a typo in a
  member segment cannot reach a user's host;
* admission — a family that declares ``admissionEqualsUse`` (editor-model) keeps its exact-record
  admission policy: the aliases its records admit may not exceed the aliases production invokes,
  minus the pinned remainder, so reviewed admission cannot widen silently.

The no-record pins are a non-increasing ratchet: a new unrecorded alias fails, and so does a pin
whose alias vanished from production, moved to a different file, or became recorded (the pin then
has to be deleted).

Usage: check_editor_model_aliases.py [repo-root] [--report]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

_SCRIPTS_DIR = str(Path(__file__).resolve().parents[1])
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)
from turboism_dev.json_io import read_json

IMPLEMENTATION_ROOT = "runtime/src/main/java"
FAMILY_ROOT = "scripts/verification-sources/families"
FAMILY_FORMAT = "turboism.verification-source-family/v1"
NO_RECORD_PATH = "scripts/verification-sources/no-record-aliases.json"
NO_RECORD_FORMAT = "turboism.no-record-aliases/v1"
RECORD_ROOT = "build/generated/verification"

# Editor-model is the one family whose records are inventories rather than exact-use evidence, so
# it keeps the admission policy this check has always enforced: the 5.2.03/5.3.02 records are the
# exact consumed admission and only these four invoked 5.3.03 aliases extend it. Family data
# declares that the family still owes this check; the pinned mechanics stay here because the
# subset is a reviewed decision, not derivable data.
ADMISSION_FAMILY = "editor-model"
ADMISSION_PREFIX = "cubism.editor-model"
BASE_RECORDS = (
    "build/generated/verification/cubism-5.2.03-editor-model.json",
    "build/generated/verification/cubism-5.3.02-editor-model.json",
)
ADDITIVE_RECORD = "build/generated/verification/cubism-5.3.03-editor-model.json"
ADDITIVE_ALIASES = frozenset({
    "cubism.editor-model.keyform-grid.keyforms-on-grid",
    "cubism.editor-model.keyform-on-grid.form-guid",
    "cubism.editor-model.undo.local-simple-factory-create",
    "cubism.editor-model.undo.local-simple-factory-instance",
})
ALIAS_LITERAL = re.compile(r'"([^"]*)"')
ALIAS_CONSTANT_DEFINITION = re.compile(
    r'public\s+static\s+final\s+String\s+([A-Z][A-Z0-9_]*_ALIAS)\s*=\s*"([^"]*)"')
ALIAS_CONSTANT_REFERENCE = re.compile(r'\.\s*([A-Z][A-Z0-9_]*_ALIAS)\b')
VERIFICATION_PATH = "/mapping/verification/"
# OptimizationCatalog declares bootstrap HookContributor policyIds (hookIds),
# not VerifiedMemberResolver member aliases.
OPTIMIZATION_CATALOG_PATH = "/config/optimization/"
PRESET_ALIAS_PREFIX = "cubism.editor-model.label-color-type."
PRESET_COLORS = ("red", "orange", "yellow", "green", "blue", "purple", "gray")
# Reviewed 5.2.03 selectors verified statically against the exact artifact but not yet
# invoked by production. They stay admitted because runtime admission pins the record
# roster exactly; any further unconsumed alias widens admission silently and must fail.
REVIEWED_UNCONSUMED_ALIASES = frozenset({
    "cubism.editor-model.morph-target-set.remove",
    "cubism.editor-model.morph-target.set-parameter",
    "cubism.editor-model.morph-target.set-parameter-and-key-value",
    "cubism.editor-model.parameter-controllable-source.internal-set-parent",
    "cubism.editor-model.parameter-group.guid",
    "cubism.editor-model.part-source.remove-child",
    "cubism.editor-model.part.id",
    # Parameter-lock row selectors (single/double-row sliders and paint): reviewed
    # into the records for the parameter-lock plugin's draft packs; the runtime
    # adapter does not invoke them yet, so they stay pinned rather than admitted.
    "cubism.editor-model.parameter-single-row.slider",
    "cubism.editor-model.parameter-single-row.paint",
    "cubism.editor-model.parameter-double-row.slider",
    "cubism.editor-model.parameter-double-row.paint",
    "cubism.editor-model.parameter-double-row.secondary-slider",
})
# The resolver accessors whose arguments are selector aliases. Only the names that cannot be
# confused with an unrelated ``invoke``/``bind`` helper pull in literals outside the reviewed
# namespaces, so a mistyped namespace segment still fails closed.
RESOLVER_ALIAS_POSITION = re.compile(
    r'\.\s*(?:verifiedSelector|bindStatic|invokeStatic|readStaticField|createFunctionalProxy'
    r'|isExactInstance|isInstance)\s*\(\s*"([^"]*)"')


@dataclass(frozen=True)
class FamilyVocabulary:
    """One family's declaration that the alias vocabulary check covers it."""

    name: str
    records: tuple[str, ...]
    admission_equals_use: bool


@dataclass(frozen=True)
class PinnedAlias:
    """An alias the reviewed records deliberately do not carry, pinned by name and file."""

    alias: str
    file: str
    reason: str


@dataclass(frozen=True)
class LocalLiteral:
    """A non-alias ``cubism.*`` string that lives in a reviewed alias namespace."""

    literal: str
    file: str
    reason: str


@dataclass(frozen=True)
class NoRecordVocabulary:
    """The pinned no-record surface: extra namespaces, aliases and local vocabulary."""

    namespaces: tuple[str, ...]
    aliases: tuple[PinnedAlias, ...]
    local_vocabulary: tuple[LocalLiteral, ...]


@dataclass(frozen=True)
class Vocabulary:
    """The measured vocabulary of one repository tree; ``failures`` stays separate."""

    families: dict[str, FamilyVocabulary]
    per_record: dict[str, set[str]]
    namespaces: tuple[str, ...]
    declared: int
    used: int
    pins: int
    local_vocabulary: int
    unrecorded: tuple[str, ...]
    unused: tuple[str, ...]


def family_vocabulary(root: Path) -> dict[str, FamilyVocabulary]:
    """Return the families that declare a ``vocabulary`` block, keyed by family name.

    Family data, not a name list here, decides which records back the alias vocabulary:
    ``compatibility-catalog`` aggregates every other family's records and ``validation-lanes``
    has none, so neither declares a block. A declared family has to name readable records,
    otherwise the alias side of the comparison would silently shrink.
    """
    base = root / FAMILY_ROOT
    if not base.is_dir():
        raise ValueError(f"missing family authoring directory {FAMILY_ROOT}")
    families: dict[str, FamilyVocabulary] = {}
    for path in sorted(base.glob("*.json")):
        document = read_json(path)
        if document.get("format") != FAMILY_FORMAT:
            raise ValueError(f"{path.name}: format must be {FAMILY_FORMAT!r}")
        vocabulary = document.get("vocabulary")
        if vocabulary is None:
            continue
        name = document.get("family")
        if not isinstance(name, str) or not name:
            raise ValueError(f"{path.name}: a vocabulary family needs a family name")
        records = tuple(document.get("records") or ())
        if not records:
            raise ValueError(f"{path.name}: a vocabulary family must bind records")
        for record in records:
            if not (root / RECORD_ROOT / record).is_file():
                raise ValueError(f"{path.name}: binds missing record {record}")
        families[name] = FamilyVocabulary(
            name=name,
            records=records,
            admission_equals_use=bool(vocabulary.get("admissionEqualsUse")),
        )
    if not families:
        raise ValueError(f"{FAMILY_ROOT}: no family declares a vocabulary block")
    strict = sorted(name for name, family in families.items() if family.admission_equals_use)
    if strict and strict != [ADMISSION_FAMILY]:
        raise ValueError(
            "admissionEqualsUse is only implemented for "
            f"{ADMISSION_FAMILY}, declared by: {', '.join(strict)}")
    if ADMISSION_FAMILY not in families or not families[ADMISSION_FAMILY].admission_equals_use:
        raise ValueError(f"{ADMISSION_FAMILY} must declare vocabulary.admissionEqualsUse")
    return families


def no_record_vocabulary(root: Path) -> NoRecordVocabulary:
    """Return the pinned no-record aliases and the local vocabulary beside them.

    Every entry is explicit: an alias without a reviewed record is a capability on a user's host
    that has no provenance, so it may only exist as a named pin with a stated reason, never as an
    unnamed allowlist pattern. An empty pin list is the intended end state once a Lane C task has
    reviewed the remaining aliases into records.
    """
    path = root / NO_RECORD_PATH
    if not path.is_file():
        raise ValueError(f"missing {NO_RECORD_PATH}")
    document = read_json(path)
    if document.get("format") != NO_RECORD_FORMAT:
        raise ValueError(f"{NO_RECORD_PATH}: format must be {NO_RECORD_FORMAT!r}")
    namespaces = tuple(document.get("namespaces") or ())
    aliases: list[PinnedAlias] = []
    local: list[LocalLiteral] = []
    for entry in document.get("aliases") or ():
        aliases.append(_pinned(entry, PinnedAlias, "alias"))
    for entry in document.get("localVocabulary") or ():
        local.append(_pinned(entry, LocalLiteral, "literal"))
    return NoRecordVocabulary(namespaces=namespaces, aliases=tuple(aliases), local_vocabulary=tuple(local))


def _pinned(entry: dict, kind: type[PinnedAlias] | type[LocalLiteral], field: str) -> PinnedAlias | LocalLiteral:
    """Return one validated pin entry; unnamed or unreascribed entries fail closed."""
    for key in (field, "file", "reason"):
        value = entry.get(key) if isinstance(entry, dict) else None
        if not isinstance(value, str) or not value:
            raise ValueError(f"{NO_RECORD_PATH}: entry {entry!r} needs a non-empty {key!r}")
    return kind(entry[field], entry["file"], entry["reason"])


def selector_aliases(root: Path, relative: str) -> set[str]:
    """Return every selector alias one record declares, whatever its kind.

    Class and type-relation selectors are host members too, so existence evidence comes from
    every selector: a class alias invoked without a record is exactly the unreviewed capability
    this check exists to stop.
    """
    path = root / relative
    if not path.exists():
        return set()
    return {selector["alias"] for selector in read_json(path)["selectors"]}


def aliases_in_record(root: Path, relative: str) -> set[str]:
    """Return non-class, non-relation Editor-model aliases in one reviewed record.

    ``inherits`` selectors are structural type-relation constraints consumed
    through the generated compatibility catalog, not resolver-invoked member
    aliases, so they are outside this checker's invoke/admit comparison.
    """
    path = root / relative
    if not path.exists():
        return set()
    data = read_json(path)
    return {
        selector["alias"]
        for selector in data["selectors"]
        if selector["alias"].startswith(ADMISSION_PREFIX)
        and selector["kind"] not in ("class", "inherits")
    }


def admitted_aliases(root: Path) -> set[str]:
    """Return every alias the exact admitted Editor-model records declare.

    The admitted roster is the pinned 5.2.03/5.3.02 pair plus the fixed 5.3.03 additive subset.
    Every namespace those records cover counts -- editor-model inventories the editor-history,
    texture-atlas, integration and object-context-menu selectors too -- while the wider 5.3.03
    static inventory still needs the additive pin, so this cannot widen silently.
    """
    aliases = set(ADDITIVE_ALIASES)
    for relative in BASE_RECORDS:
        aliases |= selector_aliases(root, relative)
    return aliases


def capability_ids(root: Path, families: dict[str, FamilyVocabulary]) -> set[str]:
    """Return the declared capabilityId union across every admitted record."""
    ids: set[str] = set()
    for family in families.values():
        for relative in family.records:
            path = root / RECORD_ROOT / relative
            if not path.exists():
                continue
            for identifier in read_json(path).get("capabilityIds", []):
                ids.add(str(identifier))
    return ids


def declared_aliases(root: Path, families: dict[str, FamilyVocabulary]) -> set[str]:
    """Return the alias union of every family's admitted records.

    Families without ``admissionEqualsUse`` contribute every selector their records carry: the
    union answers "does a reviewer know this name", which a typo fails on every version. The
    strict family contributes only what its admission policy admits.
    """
    aliases: set[str] = set()
    for family in families.values():
        if family.admission_equals_use:
            aliases |= admitted_aliases(root)
            continue
        for relative in family.records:
            aliases |= selector_aliases(root, f"{RECORD_ROOT}/{relative}")
    if not aliases:
        raise ValueError("the declared families contributed no alias")
    return aliases


def alias_namespaces(aliases: set[str]) -> set[str]:
    """Return the ``owner.member-family.`` namespaces the review evidence covers.

    A namespace is the first two dotted segments, which is where every reviewed alias family
    lives (``cubism.editor-model.``, ``cubism.ui-status-bar.``, ``workspace.dock.``). The segment
    before the member is the only namespace boundary records can supply, so an alias with a
    single dot (``object-context-menu.part.``) names itself as the namespace instead of widening
    to the whole ``object-context-menu.`` tree. An alias without any namespace is outside every
    shape this check understands and fails closed rather than being silently skipped.
    """
    namespaces: set[str] = set()
    for alias in aliases:
        segments = alias.split(".")
        if len(segments) < 2:
            raise ValueError(f"reviewed alias {alias!r} has no owner/member namespace")
        namespaces.add(".".join(segments[:2]) + ".")
    return namespaces


def is_alias_literal(value: str, namespaces: set[str]) -> bool:
    """Return whether a literal belongs to a reviewed alias namespace.

    Prefix templates (``"cubism.editor-model." + suffix``) are concatenations that hide their
    suffix from every scan; they stay out until that concatenation is deleted. A literal the
    resolver receives in alias position is an alias whatever its namespace says.
    """
    if not value or value.endswith("."):
        return False
    return any(value.startswith(namespace) for namespace in namespaces)


def implementation_aliases(root: Path, namespaces: set[str] | None = None) -> dict[str, set[str]]:
    """Return production aliases mapped to the sources that mention them.

    Contracts, manifests and compatibility catalogs declare evidence instead of invoking a host
    member, so their literals never count as implementation use. ``namespaces`` of ``None``
    accepts every literal, which is what the unit tests use to pin the constant-following rules.
    """
    aliases: dict[str, set[str]] = {}
    constants: dict[str, str] = {}
    production_sources: list[tuple[Path, str]] = []
    base = root / IMPLEMENTATION_ROOT
    if not base.exists():
        return aliases
    for source in sorted(base.rglob("*.java")):
        text = source.read_text(encoding="utf-8")
        if VERIFICATION_PATH in source.as_posix():
            for name, value in ALIAS_CONSTANT_DEFINITION.findall(text):
                constants[name] = value
            continue
        if OPTIMIZATION_CATALOG_PATH in source.as_posix():
            continue
        production_sources.append((source, text))

    def admit(alias: str, source: Path, *, alias_position: bool = False) -> None:
        if not alias or alias.endswith("."):
            return
        if namespaces is not None and not alias_position:
            if not is_alias_literal(alias, namespaces):
                return
        aliases.setdefault(alias, set()).add(source.relative_to(root).as_posix())

    for source, text in production_sources:
        for alias in ALIAS_LITERAL.findall(text):
            admit(alias, source)
        # A literal in alias position is an alias whatever its namespace says, which is what
        # catches a mistyped namespace segment.
        for alias in RESOLVER_ALIAS_POSITION.findall(text):
            admit(alias, source, alias_position=True)
        for name in ALIAS_CONSTANT_REFERENCE.findall(text):
            value = constants.get(name)
            if value is not None:
                admit(value, source)
        if "presetAlias(" in text and PRESET_ALIAS_PREFIX in text:
            for color in PRESET_COLORS:
                admit(PRESET_ALIAS_PREFIX + color, source)
    return aliases


def record_aliases(root: Path) -> tuple[set[str], dict[str, set[str]]]:
    """Return exact consumed admission from the public reviewed records.

    The 5.3.03 record is a full static host inventory and intentionally contains selectors for
    capabilities this runtime does not consume. Only its four additive, invoked aliases extend the
    admission held by the 5.2.03/5.3.02 records. The subset is fixed here so adding an
    arbitrary selector to any record cannot silently widen runtime admission. The older
    records are also verified inventories; selectors they carry beyond production use are pinned
    by REVIEWED_UNCONSUMED_ALIASES so the admitted remainder cannot grow silently either.

    Static propagation later verified the four additive aliases on the exact 5.2.03 and
    5.3.02 artifacts, so they may now also appear in the base records; the consumed-admission
    invariant this checker enforces (admission equals actual use) is unaffected either way.
    """
    union: set[str] = set()
    per_record: dict[str, set[str]] = {}
    for relative in BASE_RECORDS:
        aliases = aliases_in_record(root, relative)
        if aliases:
            per_record[Path(relative).name] = aliases
            union |= aliases

    additive_record = aliases_in_record(root, ADDITIVE_RECORD)
    missing = ADDITIVE_ALIASES - additive_record
    if missing:
        raise ValueError(
            "5.3.03 reviewed record lacks required additive aliases: "
            + ", ".join(sorted(missing))
        )
    per_record[Path(ADDITIVE_RECORD).name + " (admitted subset)"] = set(ADDITIVE_ALIASES)
    union |= ADDITIVE_ALIASES
    return union, per_record


def check(root: Path) -> tuple[list[str], Vocabulary]:
    """Return the failure messages and the measured vocabulary of one repository tree."""
    families = family_vocabulary(root)
    declared = declared_aliases(root, families)
    no_record = no_record_vocabulary(root)
    namespaces = alias_namespaces(declared) | set(no_record.namespaces)
    implementation = implementation_aliases(root, namespaces)
    used = set(implementation)
    pins = {pin.alias: pin for pin in no_record.aliases}
    local = {entry.literal: entry for entry in no_record.local_vocabulary}
    if not used:
        raise ValueError("could not read any production alias")

    # A literal that is both pinned and declared local vocabulary hides which claim it makes.
    overlap = sorted(set(pins) & set(local))
    if overlap:
        raise ValueError(
            f"{NO_RECORD_PATH}: entries are both pinned and local vocabulary: "
            + ", ".join(overlap))

    capabilities = capability_ids(root, families)
    unrecorded = sorted(
        alias for alias in used
        if alias not in declared and alias not in capabilities
        and alias not in pins and alias not in local)
    recorded_pins = sorted(set(pins) & declared)
    stale_pins = sorted(set(pins) - used)
    drifted_pins = sorted(
        alias for alias in set(pins) & used if pins[alias].file not in implementation[alias])
    stale_local = sorted(set(local) - used)
    drifted_local = sorted(
        literal for literal in set(local) & used if local[literal].file not in implementation[literal])

    union, per_record = record_aliases(root)
    admitted_implementation = {
        alias for alias in used if alias.startswith(ADMISSION_PREFIX + ".")
    }
    unused = sorted((union - admitted_implementation) - REVIEWED_UNCONSUMED_ALIASES)
    unconsumed_drift = sorted(REVIEWED_UNCONSUMED_ALIASES - union)

    failures: list[str] = []
    if unrecorded:
        failures.append(
            f"FAIL: {len(unrecorded)} invoked host aliases lack reviewed evidence "
            f"and are not pinned in {NO_RECORD_PATH}:")
        failures.extend(f"  {alias}" for alias in unrecorded)
    if recorded_pins:
        failures.append(
            f"FAIL: {len(recorded_pins)} pinned no-record aliases are now declared by a "
            "reviewed record; delete their pins:")
        failures.extend(f"  {alias}" for alias in recorded_pins)
    if stale_pins:
        failures.append(
            f"FAIL: {len(stale_pins)} pinned no-record aliases are no longer used; "
            "delete their pins:")
        failures.extend(f"  {alias}" for alias in stale_pins)
    if drifted_pins:
        failures.append(
            f"FAIL: {len(drifted_pins)} pinned no-record aliases moved away from their pinned "
            "file; update the pin:")
        failures.extend(f"  {alias} ({pins[alias].file})" for alias in drifted_pins)
    if stale_local:
        failures.append(
            f"FAIL: {len(stale_local)} local-vocabulary literals are no longer used; "
            "delete their entries:")
        failures.extend(f"  {literal}" for literal in stale_local)
    if drifted_local:
        failures.append(
            f"FAIL: {len(drifted_local)} local-vocabulary literals moved away from their "
            "declared file; update the entry:")
        failures.extend(f"  {literal} ({local[literal].file})" for literal in drifted_local)
    if unused:
        failures.append(
            f"FAIL: {len(unused)} reviewed {ADMISSION_FAMILY} aliases are unused and not pinned:")
        failures.extend(f"  {alias}" for alias in unused)
    if unconsumed_drift:
        failures.append(f"FAIL: pinned reviewed-unconsumed {ADMISSION_FAMILY} aliases vanished from the records:")
        failures.extend(f"  {alias}" for alias in unconsumed_drift)

    measurement = Vocabulary(
        families=families,
        per_record=per_record,
        namespaces=tuple(sorted(namespaces)),
        declared=len(declared),
        used=len(used),
        pins=len(pins),
        local_vocabulary=len(local),
        unrecorded=tuple(unrecorded),
        unused=tuple(unused),
    )
    return failures, measurement


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=".")
    parser.add_argument("--report", action="store_true", help="print both comparison directions")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    try:
        failures, measurement = check(root)
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as failure:
        print(f"FAIL: invalid host alias vocabulary input: {failure}", file=sys.stderr)
        return 2

    if args.report:
        print(f"vocabulary families    : {len(measurement.families)}")
        for name in sorted(measurement.families):
            print(f"  {name}: {len(measurement.families[name].records)} records")
        print(f"reviewed namespaces    : {len(measurement.namespaces)}")
        print(f"declared aliases       : {measurement.declared}")
        for name, aliases in sorted(measurement.per_record.items()):
            print(f"  {name}: {len(aliases)}")
        print(f"invoked aliases        : {measurement.used}")
        print(f"pinned no-record       : {measurement.pins}")
        print(f"local vocabulary       : {measurement.local_vocabulary}")
        print(f"unrecorded (must be 0) : {len(measurement.unrecorded)}")
        for alias in measurement.unrecorded:
            print(f"    {alias}")
        print(f"unused beyond pinned remainder (must be 0) : {len(measurement.unused)}")
        for alias in measurement.unused:
            print(f"    {alias}")
        return 0

    if failures:
        print("\n".join(failures))
        return 1
    print(
        f"PASS: {measurement.used} invoked host aliases are declared by "
        f"{len(measurement.families)} reviewed families or pinned without a record")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
