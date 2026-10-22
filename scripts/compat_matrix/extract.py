"""Repository artifact, contract, and metadata extraction."""
from __future__ import annotations

from preview.reviewed_host_artifacts import load_releases

import json
from pathlib import Path
import re

from turboism_dev.json_io import read_json
from turboism_dev.versions import cubism_version_key as version_key

from .constants import (
    ANNOTATION_RE,
    CAMPAIGN_JOBS,
    CAPABILITY_CONSTANT_RE,
    CLASS_PINS_DIR,
    CLASS_PIN_LOAD_RE,
    COMMAND_ENUMS,
    ENUM_ENTRY_RE,
    FAMILIES_DIR,
    FQCN_REF_RE,
    IMPORT_RE,
    INTERCEPTOR,
    MEMBER_RE,
    MONOTONICITY_EXCEPTIONS,
    OFFICIAL_TIMELINE,
    PACKAGE_RE,
    PLUGIN_DESCRIPTOR,
    QUOTED_VERSIONS_RE,
    REVIEWED_HOST_ARTIFACTS,
    RUNTIME_MAIN,
    SDK_IMPORT_RE,
    SDK_MAIN,
    SUPPORTED_SET_RE,
    TEMPLATES_DIR,
    TYPE_DECL_RE,
    VERIFICATION_DIR,
    VERSION_BOUNDARIES,
    VERSION_RE,
)

def load_reviewed_versions(root: Path) -> list[str]:
    return [r['version'] for r in load_releases(root / REVIEWED_HOST_ARTIFACTS)]


def load_families(root: Path) -> tuple[dict[str, list[str]], dict[str, str]]:
    """Return (family -> record basenames, record basename -> family)."""
    families: dict[str, list[str]] = {}
    record_family: dict[str, str] = {}
    for path in sorted((root / FAMILIES_DIR).glob("*.json")):
        doc = read_json(path)
        family = doc.get("family")
        if not family or family == "compatibility-catalog":
            continue
        records = doc.get("records", [])
        families[family] = records
        for record in records:
            record_family[record] = family
    return families, record_family


def load_capabilities(root: Path) -> dict[str, dict]:
    """capability id -> {verified: {version: status}, records, families}."""
    caps: dict[str, dict] = {}
    _, record_family = load_families(root)
    for path in sorted((root / VERIFICATION_DIR).glob("*.json")):
        doc = read_json(path)
        version = doc.get("cubismVersion")
        status = doc.get("status")
        family = record_family.get(path.name)
        for cap in doc.get("capabilityIds", []):
            entry = caps.setdefault(
                cap, {"verified": {}, "records": {}, "families": set()}
            )
            entry["verified"][version] = status
            entry["records"][version] = path.name
            if family:
                entry["families"].add(family)
    for entry in caps.values():
        entry["families"] = sorted(entry["families"])
    return caps


def load_verified_selector_versions(root: Path) -> dict[str, set[str]]:
    """Exact versions with a VERIFIED_STATIC record and selector for each alias."""
    aliases: dict[str, set[str]] = {}
    for path in sorted((root / VERIFICATION_DIR).glob("*.json")):
        doc = read_json(path)
        if doc.get("status") != "VERIFIED_STATIC":
            continue
        for selector in doc.get("selectors", []):
            if selector.get("status") == "VERIFIED_STATIC":
                aliases.setdefault(selector["alias"], set()).add(doc["cubismVersion"])
    return aliases


def load_contract_gates(root: Path) -> dict[str, list[dict]]:
    """capability id -> narrowing version sets from selector contracts.

    Only contracts that declare their capability id as a literal in the same
    file count: contracts that *reference* another contract's capability
    constant are variant/reduced forms (for example the no-alpha-composition
    inspector contract) and never narrow the base capability.
    """
    gates: dict[str, list[dict]] = {}
    for base in (TEMPLATES_DIR, RUNTIME_MAIN):
        base_dir = root / base
        if not base_dir.is_dir():
            continue
        for path in sorted(base_dir.rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            cap_ids = set(CAPABILITY_CONSTANT_RE.findall(text))
            if not cap_ids:
                continue
            rel = str(path.relative_to(root))
            for symbol, body in SUPPORTED_SET_RE.findall(text):
                versions = sorted(
                    set(QUOTED_VERSIONS_RE.findall(body)), key=version_key
                )
                if not versions:
                    continue
                for cap in cap_ids:
                    gates.setdefault(cap, []).append(
                        {"source": f"{rel}:{symbol}", "versions": versions}
                    )
    return gates


def load_required_capabilities(root: Path) -> dict[str, list[str]]:
    """SDK class FQN -> capability ids the availability interceptor requires."""
    path = root / INTERCEPTOR
    if not path.is_file():
        return {}
    text = path.read_text(encoding="utf-8")
    editor_read = re.search(r"editorRead\s*=\s*Set\.of\(([^)]*)\)", text)
    editor_read_caps = (
        QUOTED_VERSIONS_RE.findall(editor_read.group(1)) if editor_read else []
    )
    required: dict[str, list[str]] = {}
    for match in re.finditer(
        r"required\.put\(\s*([\w.]+)\.class,\s*(editorRead|Set\.of\(([^)]*)\))\s*\)",
        text,
    ):
        cls, inline = match.group(1), match.group(3)
        caps = editor_read_caps if inline is None else QUOTED_VERSIONS_RE.findall(inline)
        required[cls] = sorted(set(caps))
    return required


def _parse_annotation_args(args: str, reviewed: list[str]) -> set[str]:
    """Resolve one @CubismEditor argument body to a reviewed-version subset."""
    exact: list[str] = []
    value_match = re.search(r"(?:value\s*=\s*)?\{([^}]*)\}", args)
    if value_match:
        exact = QUOTED_VERSIONS_RE.findall(value_match.group(1))
    elif args.strip().startswith('"'):
        exact = QUOTED_VERSIONS_RE.findall(args)
    from_match = re.search(r'from\s*=\s*"([^"]*)"', args)
    to_match = re.search(r'to\s*=\s*"([^"]*)"', args)
    if exact and not from_match and not to_match:
        selected = set(exact)
    else:
        lo = from_match.group(1) if from_match else ""
        hi = to_match.group(1) if to_match else ""
        selected = {
            v
            for v in reviewed
            if (not lo or version_key(v) >= version_key(lo))
            and (not hi or version_key(v) <= version_key(hi))
        }
        if exact:
            selected &= set(exact)
    exclude_match = re.search(r"exclude\s*=\s*\{([^}]*)\}", args)
    if exclude_match is None:
        exclude_match = re.search(r'exclude\s*=\s*"([^"]+)"', args)
    if exclude_match:
        selected -= set(QUOTED_VERSIONS_RE.findall(exclude_match.group(1)))
    return selected


def _strip_java(text: str) -> str:
    """Blank comments and literals while preserving offsets."""
    def repl(match: re.Match) -> str:
        return "".join("\n" if c == "\n" else " " for c in match.group(0))

    return re.sub(
        r"//[^\n]*|/\*.*?\*/|\"(?:\\.|[^\"\\])*\"|\'(?:\\.|[^\'\\])*\'",
        repl,
        text,
        flags=re.DOTALL,
    )


def _type_spans(stripped: str) -> list[tuple[str, int, int]]:
    """(name, decl start, body end) for every type declaration, brace-matched."""
    spans: list[tuple[str, int, int]] = []
    for match in TYPE_DECL_RE.finditer(stripped):
        name = match.group(1)
        brace = stripped.find("{", match.end())
        if brace == -1:
            continue
        # Ensure no ';' precedes the brace (a field/prototype, not a body).
        if ";" in stripped[match.end() : brace] or ")" in stripped[match.end() : brace]:
            continue
        depth = 0
        pos = brace
        while pos < len(stripped):
            if stripped[pos] == "{":
                depth += 1
            elif stripped[pos] == "}":
                depth -= 1
                if depth == 0:
                    break
            pos += 1
        spans.append((name, match.start(), pos))
    return spans


def parse_sdk_annotations(root: Path, reviewed: list[str]) -> tuple[list[dict], dict[str, list[dict]]]:
    """Parse every @CubismEditor in the SDK.

    Returns (entries, accessor_map). Entries carry {api, kind, versions, file};
    accessor_map maps a method's return-type FQN to the annotated accessors
    producing it.
    """
    entries: list[dict] = []
    accessor_map: dict[str, list[dict]] = {}
    sdk_dir = root / SDK_MAIN
    for path in sorted(sdk_dir.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        rel = str(path.relative_to(root))
        package_match = PACKAGE_RE.search(text)
        package = package_match.group(1) if package_match else ""
        imports = {m.split(".")[-1]: m for m in IMPORT_RE.findall(text)}
        stripped = _strip_java(text)
        spans = _type_spans(stripped)
        for match in ANNOTATION_RE.finditer(text):
            if stripped[match.start()] != "@":
                continue
            raw_args = match.group(1)
            versions = _parse_annotation_args(raw_args or "", reviewed)
            tail = text[match.end() : match.end() + 400].lstrip()
            enclosing = [s for s in spans if s[1] < match.start() < s[2]]
            enclosing.sort(key=lambda s: s[1])
            owner_chain = [s[0] for s in enclosing]
            type_pattern = (
                r"(?:@[\w.]+(?:\s*\([^)]*\))?\s+|"
                r"public\s+|protected\s+|private\s+|final\s+|abstract\s+|"
                r"static\s+|sealed\s+|non-sealed\s+)*"
                r"(?:class|interface|enum|@interface|record)\s+(\w+)"
                if raw_args is None
                else r"(?:public\s+|protected\s+|private\s+|final\s+|abstract\s+|"
                r"static\s+|sealed\s+|non-sealed\s+)*"
                r"(?:class|interface|enum|@interface)\s+(\w+)"
            )
            type_match = re.match(type_pattern, tail)
            if type_match and not enclosing:
                api = f"{package}.{type_match.group(1)}"
                kind = "type"
            elif type_match:
                api = f"{package}." + ".".join(owner_chain + [type_match.group(1)])
                kind = "type"
            else:
                member_match = MEMBER_RE.match(tail)
                if not member_match:
                    continue
                return_type, method_name = member_match.groups()
                owner = ".".join(owner_chain) if owner_chain else path.stem
                api = f"{package}.{owner}#{method_name}()"
                kind = "method"
                bare = re.sub(r"<.*>", "", return_type).rstrip("[]")
                return_fqn = imports.get(bare)
                if return_fqn is None:
                    if "." in bare:
                        return_fqn = bare
                    elif bare[:1].isupper() and package:
                        # Same-package or nested type.
                        return_fqn = f"{package}.{bare}"
                if return_fqn:
                    accessor_map.setdefault(return_fqn, []).append(
                        {"api": api, "versions": versions, "file": rel}
                    )
            entries.append(
                {"api": api, "kind": kind, "versions": sorted(versions, key=version_key), "file": rel}
            )
    return entries, accessor_map


def load_commands(root: Path, reviewed: list[str]) -> list[dict]:
    """Enum constants admitted on a strict subset of the reviewed versions."""
    commands: list[dict] = []
    full = set(reviewed)
    for rel in COMMAND_ENUMS:
        path = root / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        enum_name = Path(rel).stem
        for match in ENUM_ENTRY_RE.finditer(text):
            name, body = match.group(1), match.group(2)
            versions = set(QUOTED_VERSIONS_RE.findall(body))
            if not versions and "Set.of" not in body:
                continue
            if versions != full:
                commands.append(
                    {
                        "enum": enum_name,
                        "name": name,
                        "versions": sorted(versions, key=version_key),
                    }
                )
    return commands


def load_class_pins(root: Path) -> dict[str, dict]:
    """pin name -> {versions, loadedBy} derived from ClassPinTable.load sites."""
    loaded_by: dict[str, str] = {}
    runtime_dir = root / RUNTIME_MAIN
    if runtime_dir.is_dir():
        for path in sorted(runtime_dir.rglob("*.java")):
            for pin in CLASS_PIN_LOAD_RE.findall(path.read_text(encoding="utf-8")):
                loaded_by.setdefault(pin, str(path.relative_to(root)))
    pins: dict[str, dict] = {}
    pins_dir = root / CLASS_PINS_DIR
    if pins_dir.is_dir():
        for path in sorted(pins_dir.glob("*.json")):
            doc = read_json(path)
            versions = sorted(
                (k for k in doc if VERSION_RE.match(k)), key=version_key
            )
            pins[path.stem] = {
                "versions": versions,
                "loadedBy": loaded_by.get(path.stem),
            }
    return pins


def load_runtime_gates(root: Path) -> list[dict]:
    """SUPPORTED_* sets that do not share a file with a capability-id literal
    (feature-level pins rather than capability contracts)."""
    gates: list[dict] = []
    dirs = [root / RUNTIME_MAIN, root / "plugins"]
    for base in dirs:
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.java")):
            if "/src/test/" in str(path):
                continue
            text = path.read_text(encoding="utf-8")
            if CAPABILITY_CONSTANT_RE.search(text):
                continue
            for symbol, body in SUPPORTED_SET_RE.findall(text):
                versions = sorted(
                    set(QUOTED_VERSIONS_RE.findall(body)), key=version_key
                )
                if versions:
                    gates.append(
                        {
                            "file": str(path.relative_to(root)),
                            "symbol": symbol,
                            "versions": versions,
                        }
                    )
    return gates




def load_campaign(root: Path, reviewed: list[str]) -> dict[str, dict[str, dict]]:
    """task -> version -> latest FINAL job result (file order wins).

    Jobs record versions in three shapes observed in the campaign history:
    dotted (``"5.3.01"``), unpadded dotted (``"5.20.1"``), and bare matrix
    codes (``"5301"``). Normalization compares digit sequences against the
    reviewed versions, so a bare string can never resolve to an unreviewed
    version.
    """
    path = root / CAMPAIGN_JOBS
    if not path.is_file():
        return {}
    by_digits = {re.sub(r"\D", "", v): v for v in reviewed}

    def normalize_version(raw) -> str | None:
        if not raw:
            return None
        return by_digits.get(re.sub(r"\D", "", str(raw)))

    doc = read_json(path)
    latest: dict[str, dict[str, dict]] = {}
    for job in doc.get("jobs", []):
        if job.get("status") != "FINAL":
            continue
        task = job.get("task")
        version = normalize_version(job.get("version"))
        if not task or not version:
            continue
        result = job.get("result") or job
        validation = result.get("validationStatus")
        terminal = result.get("terminalState")
        if validation is None:
            validation = {"succeeded": "PASS", "failed": "FAIL"}.get(terminal)
        triage = job.get("triage") or {}
        latest.setdefault(task, {})[version] = {
            "validationStatus": validation,
            "terminalState": terminal,
            "triageCategory": triage.get("category"),
            "triageRule": triage.get("ruleId"),
            "round": job.get("round"),
            "jobId": job.get("jobId"),
        }
    return latest


# Triage categories that denote a real capability boundary (the feature is
# absent on that host), versus noise that must never surface as a failure on
# the user-facing page.


def load_monotonicity_exceptions(root: Path) -> list[dict]:
    """Sanctioned upward-closure violations used to label the nature of a
    version gap: entries with `pending` are adaptation work in flight; entries
    with `evidence` are deliberate, real boundaries."""
    path = root / MONOTONICITY_EXCEPTIONS
    if not path.is_file():
        return []
    doc = read_json(path)
    entries = []
    for entry in doc.get("exceptions", []):
        entries.append(
            {
                "kind": entry.get("kind"),
                "path": entry.get("path"),
                "site": entry.get("site"),
                "missing": set(entry.get("missing", [])),
                "pending": bool(entry.get("pending")),
                "evidence": bool(entry.get("evidence")),
            }
        )
    return entries


def load_version_boundaries(root: Path) -> dict[str, set[str]]:
    """Feature id -> versions where the host itself lacks the feature.

    These are the audit's proven genuine boundaries; anything absent from this
    file is not asserted to be a boundary.
    """
    path = root / VERSION_BOUNDARIES
    if not path.is_file():
        return {}
    doc = read_json(path)
    return {
        entry["id"]: set(entry.get("absentOn", []))
        for entry in doc.get("boundaries", [])
    }


def _load_boundary_entries(root: Path) -> list[dict]:
    path = root / VERSION_BOUNDARIES
    if not path.is_file():
        return []
    entries = []
    for entry in read_json(path).get(
        "boundaries", []
    ):
        item = {
            "id": entry["id"],
            "absentOn": sorted(entry.get("absentOn", []), key=version_key),
            "reason": entry.get("reason"),
        }
        if "officialSource" in entry:
            item["officialSource"] = entry["officialSource"]
        entries.append(item)
    return entries


def load_official_timeline(root: Path) -> list[dict]:
    path = root / OFFICIAL_TIMELINE
    if not path.is_file():
        return []
    try:
        doc = read_json(path)
        return doc.get("features", [])
    except Exception:
        return []


def index_official_timeline(features: list[dict]) -> dict[str, dict]:
    """Token -> timeline feature mapping.

    Keys include feature id, relatedCapabilities, relatedCommands (both
    enum-qualified, command:NAME, and bare NAME), and relatedSdkApis (with
    and without trailing ()).
    """
    index: dict[str, dict] = {}
    for feat in features:
        if "id" in feat:
            index[feat["id"]] = feat
        for cap in feat.get("relatedCapabilities", []):
            index[cap] = feat
        for cmd in feat.get("relatedCommands", []):
            index[cmd] = feat
            bare = cmd.split(".")[-1]
            index[bare] = feat
            index[f"command:{bare}"] = feat
        for api in feat.get("relatedSdkApis", []):
            index[api] = feat
            index[api.rstrip("()")] = feat
    return index




def task_base(task: str) -> str:
    return re.sub(r"_\d{4}$", "", task)


def load_plugins(root: Path) -> list[dict]:
    plugins: list[dict] = []
    for descriptor in sorted((root / "plugins").glob(f"*/{PLUGIN_DESCRIPTOR}")):
        doc = json.loads(descriptor.read_text(encoding="utf-8"))
        # descriptor = plugins/<name>/src/main/resources/META-INF/turboism/plugin.json
        module = descriptor.parents[5]
        java_dir = module / "src/main/java"
        sdk_refs: set[str] = set()
        java_texts: list[str] = []
        if java_dir.is_dir():
            for source in sorted(java_dir.rglob("*.java")):
                text = source.read_text(encoding="utf-8")
                java_texts.append(text)
                sdk_refs |= set(SDK_IMPORT_RE.findall(text))
                sdk_refs |= set(FQCN_REF_RE.findall(text))
        coverage: list[dict] = []
        manifest_dir = module / "src/main/resources/META-INF/turboism"
        if manifest_dir.is_dir():
            for manifest in sorted(manifest_dir.glob("*-coverage.json")):
                cov_doc = json.loads(manifest.read_text(encoding="utf-8"))
                coverage.extend(cov_doc.get("entries", []))
        plugins.append(
            {
                "directory": module.name,
                "id": doc.get("id"),
                "name": doc.get("name"),
                "requiresCubism": bool(
                    doc.get("environment", {}).get("requiresCubism")
                ),
                "delivery": doc.get("delivery", ""),
                "capabilities": doc.get("capabilities", []),
                "sdkRefs": sorted(sdk_refs),
                "javaTexts": java_texts,
                "coverage": coverage,
            }
        )
    return plugins
