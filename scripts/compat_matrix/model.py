"""Compatibility matrix data model, capability resolution, and matrix builder."""
from __future__ import annotations

from pathlib import Path
import re

from turboism_dev.versions import cubism_version_key

from .constants import (
    CAPABILITY_GROUP_RULES,
    DECLARED_CAPABILITY_ALIASES,
    FEATURE_LABELS,
    GENERATOR_VERSION,
    GROUP_LABELS,
    INTERCEPTOR,
    MATRIX_FORMAT,
    NON_FUNCTIONAL_PREFIXES,
    TASK_GROUP_ALIASES,
    TASK_PLUGIN_ALIASES,
)
from .extract import (
    _load_boundary_entries,
    index_official_timeline,
    load_campaign,
    load_capabilities,
    load_class_pins,
    load_commands,
    load_contract_gates,
    load_monotonicity_exceptions,
    load_official_timeline,
    load_plugins,
    load_required_capabilities,
    load_reviewed_versions,
    load_runtime_gates,
    load_version_boundaries,
    parse_sdk_annotations,
    task_base,
)

def version_key(version: str) -> tuple[int, ...]:
    return cubism_version_key(version)


def sort_versions(versions) -> list[str]:
    return sorted(versions, key=version_key)


def compact_ranges(versions, reviewed: list[str]) -> str:
    """Render a subset of the reviewed list as compact ranges.

    Adjacency follows the reviewed order, so ``5.0.06`` and ``5.1.00`` collapse
    into one range just like consecutive patches.
    """
    chosen = set(versions)
    ordered = [v for v in reviewed if v in chosen]
    if not ordered:
        return "—"
    if ordered == reviewed:
        return f"{reviewed[0]}–{reviewed[-1]}"
    index = {v: i for i, v in enumerate(reviewed)}
    runs: list[list[str]] = [[ordered[0]]]
    for v in ordered[1:]:
        if index[v] == index[runs[-1][-1]] + 1:
            runs[-1].append(v)
        else:
            runs.append([v])
    return ", ".join(r[0] if len(r) == 1 else f"{r[0]}–{r[-1]}" for r in runs)


def localized(key: str, locale: str, fallback: str) -> str:
    labels = (
        FEATURE_LABELS.get(key)
        or FEATURE_LABELS.get(key.rstrip("()"))
        or GROUP_LABELS.get(key)
    )
    if labels is None:
        return fallback
    return labels.get(locale) or labels["en"]



def resolve_declared_capability(
    declared: str, cap_index: dict[str, dict]
) -> dict:
    """Map a plugin.json capability id to verification capability ids + pins.

    Exact matches win; otherwise the curated alias table applies; otherwise a
    token-subset fallback tries the id's tail tokens against known ids.
    Unknown capability ids resolve to nothing rather than the empty set.
    """
    if declared in cap_index:
        return {"capabilities": [declared], "pins": [], "source": "exact"}
    alias = DECLARED_CAPABILITY_ALIASES.get(declared)
    if alias:
        caps = [c for c in alias.get("capabilities", []) if c in cap_index]
        return {
            "capabilities": caps,
            "pins": alias.get("pins", []),
            "source": "alias",
        }
    noise = {"cubism", "turboism", "ui"}
    tokens = set(declared.split(".")) - noise
    if tokens:
        matches = sorted(
            cid for cid in cap_index if tokens <= set(cid.split(".")) - noise
        )
        if 0 < len(matches) <= 4:
            return {"capabilities": matches, "pins": [], "source": "tokens"}
    return {"capabilities": [], "pins": [], "source": "unmapped"}


def capability_group(cap_id: str) -> str:
    for prefix, group in CAPABILITY_GROUP_RULES:
        if cap_id.startswith(prefix):
            return group
    return "misc"


def admitted_versions(
    cap_id: str,
    caps: dict[str, dict],
    gates: dict[str, list[dict]],
) -> set[str]:
    """Verified versions minus literal-contract narrowing."""
    verified = {
        v
        for v, status in caps.get(cap_id, {}).get("verified", {}).items()
        if status == "VERIFIED_STATIC"
    }
    gate_versions = [
        set(g["versions"]) for g in gates.get(cap_id, []) if g["versions"]
    ]
    if gate_versions:
        # Multiple contracts covering one capability are alternative admission
        # paths, so their sets union before intersecting the verified set.
        admitted = verified & set.union(*gate_versions)
        return admitted if admitted else verified
    return verified



def classify_gap(
    missing: set[str],
    versions: set[str],
    exceptions: list[dict],
    *,
    key: str | None = None,
    narrowed: bool = False,
    pins: set[str] | None = None,
    source_files: set[str] | None = None,
    capabilities: set[str] | None = None,
    task_site: str | None = None,
    boundaries: dict[str, set[str]] | None = None,
) -> dict[str, str]:
    """Classify every missing version.

    - ``boundary``: proven — the feature is in ``version-boundaries.json``
      for this key, or an evidence-sanctioned monotonicity exception covers
      it (the Cubism version itself lacks the feature).
    - ``pending``: a sanctioned ``pending`` exception covers it, or the
      capability is narrowed by a selector contract / class pin — the feature
      exists, adaptation is simply not wired for that version yet.
    - ``unknown``: no evidence either way (待确认). Missing versions are
      never assumed to be boundaries.
    """
    pins = pins or set()
    source_files = source_files or set()
    capabilities = capabilities or set()
    boundaries = boundaries or {}
    result: dict[str, str] = {}
    for version in sorted(missing, key=version_key):
        if key is not None and (
            version in boundaries.get(key, set())
            or version in boundaries.get(key.rstrip("()"), set())
        ):
            result[version] = "boundary"
            continue
        matched: str | None = None
        for exc in exceptions:
            kind = exc["kind"]
            if kind == "verification-record":
                # `missing` lists capability ids, not versions; the site
                # transition "a>b" names the version at which they dropped.
                transition = re.findall(r"\d+\.\d+\.\d+", exc["site"] or "")
                applies = (
                    bool(capabilities & exc["missing"])
                    and transition[-1:] == [version]
                )
            else:
                if version not in exc["missing"]:
                    continue
                if kind == "class-pins":
                    applies = (exc["site"] or "").removesuffix(".json") in pins
                elif kind == "host-validation-task":
                    applies = exc["site"] == task_site
                else:
                    # java-set / java-file / java-annotation: match the
                    # declaring file, or an identical observed version set.
                    site_set = set(
                        re.findall(r"\d+\.\d+\.\d+", exc["site"] or "")
                    )
                    applies = (
                        exc["path"] in source_files or site_set == versions
                    )
            if applies:
                matched = "pending" if exc["pending"] else "boundary"
                break
        if matched is None and any(
            version in boundaries.get(c, set()) for c in capabilities
        ):
            matched = "boundary"
        if matched is None and narrowed:
            # A selector contract or pin table narrows the capability: the
            # feature exists, the admission for this version is just not
            # wired yet — adaptation pending, not a host boundary.
            matched = "pending"
        result[version] = matched or "unknown"
    return result



def _note(key: str, declared, versions, reviewed_set, source, **extra) -> dict:
    note = {
        "key": key,
        "declaredCapability": declared,
        "versions": sorted(versions, key=version_key),
        "missingOn": sorted(reviewed_set - set(versions), key=version_key),
        "source": source,
    }
    note.update(extra)
    return note


def build_matrix(root: Path) -> dict:
    reviewed = load_reviewed_versions(root)
    reviewed_set = set(reviewed)
    caps = load_capabilities(root)
    gates = load_contract_gates(root)
    required_caps = load_required_capabilities(root)
    sdk_entries, accessor_map = parse_sdk_annotations(root, reviewed)
    type_annotations = {
        e["api"]: set(e["versions"]) for e in sdk_entries if e["kind"] == "type"
    }
    commands = load_commands(root, reviewed)
    class_pins = load_class_pins(root)
    runtime_gates = load_runtime_gates(root)
    campaign = load_campaign(root, reviewed)
    exceptions = load_monotonicity_exceptions(root)
    boundaries = load_version_boundaries(root)
    timeline = load_official_timeline(root)
    timeline_index = index_official_timeline(timeline)
    boundary_entries = _load_boundary_entries(root)
    boundary_sources = {
        b["id"]: b["officialSource"]
        for b in boundary_entries
        if "officialSource" in b
    }
    for b in boundary_entries:
        if "officialSource" not in b:
            feat = timeline_index.get(b["id"]) or timeline_index.get(b["id"].rstrip("()"))
            if feat:
                b["officialSource"] = {
                    "version": feat["introducedIn"],
                    "url": feat["url"],
                    "nameZh": feat.get("nameZh"),
                    "nameEn": feat.get("nameEn"),
                }
    for command in commands:
        command["missingNature"] = classify_gap(
            reviewed_set - set(command["versions"]),
            set(command["versions"]),
            exceptions,
            key=f"command:{command['name']}",
            boundaries=boundaries,
        )
        feat = (
            timeline_index.get(f"command:{command['name']}")
            or timeline_index.get(command["name"])
        )
        if feat:
            command["officialSource"] = {
                "version": feat["introducedIn"],
                "url": feat["url"],
                "nameZh": feat.get("nameZh"),
                "nameEn": feat.get("nameEn"),
            }
        elif f"command:{command['name']}" in boundary_sources:
            command["officialSource"] = boundary_sources[f"command:{command['name']}"]

    admitted = {cap: admitted_versions(cap, caps, gates) for cap in caps}

    groups: dict[str, list[str]] = {}
    for cap in caps:
        groups.setdefault(capability_group(cap), []).append(cap)

    plugins: list[dict] = []
    for plugin in load_plugins(root):
        functional_sets: list[set[str]] = []
        feature_notes: list[dict] = []
        unmapped: list[str] = []
        for declared in plugin["capabilities"]:
            resolved = resolve_declared_capability(declared, caps)
            cap_sets = [admitted[c] for c in resolved["capabilities"]]
            pin_sets = [
                set(class_pins[pin]["versions"])
                for pin in resolved["pins"]
                if pin in class_pins
            ]
            sets = cap_sets + pin_sets
            if not sets:
                if not declared.startswith(NON_FUNCTIONAL_PREFIXES):
                    unmapped.append(declared)
                continue
            available = set.intersection(*sets)
            if not declared.startswith(NON_FUNCTIONAL_PREFIXES):
                functional_sets.append(available)
            if available and available != reviewed_set:
                key = (
                    resolved["capabilities"][0]
                    if len(resolved["capabilities"]) == 1
                    else declared
                )
                gate_files = sorted(
                    {
                        g["source"].split(":")[0]
                        for c in resolved["capabilities"]
                        for g in gates.get(c, [])
                    }
                )
                feature_notes.append(
                    _note(
                        key,
                        declared,
                        available,
                        reviewed_set,
                        "capability",
                        pins=sorted(resolved["pins"]),
                        capabilities=resolved["capabilities"],
                        sourceFiles=gate_files,
                        narrowed=bool(gate_files or pin_sets),
                    )
                )
        # Capability namespaces named after the plugin directory are the
        # plugin's feature itself (e.g. cubism.protected-export.*).
        for cap in caps:
            if f".{plugin['directory']}." in f".{cap}.":
                functional_sets.append(admitted[cap])
                if admitted[cap] != reviewed_set:
                    feature_notes.append(
                        _note(
                            cap,
                            None,
                            admitted[cap],
                            reviewed_set,
                            "family-prefix",
                            capabilities=[cap],
                            sourceFiles=sorted(
                                g["source"].split(":")[0]
                                for g in gates.get(cap, [])
                            ),
                            narrowed=bool(gates.get(cap)),
                        )
                    )
        all_java = "\n".join(plugin["javaTexts"])
        seen_keys: set[str] = set()
        ref_set = set(plugin["sdkRefs"])
        # Accessor api -> the return type it produces; a facade method whose
        # return type the plugin references is already counted via that type.
        accessor_return: dict[str, str] = {}
        for return_fqn, accessors in accessor_map.items():
            for accessor in accessors:
                accessor_return[accessor["api"]] = return_fqn
        for ref in plugin["sdkRefs"]:
            constraints: list[tuple[str, set[str], str | None]] = []
            if ref in type_annotations:
                ref_file = next(
                    e["file"] for e in sdk_entries if e["api"] == ref
                )
                constraints.append((ref, type_annotations[ref], ref_file))
            for accessor in accessor_map.get(ref, []):
                method_name = accessor["api"].split("#")[-1].rstrip("()")
                if not re.search(rf"\.{re.escape(method_name)}\s*\(", all_java):
                    continue
                # The surfaced feature is the accessor's return type, so the
                # note keys on the referenced type rather than the accessor.
                constraints.append(
                    (ref, set(accessor["versions"]), accessor["file"])
                )
            for entry in sdk_entries:
                if entry["kind"] != "method" or entry["api"].split("#")[0] != ref:
                    continue
                if accessor_return.get(entry["api"]) in ref_set:
                    # Accessor methods are already counted via the type they
                    # return; listing them again would duplicate the note.
                    continue
                member = entry["api"].split("#")[-1].rstrip("()")
                if re.search(rf"\.{re.escape(member)}\s*\(", all_java):
                    constraints.append(
                        (entry["api"], set(entry["versions"]), entry["file"])
                    )
            req = required_caps.get(ref)
            if req:
                narrowed = set(reviewed)
                for cap in req:
                    narrowed &= admitted.get(cap, set())
                if narrowed != reviewed_set:
                    constraints.append((ref, narrowed, str(INTERCEPTOR)))
            for api, versions, file in constraints:
                if not versions or versions == reviewed_set or api in seen_keys:
                    continue
                seen_keys.add(api)
                feature_notes.append(
                    _note(
                        api,
                        None,
                        versions,
                        reviewed_set,
                        "sdk-api",
                        sourceFiles=[file] if file else [],
                    )
                )
        coverage_groups: dict[tuple[str, str], dict] = {}
        for entry in plugin["coverage"]:
            versions = set(entry.get("supportedVersions", [])) & reviewed_set
            if not versions or versions == reviewed_set:
                continue
            endpoint = entry.get("endpoint") or "?"
            operation = entry.get("operation") or "?"
            grouped = coverage_groups.setdefault(
                endpoint, {"versions": set(versions), "methods": [], "ops": []}
            )
            grouped["versions"] &= versions
            grouped["ops"].append(operation)
            method = entry.get("sdkMethod", "")
            if method:
                head = re.sub(r"\(.*", "", method)
                grouped["methods"].append(head)
        for endpoint, grouped in coverage_groups.items():
            feature_notes.append(
                _note(
                    endpoint,
                    None,
                    grouped["versions"],
                    reviewed_set,
                    "coverage-manifest",
                    endpoint=endpoint,
                    operations=sorted(set(grouped["ops"])),
                    methods=sorted(set(grouped["methods"])),
                )
            )
        if functional_sets:
            supported = set.union(*functional_sets)
            full_feature = set.intersection(*functional_sets)
        else:
            supported = set(reviewed)
            full_feature = set(reviewed)
        merged: dict[str, dict] = {}
        for note in feature_notes:
            key = note["key"]
            existing = merged.get(key)
            if existing is None:
                merged[key] = dict(note)
            else:
                existing["versions"] = sorted(
                    set(existing["versions"]) & set(note["versions"]),
                    key=version_key,
                )
                existing["missingOn"] = sorted(
                    reviewed_set - set(existing["versions"]), key=version_key
                )
                existing["source"] = f'{existing["source"]}+{note["source"]}'
                for scope_key in ("pins", "capabilities", "sourceFiles"):
                    existing[scope_key] = sorted(
                        set(existing.get(scope_key, []))
                        | set(note.get(scope_key, []))
                    )
        for note in merged.values():
            note["missingNature"] = classify_gap(
                set(note["missingOn"]),
                set(note["versions"]),
                exceptions,
                key=note["key"],
                narrowed=bool(note.get("narrowed")),
                pins=set(note.get("pins", [])),
                source_files=set(note.get("sourceFiles", [])),
                capabilities=set(note.get("capabilities", [])),
                task_site=plugin["directory"],
                boundaries=boundaries,
            )
            feat = (
                timeline_index.get(note["key"])
                or timeline_index.get(note["key"].rstrip("()"))
            )
            if not feat:
                for cap in note.get("capabilities", []):
                    feat = timeline_index.get(cap)
                    if feat:
                        break
            if feat:
                note["officialSource"] = {
                    "version": feat["introducedIn"],
                    "url": feat["url"],
                    "nameZh": feat.get("nameZh"),
                    "nameEn": feat.get("nameEn"),
                }
            elif note["key"] in boundary_sources:
                note["officialSource"] = boundary_sources[note["key"]]
            elif note["key"].rstrip("()") in boundary_sources:
                note["officialSource"] = boundary_sources[note["key"].rstrip("()")]
        supported_gaps = classify_gap(
            reviewed_set - supported,
            supported,
            exceptions,
            narrowed=any(n.get("narrowed") for n in merged.values()),
            pins={
                pin for note in merged.values() for pin in note.get("pins", [])
            },
            source_files={
                f
                for note in merged.values()
                for f in note.get("sourceFiles", [])
            },
            capabilities={
                c
                for note in merged.values()
                for c in note.get("capabilities", [])
            },
            task_site=plugin["directory"],
            boundaries=boundaries,
        )
        tasks = {
            task: versions
            for task, versions in campaign.items()
            if TASK_PLUGIN_ALIASES.get(task_base(task)) == plugin["directory"]
        }
        plugins.append(
            {
                "id": plugin["id"],
                "name": plugin["name"],
                "directory": plugin["directory"],
                "requiresCubism": plugin["requiresCubism"],
                "supportedVersions": sorted(supported, key=version_key),
                "supportedGaps": supported_gaps,
                "fullFeatureVersions": sorted(full_feature, key=version_key),
                "partialFeatures": sorted(
                    merged.values(),
                    key=lambda n: (len(n["versions"]), n["key"]),
                ),
                "unmappedCapabilities": sorted(unmapped),
                "hostValidation": tasks,
            }
        )

    capability_rows = [
        {
            "id": cap,
            "group": capability_group(cap),
            "families": caps[cap]["families"],
            "verified": sorted(caps[cap]["verified"], key=version_key),
            "admitted": sorted(admitted[cap], key=version_key),
            "narrowedBy": [
                {"source": g["source"], "versions": g["versions"]}
                for g in gates.get(cap, [])
            ],
        }
        for cap in sorted(caps)
    ]

    sdk_constraints = [
        {
            "api": e["api"],
            "kind": e["kind"],
            "versions": e["versions"],
            "file": e["file"],
        }
        for e in sdk_entries
        if set(e["versions"]) != reviewed_set
    ]

    return {
        "format": MATRIX_FORMAT,
        "generatorVersion": GENERATOR_VERSION,
        "generator": "scripts/generate_compatibility_matrix.py",
        "reviewedVersions": reviewed,
        "capabilities": capability_rows,
        "capabilityGroups": [
            {
                "id": group,
                "capabilities": sorted(members),
                "label": GROUP_LABELS.get(group, {}),
            }
            for group, members in sorted(groups.items())
        ],
        "commands": commands,
        "sdkApiConstraints": sorted(sdk_constraints, key=lambda e: e["api"]),
        "runtimeGates": sorted(runtime_gates, key=lambda g: (g["file"], g["symbol"])),
        "classPins": [
            {"name": name, "versions": pin["versions"], "loadedBy": pin["loadedBy"]}
            for name, pin in sorted(class_pins.items())
        ],
        "plugins": plugins,
        "hostValidation": {task: v for task, v in sorted(campaign.items())},
        "taskPluginAliases": TASK_PLUGIN_ALIASES,
        "taskGroupAliases": TASK_GROUP_ALIASES,
        "versionBoundaries": boundary_entries,
        "officialTimeline": timeline,
    }


