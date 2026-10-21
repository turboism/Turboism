"""Capability derivation, editor-model refresh, and draft profile/pack synthesis."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re

from .artifacts import artifact_facts
from .bytecode import JarIndex, verified_aliases, verify_selector
from .constants import (
    ALL_VERSIONS,
    EDITOR_MODEL_SLICE,
    MEMBER_VARIANTS,
    PACK_DIR,
    PROFILE_DIR,
    REF_DIR,
    REVIEWED,
    TARGET_VERSIONS,
    VERIFICATION_DIR,
    VERIFIED_AT,
)
from .contracts import required_aliases
from .wire import wire_editor_model

def derive_version(
    version: str,
    families: dict[str, list[dict]],
    editor: dict,
    constants: dict,
    ref_dir: Path | None = None,
) -> dict:
    """Derive the surviving record set for one target version."""
    if ref_dir is None:
        ref_dir = REF_DIR
    jar = JarIndex(ref_dir / version / "Live2D_Cubism.jar")
    derived = {}
    for family, sources in sorted(families.items()):
        if family == "core-model-read":
            continue  # core jars are handled by the core pipeline, if at all
        if sources and sources[0][1]["adapterSliceId"] == EDITOR_MODEL_SLICE:
            record = derive_editor_model_record(
                jar, sources, version, family, editor, constants, ref_dir=ref_dir
            )
            if record is not None:
                derived[family] = (record, "merged")
            continue
        # Non-editor-model slices are all-or-nothing on a reviewed record:
        # keep the source that fully verifies with the largest capability set.
        best = None
        for source_version, source in sources:
            if source["artifact"]["name"] != "Live2D_Cubism.jar":
                continue
            surviving = verified_aliases(jar, source)
            if len(surviving) != len(source["selectors"]):
                continue
            score = (len(source["capabilityIds"]), len(source["selectors"]))
            if best is None or score > best[2]:
                best = (source, surviving, score, source_version)
        if best is None:
            continue
        source, surviving, _score, source_version = best
        record = derive_record(
            source, surviving, True, version, family, editor, constants, ref_dir=ref_dir
        )
        if record is not None:
            derived[family] = (record, source_version)
    return derived


def derive_editor_model_record(
    jar: JarIndex,
    sources: list[tuple[str, dict]],
    version: str,
    family: str,
    editor: dict,
    constants: dict,
    ref_dir: Path | None = None,
) -> dict | None:
    """Merge the alias universe of every reviewed editor-model record.

    An alias is a semantic contract; its concrete selector can differ between
    reviewed generations (nine known conflicts). For each alias the emitted
    record keeps the newest selector definition that structurally verifies on
    the target jar. Listed capabilities survive when their ``requiredAliases``
    (computed over the merged record, exactly as the runtime does) are all
    present; capabilities absent from the contract table only survive when the
    entire merged alias universe verifies, matching the conservative
    all-record requirement of ``CapabilitySelectorDependencies``.
    """
    # Newest reviewed definition first; an alias whose newest definition fails
    # structural verification falls through to an older one that passes.
    chosen: dict[str, dict] = {}
    for _source_version, source in sources:
        for selector in source["selectors"]:
            alias = selector["alias"]
            if alias in chosen:
                continue
            if verify_selector(jar, selector) == "VERIFIED_STATIC":
                chosen[alias] = selector
    universe: dict[str, dict] = {}
    for _source_version, source in sources:
        for selector in source["selectors"]:
            universe.setdefault(selector["alias"], selector)
    for (alias, target_version), overrides in MEMBER_VARIANTS.items():
        if target_version != version or alias in chosen or alias not in universe:
            continue
        variant = dict(universe[alias])
        variant.update(overrides)
        if verify_selector(jar, variant) == "VERIFIED_STATIC":
            chosen[alias] = variant
    merged = [chosen[a] for a in sorted(chosen)]

    merged_surviving = set(chosen)
    conditions_by_cap: dict[str, list[str]] = {}
    cap_order: list[str] = []
    for _source_version, source in sources:
        record_conditions = source.get("capabilityConditions") or {}
        for capability in source["capabilityIds"]:
            if capability in conditions_by_cap:
                continue
            conditions_by_cap[capability] = record_conditions.get(capability, [])
            cap_order.append(capability)
    supported_versions = constants.get("_capabilitySupportedVersions") or {}
    capabilities = []
    for capability in cap_order:
        declared = supported_versions.get(capability)
        if declared is not None and version not in declared:
            continue
        if capability not in editor:
            # Unlisted capabilities conservatively require a complete source
            # record that declares them: keep only when some declaring
            # record's entire selector set survives on this jar.
            if not any(
                capability in source["capabilityIds"]
                and {s["alias"] for s in source["selectors"]} <= merged_surviving
                for _source_version, source in sources
            ):
                continue
        else:
            required = required_aliases(
                constants,
                editor,
                merged,
                version,
                capability,
                conditions_by_cap[capability],
            )
            if not required <= merged_surviving:
                continue
        capabilities.append(capability)
    if not capabilities:
        return None
    record = dict(sources[0][1])
    record["verificationId"] = f"cubism-{version}.{family}.static"
    record["capabilityIds"] = capabilities
    record["cubismVersion"] = version
    record["profileId"] = f"cubism-{version}"
    record["artifact"] = artifact_facts(version, ref_dir=ref_dir)
    record["evidencePath"] = (
        f"compatibility/cubism/verification/cubism-{version}-{family}.json"
    )
    record["verifiedAt"] = VERIFIED_AT
    record["selectors"] = merged
    record["capabilityConditions"] = {
        cap: conditions_by_cap[cap] or ["declaredGeneration"] for cap in capabilities
    }
    return record


def derive_record(
    source: dict,
    surviving: set[str],
    full: bool,
    version: str,
    family: str,
    editor: dict,
    constants: dict,
    ref_dir: Path | None = None,
) -> dict | None:
    if source["adapterSliceId"] != EDITOR_MODEL_SLICE:
        # Non-editor-model slices are all-or-nothing on the reviewed record.
        if not full:
            return None
        capabilities = list(source["capabilityIds"])
        selectors = source["selectors"]
        # capabilityConditions is a required object covering every capability;
        # an absent source entry carries the runtime's declaredGeneration
        # default, so the emitted record must spell it out explicitly.
        conditions = source.get("capabilityConditions") or {
            cap: ["declaredGeneration"] for cap in capabilities
        }
    else:
        selectors = [s for s in source["selectors"] if s["alias"] in surviving]
        record_conditions = source.get("capabilityConditions") or {}
        supported_versions = constants.get("_capabilitySupportedVersions") or {}
        capabilities = []
        for capability in source["capabilityIds"]:
            declared = supported_versions.get(capability)
            if declared is not None and version not in declared:
                continue
            listed = capability in editor
            if not listed and not full:
                # Unlisted capabilities conservatively require the entire
                # source record; partial evidence can never keep them.
                continue
            required = required_aliases(
                constants,
                editor,
                source["selectors"],
                version,
                capability,
                record_conditions.get(capability, []),
            )
            if required <= surviving:
                capabilities.append(capability)
        if not capabilities:
            return None
        conditions = {
            cap: record_conditions[cap]
            for cap in capabilities
            if cap in record_conditions
        }
    artifact = artifact_facts(version, ref_dir=ref_dir)
    record = dict(source)
    # Retired governance prefixes (``m15.``) are never propagated; new records
    # always use the canonical per-family verification id.
    record["verificationId"] = f"cubism-{version}.{family}.static"
    record["capabilityIds"] = capabilities
    record["cubismVersion"] = version
    record["profileId"] = f"cubism-{version}"
    record["artifact"] = artifact
    record["evidencePath"] = (
        f"compatibility/cubism/verification/cubism-{version}-{family}.json"
    )
    record["verifiedAt"] = VERIFIED_AT
    record["selectors"] = selectors
    # capabilityConditions is a required field; its keys must stay within the
    # emitted capabilityIds.
    record["capabilityConditions"] = conditions
    return record


def pack_name_for(family: str) -> str:
    """Map a record family to its draft pack file name."""
    special = {"editor-model": "editor-model-read"}
    return special.get(family, family)


def load_source_pack(
    source_version: str,
    family: str,
    pack_dir: Path | None = None,
) -> dict | None:
    if pack_dir is None:
        pack_dir = PACK_DIR
    for candidate in (
        f"cubism-{source_version}-{pack_name_for(family)}.json",
        f"cubism-{source_version}-{family}.json",
    ):
        path = pack_dir / candidate
        if path.is_file():
            return json.loads(path.read_text())
    return None


def load_family_packs(family: str, pack_dir: Path | None = None) -> dict[str, dict]:
    """All reviewed packs for a family, keyed by version, newest first."""
    if pack_dir is None:
        pack_dir = PACK_DIR
    packs = {}
    for version in REVIEWED:
        pack = load_source_pack(version, family, pack_dir=pack_dir)
        if pack is not None:
            packs[version] = pack
    return packs


def synthesize_entry(
    selector: dict,
    family: str,
    profile: str,
    uses_intermediary: bool,
    uses_owner: str | None,
) -> dict:
    """Build a pack entry faithful to the strict record↔entry join contract."""
    entry = {
        "semanticName": selector["mappingId"],
        "kind": selector["kind"],
        "name": selector["alias"],
        "runtime": (
            selector["ownerInternalName"]
            if selector["kind"] == "class"
            else selector["memberName"]
        ),
        "profile": profile,
        "status": "DRAFT",
        "stability": "experimental",
        "source": "exact-public-classfile-observation",
        "verifiedBy": "none",
        "verifiedAt": None,
        "confidence": "high",
    }
    if uses_owner is not None:
        entry["owner"] = uses_owner
    if uses_intermediary:
        entry["intermediary"] = ""
    if selector["kind"] != "class":
        # The strict pack test reads entry.descriptor via get() — the key must
        # exist with an explicit null for inherits/field-less kinds.
        entry["descriptor"] = selector.get("descriptor")
    entry["x.verification"] = {
        "ownerInternalName": selector["ownerInternalName"],
    }
    return entry


def derive_pack(
    source_pack: dict,
    record: dict,
    version: str,
    family: str,
    family_packs: dict[str, dict] | None = None,
) -> dict:
    """Clone a source pack as a strict 1:1 projection of the emitted record."""
    profile = f"cubism-{version}"
    sha = record["artifact"]["sha256"]
    candidates = [source_pack]
    if family_packs:
        for pack in family_packs.values():
            if pack is not source_pack:
                candidates.append(pack)
    by_id: dict[str, dict] = {}
    by_alias: dict[str, dict] = {}
    for pack in candidates:
        for e in pack["entries"]:
            by_id.setdefault(e["semanticName"], e)
            by_alias.setdefault(e.get("name"), e)
    uses_intermediary = any("intermediary" in e for e in source_pack["entries"])
    owners = {e["owner"] for e in source_pack["entries"] if "owner" in e}
    uses_owner = sorted(owners)[0] if owners else None
    entries = []
    for selector in record["selectors"]:
        template = by_id.get(selector["mappingId"]) or by_alias.get(selector["alias"])
        if template is None:
            entry = synthesize_entry(
                selector,
                family,
                profile,
                uses_intermediary,
                uses_owner,
            )
        else:
            entry = dict(template)
            entry["semanticName"] = selector["mappingId"]
            entry["kind"] = selector["kind"]
            entry["name"] = selector["alias"]
            entry["runtime"] = (
                selector["ownerInternalName"]
                if selector["kind"] == "class"
                else selector["memberName"]
            )
            entry["profile"] = profile
            if selector["kind"] != "class":
                entry["descriptor"] = selector.get("descriptor")
            elif "descriptor" in entry:
                entry.pop("descriptor")
            entry["x.verification"] = {
                "ownerInternalName": selector["ownerInternalName"],
            }
        entries.append(entry)
    pack = {}
    for key, value in source_pack.items():
        if key == "exactArtifactSha256":
            continue
        pack[key] = value
        if key == "cubismVersion":
            pack[key] = version
            if "exactArtifactSha256" in source_pack:
                pack["exactArtifactSha256"] = sha
    pack["entries"] = entries
    if "metadata" in source_pack:
        record_bytes = json.dumps(record, indent=2).encode() + b"\n"
        metadata = dict(
            source_pack["metadata"],
            inventoryRef=(
                f"compatibility/cubism/core-api/observed/cubism-core-{version}.json"
                if family == "core-model-read"
                else record["evidencePath"]
            ),
            artifactName=record["artifact"]["name"],
            artifactSize=record["artifact"]["size"],
            artifactSha256=sha,
            profileId=profile,
            verificationId=record["verificationId"],
            selectorCount=len(record["selectors"]),
            capabilityCount=len(record["capabilityIds"]),
            capabilityIds=list(record["capabilityIds"]),
            verificationRecordSha256=hashlib.sha256(record_bytes).hexdigest(),
        )
        if "validationNotes" in metadata:
            metadata["validationNotes"] = re.sub(
                r"\d+\.\d+\.\d+",
                version,
                str(metadata["validationNotes"]),
            )
        pack["metadata"] = metadata
    return pack


def refresh_editor_model(constants: dict, editor: dict, families: dict) -> None:
    """Add newly verified Editor selectors/capabilities without replacing old contracts.

    Plan every version before writing anything. Preserve existing selector definitions,
    record identities and capability conditions; never use refresh to shrink admission.
    Other families, host tasks, profiles and native Core admission are untouched.
    """
    sources = families["editor-model"]
    family_packs = load_family_packs("editor-model")
    relation_contract = constants["EditorTextureRelationsSelectorContract"]
    texture_contract = constants["EditorTextureSelectorContract"]
    inspector_contract = constants["EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract"]
    psd_contracts = {
        constants[name]["CAPABILITY_ID"]: constants[name]
        for name in (
            "EditorRawImagePsdSelectorContract",
            "EditorRawImagePsdReplaceSelectorContract",
        )
    }
    # Stored authoring/keyform additions whose per-version records are derived
    # from their contract alias sets (model-import-3d version adaptation).
    scoped_contracts = (
        "EditorArtMeshEvaluationReadSelectorContract",
        "EditorArtMeshMorphSelectorContract",
        "EditorSourceTriangulationSelectorContract",
        "EditorParameterInterpolationSelectorContract",
        "EditorRenderPixelsSelectorContract",
    )
    updates = []
    for version in ALL_VERSIONS:
        record_path = VERIFICATION_DIR / f"cubism-{version}-editor-model.json"
        pack_path = PACK_DIR / f"cubism-{version}-editor-model-read.json"
        current = json.loads(record_path.read_text())
        jar = JarIndex(REF_DIR / version / "Live2D_Cubism.jar")
        if current["artifact"] != artifact_facts(version):
            raise ValueError(f"reviewed artifact identity drifted: {version}")
        for selector in current["selectors"]:
            if verify_selector(jar, selector) != "VERIFIED_STATIC":
                raise ValueError(
                    f"existing selector no longer verifies: {version} {selector['alias']}"
                )
        derived = derive_editor_model_record(
            jar, sources, version, "editor-model", editor, constants
        )
        if derived is None:
            raise ValueError(f"no derived Editor-model contract for {version}")
        aliases = {selector["alias"] for selector in current["selectors"]}
        original_aliases = set(aliases)
        if version not in relation_contract["SUPPORTED_CUBISM_VERSIONS"]:
            raise ValueError(f"relation generation is not reviewed: {version}")
        scoped_aliases = set(relation_contract["REQUIRED_ALIASES"])
        scoped_capabilities = []
        if version in inspector_contract["SUPPORTED_CUBISM_VERSIONS"]:
            inspector_write = inspector_contract["CAPABILITY_ID"]
            if inspector_write not in derived["capabilityIds"]:
                raise ValueError(f"Inspector no-alpha contract is incomplete: {version}")
            scoped_capabilities.append(inspector_write)
            scoped_aliases.update(required_aliases(
                constants, editor, derived["selectors"], version, inspector_write,
                derived["capabilityConditions"][inspector_write],
            ))
        texture_write = texture_contract["WRITE_CAPABILITY_ID"]
        if texture_write not in derived["capabilityIds"]:
            raise ValueError(f"texture write lacks a complete capability contract: {version}")
        scoped_capabilities.append(texture_write)
        scoped_aliases.update(required_aliases(
            constants, editor, derived["selectors"], version, texture_write,
            derived["capabilityConditions"][texture_write],
        ))
        texture_register_bind = texture_contract["REGISTER_BIND_CAPABILITY_ID"]
        if texture_register_bind not in derived["capabilityIds"]:
            raise ValueError(
                f"texture register-bind lacks a complete capability contract: {version}"
            )
        scoped_capabilities.append(texture_register_bind)
        scoped_aliases.update(required_aliases(
            constants, editor, derived["selectors"], version, texture_register_bind,
            derived["capabilityConditions"][texture_register_bind],
        ))
        for capability, contract in psd_contracts.items():
            if version in contract["SUPPORTED_CUBISM_VERSIONS"]:
                if capability not in derived["capabilityIds"]:
                    raise ValueError(
                        f"PSD generation lacks a complete capability contract: {version} {capability}"
                    )
                scoped_capabilities.append(capability)
                # PSD capabilities currently use the runtime's whole-record
                # fallback. That admission rule must not import unrelated
                # selectors from the derived inventory into an additive refresh.
                # Both PSD contracts and the source relation contract are
                # included together, preserving replacement's export dependency.
                required = set(contract["REQUIRED_ALIASES"])
                if version in contract.get("TRANSPARENCY_SHAPES_ABSENT_VERSIONS", ()):
                    required -= set(contract["TRANSPARENCY_SHAPES_ALIASES"])
                scoped_aliases.update(required)
        for name in scoped_contracts:
            contract = constants[name]
            capability = contract["CAPABILITY_ID"]
            if capability not in derived["capabilityIds"]:
                if capability in current["capabilityIds"]:
                    raise ValueError(
                        f"derived record lost an admitted capability: {version} {capability}"
                    )
                continue
            scoped_capabilities.append(capability)
            scoped_aliases.update(required_aliases(
                constants, editor, derived["selectors"], version, capability,
                derived["capabilityConditions"][capability],
            ))
        derived_aliases = {selector["alias"] for selector in derived["selectors"]}
        if not scoped_aliases <= derived_aliases:
            raise ValueError(f"incomplete refreshed feature selectors: {version}")
        added = [
            selector
            for selector in derived["selectors"]
            if selector["alias"] in scoped_aliases and selector["alias"] not in aliases
        ]
        record = dict(current)
        record["selectors"] = current["selectors"] + added
        record["capabilityIds"] = list(current["capabilityIds"])
        record["capabilityConditions"] = dict(current["capabilityConditions"])
        for capability in scoped_capabilities:
            if capability not in record["capabilityIds"]:
                record["capabilityIds"].append(capability)
                record["capabilityConditions"][capability] = derived[
                    "capabilityConditions"
                ][capability]
        aliases.update(selector["alias"] for selector in added)
        for capability in record["capabilityIds"]:
            required = required_aliases(
                constants,
                editor,
                record["selectors"],
                version,
                capability,
                record["capabilityConditions"][capability],
            )
            missing = required - aliases
            if capability in current["capabilityIds"]:
                previous_required = required_aliases(
                    constants,
                    editor,
                    current["selectors"],
                    version,
                    capability,
                    current["capabilityConditions"][capability],
                )
                previous_missing = previous_required - original_aliases
                if not missing <= previous_missing:
                    raise ValueError(
                        f"existing capability lost dependencies: {version} {capability}"
                    )
                if missing:
                    print(
                        f"BASELINE_DEPENDENCY_GAP {version} {capability}: {sorted(missing)}"
                    )
            elif missing:
                raise ValueError(
                    f"incomplete new capability contract: {version} {capability}"
                )
        source_pack = json.loads(pack_path.read_text())
        derived_pack = derive_pack(
            source_pack, record, version, "editor-model", family_packs=family_packs
        )
        pack = dict(source_pack)
        pack["metadata"] = derived_pack["metadata"]
        existing_pack_aliases = {entry["name"] for entry in source_pack["entries"]}
        pack["entries"] = source_pack["entries"] + [
            entry
            for entry in derived_pack["entries"]
            if entry["name"] not in existing_pack_aliases
        ]
        updates.append(
            (
                record_path,
                pack_path,
                record,
                pack,
                len(added),
                len(record["capabilityIds"]) - len(current["capabilityIds"]),
            )
        )
    for record_path, pack_path, record, pack, added, capabilities in updates:
        for path, document in ((record_path, record), (pack_path, pack)):
            rendered = json.dumps(document, indent=2) + "\n"
            if path.read_text() != rendered:
                path.write_text(rendered)
        print(
            f"{record['cubismVersion']}: added selectors={added} capabilities={capabilities}"
        )
    wire_editor_model(list(TARGET_VERSIONS))


def repair_committed_records() -> list[str]:
    """Backfill the required ``capabilityConditions`` object on committed
    records that predate the rule. ``["declaredGeneration"]`` is exactly what
    the resolver's ``getOrDefault`` applies to an unconditioned capability, so
    the patch preserves runtime semantics bit-for-bit."""
    repaired = []
    for path in sorted(VERIFICATION_DIR.glob("cubism-*.json")):
        doc = json.loads(path.read_text())
        if "capabilityConditions" in doc:
            continue
        doc["capabilityConditions"] = {
            cap: ["declaredGeneration"] for cap in doc["capabilityIds"]
        }
        path.write_text(json.dumps(doc, indent=2) + "\n")
        repaired.append(path.name)
    return repaired


def repair_committed_packs() -> list[str]:
    """Re-emit committed DRAFT pack entries as a strict 1:1 projection of their
    records. The reviewed editor-model packs were left behind by the
    mapping-id migration: they omit the nine ``inherits`` selectors and carry
    four stale semanticNames, so ``packEntries == selectors`` fails."""
    repaired = []
    for path in sorted(PACK_DIR.glob("cubism-*.json")):
        match = re.match(r"cubism-(\d+\.\d+\.\d+)-(.+)\.json", path.name)
        version = match.group(1)
        if version not in REVIEWED:
            continue
        family = match.group(2)
        record_path = None
        for candidate in sorted(VERIFICATION_DIR.glob(f"cubism-{version}-*.json")):
            record_family = re.match(
                r"cubism-\d+\.\d+\.\d+-(.+)\.json", candidate.name
            ).group(1)
            if record_family == family or pack_name_for(record_family) == family:
                record_path = candidate
                break
        if record_path is None:
            continue
        record = json.loads(record_path.read_text())
        pack = json.loads(path.read_text())
        fixed = derive_pack(pack, record, version, family)
        if (
            fixed["entries"] != pack["entries"]
            or fixed.get("metadata") != pack.get("metadata")
            or fixed.get("exactArtifactSha256") != pack.get("exactArtifactSha256")
        ):
            path.write_text(json.dumps(fixed, indent=2) + "\n")
            repaired.append(path.name)
    return repaired


def shared_core_profile(version: str) -> str | None:
    """A target carries its own propagated core pack when one exists.
    Otherwise it inherits a reviewed core profile only when its
    ``Live2DCubismCore.jar`` is byte-identical (``profileForArtifact`` binds
    by exact digest). The native runtime tuple stays unobserved for distinct
    jars, so they receive no core pack reference."""
    if (PACK_DIR / f"cubism-{version}-core-model-read.json").is_file():
        return f"cubism-{version}-core-model-read"
    sha = artifact_facts(version, "Live2DCubismCore.jar")["sha256"]
    for reviewed in ("5.1.03", "5.2.03", "5.3.02", "5.3.04"):
        if artifact_facts(reviewed, "Live2DCubismCore.jar")["sha256"] == sha:
            return f"cubism-{reviewed}-core-model-read"
    return None


def derive_profile(version: str, packs: list[str]) -> dict:
    template_path = PROFILE_DIR / "cubism-5.3.03.json"
    profile = json.loads(template_path.read_text())
    profile["profileId"] = f"cubism-{version}"
    profile["mappingPacks"] = packs
    profile["notes"] = (
        f"DRAFT profile for exact Cubism Editor {version}. Static "
        "exact-artifact records and DRAFT mapping packs are repository "
        "evidence only; runtime admission remains explicitly "
        "capability-scoped and does not claim exact-host readiness."
    )
    profile["cubismVersion"] = version
    return profile
