"""Cross-version verification record and mapping pack propagation."""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys

from .artifacts import artifact_facts, load_source_records
from .bytecode import JarIndex, verify_selector
from .derive import (
    derive_pack,
    derive_record,
    load_family_packs,
    load_source_pack,
    pack_name_for,
)
from .constants import (
    ALL_VERSIONS,
    EDITOR_MODEL_SLICE,
    GENERATE,
    MAPPING_ID_VERSION,
    MEMBER_VARIANTS,
    ROOT,
    SELECTOR_IDENTITY,
    get_pack_dir,
    get_ref_dir,
    get_verification_dir,
)
from .wire import vcompact, vtag

PROPAGATE_REPORT_DIR = ROOT / "build/verification-propagation"

def selector_identity(selector: dict) -> tuple:
    """The structural part of a selector — everything the verifier reads.
    ``mappingId`` is audit metadata, so a same-shape selector under a
    different version tag is the same verified result, never a conflict."""
    return tuple(selector.get(k) for k in SELECTOR_IDENTITY)


def retag_mapping_id(mapping_id: str, from_version: str, to_version: str) -> str:
    """Re-version a ``cubism.mapping.v<tag>.`` id when its tag spells
    ``from_version`` (underscores ignored, so v5_3_02 and v5303 both match);
    ids carrying another generation's tag are provenance and stay put."""
    match = MAPPING_ID_VERSION.match(mapping_id)
    if not match or match.group(2).replace("_", "") != vcompact(from_version):
        return mapping_id
    return match.group(1) + "v" + vtag(to_version) + "." + mapping_id[match.end() :]


def load_symbol_resolver(module_path: Path | None = None):
    """Optional hook owned by the parallel symbol-mapping effort.

    ``scripts/dev/cubism_symbol_map.py`` may provide
    ``resolve(selector, from_version, to_version) -> candidate | None`` where
    the candidate is a selector dict (full or field overrides). A missing or
    unloadable module, or a missing ``resolve``, means "no hook"."""
    path = module_path or (ROOT / "scripts/dev/cubism_symbol_map.py")
    if not path.is_file():
        return None
    dev_dir = str(path.parent)
    if dev_dir not in sys.path:
        sys.path.insert(0, dev_dir)
    # Name the spec after the file stem, not a fixed "cubism_symbol_map":
    # test stubs loaded through this hook would otherwise overwrite the real
    # module's sys.modules entry — which exec_module needs for dataclasses on
    # Python 3.12+, and which pickle consults for its pair-map caches — and
    # silently break any later resolve() in the same process.
    spec = importlib.util.spec_from_file_location(path.stem, path)
    existing = sys.modules.get(spec.name)
    if existing is not None and getattr(existing, "__file__", None) == str(path):
        # Already loaded from the same file: reuse it. A second exec would
        # create a distinct module object under the same name, and pickled
        # models/pair-maps check class identity through sys.modules.
        module = existing
    else:
        try:
            module = importlib.util.module_from_spec(spec)
            sys.modules[spec.name] = module
            spec.loader.exec_module(module)
        except (Exception, SystemExit):
            sys.modules.pop(spec.name, None)
            return None
    resolve = getattr(module, "resolve", None)
    return resolve if callable(resolve) else None


def verify_propagated(
    jar: JarIndex, selector: dict, from_version: str, to_version: str, resolver
):
    """Returns (selector_to_use, status, via): 'direct' when the reference
    selector verifies as-is, 'variant' when a reviewed MEMBER_VARIANTS
    override does, 'symbol-map' when a resolver candidate does, or
    'unresolved' with the original verifier status."""
    status = verify_selector(jar, selector)
    if status == "VERIFIED_STATIC":
        return selector, status, "direct"
    reviewed = MEMBER_VARIANTS.get((selector["alias"], to_version))
    if reviewed is not None:
        variant = dict(selector)
        variant.update(reviewed)
        variant["alias"] = selector["alias"]
        if verify_selector(jar, variant) == "VERIFIED_STATIC":
            return variant, "VERIFIED_STATIC", "variant"
    if resolver is None:
        return selector, status, "unresolved"
    try:
        candidate = resolver(dict(selector), from_version, to_version)
    except Exception:
        candidate = None
    if not isinstance(candidate, dict):
        return selector, status, "unresolved"
    variant = dict(selector)
    variant.update(candidate)
    variant["alias"] = selector["alias"]
    if verify_selector(jar, variant) == "VERIFIED_STATIC":
        return variant, "VERIFIED_STATIC", "symbol-map"
    return selector, status, "unresolved"


def plan_version_propagation(
    jar: JarIndex, source: dict, from_version: str, to_version: str, resolver
):
    """Verify every selector of ``source`` on the target jar.

    Returns (resolved_selectors, failures); failures carry the version x
    alias x reason detail for the pending list."""
    resolved, failures = [], []
    for selector in source["selectors"]:
        used, status, via = verify_propagated(
            jar, selector, from_version, to_version, resolver
        )
        if status == "VERIFIED_STATIC":
            resolved.append(used)
            continue
        failures.append(
            {
                "alias": selector["alias"],
                "mappingId": selector["mappingId"],
                "kind": selector["kind"],
                "ownerInternalName": selector["ownerInternalName"],
                "memberName": selector.get("memberName"),
                "descriptor": selector.get("descriptor"),
                "status": status,
                "via": via,
            }
        )
    return resolved, failures


def propagated_record(
    source: dict,
    resolved: list[dict],
    to_version: str,
    family: str,
    ref_dir: Path | None = None,
) -> dict:
    """Build the target record for a fully verified propagation.

    Editor-model selector mappingIds keep their reviewed-generation tag
    (provenance); other slices re-tag to the target version, matching the
    convention of the manually propagated ui-main-toolbar records."""
    retag = source["adapterSliceId"] != EDITOR_MODEL_SLICE
    selectors = [
        (
            {
                **selector,
                "mappingId": retag_mapping_id(
                    selector["mappingId"], source["cubismVersion"], to_version
                ),
            }
            if retag
            else selector
        )
        for selector in resolved
    ]
    effective = dict(source)
    effective["selectors"] = selectors
    # Empty contract table: every capability is unlisted, so derive_record
    # keeps them only when the whole selector set verifies — which is exactly
    # the propagation precondition (full=True).
    record = derive_record(
        effective,
        {s["alias"] for s in selectors},
        True,
        to_version,
        family,
        {},
        {},
        ref_dir=ref_dir,
    )
    # derive_record pins the default editor jar; core slices verified against
    # source["artifact"]["name"], so the record must pin that artifact.
    record["artifact"] = artifact_facts(
        to_version, source["artifact"]["name"], ref_dir=ref_dir
    )
    return record


def merge_record(existing: dict, propagated: dict):
    """Fold a fully verified propagated record into ``existing``.

    Existing selectors keep their verified definition — a different verified
    definition for the same alias is a reported conflict, never overwritten.
    Returns (merged, added_selectors, added_capabilities, conflicts)."""
    by_alias = {s["alias"]: s for s in existing["selectors"]}
    added, conflicts = [], []
    for incoming in propagated["selectors"]:
        previous = by_alias.get(incoming["alias"])
        if previous is None:
            added.append(incoming)
        elif selector_identity(previous) != selector_identity(incoming):
            conflicts.append(
                {
                    "kind": "selector-definition",
                    "alias": incoming["alias"],
                    "existing": {k: previous.get(k) for k in SELECTOR_IDENTITY},
                    "incoming": {k: incoming.get(k) for k in SELECTOR_IDENTITY},
                }
            )
    merged = dict(existing)
    merged["selectors"] = existing["selectors"] + added
    capabilities = list(existing["capabilityIds"])
    conditions = dict(existing["capabilityConditions"])
    added_capabilities = []
    incoming_conditions = propagated.get("capabilityConditions") or {}
    for capability in propagated["capabilityIds"]:
        if capability in capabilities:
            continue
        capabilities.append(capability)
        added_capabilities.append(capability)
        conditions[capability] = incoming_conditions.get(capability) or [
            "declaredGeneration"
        ]
    merged["capabilityIds"] = capabilities
    merged["capabilityConditions"] = conditions
    return merged, added, added_capabilities, conflicts


def propagated_pack(
    record: dict,
    base_pack: dict,
    existing_pack: dict | None,
    version: str,
    family: str,
    candidates: dict[str, dict],
    from_version: str,
) -> dict:
    """Derive the draft pack for a propagated record.

    A merge keeps existing entries byte-identical and appends entries for
    newly admitted aliases; a fresh pack uses the normalized derived envelope
    (format/schemaVersion/status/source/cubismVersion) that previous
    propagation runs established."""
    derived = derive_pack(base_pack, record, version, family, family_packs=candidates)

    def retag_entry_source(entry: dict) -> dict:
        entry = dict(entry)
        source = entry.get("source")
        if isinstance(source, str):
            entry["source"] = source.replace(from_version, version)
        return entry

    if existing_pack is not None:
        names = {entry["name"] for entry in existing_pack["entries"]}
        pack = dict(existing_pack)
        pack["entries"] = existing_pack["entries"] + [
            retag_entry_source(entry)
            for entry in derived["entries"]
            if entry["name"] not in names
        ]
        if "metadata" in derived:
            pack["metadata"] = derived["metadata"]
        return pack
    pack = {
        "format": base_pack.get("format", "turboism.mapping.pack"),
        "schemaVersion": base_pack.get("schemaVersion", 1),
        "status": base_pack.get("status", "DRAFT"),
        "source": base_pack.get("source", "derived-from-verified-record"),
        "cubismVersion": version,
        "entries": [retag_entry_source(entry) for entry in derived["entries"]],
    }
    if "metadata" in derived:
        pack["metadata"] = derived["metadata"]
    return pack


def write_document(path: Path, document: dict) -> bool:
    """Write the canonical ``json.dumps(doc, indent=2) + newline`` rendering;
    returns True when bytes actually changed."""
    rendered = json.dumps(document, indent=2) + "\n"
    if path.is_file() and path.read_text() == rendered:
        return False
    path.write_text(rendered)
    return True


def propagate_family(
    family: str,
    from_version: str,
    targets: list[str],
    write: bool,
    resolver=None,
    ref_dir: Path | None = None,
    verification_dir: Path | None = None,
    pack_dir: Path | None = None,
) -> dict:
    """Propagate one family's ``from_version`` record onto ``targets``.

    Per-target outcomes: self, no-artifact, blocked (pending list),
    no-source-pack, conflict-artifact-drift, unchanged, merged, written.
    Nothing is written unless ``write`` and the whole selector set verifies."""
    ref_base = ref_dir if ref_dir is not None else get_ref_dir()
    verif_base = verification_dir if verification_dir is not None else get_verification_dir()
    pack_base = pack_dir if pack_dir is not None else get_pack_dir()
    sources = (load_source_records(verif_base)).get(family) or []
    source = next((doc for version, doc in sources if version == from_version), None)
    if source is None:
        raise SystemExit(f"no record cubism-{from_version}-{family}.json")
    jar_name = source["artifact"]["name"]
    source_pack = load_source_pack(from_version, family, pack_dir=pack_base)
    candidates = dict(load_family_packs(family, pack_dir=pack_base))
    if source_pack is not None:
        candidates = {from_version: source_pack, **candidates}
    results = []
    writes: list[tuple[Path, dict]] = []
    for version in targets:
        if version == from_version:
            results.append({"version": version, "outcome": "self"})
            continue
        entry = {
            "version": version,
            "selectorsTotal": len(source["selectors"]),
            "selectorsVerified": 0,
            "failures": [],
            "conflicts": [],
        }
        jar_path = ref_base / version / jar_name
        if not jar_path.is_file():
            entry["outcome"] = "no-artifact"
            results.append(entry)
            continue
        jar = JarIndex(jar_path)
        resolved, failures = plan_version_propagation(
            jar, source, from_version, version, resolver
        )
        entry["selectorsVerified"] = len(resolved)
        entry["failures"] = failures
        if failures:
            entry["outcome"] = "blocked"
            results.append(entry)
            continue
        record_path = verif_base / f"cubism-{version}-{family}.json"
        pack_path = pack_base / f"cubism-{version}-{pack_name_for(family)}.json"
        existing = (
            json.loads(record_path.read_text()) if record_path.is_file() else None
        )
        propagated = propagated_record(source, resolved, version, family, ref_dir=ref_base)
        if existing is not None:
            if existing.get("artifact") != artifact_facts(version, jar_name, ref_dir=ref_base):
                entry["conflicts"].append(
                    {
                        "kind": "artifact-drift",
                        "existing": existing.get("artifact"),
                        "measured": artifact_facts(version, jar_name, ref_dir=ref_base),
                    }
                )
                entry["outcome"] = "conflict"
                results.append(entry)
                continue
            for stale in existing["selectors"]:
                status = verify_selector(jar, stale)
                if status != "VERIFIED_STATIC":
                    entry["conflicts"].append(
                        {
                            "kind": "stale-selector",
                            "alias": stale["alias"],
                            "status": status,
                        }
                    )
            record, added, added_caps, conflicts = merge_record(existing, propagated)
            entry["conflicts"].extend(conflicts)
            if not added and not added_caps:
                entry["outcome"] = "unchanged"
                results.append(entry)
                continue
            entry["selectorsAdded"] = [s["alias"] for s in added]
            entry["capabilitiesAdded"] = added_caps
        else:
            record = propagated
            entry["capabilitiesAdded"] = list(propagated["capabilityIds"])
        existing_pack = (
            json.loads(pack_path.read_text()) if pack_path.is_file() else None
        )
        base_pack = existing_pack or source_pack
        if base_pack is None:
            entry["outcome"] = "no-source-pack"
            results.append(entry)
            continue
        pack = propagated_pack(
            record,
            base_pack,
            existing_pack,
            version,
            family,
            candidates,
            from_version,
        )
        writes.append((record_path, record))
        writes.append((pack_path, pack))
        entry["outcome"] = "merged" if existing is not None else "written"
        entry["selectors"] = len(record["selectors"])
        entry["capabilities"] = len(record["capabilityIds"])
        results.append(entry)
    changed = []
    if write:
        for path, document in writes:
            if write_document(path, document):
                changed.append(path)
    return {
        "command": "propagate",
        "family": family,
        "from": from_version,
        "mode": "write" if write else "dry-run",
        "symbolMapHook": resolver is not None,
        "results": results,
        "pending": [
            {"version": r["version"], **failure}
            for r in results
            for failure in r["failures"]
        ],
        "filesWritten": [str(p) for p in changed],
    }


def matrix_cell(
    jar_for, family: str, sources: list[tuple[str, dict]], version: str,
    resolver=None,
) -> dict:
    """One family x version cell of the propagation matrix.

    verified: a committed record exists and every selector verifies.
    extendable: verified, plus source-defined aliases absent from the record
      that verify on this jar.
    propagatable: no record; some source record verifies in full.
    blocked: no record; every source has unresolved failures.
    conflict: a committed record has selectors that no longer verify."""
    record_path = get_verification_dir() / f"cubism-{version}-{family}.json"
    if record_path.is_file():
        record = json.loads(record_path.read_text())
        jar = jar_for(record["artifact"]["name"])
        if jar is None:
            return {"status": "no-artifact"}
        stale = [
            s["alias"]
            for s in record["selectors"]
            if verify_selector(jar, s) != "VERIFIED_STATIC"
        ]
        if stale:
            return {"status": "conflict", "staleAliases": stale}
        committed = {s["alias"] for s in record["selectors"]}
        universe: dict[str, list[dict]] = {}
        for _source_version, source in sources:
            for selector in source["selectors"]:
                universe.setdefault(selector["alias"], []).append(selector)
        jar = jar_for(sources[0][1]["artifact"]["name"])
        extendable = [
            alias
            for alias, defs in universe.items()
            if alias not in committed
            and any(verify_selector(jar, d) == "VERIFIED_STATIC" for d in defs)
        ]
        return {
            "status": "verified" if not extendable else "extendable",
            "selectors": len(committed),
            "extendableAliases": sorted(extendable),
        }
    jar = jar_for(sources[0][1]["artifact"]["name"])
    if jar is None:
        return {"status": "no-artifact"}
    best = None
    for source_version, source in sources:
        failures = []
        rescued = 0
        for s in source["selectors"]:
            _used, status, via = verify_propagated(
                jar, s, source_version, version, resolver
            )
            if status != "VERIFIED_STATIC":
                failures.append(
                    {"alias": s["alias"], "status": status, "via": via}
                )
            elif via == "symbol-map":
                rescued += 1
        if not failures:
            return {
                "status": "propagatable",
                "from": source_version,
                "symbolMapRescued": rescued,
            }
        if best is None or len(failures) < len(best["failures"]):
            best = {
                "from": source_version,
                "failures": failures,
                "symbolMapRescued": rescued,
            }
    return {"status": "blocked", **best}


def propagation_matrix(resolver=None) -> dict:
    """Read-only family x version adaptation board."""
    families = load_source_records()
    rows: dict[str, dict[str, dict]] = {family: {} for family in families}
    for version in ALL_VERSIONS:
        jars: dict[str, JarIndex | None] = {}

        def jar_for(name: str, version=version, jars=jars):
            if name not in jars:
                path = get_ref_dir() / version / name
                jars[name] = JarIndex(path) if path.is_file() else None
            return jars[name]

        for family, sources in sorted(families.items()):
            rows[family][version] = matrix_cell(
                jar_for, family, sources, version, resolver
            )
    return rows


CELL_CODES = {
    "verified": "V",
    "extendable": "E",
    "propagatable": "P",
    "blocked": "B",
    "conflict": "X",
    "no-artifact": "-",
}


def print_matrix(rows: dict[str, dict[str, dict]]) -> None:
    header_versions = [v[2:] for v in ALL_VERSIONS]  # 5.x.y -> x.y
    print("legend: V=verified E=extendable P=propagatable B=blocked X=conflict -=no-artifact")
    print(" " * 28 + "".join(f"{h:>7}" for h in header_versions))
    propagatable = []
    for family in sorted(rows):
        cells = rows[family]
        counts = {code: 0 for code in CELL_CODES}
        line = []
        for version in ALL_VERSIONS:
            cell = cells.get(version) or {"status": "no-artifact"}
            counts[cell["status"]] += 1
            code = CELL_CODES[cell["status"]]
            if cell["status"] == "extendable":
                code = f"+{len(cell['extendableAliases'])}"
            elif cell["status"] == "blocked":
                code = f"!{len(cell['failures'])}"
            elif cell["status"] == "propagatable":
                propagatable.append((family, version, cell["from"]))
            line.append(f"{code:>7}")
        print(f"{family:28}" + "".join(line))
    print()
    print(f"{'family':28}{'verified':>9}{'extend':>8}{'propagatable':>14}{'blocked':>9}{'conflict':>10}")
    for family in sorted(rows):
        cells = rows[family]
        counts = {code: 0 for code in CELL_CODES}
        for version in ALL_VERSIONS:
            counts[(cells.get(version) or {"status": "no-artifact"})["status"]] += 1
        print(
            f"{family:28}{counts['verified']:>9}{counts['extendable']:>8}"
            f"{counts['propagatable']:>14}{counts['blocked']:>9}{counts['conflict']:>10}"
        )
    if propagatable:
        print()
        print("propagatable but not yet declared:")
        for family, version, source_version in propagatable:
            print(f"  {family} {version} (from {source_version})")


def propagate_main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="cubism_version_matrix.py propagate",
        description="Extrapolate verified records across Cubism versions.",
    )
    parser.add_argument("--family", help="record family (filename suffix)")
    parser.add_argument("--from", dest="from_version", help="reference version")
    parser.add_argument(
        "--to",
        help="'all' or comma-separated target versions",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", default=True)
    mode.add_argument("--write", action="store_true")
    parser.add_argument(
        "--all-families",
        action="store_true",
        help="read-only family x version propagation matrix",
    )
    parser.add_argument(
        "--no-symbol-map",
        action="store_true",
        help="disable the optional cubism_symbol_map.resolve hook",
    )
    args = parser.parse_args(argv)

    if args.all_families:
        rows = propagation_matrix(
            None if args.no_symbol_map else load_symbol_resolver()
        )
        print_matrix(rows)
        PROPAGATE_REPORT_DIR.mkdir(parents=True, exist_ok=True)
        report_path = PROPAGATE_REPORT_DIR / "matrix.json"
        report_path.write_text(json.dumps(rows, indent=2) + "\n")
        print(f"\nreport: {report_path}")
        return 0

    if not args.family or not args.from_version or not args.to:
        parser.error("--family, --from and --to are required (or --all-families)")
    if args.to == "all":
        targets = [v for v in ALL_VERSIONS if v != args.from_version]
    else:
        targets = args.to.split(",")
        for version in targets:
            if not re.fullmatch(r"\d+\.\d+\.\d+", version):
                parser.error(f"bad version {version!r}")
    resolver = None if args.no_symbol_map else load_symbol_resolver()
    report = propagate_family(
        args.family, args.from_version, targets, args.write, resolver
    )
    for result in report["results"]:
        line = f"  {result['version']:8} {result['outcome']:16}"
        if result["outcome"] in ("written", "merged"):
            line += (
                f" selectors={result['selectors']} capabilities={result['capabilities']}"
            )
            if result.get("selectorsAdded"):
                line += f" +{len(result['selectorsAdded'])} aliases"
            if result.get("capabilitiesAdded"):
                line += f" +caps:{','.join(result['capabilitiesAdded'])}"
        if result["failures"]:
            line += f" failures={len(result['failures'])}"
        if result["conflicts"]:
            line += f" conflicts={len(result['conflicts'])}"
        print(line)
    if report["pending"]:
        print(f"\npending ({len(report['pending'])} unresolved selector failures):")
        for failure in report["pending"]:
            print(
                f"  {failure['version']:8} {failure['alias']:58} "
                f"{failure['status']:20} {failure['ownerInternalName']}"
                f"::{failure.get('memberName') or ''}{failure.get('descriptor') or ''}"
            )
    PROPAGATE_REPORT_DIR.mkdir(parents=True, exist_ok=True)
    report_path = PROPAGATE_REPORT_DIR / f"{args.family}-{args.from_version}.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    print(f"\nreport: {report_path}")
    if not args.write:
        print("dry-run; re-run with --write to apply")
        return 0
    if not report["filesWritten"]:
        print("no files changed")
        return 0
    print(f"\nwrote {len(report['filesWritten'])} file(s); running post-write checks")
    sync = subprocess.run(
        [sys.executable, str(ROOT / "scripts/sync_draft_pack_metadata.py"), str(ROOT)],
        capture_output=True,
        text=True,
    )
    print(sync.stdout.strip() or "(sync: no output)")
    if sync.returncode:
        print(f"SYNC FAILED ({sync.returncode}): {sync.stderr.strip()}")
    check = subprocess.run(
        [sys.executable, str(GENERATE), str(ROOT), "check"],
        capture_output=True,
        text=True,
    )
    print(check.stdout.strip() or "(check: no output)")
    if check.returncode:
        print(f"CHECK FAILED ({check.returncode}): {check.stderr.strip()}")
    print(
        "\nfollow-ups before admission:\n"
        "  - bind new record names into scripts/verification-sources/families/*.json\n"
        "  - run `python3 scripts/dev/cubism_version_matrix.py wire` to rewire manifests\n"
        "  - SHA-pin tests embedding record digests may need updates, e.g.\n"
        "    testing/integration-tests/.../StaticVerificationRecordRepositoryTest.java"
    )
    return 0 if check.returncode == 0 and sync.returncode == 0 else 1


