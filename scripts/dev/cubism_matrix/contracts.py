"""Selector contract compilation and capability requirement resolution."""
from __future__ import annotations

import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from .constants import DEPS_SOURCE, GENERATE, MODEL_BINDING, ROOT, TYPE_TOKEN



# ---------------------------------------------------------------------------
# CapabilitySelectorDependencies mirror
# ---------------------------------------------------------------------------

ALIAS_TABLE_JAVA = r"""
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
"""


def contract_constants() -> dict[str, dict[str, object]]:
    """Render + compile the selector contracts and dump their constants."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        subprocess.run(
            [
                sys.executable,
                str(GENERATE),
                str(ROOT),
                "render",
                "--out",
                str(tmp_path / "src"),
            ],
            check=True,
            capture_output=True,
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
            ["javac", "-d", str(classes), str(tmp_path / "AliasTable.java")]
            + [str(s) for s in sources],
            check=True,
            capture_output=True,
        )
        src_root = tmp_path / "src"
        names = [
            ".".join(s.relative_to(src_root).with_suffix("").parts) for s in sources
        ]
        out = subprocess.run(
            ["java", "-cp", str(classes), "AliasTable"] + names,
            check=True,
            capture_output=True,
            text=True,
        ).stdout
    table: dict[str, dict[str, object]] = {}
    for cls, fields in json.loads(out).items():
        table[cls] = {
            name: (v["v"] if v["t"] == "s" else frozenset(v["v"]))
            for name, v in fields.items()
        }
    # Runtime-side admission contracts referenced by
    # CapabilitySelectorDependencies.add(...) entries. They live outside the
    # template tree, so their literals are folded in from source instead of
    # being compiled.
    table.update(runtime_contract_constants())
    # Capability -> declared SUPPORTED_VERSIONS for runtime-side contracts;
    # emitted records must not advertise a capability outside its contract.
    versions: dict[str, frozenset] = {}
    for cls in _RUNTIME_CONTRACT_FILES:
        simple = cls.rsplit("/", 1)[-1].removesuffix(".java")
        fields = table[simple]
        cap = fields.get("CAPABILITY_ID")
        supported = fields.get("SUPPORTED_VERSIONS")
        if isinstance(cap, str) and isinstance(supported, frozenset):
            versions[cap] = supported
    table["_capabilitySupportedVersions"] = versions
    return table


_RUNTIME_CONTRACT_FILES = (
    "dev/turboism/adapter/cubism/mesh/MeshToolSessionSelectorContract.java",
    "dev/turboism/adapter/cubism/modeling/ModelingSelectionSelectorContract.java",
)


def runtime_contract_constants() -> dict[str, dict[str, object]]:
    """Extract String/Set-of-String constants from runtime-only contracts."""
    table: dict[str, dict[str, object]] = {}
    for relative in _RUNTIME_CONTRACT_FILES:
        path = ROOT / "runtime/src/main/java" / relative
        text = path.read_text(encoding="utf-8")
        fields: dict[str, object] = {}
        for match in re.finditer(
            r'public static final String (\w+) =\s*"([^"]+)"', text
        ):
            fields[match.group(1)] = match.group(2)
        for match in re.finditer(
            r"public static final Set<String> (\w+) =\s*Set\.of\(([^;]+?)\);",
            text,
            re.DOTALL,
        ):
            values = []
            for token in re.findall(r'"([^"]+)"|(\b[A-Z][A-Z0-9_]*\b)', match.group(2)):
                literal, constant = token
                if literal:
                    values.append(literal)
                else:
                    resolved = fields.get(constant)
                    if resolved is None:
                        raise ValueError(
                            f"unresolvable runtime contract constant: {constant} in {relative}"
                        )
                    values.append(resolved)
            fields[match.group(1)] = frozenset(values)
        simple = relative.rsplit("/", 1)[-1].removesuffix(".java")
        table[simple] = fields
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

    for match in re.finditer(r"add\(\s*result,\s*([^,]+),\s*([^)]+)\)", source):
        cap = resolve(match.group(1))
        aliases = resolve(match.group(2))
        table.setdefault(cap, set()).update(aliases)
    return {k: frozenset(v) for k, v in table.items()}


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
    inspector = constants["EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract"]
    if capability == inspector["CAPABILITY_ID"] and cubism_version in inspector["SUPPORTED_CUBISM_VERSIONS"]:
        aliases -= set(constants["EditorInspectorDrawableWriteSelectorContract"]["ALPHA_COMPOSITION_ALIASES"])
    tex = constants["EditorTextureSelectorContract"]
    if capability == tex["WRITE_CAPABILITY_ID"]:
        aliases.update(
            tex["REMOVE_RAW_IMAGE_5203_ALIASES"]
            if cubism_version in tex["LEGACY_RAW_IMAGE_REMOVAL_VERSIONS"]
            else tex["REMOVE_RAW_IMAGE_ALIASES"]
        )
    inst = constants["EditorModelInstanceReadSelectorContract"]
    if (
        capability == inst["CAPABILITY_ID"]
        and cubism_version in inst["ONION_SKIN_ABSENT_VERSIONS"]
    ):
        aliases -= set(inst["ONION_SKIN_ALIASES"])
    psd = constants["EditorRawImagePsdSelectorContract"]
    if (
        capability == psd["CAPABILITY_ID"]
        and cubism_version in psd["TRANSPARENCY_SHAPES_ABSENT_VERSIONS"]
    ):
        aliases -= set(psd["TRANSPARENCY_SHAPES_ALIASES"])
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


