"""Manifest, catalog, and template wiring for admitted Cubism versions."""
from __future__ import annotations

import json
from pathlib import Path
import re

from turboism_dev.versions import cubism_version_key, dotted_to_compact, version_tag

from .constants import (
    ALL_VERSIONS,
    CATALOG_SLICES,
    FAMILY_JSON,
    REVIEWED,
    ROOT,
    TEMPLATE_DIR,
    VERIFICATION_DIR,
)

def vtag(version: str) -> str:
    return version_tag(version)


def vcompact(version: str) -> str:
    return dotted_to_compact(version)


def emitted_records() -> dict[str, dict[str, str]]:
    """{family: {version: record filename}} for every on-disk record."""
    out: dict[str, dict[str, str]] = {}
    for path in sorted(VERIFICATION_DIR.glob("cubism-*.json")):
        match = re.match(r"cubism-(\d+\.\d+\.\d+)-(.+)\.json", path.name)
        if match:
            out.setdefault(match.group(2), {})[match.group(1)] = path.name
    return out


def emitted_record(family: str, version: str) -> dict:
    return json.loads(
        (VERIFICATION_DIR / f"cubism-{version}-{family}.json").read_text()
    )


def wire_catalog_json(records: dict[str, dict[str, str]]) -> None:
    path = FAMILY_JSON / "compatibility-catalog.json"
    document = json.loads(path.read_text())
    names = set(document["records"])
    for slice_id in CATALOG_SLICES:
        names.update((records.get(slice_id) or {}).values())
    document["records"] = sorted(names)
    literals = set(document["extraLiterals"])
    literals.update(names)
    document["extraLiterals"] = sorted(literals)
    path.write_text(json.dumps(document, indent=1) + "\n")


def wire_family_manifests(records: dict[str, dict[str, str]]) -> None:
    for family_path in sorted(FAMILY_JSON.glob("*.json")):
        family = json.loads(family_path.read_text())
        name = family["family"]
        if name == "compatibility-catalog":
            continue
        bound = set(family["records"])
        bound.update((records.get(name) or {}).values())
        # recent-preview binds the embedded-panel + project-workspace records it
        # resolves across; keep that coupling for every admitted version.
        if name == "recent-preview":
            for dep in ("ui-embedded-panel", "project-workspace"):
                bound.update((records.get(dep) or {}).values())
        family["records"] = sorted(bound)
        if name == "protected-export":
            packs = set(family.get("packs") or [])
            for version in records.get(name) or {}:
                packs.add(f"cubism-{version}-protected-export.json")
            family["packs"] = sorted(packs)
        family_path.write_text(json.dumps(family, indent=1) + "\n")


def candidate_constant(slice_id: str, version: str, record_name: str) -> str:
    tag = vtag(version).upper()
    const = slice_id.replace("-", "_").upper()
    return (
        f"    private static final Candidate C_{const}_{tag} = new Candidate(\n"
        f'        "{slice_id}",\n'
        f'        "${{record:{record_name}:cubismVersion}}",\n'
        f'        "{record_name}",\n'
        f'        "${{record:{record_name}:sha256}}",\n'
        f'        "${{record:{record_name}:verificationId}}",\n'
        f'        "${{record:{record_name}:adapterSliceId}}",\n'
        f'        "${{record:{record_name}:profileId}}"\n'
        f"    );\n"
    )


def wire_slice_catalog(records: dict[str, dict[str, str]]) -> None:
    path = TEMPLATE_DIR / "selector/CubismSliceCatalog.java"
    text = path.read_text()
    start = text.index("    private static final Candidate C_")
    end = text.index("    private static final Map<String, Candidate[]> BY_SLICE")
    blocks = []
    arrays = []
    for slice_id in CATALOG_SLICES:
        versions = sorted(
            records.get(slice_id, {}), key=cubism_version_key
        )
        names = []
        for version in versions:
            record_name = records[slice_id][version]
            tag = vtag(version).upper()
            const = slice_id.replace("-", "_").upper()
            blocks.append(candidate_constant(slice_id, version, record_name))
            names.append(f"C_{const}_{tag}")
        arrays.append(
            "    private static final Candidate[] CANDIDATES_"
            + slice_id.replace("-", "_").upper()
            + " = {\n        "
            + ",\n        ".join(names)
            + "\n    };\n"
        )
    text = text[:start] + "".join(blocks) + "\n" + "\n".join(arrays) + "\n" + text[end:]
    path.write_text(text)


def _version_const(version: str, record_name: str) -> str:
    return (
        f"    public static final String CUBISM_VERSION_{vtag(version)} = "
        f'"${{record:{record_name}:cubismVersion}}";'
    )


def _record_const(version: str, record_name: str) -> str:
    return (
        f"    public static final ReviewedSliceRecord RECORD_{vtag(version)} = "
        "new ReviewedSliceRecord(\n"
        f"        ReviewedHostArtifacts.CUBISM_{vtag(version)},\n"
        f'        "${{record:{record_name}:verificationId}}",\n'
        f'        "${{record:{record_name}:sha256}}",\n'
        f"        CUBISM_VERSION_{vtag(version)},\n"
        f'        "${{record:{record_name}:profileId}}"\n'
        "    );"
    )


def wire_record_style_manifest(
    template: str, family: str, versions: list[str], slice_label: str
) -> None:
    """Rebuild the CUBISM_VERSION/RECORD/RECORDS region of Record-style
    manifests (clipmask, status-bar, project-workspace, top-menu,
    embedded-panel, main-toolbar) plus ``reviewedCubismVersions``."""
    path = TEMPLATE_DIR / template
    text = path.read_text()
    # Rebuild the constant region: it spans the first CUBISM_VERSION const
    # through the RECORDS list terminator.
    first = re.search(
        r"    /\*\* Cubism version[^/]*\*/\n    public static final String CUBISM_VERSION_",
        text,
    )
    if first is None:
        first = re.search(r"    public static final String CUBISM_VERSION_", text)
    records_end = re.search(
        r"    private static final List<ReviewedSliceRecord> RECORDS = List\.of\([^;]*\);",
        text,
    )
    assert first and records_end, template
    chunk = []
    for version in versions:
        record_name = f"cubism-{version}-{family}.json"
        chunk.append(
            f"    /** Cubism version reported for the reviewed {version} artifact. */\n"
            + _version_const(version, record_name)
        )
    chunk.append("")
    for version in versions:
        record_name = f"cubism-{version}-{family}.json"
        chunk.append(
            f"    /** Reviewed record admitted for exact Cubism {version}. */\n"
            + _record_const(version, record_name)
        )
    list_entries = ",\n        ".join(f"RECORD_{vtag(v)}" for v in versions)
    chunk.append(
        "    private static final List<ReviewedSliceRecord> RECORDS = List.of(\n"
        f"        {list_entries}\n    );"
    )
    text = text[: first.start()] + "\n".join(chunk) + "\n" + text[records_end.end() :]
    # reviewedCubismVersions() if present
    match = re.search(
        r"return Set\.of\(\n?(\s*CUBISM_VERSION_\d_\d+_\d+,?)+\n?\s*\);", text
    )
    if match:
        repl = (
            "return Set.of(\n            "
            + ",\n            ".join(f"CUBISM_VERSION_{vtag(v)}" for v in versions)
            + "\n        );"
        )
        text = text[: match.start()] + repl + text[match.end() :]
    path.write_text(text)


def wire_arm_style_manifest(
    template: str,
    family: str,
    versions: list[str],
    fail_label: str,
    with_slice: bool = True,
) -> None:
    """Workspace-control / control-appearance style: per-version
    ``manifest(...)`` arms inside ``forArtifact``."""
    path = TEMPLATE_DIR / template
    text = path.read_text()
    start = text.index(
        "    static PinnedVerifiedResolverWorkflow.Manifest forArtifact("
    )
    brace = text.index("{", start)
    depth = 0
    index = brace
    while True:
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                break
        index += 1
    arms = []
    for version in versions:
        record_name = f"cubism-{version}-{family}.json"
        if with_slice:
            tail = (
                f"                ReviewedHostArtifacts.CUBISM_{vtag(version)}.size(), artifact.sha256(),\n"
                f'                "${{record:{record_name}:adapterSliceId}}"\n'
            )
        else:
            tail = (
                f"                ReviewedHostArtifacts.CUBISM_{vtag(version)}.size(),\n"
                f"                ReviewedHostArtifacts.CUBISM_{vtag(version)}.sha256()\n"
            )
        arms.append(
            f"        if (ReviewedHostArtifacts.CUBISM_{vtag(version)}.equals(artifact)) {{\n"
            "            return manifest(\n"
            f'                "${{record:{record_name}:verificationId}}",\n'
            f'                "${{record:{record_name}:sha256}}",\n'
            f'                ReviewedHostArtifacts.CUBISM_{vtag(version)}_VERSION, "${{record:{record_name}:profileId}}",\n'
            + tail
            + "            );\n"
            "        }"
        )
    body = (
        "forArtifact(final HostArtifactDigest artifact) {\n"
        + "\n".join(arms)
        + f'\n        throw new IllegalArgumentException("host artifact is not a reviewed Cubism {fail_label} artifact");\n    }}'
    )
    text = (
        text[:start]
        + "    static PinnedVerifiedResolverWorkflow.Manifest "
        + body
        + text[index + 1 :]
    )
    path.write_text(text)


def wire_autobackup(versions: list[str]) -> None:
    path = TEMPLATE_DIR / "AutoBackupVerificationManifest.java"
    text = path.read_text()
    # constants region: from first VERIFICATION_ID const to the last ARTIFACT_SHA256 line before the javadoc
    start = text.index("    public static final String VERIFICATION_ID_")
    end_marker = text.index(
        "    /**\n     * Returns whether this JVM is the exact task-scoped"
    )
    consts = []
    methods = []
    arms = []
    for version in versions:
        record_name = f"cubism-{version}-autobackup.json"
        tag = vtag(version).upper()
        consts.append(
            f'    public static final String VERIFICATION_ID_{tag} = "${{record:{record_name}:verificationId}}";\n'
            f'    public static final String RECORD_SHA256_{tag} =\n        "${{record:{record_name}:sha256}}";\n'
            f'    public static final String CUBISM_VERSION_{tag} = "${{record:{record_name}:cubismVersion}}";\n'
            f'    public static final String PROFILE_ID_{tag} = "${{record:{record_name}:profileId}}";\n'
            f"    public static final long ARTIFACT_SIZE_{tag} = ReviewedHostArtifacts.CUBISM_{vtag(version)}.size();\n"
            f"    public static final String ARTIFACT_SHA256_{tag} = ReviewedHostArtifacts.CUBISM_{vtag(version)}.sha256();\n"
        )
        methods.append(
            f"    private static PinnedVerifiedResolverWorkflow.Manifest manifest{tag}() {{\n"
            "        return new PinnedVerifiedResolverWorkflow.Manifest(\n"
            f"            VERIFICATION_ID_{tag},\n"
            f"            RECORD_SHA256_{tag},\n"
            f"            CUBISM_VERSION_{tag},\n"
            f"            PROFILE_ID_{tag},\n"
            f"            ARTIFACT_SIZE_{tag},\n"
            f"            ARTIFACT_SHA256_{tag},\n"
            "            ADAPTER_SLICE_ID,\n"
            "            CAPABILITY_IDS,\n"
            "            REQUIRED_ALIASES\n"
            "        );\n"
            "    }\n"
        )
        arms.append(
            f"        if (artifact.size() == ARTIFACT_SIZE_{tag}\n"
            f"            && artifact.sha256().equals(ARTIFACT_SHA256_{tag})) {{\n"
            f"            return manifest{tag}();\n"
            "        }"
        )
    text = text[:start] + "\n".join(consts) + text[end_marker:]
    # forArtifact body
    start = text.index(
        "    static PinnedVerifiedResolverWorkflow.Manifest forArtifact("
    )
    brace = text.index("{", start)
    depth, index = 0, brace
    while True:
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                break
        index += 1
    body = (
        "forArtifact(final HostArtifactDigest artifact) {\n"
        + "\n".join(arms)
        + "\n        throw new IllegalArgumentException(\n"
        '            "host artifact is not the reviewed Cubism auto-backup artifact"\n'
        "        );\n    }"
    )
    text = (
        text[:start]
        + "    static PinnedVerifiedResolverWorkflow.Manifest "
        + body
        + text[index + 1 :]
    )
    # manifest methods: strip existing ones, then insert the full rebuilt set
    # before the private ctor.
    text = re.sub(
        r"    private static PinnedVerifiedResolverWorkflow\.Manifest manifest\w*\(\) \{\n(?:.*\n)*?    \}\n\n?",
        "",
        text,
    )
    anchor = "    private AutoBackupVerificationManifest() {"
    idx = text.index(anchor)
    text = text[:idx] + "\n".join(methods) + "\n" + text[idx:]
    path.write_text(text)


def _strip_marked(text: str, key: str) -> str:
    """Remove a previously generated ``// <version-matrix:key>`` block so the
    insert-style wire functions stay idempotent across repeated ``wire`` runs."""
    return re.sub(
        rf"[ ]*// <version-matrix:{key}>\n.*?[ ]*// </version-matrix:{key}>\n*",
        "",
        text,
        flags=re.S,
    )


def _set_literal(name: str, values: list[str]) -> str:
    # Emit a lazy method rather than a static Set.of field: seventeen ~860-item
    # literal sets in one <clinit> exceeds the 64KB per-method bytecode limit.
    items = ",\n            ".join(f'"{v}"' for v in values)
    method = name[0].lower() + name[1:]
    return (
        f"    private static Set<String> {method}() {{\n"
        f"        return Set.of(\n            {items}\n        );\n    }}"
    )


def wire_editor_model(
    versions: list[str],
    template_dir: Path | None = None,
) -> None:
    if template_dir is None:
        template_dir = TEMPLATE_DIR
    path = template_dir / "EditorModelVerificationManifest.java"
    text = path.read_text()
    for key in ("em-consts", "em-sets", "em-arms"):
        text = _strip_marked(text, key)
    # RECORD_<V> consts: insert after RECORD_5_3_03 block
    anchor_end = text.index(
        "    private static final List<ReviewedSliceRecord> RECORDS"
    )
    const_blocks = []
    blocks = []
    for version in versions:
        record_name = f"cubism-{version}-editor-model.json"
        const_blocks.append(
            f"    /** Cubism version reported for the reviewed {version} artifact. */\n"
            + _version_const(version, record_name)
            + "\n"
        )
        blocks.append(
            f"    /** Reviewed Editor-model record admitted for exact Cubism {version}. */\n"
            + _record_const(version, record_name)
            + "\n"
        )
    text = (
        text[:anchor_end]
        + "    // <version-matrix:em-consts>\n"
        + "".join(const_blocks)
        + "".join(blocks)
        + "    // </version-matrix:em-consts>\n\n"
        + text[anchor_end:]
    )
    # RECORDS list
    match = re.search(
        r"private static final List<ReviewedSliceRecord> RECORDS = List\.of\([^;]*\);",
        text,
    )
    all_records = sorted(
        versions + ["5.2.03", "5.3.02", "5.3.03"],
        key=cubism_version_key,
    )
    repl = (
        "private static final List<ReviewedSliceRecord> RECORDS = List.of(\n        "
        + ",\n        ".join(f"RECORD_{vtag(v)}" for v in all_records)
        + "\n    );"
    )
    text = text[: match.start()] + repl + text[match.end() :]
    # per-version literal sets + forArtifact arms
    sets = []
    for version in versions:
        record = emitted_record("editor-model", version)
        caps = sorted(record["capabilityIds"])
        aliases = sorted(s["alias"] for s in record["selectors"])
        sets.append(_set_literal(f"Cubism{vtag(version)}Capabilities", caps))
        sets.append(_set_literal(f"Cubism{vtag(version)}Aliases", aliases))
    anchor = "    static PinnedVerifiedResolverWorkflow.Manifest forArtifact("
    idx = text.index(anchor)
    text = (
        text[:idx]
        + "    // <version-matrix:em-sets>\n"
        + "\n\n".join(sets)
        + "\n    // </version-matrix:em-sets>\n\n"
        + text[idx:]
    )
    # forArtifact arms: insert before the fallthrough `return record.toManifest(`
    arm_anchor = re.search(
        r"(        return record\.toManifest\(\n            ADAPTER_SLICE_ID,\n            ObjectContextMenuVerificationManifest\.capabilities\((?:CAPABILITY_IDS|cubism5302Capabilities\(\))\),)",
        text,
    )
    assert arm_anchor
    arms = []
    for version in versions:
        arms.append(
            f"        if (RECORD_{vtag(version)}.equals(record)) {{\n"
            "            return record.toManifest(\n"
            "                ADAPTER_SLICE_ID,\n"
            f"                cubism{vtag(version)}Capabilities(),\n"
            f"                cubism{vtag(version)}Aliases()\n"
            "            );\n"
            "        }"
        )
    text = (
        text[: arm_anchor.start()]
        + "        // <version-matrix:em-arms>\n"
        + "\n".join(arms)
        + "\n        // </version-matrix:em-arms>\n"
        + text[arm_anchor.start() :]
    )
    path.write_text(text)


def wire_protected_export(versions: list[str]) -> None:
    path = TEMPLATE_DIR / "ProtectedExportVerificationManifest.java"
    text = path.read_text()
    for key in ("pe-consts", "pe-arms"):
        text = _strip_marked(text, key)
    anchor_end = text.index(
        "    private static final List<ReviewedSliceRecord> RECORDS"
    )
    # keep CUBISM_VERSION consts grouped first: place them before RECORD blocks
    const_blocks = []
    rec_blocks = []
    for version in versions:
        record_name = f"cubism-{version}-protected-export.json"
        const_blocks.append(
            f"    /** Cubism version reported for the reviewed {version} artifact. */\n"
            + _version_const(version, record_name)
            + "\n"
        )
        rec_blocks.append(
            f"    /** Reviewed protected-export record admitted for exact Cubism {version}. */\n"
            + _record_const(version, record_name)
            + "\n"
        )
    text = (
        text[:anchor_end]
        + "    // <version-matrix:pe-consts>\n"
        + "".join(const_blocks)
        + "".join(rec_blocks)
        + "    // </version-matrix:pe-consts>\n\n"
        + text[anchor_end:]
    )
    # forArtifact: legacy-surface records (no alias-source family) take
    # cubism52Aliases(); full-surface records take REQUIRED_ALIASES.
    match = re.search(
        r"private static final List<ReviewedSliceRecord> RECORDS = List\.of\([^;]*\);",
        text,
    )
    all_records = sorted(
        versions + ["5.2.03", "5.3.02", "5.3.03"],
        key=cubism_version_key,
    )
    repl = (
        "private static final List<ReviewedSliceRecord> RECORDS = List.of(\n        "
        + ",\n        ".join(f"RECORD_{vtag(v)}" for v in all_records)
        + "\n    );"
    )
    text = text[: match.start()] + repl + text[match.end() :]
    anchor = re.search(r"        if \(RECORD_5_2_03\.equals\(record\)\) \{\n", text)
    assert anchor
    arms = []
    for version in versions:
        record = emitted_record("protected-export", version)
        aliases = {s["alias"] for s in record["selectors"]}
        legacy = "cubism.protected-export.alias-source.class" not in aliases
        alias_expr = "cubism52Aliases()" if legacy else "REQUIRED_ALIASES"
        arms.append(
            f"        if (RECORD_{vtag(version)}.equals(record)) {{\n"
            f"            return record.toManifest(ADAPTER_SLICE_ID, CAPABILITY_IDS, {alias_expr});\n"
            "        }"
        )
    text = (
        text[: anchor.start()]
        + "        // <version-matrix:pe-arms>\n"
        + "\n".join(arms)
        + "\n        // </version-matrix:pe-arms>\n"
        + text[anchor.start() :]
    )
    path.write_text(text)


def wire_bounding_box(versions: list[str]) -> None:
    path = TEMPLATE_DIR / "BoundingBoxOverlayButtonVerificationManifest.java"
    text = path.read_text()
    for key in ("bb-sha", "bb-cases", "bb-admission", "bb-vid"):
        text = _strip_marked(text, key)
    # RECORD_SHA_<T> consts
    last_sha = None
    for m in re.finditer(
        r'    private static final String RECORD_SHA_\w+ = "\$\{record:[^}]+:sha256\}";\n',
        text,
    ):
        last_sha = m.end()
    assert last_sha
    sha_consts = "".join(
        f"    private static final String RECORD_SHA_{vcompact(v)} = "
        f'"${{record:cubism-{v}-ui-bounding-box-overlay.json:sha256}}";\n'
        for v in versions
    )
    text = (
        text[:last_sha]
        + "    // <version-matrix:bb-sha>\n"
        + sha_consts
        + "    // </version-matrix:bb-sha>\n"
        + text[last_sha:]
    )
    # recordSha256ForVersion switch arms
    sw_anchor = "        return switch (cubismVersion) {"
    idx = text.index(sw_anchor) + len(sw_anchor)
    arms = "".join(
        f'            case "${{record:cubism-{v}-ui-bounding-box-overlay.json:cubismVersion}}" -> RECORD_SHA_{vcompact(v)};\n'
        for v in versions
    )
    text = (
        text[:idx]
        + "\n            // <version-matrix:bb-cases>\n"
        + arms
        + "            // </version-matrix:bb-cases>\n"
        + text[idx:]
    )
    # admissionForArtifact arms
    anchor = "        if (ReviewedHostArtifacts.CUBISM_5_3_03.equals(artifact)) {"
    idx = text.index(anchor)
    # find end of that if-block
    end = text.index(
        '        throw new IllegalArgumentException("Unsupported Cubism artifact for overlay buttons");',
        idx,
    )
    arms = "".join(
        f"        if (ReviewedHostArtifacts.CUBISM_{vtag(v)}.equals(artifact)) {{\n"
        "            return new AdmissionEvidence(\n"
        f"                ReviewedHostArtifacts.CUBISM_{vtag(v)}_VERSION, artifact.size(), artifact.sha256()\n"
        "            );\n"
        "        }\n"
        for v in versions
    )
    text = (
        text[:end]
        + "        // <version-matrix:bb-admission>\n"
        + arms
        + "        // </version-matrix:bb-admission>\n"
        + text[end:]
    )
    # forArtifact verificationId switch
    sw = re.search(r"final String verificationId = switch \(cubismVersion\) \{\n", text)
    assert sw
    arms = "".join(
        f'            case "${{record:cubism-{v}-ui-bounding-box-overlay.json:cubismVersion}}" -> "${{record:cubism-{v}-ui-bounding-box-overlay.json:verificationId}}";\n'
        for v in versions
    )
    text = (
        text[: sw.end()]
        + "            // <version-matrix:bb-vid>\n"
        + arms
        + "            // </version-matrix:bb-vid>\n"
        + text[sw.end() :]
    )
    path.write_text(text)


def wire_bootstrap(records: dict[str, dict[str, str]]) -> None:
    path = ROOT / "bootstrap/build.gradle.kts"
    text = path.read_text()
    start = re.search(r'listOf\(\n(\s+"cubism-5\.[^"]+",?\n?)+\s*\)', text)
    assert start
    names = sorted(
        name for family_map in records.values() for name in family_map.values()
    )
    block = (
        "listOf(\n        " + ",\n        ".join(f'"{n}"' for n in names) + "\n    )"
    )
    text = text[: start.start()] + block + text[start.end() :]
    path.write_text(text)


def wire_host_validation() -> None:
    # host_validation.py whitelist
    path = ROOT / "scripts/preview/host_validation.py"
    text = path.read_text()
    whitelist = "{" + ", ".join(f'"{vcompact(v)}"' for v in ALL_VERSIONS) + "}"
    text = re.sub(r"version not in \{[^}]*\}", f"version not in {whitelist}", text)
    path.write_text(text)
    # tasks.json: every task admits the full set except protected-export-scoped
    path = ROOT / "scripts/preview/host-validation-tasks.json"
    document = json.loads(path.read_text())
    full = [vcompact(v) for v in ALL_VERSIONS]
    export_versions = [
        vcompact(v)
        for v in ALL_VERSIONS
        if (VERIFICATION_DIR / f"cubism-{v}-protected-export.json").is_file()
    ]
    for task in document.get("tasks", {}).values():
        description = task.get("description", "")
        task["versions"] = (
            export_versions
            if "protected-export" in description.lower()
            or "export-settings" in description.lower()
            else full
        )
    path.write_text(json.dumps(document, indent=2) + "\n")


def wire_index_md() -> None:
    path = ROOT / "compatibility/cubism/index.md"
    text = path.read_text()
    text = text.replace(
        "exact-version mapping-pack catalogues for 5.2.03, 5.3.02, and 5.3.03.",
        "exact-version mapping-pack catalogues for "
        + ", ".join(ALL_VERSIONS[:-1])
        + f", and {ALL_VERSIONS[-1]}.",
    )
    path.write_text(text)


def wire() -> None:
    records = emitted_records()
    wire_catalog_json(records)
    wire_family_manifests(records)
    wire_slice_catalog(records)
    families = {
        "ClipMaskVerificationManifest.java": "clipmask",
        "StatusBarVerificationManifest.java": "ui-status-bar",
        "ProjectWorkspaceVerificationManifest.java": "project-workspace",
        "TopMenuVerificationManifest.java": "ui-top-menu",
        "EmbeddedPanelVerificationManifest.java": "ui-embedded-panel",
        "MainToolbarVerificationManifest.java": "ui-main-toolbar",
    }
    for template, family in families.items():
        versions = sorted(
            records.get(family, {}), key=cubism_version_key
        )
        wire_record_style_manifest(template, family, versions, family)
    for template, family, label, with_slice in (
        (
            "WorkspaceControlVerificationManifest.java",
            "workspace-control",
            "workspace-control",
            True,
        ),
        (
            "ControlAppearanceVerificationManifest.java",
            "ui-control-appearance",
            "control-appearance",
            False,
        ),
    ):
        versions = sorted(
            records.get(family, {}), key=cubism_version_key
        )
        wire_arm_style_manifest(
            template, family, versions, label, with_slice=with_slice
        )
    wire_autobackup(
        sorted(
            records.get("autobackup", {}),
            key=cubism_version_key,
        )
    )
    wire_bounding_box(
        sorted(
            (
                v
                for v in records.get("ui-bounding-box-overlay", {})
                if v not in REVIEWED
            ),
            key=cubism_version_key,
        )
    )
    wire_editor_model(
        sorted(
            (v for v in records.get("editor-model", {}) if v not in REVIEWED),
            key=cubism_version_key,
        )
    )
    wire_protected_export(
        sorted(
            (v for v in records.get("protected-export", {}) if v not in REVIEWED),
            key=cubism_version_key,
        )
    )
    wire_bootstrap(records)
    wire_host_validation()
    wire_index_md()
    print("wired", sum(len(v) for v in records.values()), "records")


