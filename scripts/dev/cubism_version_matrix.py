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
"""

from __future__ import annotations

import hashlib
import json
import re
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def cubism_ref_dir() -> Path:
    """Locate ``cubism-ref/`` — it lives in the primary checkout, not worktrees."""
    local = ROOT / "cubism-ref"
    if local.is_dir():
        return local
    common = subprocess.run(
        ["git", "-C", str(ROOT), "rev-parse", "--git-common-dir"],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    candidate = Path(common).resolve().parent / "cubism-ref"
    if candidate.is_dir():
        return candidate
    raise SystemExit(
        "cubism-ref not found in worktree or primary checkout; set "
        "TURBOISM_CUBISM_REF"
    )


REF_DIR = cubism_ref_dir()
VERIFICATION_DIR = ROOT / "compatibility/cubism/verification"
PACK_DIR = ROOT / "compatibility/cubism/mapping-packs/draft"
PROFILE_DIR = ROOT / "compatibility/cubism/profiles/draft"
GENERATE = ROOT / "scripts/generate_verification_sources.py"
DEPS_SOURCE = (
    ROOT / "runtime/src/main/java/dev/turboism/mapping/verification/"
    "CapabilitySelectorDependencies.java"
)

REVIEWED = ("5.2.03", "5.3.02", "5.3.03")
TARGET_VERSIONS = (
    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06",
    "5.1.00", "5.1.01", "5.1.02", "5.1.03",
    "5.2.00", "5.2.01", "5.2.02",
    "5.3.00", "5.3.01", "5.3.04",
)
VERIFIED_AT = "2026-09-27T00:00:00Z"
EDITOR_MODEL_SLICE = "adapter.editor-model.readwrite"

# Declared builds read from each exact jar's release declaration class
# (``com/live2d/cubism/h.class``; ``g.class`` on 5.0.x) via the same ldc/putstatic
# ``<clinit>`` scan CubismEditorReleaseDetector performs.
DECLARED_BUILDS = {
    "5.0.00": 500000005, "5.0.01": 500010000, "5.0.02": 500020000,
    "5.0.03": 500030001, "5.0.04": 500040000, "5.0.05": 500050001,
    "5.0.06": 500060001, "5.1.00": 501000004, "5.1.01": 501010000,
    "5.1.02": 501020001, "5.1.03": 501030000, "5.2.00": 502000003,
    "5.2.01": 502010000, "5.2.02": 502020000, "5.2.03": 502030002,
    "5.3.00": 503000005, "5.3.01": 503010000, "5.3.02": 503020001,
    "5.3.03": 503030001, "5.3.04": 503040001,
}

MODEL_BINDING = frozenset({
    "cubism.editor-model.app-controller.instance",
    "cubism.editor-model.app-controller.current-document",
    "cubism.editor-model.modeling-document.class",
    "cubism.editor-model.modeling-document.model-source",
    "cubism.editor-model.model-source.current-instance",
    "cubism.editor-model.model.class",
    "cubism.editor-model.model-source.guid",
    "cubism.editor-model.guid.value",
})

# (alias, target version) -> selector field overrides. Some members keep their
# semantic contract but get a different obfuscated name on one exact version;
# the variant is seeded from the newest reviewed definition, re-verified on the
# target jar, and only adopted when the canonical definition fails.
MEMBER_VARIANTS = {
    ("object-context-menu.workspace.selector", "5.3.04"): {
        "memberName": "ap",
        "mappingId":
            "cubism.mapping.v5_3_04.object_context_menu.method.workspace_selector",
    },
}


def decode_mutf8(data: bytes) -> str:
    """Decode Java modified UTF-8 the way DataInputStream.readUTF does."""
    chars = []
    i = 0
    while i < len(data):
        b = data[i]
        if b < 0x80:
            chars.append(chr(b))
            i += 1
        elif b & 0xE0 == 0xC0:
            chars.append(chr(((b & 0x1F) << 6) | (data[i + 1] & 0x3F)))
            i += 2
        else:
            chars.append(
                chr(
                    ((b & 0x0F) << 12)
                    | ((data[i + 1] & 0x3F) << 6)
                    | (data[i + 2] & 0x3F)
                )
            )
            i += 3
    text = "".join(chars)
    # Supplementary characters are encoded as surrogate pairs; recombine them so
    # name comparisons match readUTF behaviour.
    out = []
    i = 0
    while i < len(text):
        c = text[i]
        if (
            i + 1 < len(text)
            and 0xD800 <= ord(c) <= 0xDBFF
            and 0xDC00 <= ord(text[i + 1]) <= 0xDFFF
        ):
            out.append(
                chr(
                    0x10000
                    + ((ord(c) - 0xD800) << 10)
                    + (ord(text[i + 1]) - 0xDC00)
                )
            )
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def parse_class(data: bytes):
    """Return (access, this_name, super_name, interfaces, fields, methods)."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("bad magic")
    pos = 8
    (cp_count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    utf8 = [None] * cp_count
    cls = [0] * cp_count
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            (ln,) = struct.unpack_from(">H", data, pos)
            pos += 2
            utf8[i] = decode_mutf8(data[pos:pos + ln])
            pos += ln
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag == 7:
            (cls[i],) = struct.unpack_from(">H", data, pos)
            pos += 2
        elif tag in (8, 16, 19, 20):
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f"bad constant-pool tag {tag}")
        i += 1

    def cname(idx):
        if idx <= 0 or idx >= cp_count:
            return None
        ni = cls[idx]
        return utf8[ni] if ni else None

    access, this_i, super_i = struct.unpack_from(">HHH", data, pos)
    pos += 6
    (iface_count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    interfaces = []
    for _ in range(iface_count):
        (ci,) = struct.unpack_from(">H", data, pos)
        pos += 2
        interfaces.append(cname(ci))

    def members():
        nonlocal pos
        (count,) = struct.unpack_from(">H", data, pos)
        pos += 2
        out = []
        for _ in range(count):
            a, n, d = struct.unpack_from(">HHH", data, pos)
            pos += 6
            (ac,) = struct.unpack_from(">H", data, pos)
            pos += 2
            for _ in range(ac):
                pos += 2
                (ln,) = struct.unpack_from(">I", data, pos)
                pos += 4 + ln
            out.append((utf8[n], utf8[d], a))
        return out

    fields = members()
    methods = members()
    return access, cname(this_i), cname(super_i), interfaces, fields, methods


class JarIndex:
    """Class-metadata index over one jar, faithful to StaticSelectorVerifier."""

    def __init__(self, path: Path):
        self.path = path
        self.zf = zipfile.ZipFile(path)
        self.names = set(self.zf.namelist())
        self._cache: dict[str, object] = {}

    def metadata(self, owner: str):
        if owner in self._cache:
            return self._cache[owner]
        entry = owner + ".class"
        if entry not in self.names:
            self._cache[owner] = None
            return None
        try:
            meta = parse_class(self.zf.read(entry))
        except Exception:
            meta = "invalid"
        if meta != "invalid" and meta[1] != owner:
            meta = "invalid"
        self._cache[owner] = meta
        return meta


def verify_selector(jar: JarIndex, sel: dict) -> str:
    owner = sel["ownerInternalName"]
    kind = sel["kind"]
    meta = jar.metadata(owner)
    if meta is None:
        return "CLASS_MISSING"
    if meta == "invalid":
        return "INVALID_CLASS_FILE"
    access, _this, super_name, interfaces, fields, methods = meta
    req = sel.get("requiredAccessFlags", 0)
    forb = sel.get("forbiddenAccessFlags", 0)
    if kind == "class":
        return (
            "VERIFIED_STATIC"
            if (access & req) == req and (access & forb) == 0
            else "ACCESS_MISMATCH"
        )
    if kind == "inherits":
        ancestor = sel["memberName"]
        return (
            "VERIFIED_STATIC"
            if ancestor == super_name or ancestor in interfaces
            else "SUPERTYPE_MISMATCH"
        )
    pool = fields if kind == "field" else methods
    same_name = [m for m in pool if m[0] == sel["memberName"]]
    if not same_name:
        return "MEMBER_MISSING"
    same_desc = [m for m in same_name if m[1] == sel["descriptor"]]
    if not same_desc:
        return "DESCRIPTOR_MISMATCH"
    for _n, _d, a in same_desc:
        if (a & req) == req and (a & forb) == 0:
            return "VERIFIED_STATIC"
    return "ACCESS_MISMATCH"


def verified_aliases(jar: JarIndex, record: dict) -> set[str]:
    return {
        s["alias"]
        for s in record["selectors"]
        if verify_selector(jar, s) == "VERIFIED_STATIC"
    }


# ---------------------------------------------------------------------------
# CapabilitySelectorDependencies mirror
# ---------------------------------------------------------------------------

ALIAS_TABLE_JAVA = r'''
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;

/** Dumps static String/Set constants from compiled selector contracts. */
public final class AliasTable {
    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder("{");
        boolean firstClass = true;
        for (String name : args) {
            Class<?> cls = Class.forName(name);
            String simpleName = name.substring(name.lastIndexOf('.') + 1);
            if (!firstClass) out.append(",");
            firstClass = false;
            out.append("\"").append(simpleName).append("\":{");
            boolean first = true;
            for (Field f : cls.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())
                        || !Modifier.isPublic(f.getModifiers())) continue;
                Object v = f.get(null);
                String json = null;
                if (v instanceof String s) {
                    json = "{\"t\":\"s\",\"v\":" + q(s) + "}";
                } else if (v instanceof Set<?> set) {
                    StringBuilder sb = new StringBuilder(
                        "{\"t\":\"set\",\"v\":[");
                    boolean f2 = true;
                    for (Object e : new TreeSet<>((Set<String>) set)) {
                        if (!f2) sb.append(",");
                        f2 = false;
                        sb.append(q((String) e));
                    }
                    json = sb.append("]}").toString();
                }
                if (json == null) continue;
                if (!first) out.append(",");
                first = false;
                out.append(q(f.getName())).append(":").append(json);
            }
            out.append("}");
        }
        System.out.println(out.append("}"));
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
'''


def contract_constants() -> dict[str, dict[str, object]]:
    """Render + compile the selector contracts and dump their constants."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        subprocess.run(
            [sys.executable, str(GENERATE), str(ROOT), "render",
             "--out", str(tmp_path / "src")],
            check=True, capture_output=True,
        )
        sel_dir = tmp_path / "src/dev/turboism/mapping/verification/selector"
        (tmp_path / "AliasTable.java").write_text(ALIAS_TABLE_JAVA)
        classes = tmp_path / "classes"
        classes.mkdir()
        sources = sorted(sel_dir.glob("*.java"))
        sources.append(
            tmp_path / "src/dev/turboism/mapping/verification/"
            "ObjectContextMenuVerificationManifest.java"
        )
        subprocess.run(
            ["javac", "-d", str(classes),
             str(tmp_path / "AliasTable.java")] + [str(s) for s in sources],
            check=True, capture_output=True,
        )
        src_root = tmp_path / "src"
        names = [
            ".".join(s.relative_to(src_root).with_suffix("").parts)
            for s in sources
        ]
        out = subprocess.run(
            ["java", "-cp", str(classes), "AliasTable"] + names,
            check=True, capture_output=True, text=True,
        ).stdout
    table: dict[str, dict[str, object]] = {}
    for cls, fields in json.loads(out).items():
        table[cls] = {
            name: (v["v"] if v["t"] == "s" else frozenset(v["v"]))
            for name, v in fields.items()
        }
    return table


def editor_requirements(constants: dict) -> dict[str, frozenset]:
    """Replay the add(...) table from CapabilitySelectorDependencies."""
    source = DEPS_SOURCE.read_text(encoding="utf-8")
    table: dict[str, set[str]] = {}

    def resolve(ref: str):
        ref = ref.strip()
        if ref.startswith('"'):
            return ref.strip('"')
        if "." not in ref:
            if ref == "MODEL_BINDING":
                return MODEL_BINDING
            raise ValueError(f"unresolvable dependency operand: {ref}")
        cls, field = ref.split(".", 1)
        return constants[cls][field]

    for match in re.finditer(
        r"add\(result,\s*([^,]+),\s*([^)]+)\)", source
    ):
        cap = resolve(match.group(1))
        aliases = resolve(match.group(2))
        table.setdefault(cap, set()).update(aliases)
    return {k: frozenset(v) for k, v in table.items()}


TYPE_TOKEN = re.compile(r"L([^;]+);")


def required_aliases(
    constants: dict,
    editor: dict,
    selectors: list[dict],
    cubism_version: str,
    capability: str,
    conditions: list[str],
) -> frozenset:
    """Mirror of CapabilitySelectorDependencies.requiredAliases."""
    required = editor.get(capability)
    if required is None:
        return frozenset(s["alias"] for s in selectors)
    aliases = set(required) | set(MODEL_BINDING)
    tex = constants["EditorTextureSelectorContract"]
    if capability == tex["WRITE_CAPABILITY_ID"]:
        aliases.update(
            tex["REMOVE_RAW_IMAGE_5203_ALIASES"]
            if cubism_version == "5.2.03"
            else tex["REMOVE_RAW_IMAGE_ALIASES"]
        )
    inst = constants["EditorModelInstanceReadSelectorContract"]
    if capability == inst["CAPABILITY_ID"] and cubism_version == "5.2.03":
        aliases -= set(inst["ONION_SKIN_ALIASES"])
    session = constants["EditorEditSessionSelectorContract"]
    if capability.startswith("cubism.editor-model.edit."):
        aliases.update(session["SESSION_ADMISSION_REQUIRED_ALIASES"])
        aliases.update(session["EDIT_SESSION_WRITE_ENVELOPE_ALIASES"])
    if "hook:native-edit-begin" in conditions:
        aliases.update(
            constants["EditorHistoryReadSelectorContract"]["REQUIRED_ALIASES"]
        )
        aliases.update(
            constants["EditorHistoryIngressSelectorContract"]["REQUIRED_ALIASES"]
        )
        aliases.add(
            constants["EditorHistoryIngressSelectorContract"][
                "MODELING_EDIT_ENTRY_ALIAS"
            ]
        )
    types = set()
    for selector in selectors:
        if selector["alias"] in aliases:
            types.add(selector["ownerInternalName"])
            descriptor = selector.get("descriptor") or ""
            types.update(TYPE_TOKEN.findall(descriptor))
    changed = True
    while changed:
        changed = False
        for selector in selectors:
            if (
                selector["kind"] == "inherits"
                and selector["ownerInternalName"] in types
                and selector["alias"] not in aliases
            ):
                aliases.add(selector["alias"])
                changed |= selector["memberName"] not in types
                types.add(selector["memberName"])
    return frozenset(aliases)


# ---------------------------------------------------------------------------
# Facts and derivation
# ---------------------------------------------------------------------------


def manifest_hashes() -> dict[str, str]:
    expected = {}
    for line in (REF_DIR / "MANIFEST.txt").read_text().splitlines():
        parts = line.split()
        if len(parts) == 2 and re.fullmatch(r"[0-9a-f]{64}", parts[0]):
            expected[parts[1]] = parts[0]
    return expected


def artifact_facts(version: str, jar_name: str = "Live2D_Cubism.jar") -> dict:
    path = REF_DIR / version / jar_name
    data = path.read_bytes()
    return {
        "name": jar_name,
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }


def load_source_records() -> dict[str, list[tuple[str, dict]]]:
    """Group verification records by family, newest-reviewed first."""
    families: dict[str, list[tuple[str, dict]]] = {}
    for path in sorted(VERIFICATION_DIR.glob("cubism-*.json")):
        doc = json.loads(path.read_text())
        match = re.match(r"cubism-(\d+\.\d+\.\d+)-(.+)\.json", path.name)
        version, family = match.group(1), match.group(2)
        families.setdefault(family, []).append((version, doc))
    for entries in families.values():
        entries.sort(key=lambda item: item[0], reverse=True)
    return families


def family_of(record_name: str) -> str:
    return re.match(r"cubism-\d+\.\d+\.\d+-(.+)\.json", record_name).group(1)


def derive_version(
    version: str,
    families: dict[str, list[dict]],
    editor: dict,
    constants: dict,
) -> dict:
    """Derive the surviving record set for one target version."""
    jar = JarIndex(REF_DIR / version / "Live2D_Cubism.jar")
    derived = {}
    for family, sources in sorted(families.items()):
        if family == "core-model-read":
            continue  # core jars are handled by the core pipeline, if at all
        if (
            sources
            and sources[0][1]["adapterSliceId"] == EDITOR_MODEL_SLICE
        ):
            record = derive_editor_model_record(
                jar, sources, version, family, editor, constants
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
            source, surviving, True, version, family, editor, constants
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
    full_universe = len(chosen) == len(universe)
    merged = [chosen[a] for a in sorted(chosen)]

    merged_surviving = set(chosen)
    conditions_by_cap: dict[str, list[str]] = {}
    cap_order: list[str] = []
    for _source_version, source in sources:
        record_conditions = source.get("capabilityConditions") or {}
        for capability in source["capabilityIds"]:
            if capability in conditions_by_cap:
                continue
            conditions_by_cap[capability] = record_conditions.get(
                capability, []
            )
            cap_order.append(capability)
    capabilities = []
    for capability in cap_order:
        if capability not in editor:
            if not full_universe:
                continue
        else:
            required = required_aliases(
                constants, editor, merged, version,
                capability, conditions_by_cap[capability],
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
    record["artifact"] = artifact_facts(version)
    record["evidencePath"] = (
        f"compatibility/cubism/verification/cubism-{version}-{family}.json"
    )
    record["verifiedAt"] = VERIFIED_AT
    record["selectors"] = merged
    record["capabilityConditions"] = {
        cap: conditions_by_cap[cap] or ["declaredGeneration"]
        for cap in capabilities
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
        selectors = [
            s for s in source["selectors"] if s["alias"] in surviving
        ]
        record_conditions = source.get("capabilityConditions") or {}
        capabilities = []
        for capability in source["capabilityIds"]:
            listed = capability in editor
            if not listed and not full:
                # Unlisted capabilities conservatively require the entire
                # source record; partial evidence can never keep them.
                continue
            required = required_aliases(
                constants, editor, source["selectors"], version,
                capability, record_conditions.get(capability, []),
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
    artifact = artifact_facts(version)
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


def load_source_pack(source_version: str, family: str) -> dict | None:
    for candidate in (
        f"cubism-{source_version}-{pack_name_for(family)}.json",
        f"cubism-{source_version}-{family}.json",
    ):
        path = PACK_DIR / candidate
        if path.is_file():
            return json.loads(path.read_text())
    return None


def load_family_packs(family: str) -> dict[str, dict]:
    """All reviewed packs for a family, keyed by version, newest first."""
    packs = {}
    for version in REVIEWED:
        pack = load_source_pack(version, family)
        if pack is not None:
            packs[version] = pack
    return packs


def synthesize_entry(
    selector: dict, family: str, profile: str, artifact_sha: str,
    uses_intermediary: bool, uses_owner: str | None, uses_review: bool,
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
        "requiredAccessFlags": selector["requiredAccessFlags"],
        "forbiddenAccessFlags": selector["forbiddenAccessFlags"],
    }
    if uses_review:
        entry["x.review"] = {
            "recordAlias": selector["alias"],
            "recordMappingId": selector["mappingId"],
            "exactArtifactSha256": artifact_sha,
            "provenance": (
                "exact classfile verification on the reviewed artifact via "
                "cubism_version_matrix"
            ),
        }
    return entry


def derive_pack(
    source_pack: dict, record: dict, version: str, family: str,
    family_packs: dict[str, dict] | None = None,
    rewrite_provenance: bool = False,
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
    uses_intermediary = any(
        "intermediary" in e for e in source_pack["entries"]
    )
    owners = {
        e["owner"] for e in source_pack["entries"] if "owner" in e
    }
    uses_owner = sorted(owners)[0] if owners else None
    uses_review = any("x.review" in e for e in source_pack["entries"])
    entries = []
    for selector in record["selectors"]:
        template = by_id.get(selector["mappingId"]) or by_alias.get(
            selector["alias"]
        )
        if template is None:
            entry = synthesize_entry(
                selector, family, profile, sha,
                uses_intermediary, uses_owner, uses_review,
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
                "requiredAccessFlags": selector["requiredAccessFlags"],
                "forbiddenAccessFlags": selector["forbiddenAccessFlags"],
            }
            if "x.review" in entry:
                entry["x.review"] = dict(
                    entry["x.review"],
                    recordAlias=selector["alias"],
                    recordMappingId=selector["mappingId"],
                    exactArtifactSha256=sha,
                )
                if rewrite_provenance:
                    entry["x.review"]["provenance"] = (
                        "exact classfile verification on the reviewed "
                        f"{version} artifact via cubism_version_matrix"
                    )
        entries.append(entry)
    pack = dict(source_pack)
    pack["cubismVersion"] = version
    pack["entries"] = entries
    if "metadata" in source_pack:
        record_bytes = json.dumps(record, indent=2).encode() + b"\n"
        metadata = dict(
            source_pack["metadata"],
            inventoryRef=record["evidencePath"],
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
                r"\d+\.\d+\.\d+", version,
                str(metadata["validationNotes"]),
            )
        pack["metadata"] = metadata
    return pack


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
        for candidate in sorted(
            VERIFICATION_DIR.glob(f"cubism-{version}-*.json")
        ):
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
        if fixed["entries"] != pack["entries"] or fixed.get(
            "metadata"
        ) != pack.get("metadata"):
            path.write_text(json.dumps(fixed, indent=2) + "\n")
            repaired.append(path.name)
    return repaired


# ---------------------------------------------------------------------------
# Shared restatement wiring
# ---------------------------------------------------------------------------

ALL_VERSIONS = tuple(
    sorted(TARGET_VERSIONS + REVIEWED, key=lambda v: tuple(int(p) for p in v.split(".")))
)
CATALOG_SLICES = (
    "autobackup",
    "clipmask",
    "core-model-read",
    "editor-model",
    "performance-render-scene",
    "project-workspace",
    "ui-bounding-box-overlay",
    "ui-control-appearance",
    "ui-embedded-panel",
    "ui-main-toolbar",
    "ui-status-bar",
    "ui-top-menu",
    "workspace-control",
)
# Record filename suffix -> CubismSliceCatalog slice id (identical today, kept
# explicit so a future rename can never silently misroute candidates).
SLICE_OF = {name: name for name in CATALOG_SLICES}

FAMILY_JSON = ROOT / "scripts/verification-sources/families"
TEMPLATE_DIR = (
    ROOT / "scripts/verification-sources/templates/dev/turboism/mapping/verification"
)


def vtag(version: str) -> str:
    return version.replace(".", "_")


def vcompact(version: str) -> str:
    return version.replace(".", "")


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
            for version in (records.get(name) or {}):
                packs.add(f"cubism-{version}-protected-export.json")
            family["packs"] = sorted(packs)
        family_path.write_text(json.dumps(family, indent=1) + "\n")


def candidate_constant(slice_id: str, version: str, record_name: str) -> str:
    tag = vtag(version).upper()
    const = slice_id.replace("-", "_").upper()
    return (
        f'    private static final Candidate C_{const}_{tag} = new Candidate(\n'
        f'        "{slice_id}",\n'
        f'        "${{record:{record_name}:cubismVersion}}",\n'
        f'        "{record_name}",\n'
        f'        "${{record:{record_name}:sha256}}",\n'
        f'        "${{record:{record_name}:verificationId}}",\n'
        f'        "${{record:{record_name}:adapterSliceId}}",\n'
        f'        "${{record:{record_name}:profileId}}"\n'
        f'    );\n'
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
            records.get(slice_id, {}), key=lambda v: tuple(int(p) for p in v.split("."))
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
            + " = {\n        " + ",\n        ".join(names) + "\n    };\n"
        )
    text = text[:start] + "".join(blocks) + "\n" + "\n".join(arrays) + "\n" + text[end:]
    path.write_text(text)


def _version_const(version: str, record_name: str) -> str:
    return (
        f'    public static final String CUBISM_VERSION_{vtag(version)} = '
        f'"${{record:{record_name}:cubismVersion}}";'
    )


def _record_const(version: str, record_name: str) -> str:
    return (
        f'    public static final ReviewedSliceRecord RECORD_{vtag(version)} = '
        'new ReviewedSliceRecord(\n'
        f'        ReviewedHostArtifacts.CUBISM_{vtag(version)},\n'
        f'        "${{record:{record_name}:verificationId}}",\n'
        f'        "${{record:{record_name}:sha256}}",\n'
        f'        CUBISM_VERSION_{vtag(version)},\n'
        f'        "${{record:{record_name}:profileId}}"\n'
        '    );'
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
    first = re.search(r'    /\*\* Cubism version[^/]*\*/\n    public static final String CUBISM_VERSION_', text)
    if first is None:
        first = re.search(r'    public static final String CUBISM_VERSION_', text)
    records_end = re.search(
        r'    private static final List<ReviewedSliceRecord> RECORDS = List\.of\([^;]*\);',
        text,
    )
    assert first and records_end, template
    chunk = []
    for version in versions:
        record_name = f"cubism-{version}-{family}.json"
        chunk.append(
            f'    /** Cubism version reported for the reviewed {version} artifact. */\n'
            + _version_const(version, record_name)
        )
    chunk.append("")
    for version in versions:
        record_name = f"cubism-{version}-{family}.json"
        chunk.append(
            f'    /** Reviewed record admitted for exact Cubism {version}. */\n'
            + _record_const(version, record_name)
        )
    list_entries = ",\n        ".join(f"RECORD_{vtag(v)}" for v in versions)
    chunk.append(
        "    private static final List<ReviewedSliceRecord> RECORDS = List.of(\n"
        f"        {list_entries}\n    );"
    )
    text = text[: first.start()] + "\n".join(chunk) + "\n" + text[records_end.end():]
    # reviewedCubismVersions() if present
    match = re.search(
        r'return Set\.of\(\n?(\s*CUBISM_VERSION_\d_\d+_\d+,?)+\n?\s*\);', text
    )
    if match:
        repl = "return Set.of(\n            " + ",\n            ".join(
            f"CUBISM_VERSION_{vtag(v)}" for v in versions
        ) + "\n        );"
        text = text[: match.start()] + repl + text[match.end():]
    path.write_text(text)


def wire_arm_style_manifest(
    template: str, family: str, versions: list[str], fail_label: str,
    with_slice: bool = True,
) -> None:
    """Workspace-control / control-appearance style: per-version
    ``manifest(...)`` arms inside ``forArtifact``."""
    path = TEMPLATE_DIR / template
    text = path.read_text()
    start = text.index("    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(")
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
                f'                ReviewedHostArtifacts.CUBISM_{vtag(version)}.size(), artifact.sha256(),\n'
                f'                "${{record:{record_name}:adapterSliceId}}"\n'
            )
        else:
            tail = (
                f'                ReviewedHostArtifacts.CUBISM_{vtag(version)}.size(),\n'
                f'                ReviewedHostArtifacts.CUBISM_{vtag(version)}.sha256()\n'
            )
        arms.append(
            f'        if (ReviewedHostArtifacts.CUBISM_{vtag(version)}.equals(artifact)) {{\n'
            '            return manifest(\n'
            f'                "${{record:{record_name}:verificationId}}",\n'
            f'                "${{record:{record_name}:sha256}}",\n'
            f'                ReviewedHostArtifacts.CUBISM_{vtag(version)}_VERSION, "${{record:{record_name}:profileId}}",\n'
            + tail +
            '            );\n'
            '        }'
        )
    body = (
        "forArtifact(final HostArtifactDigest artifact) {\n"
        + "\n".join(arms)
        + f'\n        throw new IllegalArgumentException("host artifact is not a reviewed Cubism {fail_label} artifact");\n    }}'
    )
    text = text[:start] + "    static PinnedVerifiedResolverWorkflow.Manifest " + body + text[index + 1:]
    path.write_text(text)


def wire_autobackup(versions: list[str]) -> None:
    path = TEMPLATE_DIR / "AutoBackupVerificationManifest.java"
    text = path.read_text()
    # constants region: from first VERIFICATION_ID const to the last ARTIFACT_SHA256 line before the javadoc
    start = text.index("    public static final String VERIFICATION_ID_")
    end_marker = text.index("    /**\n     * Returns whether this JVM is the exact task-scoped")
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
            f'    public static final long ARTIFACT_SIZE_{tag} = ReviewedHostArtifacts.CUBISM_{vtag(version)}.size();\n'
            f'    public static final String ARTIFACT_SHA256_{tag} = ReviewedHostArtifacts.CUBISM_{vtag(version)}.sha256();\n'
        )
        methods.append(
            f'    private static PinnedVerifiedResolverWorkflow.Manifest manifest{tag}() {{\n'
            '        return new PinnedVerifiedResolverWorkflow.Manifest(\n'
            f'            VERIFICATION_ID_{tag},\n'
            f'            RECORD_SHA256_{tag},\n'
            f'            CUBISM_VERSION_{tag},\n'
            f'            PROFILE_ID_{tag},\n'
            f'            ARTIFACT_SIZE_{tag},\n'
            f'            ARTIFACT_SHA256_{tag},\n'
            '            ADAPTER_SLICE_ID,\n'
            '            CAPABILITY_IDS,\n'
            '            REQUIRED_ALIASES\n'
            '        );\n'
            '    }\n'
        )
        arms.append(
            f'        if (artifact.size() == ARTIFACT_SIZE_{tag}\n'
            f'            && artifact.sha256().equals(ARTIFACT_SHA256_{tag})) {{\n'
            f'            return manifest{tag}();\n'
            '        }'
        )
    text = text[:start] + "\n".join(consts) + text[end_marker:]
    # forArtifact body
    start = text.index("    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(")
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
        + '\n        throw new IllegalArgumentException(\n'
        '            "host artifact is not the reviewed Cubism auto-backup artifact"\n'
        '        );\n    }'
    )
    text = text[:start] + "    static PinnedVerifiedResolverWorkflow.Manifest " + body + text[index + 1:]
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
        rf'[ ]*// <version-matrix:{key}>\n.*?[ ]*// </version-matrix:{key}>\n?',
        '',
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


def wire_editor_model(versions: list[str]) -> None:
    path = TEMPLATE_DIR / "EditorModelVerificationManifest.java"
    text = path.read_text()
    for key in ("em-consts", "em-sets", "em-arms"):
        text = _strip_marked(text, key)
    # RECORD_<V> consts: insert after RECORD_5_3_03 block
    anchor_end = text.index("    private static final List<ReviewedSliceRecord> RECORDS")
    const_blocks = []
    blocks = []
    for version in versions:
        record_name = f"cubism-{version}-editor-model.json"
        const_blocks.append(
            f'    /** Cubism version reported for the reviewed {version} artifact. */\n'
            + _version_const(version, record_name)
            + "\n"
        )
        blocks.append(
            f'    /** Reviewed Editor-model record admitted for exact Cubism {version}. */\n'
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
        r'private static final List<ReviewedSliceRecord> RECORDS = List\.of\([^;]*\);',
        text,
    )
    all_records = sorted(
        versions + ["5.2.03", "5.3.02", "5.3.03"],
        key=lambda v: tuple(int(p) for p in v.split(".")),
    )
    repl = (
        "private static final List<ReviewedSliceRecord> RECORDS = List.of(\n        "
        + ",\n        ".join(f"RECORD_{vtag(v)}" for v in all_records)
        + "\n    );"
    )
    text = text[: match.start()] + repl + text[match.end():]
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
        r'(        return record\.toManifest\(\n            ADAPTER_SLICE_ID,\n            ObjectContextMenuVerificationManifest\.capabilities\(CAPABILITY_IDS\),)',
        text,
    )
    assert arm_anchor
    arms = []
    for version in versions:
        arms.append(
            f'        if (RECORD_{vtag(version)}.equals(record)) {{\n'
            '            return record.toManifest(\n'
            '                ADAPTER_SLICE_ID,\n'
            f'                cubism{vtag(version)}Capabilities(),\n'
            f'                cubism{vtag(version)}Aliases()\n'
            '            );\n'
            '        }'
        )
    text = (
        text[: arm_anchor.start()]
        + "        // <version-matrix:em-arms>\n"
        + "\n".join(arms)
        + "\n        // </version-matrix:em-arms>\n"
        + text[arm_anchor.start():]
    )
    path.write_text(text)


def wire_protected_export(versions: list[str]) -> None:
    path = TEMPLATE_DIR / "ProtectedExportVerificationManifest.java"
    text = path.read_text()
    for key in ("pe-consts", "pe-arms"):
        text = _strip_marked(text, key)
    anchor_end = text.index("    private static final List<ReviewedSliceRecord> RECORDS")
    # keep CUBISM_VERSION consts grouped first: place them before RECORD blocks
    const_blocks = []
    rec_blocks = []
    for version in versions:
        record_name = f"cubism-{version}-protected-export.json"
        const_blocks.append(
            f'    /** Cubism version reported for the reviewed {version} artifact. */\n'
            + _version_const(version, record_name)
            + "\n"
        )
        rec_blocks.append(
            f'    /** Reviewed protected-export record admitted for exact Cubism {version}. */\n'
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
    match = re.search(
        r'private static final List<ReviewedSliceRecord> RECORDS = List\.of\([^;]*\);',
        text,
    )
    all_records = sorted(
        versions + ["5.2.03", "5.3.02", "5.3.03"],
        key=lambda v: tuple(int(p) for p in v.split(".")),
    )
    repl = (
        "private static final List<ReviewedSliceRecord> RECORDS = List.of(\n        "
        + ",\n        ".join(f"RECORD_{vtag(v)}" for v in all_records)
        + "\n    );"
    )
    text = text[: match.start()] + repl + text[match.end():]
    # forArtifact: legacy-surface records (no alias-source family) take
    # cubism52Aliases(); full-surface records take REQUIRED_ALIASES.
    anchor = re.search(r'        if \(RECORD_5_2_03\.equals\(record\)\) \{\n', text)
    assert anchor
    arms = []
    for version in versions:
        record = emitted_record("protected-export", version)
        aliases = {s["alias"] for s in record["selectors"]}
        legacy = "cubism.protected-export.alias-source.class" not in aliases
        alias_expr = "cubism52Aliases()" if legacy else "REQUIRED_ALIASES"
        arms.append(
            f'        if (RECORD_{vtag(version)}.equals(record)) {{\n'
            f'            return record.toManifest(ADAPTER_SLICE_ID, CAPABILITY_IDS, {alias_expr});\n'
            '        }'
        )
    text = (
        text[: anchor.start()]
        + "        // <version-matrix:pe-arms>\n"
        + "\n".join(arms)
        + "\n        // </version-matrix:pe-arms>\n"
        + text[anchor.start():]
    )
    path.write_text(text)


def wire_bounding_box(versions: list[str]) -> None:
    path = TEMPLATE_DIR / "BoundingBoxOverlayButtonVerificationManifest.java"
    text = path.read_text()
    for key in ("bb-sha", "bb-cases", "bb-admission", "bb-vid"):
        text = _strip_marked(text, key)
    # RECORD_SHA_<T> consts
    anchor = re.search(r'    private static final String RECORD_SHA_\w+ = "[^"]+";\n', text)
    first_sha = anchor.start()
    last_sha = None
    for m in re.finditer(r'    private static final String RECORD_SHA_\w+ = "\$\{record:[^}]+:sha256\}";\n', text):
        last_sha = m.end()
    assert last_sha
    sha_consts = "".join(
        f'    private static final String RECORD_SHA_{vcompact(v)} = '
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
    sw_anchor = '        return switch (cubismVersion) {'
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
    anchor = '        if (ReviewedHostArtifacts.CUBISM_5_3_03.equals(artifact)) {'
    idx = text.index(anchor)
    # find end of that if-block
    end = text.index("        throw new IllegalArgumentException(\"Unsupported Cubism artifact for overlay buttons\");", idx)
    arms = "".join(
        f'        if (ReviewedHostArtifacts.CUBISM_{vtag(v)}.equals(artifact)) {{\n'
        '            return new AdmissionEvidence(\n'
        f'                ReviewedHostArtifacts.CUBISM_{vtag(v)}_VERSION, artifact.size(), artifact.sha256()\n'
        '            );\n'
        '        }\n'
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
    sw = re.search(r'final String verificationId = switch \(cubismVersion\) \{\n', text)
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
        + text[sw.end():]
    )
    path.write_text(text)


def wire_bootstrap(records: dict[str, dict[str, str]]) -> None:
    path = ROOT / "bootstrap/build.gradle.kts"
    text = path.read_text()
    start = re.search(r'listOf\(\n(\s+"cubism-5\.[^"]+",?\n?)+\s*\)', text)
    assert start
    names = sorted(
        name
        for family_map in records.values()
        for name in family_map.values()
    )
    block = "listOf(\n        " + ",\n        ".join(f'"{n}"' for n in names) + "\n    )"
    text = text[: start.start()] + block + text[start.end():]
    path.write_text(text)


def wire_host_validation() -> None:
    # host_validation.py whitelist
    path = ROOT / "scripts/preview/host_validation.py"
    text = path.read_text()
    whitelist = "{" + ", ".join(f'"{vcompact(v)}"' for v in ALL_VERSIONS) + "}"
    text = re.sub(
        r'version not in \{[^}]*\}', f'version not in {whitelist}', text
    )
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
            records.get(family, {}), key=lambda v: tuple(int(p) for p in v.split("."))
        )
        wire_record_style_manifest(template, family, versions, family)
    for template, family, label, with_slice in (
        ("WorkspaceControlVerificationManifest.java", "workspace-control", "workspace-control", True),
        ("ControlAppearanceVerificationManifest.java", "ui-control-appearance", "control-appearance", False),
    ):
        versions = sorted(
            records.get(family, {}), key=lambda v: tuple(int(p) for p in v.split("."))
        )
        wire_arm_style_manifest(template, family, versions, label, with_slice=with_slice)
    wire_autobackup(sorted(
        records.get("autobackup", {}), key=lambda v: tuple(int(p) for p in v.split("."))
    ))
    wire_bounding_box(sorted(
        (
            v
            for v in records.get("ui-bounding-box-overlay", {})
            if v not in REVIEWED
        ),
        key=lambda v: tuple(int(p) for p in v.split(".")),
    ))
    wire_editor_model(sorted(
        (v for v in records.get("editor-model", {}) if v not in REVIEWED),
        key=lambda v: tuple(int(p) for p in v.split(".")),
    ))
    wire_protected_export(sorted(
        (v for v in records.get("protected-export", {}) if v not in REVIEWED),
        key=lambda v: tuple(int(p) for p in v.split(".")),
    ))
    wire_bootstrap(records)
    wire_host_validation()
    wire_index_md()
    print("wired", sum(len(v) for v in records.values()), "records")


def shared_core_profile(version: str) -> str | None:
    """A target inherits a reviewed core profile only when its
    ``Live2DCubismCore.jar`` is byte-identical (``profileForArtifact`` binds
    by exact digest). The native runtime tuple stays unobserved for distinct
    jars, so they receive no core pack reference."""
    sha = artifact_facts(version, "Live2DCubismCore.jar")["sha256"]
    for reviewed in REVIEWED:
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


def source_version_of(source: dict) -> str:
    return source["cubismVersion"]


def main() -> int:
    if len(sys.argv) < 2 or sys.argv[1] not in {"matrix", "emit", "facts", "wire"}:
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
                f for f in families if f != "core-model-read"
                and f not in derived
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
    family_packs = {
        family: load_family_packs(family) for family in families
    }
    pack_order = {
        re.sub(r"^cubism-\d+\.\d+\.\d+-", "", name): i
        for i, name in enumerate(
            json.loads(
                (PROFILE_DIR / "cubism-5.3.03.json").read_text()
            )["mappingPacks"]
        )
    }
    for version in TARGET_VERSIONS:
        derived = derive_version(version, families, editor, constants)
        packs = {}
        for family, (record, source_version) in sorted(derived.items()):
            record_path = (
                VERIFICATION_DIR / f"cubism-{version}-{family}.json"
            )
            record_path.write_text(
                json.dumps(record, indent=2) + "\n"
            )
            candidates = family_packs.get(family) or {}
            source_pack = (
                candidates.get(source_version)
                or (candidates["5.3.03"] if "5.3.03" in candidates else None)
                or (next(iter(candidates.values())) if candidates else None)
            )
            if source_pack is None:
                continue
            pack = derive_pack(
                source_pack, record, version, family,
                family_packs=candidates,
                rewrite_provenance=True,
            )
            name = f"cubism-{version}-{pack_name_for(family)}"
            (PACK_DIR / f"{name}.json").write_text(
                json.dumps(pack, indent=2) + "\n"
            )
            packs[name] = pack
        core_pack = shared_core_profile(version)
        pack_names = sorted(packs, key=lambda n: pack_order.get(
            re.sub(r"^cubism-\d+\.\d+\.\d+-", "", n), 99
        ))
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
