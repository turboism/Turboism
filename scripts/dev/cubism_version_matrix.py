#!/usr/bin/env python3
"""Derive per-version Cubism static verification artifacts (spec 057).

This tool is the deterministic onboarding pipeline for the reviewed Cubism
Editor versions present under ``cubism-ref/``. For every target version it:

1. measures the exact ``Live2D_Cubism.jar``/``Live2DCubismCore.jar`` identity
   (size + SHA-256) and re-checks it against ``cubism-ref/MANIFEST.txt``;
2. structurally verifies every selector of each existing reviewed record
   against the target jar with a faithful port of
   ``StaticSelectorVerifier.verifyStructure``;
3. derives the surviving capability set using the same rules as
   ``CapabilitySelectorDependencies``: non editor-model slices are
   all-or-nothing on the full record, editor-model capabilities require their
   contract ``REQUIRED_ALIASES`` plus the record-version-conditional extras;
   capabilities not modelled by the contract table only survive when the
   entire source record verifies;
4. emits ``compatibility/cubism/verification/cubism-<v>-<family>.json``
   records, ``mapping-packs/draft/`` packs cloned from the source pack, and a
   ``profiles/draft/cubism-<v>.json`` profile.

The Java ``StaticVerificationCli`` remains the authority: every emitted record
is re-verified end-to-end by the real verifier before the tree is committed.

Usage:
    cubism_version_matrix.py matrix   # print the derived capability matrix
    cubism_version_matrix.py emit     # write records + packs + profiles
    cubism_version_matrix.py facts    # dump measured artifact facts as JSON
    cubism_version_matrix.py refresh-editor-model  # additive refresh, all 20 versions
    cubism_version_matrix.py propagate --family F --from V --to all|V,... [--dry-run|--write]
    cubism_version_matrix.py propagate --all-families   # read-only matrix report
"""

from __future__ import annotations

import json
from pathlib import Path
import re
import sys

_DEV_DIR = str(Path(__file__).resolve().parent)
if _DEV_DIR not in sys.path:
    sys.path.insert(0, _DEV_DIR)
_SCRIPTS_DIR = str(Path(__file__).resolve().parents[1])
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

from cubism_matrix import (
    ALL_VERSIONS,
    CATALOG_SLICES,
    DECLARED_BUILDS,
    DEPS_SOURCE,
    EDITOR_MODEL_SLICE,
    FAMILY_JSON,
    GENERATE,
    JarIndex,
    MAPPING_ID_VERSION,
    MEMBER_VARIANTS,
    MODEL_BINDING,
    PACK_DIR,
    PROFILE_DIR,
    REF_DIR,
    REVIEWED,
    ROOT,
    SELECTOR_IDENTITY,
    SLICE_OF,
    TARGET_VERSIONS,
    TEMPLATE_DIR,
    TYPE_TOKEN,
    VERIFICATION_DIR,
    VERIFIED_AT,
    _editor_variants,
    artifact_facts as _artifact_facts,
    candidate_constant,
    contract_constants,
    cubism_ref_dir,
    decode_mutf8,
    derive_editor_model_record,
    derive_pack,
    derive_profile,
    derive_record,
    derive_version,
    editor_requirements,
    emitted_record,
    emitted_records,
    family_of,
    load_family_packs,
    load_source_pack,
    load_source_records as _load_source_records,
    load_symbol_resolver,
    manifest_hashes as _manifest_hashes,
    matrix_cell,
    merge_record,
    pack_name_for,
    parse_class,
    plan_version_propagation,
    print_matrix,
    propagate_family as _propagate_family,
    propagate_main,
    propagated_pack,
    propagated_record,
    propagation_matrix,
    refresh_editor_model,
    repair_committed_packs,
    repair_committed_records,
    required_aliases,
    retag_mapping_id,
    runtime_contract_constants,
    selector_identity,
    shared_core_profile,
    source_version_of,
    synthesize_entry,
    vcompact,
    verify_propagated,
    verify_selector,
    verified_aliases,
    vtag,
    wire,
    wire_arm_style_manifest,
    wire_autobackup,
    wire_bootstrap,
    wire_bounding_box,
    wire_catalog_json,
    wire_editor_model as _wire_editor_model,
    wire_family_manifests,
    wire_host_validation,
    wire_index_md,
    wire_protected_export,
    wire_record_style_manifest,
    wire_slice_catalog,
    write_document,
)

__all__ = [
    "ALL_VERSIONS",
    "CATALOG_SLICES",
    "DECLARED_BUILDS",
    "DEPS_SOURCE",
    "EDITOR_MODEL_SLICE",
    "FAMILY_JSON",
    "GENERATE",
    "JarIndex",
    "MAPPING_ID_VERSION",
    "MEMBER_VARIANTS",
    "MODEL_BINDING",
    "PACK_DIR",
    "PROFILE_DIR",
    "REF_DIR",
    "REVIEWED",
    "ROOT",
    "SELECTOR_IDENTITY",
    "SLICE_OF",
    "TARGET_VERSIONS",
    "TEMPLATE_DIR",
    "TYPE_TOKEN",
    "VERIFICATION_DIR",
    "VERIFIED_AT",
    "_editor_variants",
    "artifact_facts",
    "candidate_constant",
    "contract_constants",
    "cubism_ref_dir",
    "decode_mutf8",
    "derive_editor_model_record",
    "derive_pack",
    "derive_profile",
    "derive_record",
    "derive_version",
    "editor_requirements",
    "emitted_record",
    "emitted_records",
    "family_of",
    "load_family_packs",
    "load_source_pack",
    "load_source_records",
    "load_symbol_resolver",
    "main",
    "manifest_hashes",
    "matrix_cell",
    "merge_record",
    "pack_name_for",
    "parse_class",
    "plan_version_propagation",
    "print_matrix",
    "propagate_family",
    "propagate_main",
    "propagated_pack",
    "propagated_record",
    "propagation_matrix",
    "refresh_editor_model",
    "repair_committed_packs",
    "repair_committed_records",
    "required_aliases",
    "retag_mapping_id",
    "runtime_contract_constants",
    "selector_identity",
    "shared_core_profile",
    "source_version_of",
    "synthesize_entry",
    "vcompact",
    "verify_propagated",
    "verify_selector",
    "verified_aliases",
    "vtag",
    "wire",
    "wire_arm_style_manifest",
    "wire_autobackup",
    "wire_bootstrap",
    "wire_bounding_box",
    "wire_catalog_json",
    "wire_editor_model",
    "wire_family_manifests",
    "wire_host_validation",
    "wire_index_md",
    "wire_protected_export",
    "wire_record_style_manifest",
    "wire_slice_catalog",
    "write_document",
]


def artifact_facts(
    version: str,
    jar_name: str = "Live2D_Cubism.jar",
    ref_dir: Path | None = None,
) -> dict:
    return _artifact_facts(version, jar_name=jar_name, ref_dir=ref_dir if ref_dir is not None else REF_DIR)


def manifest_hashes(ref_dir: Path | None = None) -> dict[str, str]:
    return _manifest_hashes(ref_dir=ref_dir if ref_dir is not None else REF_DIR)


def load_source_records(
    verification_dir: Path | None = None,
) -> dict[str, list[tuple[str, dict]]]:
    return _load_source_records(verification_dir=verification_dir if verification_dir is not None else VERIFICATION_DIR)


def wire_editor_model(versions: list[str], template_dir: Path | None = None) -> None:
    return _wire_editor_model(versions, template_dir=template_dir if template_dir is not None else TEMPLATE_DIR)


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
    return _propagate_family(
        family,
        from_version,
        targets,
        write,
        resolver=resolver,
        ref_dir=ref_dir if ref_dir is not None else REF_DIR,
        verification_dir=verification_dir if verification_dir is not None else VERIFICATION_DIR,
        pack_dir=pack_dir if pack_dir is not None else PACK_DIR,
    )


def main() -> int:
    if len(sys.argv) < 2 or sys.argv[1] not in {
        "matrix",
        "emit",
        "facts",
        "wire",
        "refresh-editor-model",
        "propagate",
    }:
        print(__doc__)
        return 2
    command = sys.argv[1]

    # Cross-check measured facts against the manifest before deriving.
    manifest = manifest_hashes()
    for version in TARGET_VERSIONS + REVIEWED:
        for jar in ("Live2D_Cubism.jar", "Live2DCubismCore.jar"):
            facts = artifact_facts(version, jar)
            key = f"{version}/{jar}"
            if key in manifest and manifest[key] != facts["sha256"]:
                print(f"FAIL: {key} sha256 drifted", file=sys.stderr)
                return 2

    if command == "propagate":
        return propagate_main(sys.argv[2:])

    if command == "wire":
        wire()
        return 0

    if command == "facts":
        out = {}
        for version in TARGET_VERSIONS:
            out[version] = {
                "jar": artifact_facts(version),
                "core": artifact_facts(version, "Live2DCubismCore.jar"),
                "build": DECLARED_BUILDS[version],
            }
        print(json.dumps(out, indent=2))
        return 0

    constants = contract_constants()
    editor = editor_requirements(constants)
    families = load_source_records()

    if command == "refresh-editor-model":
        refresh_editor_model(constants, editor, families)
        return 0

    if command == "matrix":
        for version in TARGET_VERSIONS:
            derived = derive_version(version, families, editor, constants)
            print(f"{version}:")
            for family, (record, src) in derived.items():
                print(
                    f"  {family:28} caps={len(record['capabilityIds']):3} "
                    f"selectors={len(record['selectors'])} "
                    f"(from {src})"
                )
            missing = sorted(
                f for f in families if f != "core-model-read" and f not in derived
            )
            if missing:
                print(f"  SKIPPED: {', '.join(missing)}")
        return 0

    # emit
    repaired = repair_committed_records()
    if repaired:
        print("repaired missing capabilityConditions:", ", ".join(repaired))
    repaired_packs = repair_committed_packs()
    if repaired_packs:
        print("repaired stale pack entries:", ", ".join(repaired_packs))
    family_packs = {family: load_family_packs(family) for family in families}
    pack_order = {
        re.sub(r"^cubism-\d+\.\d+\.\d+-", "", name): i
        for i, name in enumerate(
            json.loads((PROFILE_DIR / "cubism-5.3.03.json").read_text())["mappingPacks"]
        )
    }
    for version in TARGET_VERSIONS:
        derived = derive_version(version, families, editor, constants)
        packs = {}
        for family, (record, source_version) in sorted(derived.items()):
            record_path = VERIFICATION_DIR / f"cubism-{version}-{family}.json"
            record_path.write_text(json.dumps(record, indent=2) + "\n")
            candidates = family_packs.get(family) or {}
            source_pack = (
                candidates.get(source_version)
                or (candidates["5.3.03"] if "5.3.03" in candidates else None)
                or (next(iter(candidates.values())) if candidates else None)
            )
            if source_pack is None:
                continue
            pack = derive_pack(
                source_pack,
                record,
                version,
                family,
                family_packs=candidates,
                rewrite_provenance=True,
            )
            name = f"cubism-{version}-{pack_name_for(family)}"
            (PACK_DIR / f"{name}.json").write_text(json.dumps(pack, indent=2) + "\n")
            packs[name] = pack
        core_pack = shared_core_profile(version)
        pack_names = sorted(
            packs,
            key=lambda n: pack_order.get(re.sub(r"^cubism-\d+\.\d+\.\d+-", "", n), 99),
        )
        if core_pack is not None:
            pack_names.append(core_pack)
        profile = derive_profile(version, pack_names)
        (PROFILE_DIR / f"cubism-{version}.json").write_text(
            json.dumps(profile, indent=2) + "\n"
        )
        print(f"{version}: {len(derived)} records, {len(pack_names)} packs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
