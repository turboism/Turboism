#!/usr/bin/env python3
"""Unit verification of config.json lifecycle and Full/Thin plugin payload semantics.

This script mirrors the configuration policy of installer.nsi and configure_turboism.ps1:
  - Config validation, migration, and plugin selection commit precede any permanent payload write;
  - Fresh installs create config.json from the current template and write disabledPlugins from selection;
  - Existing current v1 configs update only disabledPlugins while preserving other valid user fields;
  - Other parseable own documents (legacy/future schemaVersion, unknown fields, invalid values)
    normalize to v1: valid known values carry over, others are dropped, then selection applies;
  - Fail closed only when format explicitly names another product or the file is corrupt/oversized/unparseable;
  - Full installs always carry all bundled plugin JARs; Thin installs write no new JARs and disable all bundled plugins;
  - Uninstall fail-closed cleanup ordering and exact LICENSE deletion remain unchanged.

packaging/release-plugins.txt remains the sole authoritative payload manifest: this script checks
it as an explicit regression oracle (manifest drift fails), while the synthetic id/module fixtures
below stay independent because Gradle module names need not match plugin ids (actual ids come from
META-INF/turboism/plugin.json, checked by verify-installer.py and assemble-release.sh).
"""

import json
import re
import sys
from pathlib import Path

MANIFEST_PATH = Path(__file__).resolve().parent.parent / "release-plugins.txt"
INSTALLER_NSI = Path(__file__).resolve().parent / "installer.nsi"
EULA_DIR = Path(__file__).resolve().parent.parent / "eula"
ICON_DIR = Path(__file__).resolve().parent / "assets"

# The frozen 33-item approval list, which is the regression oracle: an added, removed, reordered, or newly public excluded module fails.
# Item 2 was renamed from "backup" to "webdav-backup" with the plugin rename, and frame-animation-import was added.
EXPECTED_PATHS = [
    ":plugins:acp",
    ":plugins:artmesh-mirror",
    ":plugins:atlas-bitmask-nesting",
    ":plugins:atlas-dalsoo-polygon",
    ":plugins:atlas-folder-layout",
    ":plugins:atlas-maxrects-bssf",
    ":plugins:boundingbox-warp-mirror",
    ":plugins:clipmask-viewer",
    ":plugins:command-palette",
    ":plugins:cubism-tab-filter",
    ":plugins:deformer-batch-rename",
    ":plugins:external-psd-edit",
    ":plugins:frame-animation-import",
    ":plugins:geometric-mesh-layout",
    ":plugins:history-panel",
    ":plugins:mcp",
    ":plugins:mesh-edit-mirror-axis-enhance",
    ":plugins:model-import-3d",
    ":plugins:palette-label-style",
    ":plugins:parameter-batch-transfer",
    ":plugins:parameter-sync",
    ":plugins:perf-stats",
    ":plugins:physics-editor",
    ":plugins:project-work-time",
    ":plugins:protected-export",
    ":plugins:psd-clip-mask-import",
    ":plugins:recent-preview",
    ":plugins:recent-project-board",
    ":plugins:scene-palette-enhancer",
    ":plugins:selection-brush",
    ":plugins:texture-atlas-stats",
    ":plugins:turbo-mesh",
    ":plugins:ui-theme",
    ":plugins:warp-deformer-alt-symmetry",
    ":plugins:webdav-backup",
]
# The three public excluded modules must stay absent from the list and from every release payload and selection surface, which is the regression oracle
EXCLUDED = {"demo", "parameter", "project-inspector"}


def check(name, cond, detail=""):
    if not cond:
        print(f"FAIL: {name} {detail}")
        sys.exit(1)
    print(f"  ok: {name}")


def load_manifest():
    """Regression oracle: verify release-plugins.txt has no blank/comment/non-plugin/duplicate/
    unsorted entries, matches the approved list, and excludes public-exclusion modules. Returned
    module names serve the oracle only; plugin ids come from each JAR's plugin.json."""
    raw = MANIFEST_PATH.read_text(encoding="utf-8").splitlines()
    invalid = [l for l in raw if not l.strip() or l.strip().startswith("#")]
    check("manifest has no blank lines or comments", not invalid, f"found={invalid[:3]}")
    lines = [l.strip() for l in raw if l.strip() and not l.strip().startswith("#")]
    entry = re.compile(r"^:plugins:[a-z0-9-]+$")
    bad = [l for l in lines if not entry.match(l)]
    check("all manifest entries are plugin paths", not bad, f"bad={bad[:3]}")
    check("manifest has no duplicates", len(set(lines)) == len(lines))
    check("manifest is sorted in ASCII ascending order", lines == sorted(lines))
    check(f"manifest matches frozen {len(EXPECTED_PATHS)} entries", lines == EXPECTED_PATHS,
          f"n={len(lines)}")
    modules = [l[len(":plugins:"):] for l in lines]
    check("public-exclusion modules absent from manifest", not (set(modules) & EXCLUDED),
          f"found={set(modules) & EXCLUDED}")
    return modules


# A standalone synthetic fixture whose three id and module pairs mirror the NSIS config merge and
# the payload section semantics only. It never derives from the real plugin list, because a Gradle
# module name is no general convention for a plugin id. The real bundled ids come from
# verify-installer.py and assemble-release.sh reading each JAR's plugin.json.
BUNDLED = [
    ("dev.turboism.plugin.alpha", "plugin-alpha"),
    ("dev.turboism.plugin.beta", "plugin-beta"),
    ("dev.turboism.plugin.gamma", "plugin-gamma"),
]
BUNDLED_IDS = [i for i, _ in BUNDLED]
BUNDLED_MODULES = sorted(m for _, m in BUNDLED)
UNRELATED = "dev.turboism.plugin.not-bundled"
ACP_ID = "dev.turboism.plugin.acp"
RETIRED_IDS = [
    "dev.turboism.plugin.clipmask",
    "dev.turboism.plugin.logfilter",
    "dev.turboism.plugin.perfopt",
    "dev.turboism.plugin.renderopt",
    # superseded official id (webdav-backup rename), not a fake
    "dev.turboism.plugin.backup",
]
# "backup" is the old module and delivery name of webdav-backup before the rename, so
# plugins/backup.jar must disappear after an upgrade. The real mechanism deletes by the embedded
# old id, and this check mirrors the same result by file name.
RETIRED_MODULES = ["backup", "clip-mask", "log-filter", "perf-opt", "render-opt"]

REAL_MODULES = load_manifest()  # Regression oracle: a manifest drift, an addition, a removal, a reorder, or a placeholder regression fails



CURRENT_SCHEMA = 1
V1_FIELDS = {
    "format", "schemaVersion", "worktreeId", "pluginDirs", "disabledPlugins",
    "logLevel", "maxLogStorageMiB", "locale", "safeMode", "useTextIcon", "diagnostics",
    "hooks", "guide", "launcher", "textureAtlas", "reduceAutoBackup",
    "meshTriangulationHashFix", "atlasTileBbox", "atlasCacheReuse", "meshTriangulationEdgeIndex",
    "atlasLoading", "triangulationSpatialIndex",
    "modelOpenAcceleration", "runtimeExportAcceleration",
}
LOG_LEVELS = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"}
LOCALES = {"system", "en", "ja", "ko", "zh-Hans", "zh-Hant"}
HOOK_FIELDS = {"disabledIds", "denylistedClasses", "startup"}
STARTUP_FIELDS = {
    "skipUpdateCheck", "skipSplash", "skipInformation",
    "separateExportSaveDirectory",
}
GUIDE_FIELDS = {"showOnStartup"}
LAUNCHER_FIELDS = {"cubismJvm", "graalVmPath", "zgc", "memoryProfile", "modelUpdateSkip", "incrementalUpdate", "incrementalUpdateAffecters", "uniformLocationCache", "uploadElision", "meshSetupElision", "inputPathElision", "mesaGlThread", "asyncReadback", "meshInterpolation", "shaderUniform", "matrixScratch", "imageArchiveReuse", "floatArrayParseCache", "textureUploadPreparation", "warpPositionProjection", "reinitPairElision", "clipMaskDeduplication", "clipMaskScissorClear", "extendedInterpolationIncrementalSetup", "modelInstanceCoalesce", "inspectorPropertyBypass", "extendedInterpolationSiblingElision", "warpGridInterpolation", "warpNormalPointElision", "cameraMatrixCache", "renderPassTraversal", "meshHitTest", "sceneHandlerCache", "cursorValidationElision", "parameterRowRepaint", "warpDeformEditAcceleration"}
TEXTURE_ATLAS_FIELDS = {"algorithmId", "parallel"}
LEGACY_ROOT_LAUNCHER_FIELDS = {"cubismJvm", "graalVmPath"}
BOOLEAN_LAUNCHER_FIELDS = {"zgc", "modelUpdateSkip", "incrementalUpdate", "incrementalUpdateAffecters", "uniformLocationCache", "uploadElision", "meshSetupElision", "inputPathElision", "mesaGlThread", "asyncReadback", "meshInterpolation", "shaderUniform", "matrixScratch", "imageArchiveReuse", "floatArrayParseCache", "textureUploadPreparation", "warpPositionProjection", "reinitPairElision", "clipMaskDeduplication", "clipMaskScissorClear", "extendedInterpolationIncrementalSetup", "modelInstanceCoalesce", "inspectorPropertyBypass", "extendedInterpolationSiblingElision", "warpGridInterpolation", "warpNormalPointElision", "cameraMatrixCache", "renderPassTraversal", "meshHitTest", "sceneHandlerCache", "cursorValidationElision", "parameterRowRepaint", "warpDeformEditAcceleration"}
BOOLEAN_V1_FIELDS = {
    "reduceAutoBackup", "meshTriangulationHashFix", "atlasTileBbox", "atlasCacheReuse",
    "meshTriangulationEdgeIndex", "atlasLoading", "triangulationSpatialIndex",
    "modelOpenAcceleration", "runtimeExportAcceleration",
}
CUBISM_JVMS = {"graalvm", "bundled"}
MEMORY_PROFILES = {"system", "balanced4g", "balanced4gFastSoft"}


def require_string_array(value, name):
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise ValueError(f"{name} must be an array of strings")


def validate_v1(doc):
    if not isinstance(doc, dict):
        raise ValueError("runtime config root must be an object")
    unknown = set(doc) - V1_FIELDS
    if unknown:
        raise ValueError("unknown v1 fields: " + ",".join(sorted(unknown)))
    if doc.get("format") != "turboism.runtime.config":
        raise ValueError("current schema format mismatch")
    if type(doc.get("schemaVersion")) is not int or doc["schemaVersion"] != CURRENT_SCHEMA:
        raise ValueError("schemaVersion must be the JSON integer 1")
    worktree = doc.get("worktreeId")
    if not isinstance(worktree, str) or not re.fullmatch(r"[a-z][a-z0-9-]{2,63}", worktree):
        raise ValueError("worktreeId is invalid")
    if "pluginDirs" in doc:
        require_string_array(doc["pluginDirs"], "pluginDirs")
        for path in doc["pluginDirs"]:
            if path.startswith("/") or path.startswith("\\\\") or ".." in path:
                raise ValueError("pluginDirs contains an unsafe path")
    if "disabledPlugins" in doc:
        require_string_array(doc["disabledPlugins"], "disabledPlugins")
    if "logLevel" in doc and doc["logLevel"] is not None:
        if not isinstance(doc["logLevel"], str) or doc["logLevel"] not in LOG_LEVELS:
            raise ValueError("logLevel is invalid")
    if "maxLogStorageMiB" in doc:
        value = doc["maxLogStorageMiB"]
        if type(value) is not int or not 1 <= value <= 4096:
            raise ValueError("maxLogStorageMiB is invalid")
    if "locale" in doc:
        if not isinstance(doc["locale"], str) or doc["locale"] not in LOCALES:
            raise ValueError("locale is invalid")
    if "safeMode" in doc and type(doc["safeMode"]) is not bool:
        raise ValueError("safeMode must be boolean")
    if "useTextIcon" in doc and type(doc["useTextIcon"]) is not bool:
        raise ValueError("useTextIcon must be boolean")
    for field in BOOLEAN_V1_FIELDS:
        if field in doc and type(doc[field]) is not bool:
            raise ValueError(field + " must be boolean")
    if "hooks" in doc:
        hooks = doc["hooks"]
        if not isinstance(hooks, dict) or set(hooks) - HOOK_FIELDS:
            raise ValueError("hooks is invalid")
        for field in ("disabledIds", "denylistedClasses"):
            if field in hooks:
                require_string_array(hooks[field], "hooks." + field)
        if "startup" in hooks:
            startup = hooks["startup"]
            if not isinstance(startup, dict) or set(startup) - STARTUP_FIELDS:
                raise ValueError("hooks.startup is invalid")
            if any(type(value) is not bool for value in startup.values()):
                raise ValueError("hooks.startup values must be boolean")
    if "launcher" in doc:
        launcher = doc["launcher"]
        if not isinstance(launcher, dict) or set(launcher) - LAUNCHER_FIELDS:
            raise ValueError("launcher is invalid")
        if "cubismJvm" in launcher:
            if not isinstance(launcher["cubismJvm"], str) or launcher["cubismJvm"] not in CUBISM_JVMS:
                raise ValueError("launcher.cubismJvm is invalid")
        if "memoryProfile" in launcher:
            if (not isinstance(launcher["memoryProfile"], str)
                    or launcher["memoryProfile"] not in MEMORY_PROFILES):
                raise ValueError("launcher.memoryProfile is invalid")
        for field in BOOLEAN_LAUNCHER_FIELDS:
            if field in launcher and type(launcher[field]) is not bool:
                raise ValueError("launcher." + field + " must be boolean")
        if "graalVmPath" in launcher:
            path = launcher["graalVmPath"]
            if (not isinstance(path, str) or not path.strip() or len(path) > 4096
                    or any(ord(character) < 0x20 for character in path)):
                raise ValueError("launcher.graalVmPath is invalid")
    if "textureAtlas" in doc:
        atlas = doc["textureAtlas"]
        if not isinstance(atlas, dict) or set(atlas) - TEXTURE_ATLAS_FIELDS:
            raise ValueError("textureAtlas is invalid")
        if "algorithmId" in atlas:
            algorithm = atlas["algorithmId"]
            if algorithm is not None and (not isinstance(algorithm, str) or not algorithm.strip()):
                raise ValueError("textureAtlas.algorithmId is invalid")
        if "parallel" in atlas and type(atlas["parallel"]) is not bool:
            raise ValueError("textureAtlas.parallel must be boolean")
    if "guide" in doc:
        guide = doc["guide"]
        if not isinstance(guide, dict) or set(guide) - GUIDE_FIELDS:
            raise ValueError("guide is invalid")
        if "showOnStartup" in guide and type(guide["showOnStartup"]) is not bool:
            raise ValueError("guide.showOnStartup must be boolean")
    return doc


def _valid_graal_path(value):
    return (isinstance(value, str) and value.strip() and len(value) <= 4096
            and not any(ord(character) < 0x20 for character in value))


def _string_list_or_none(value):
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        return None
    return list(value)


def normalize_v1(doc):
    """Tolerant normalization: promote any parseable own document to current v1.

    Carry over valid known field values; drop unknown fields and invalid values instead of
    rejecting; promote legacy root cubismJvm/graalVmPath into launcher (launcher wins). Fail
    closed only when format explicitly names another product. Result must pass full v1 checks."""
    if not isinstance(doc, dict):
        raise ValueError("runtime config root must be an object")
    if isinstance(doc.get("format"), str) and doc["format"] != "turboism.runtime.config":
        raise ValueError("foreign config format")
    normalized = {
        "format": "turboism.runtime.config",
        "schemaVersion": CURRENT_SCHEMA,
    }
    worktree = doc.get("worktreeId")
    normalized["worktreeId"] = (
        worktree if isinstance(worktree, str)
        and re.fullmatch(r"[a-z][a-z0-9-]{2,63}", worktree)
        else "turboism-runtime")
    dirs = _string_list_or_none(doc.get("pluginDirs"))
    if dirs is None:
        normalized["pluginDirs"] = ["plugins"]
    else:
        normalized["pluginDirs"] = [
            p for p in dirs
            if not p.startswith("/") and not p.startswith("\\\\") and ".." not in p]
    disabled = _string_list_or_none(doc.get("disabledPlugins"))
    if disabled is not None:
        normalized["disabledPlugins"] = [i for i in disabled if i != ""]
    if isinstance(doc.get("logLevel"), str) and doc["logLevel"] in LOG_LEVELS:
        normalized["logLevel"] = doc["logLevel"]
    if (type(doc.get("maxLogStorageMiB")) is int
            and 1 <= doc["maxLogStorageMiB"] <= 4096):
        normalized["maxLogStorageMiB"] = doc["maxLogStorageMiB"]
    if isinstance(doc.get("locale"), str) and doc["locale"] in LOCALES:
        normalized["locale"] = doc["locale"]
    for field in ("safeMode", "useTextIcon") + tuple(sorted(BOOLEAN_V1_FIELDS)):
        if type(doc.get(field)) is bool:
            normalized[field] = doc[field]
    if "diagnostics" in doc:
        normalized["diagnostics"] = doc["diagnostics"]
    if isinstance(doc.get("hooks"), dict):
        hooks = {}
        for field in ("disabledIds", "denylistedClasses"):
            values = _string_list_or_none(doc["hooks"].get(field))
            if values is not None:
                hooks[field] = values
        startup = doc["hooks"].get("startup")
        if isinstance(startup, dict):
            hooks["startup"] = {
                key: value for key, value in startup.items()
                if key in STARTUP_FIELDS and type(value) is bool}
        normalized["hooks"] = hooks
    if isinstance(doc.get("textureAtlas"), dict):
        atlas = {}
        if "algorithmId" in doc["textureAtlas"]:
            algorithm = doc["textureAtlas"]["algorithmId"]
            if algorithm is None or (isinstance(algorithm, str) and algorithm.strip()):
                atlas["algorithmId"] = algorithm
        if type(doc["textureAtlas"].get("parallel")) is bool:
            atlas["parallel"] = doc["textureAtlas"]["parallel"]
        normalized["textureAtlas"] = atlas
    if isinstance(doc.get("guide"), dict):
        guide = {}
        if type(doc["guide"].get("showOnStartup")) is bool:
            guide["showOnStartup"] = doc["guide"]["showOnStartup"]
        if guide:
            normalized["guide"] = guide
    launcher = {}
    source_launcher = doc.get("launcher")
    if isinstance(source_launcher, dict):
        cubism_jvm = source_launcher.get("cubismJvm")
        if isinstance(cubism_jvm, str) and cubism_jvm in CUBISM_JVMS:
            launcher["cubismJvm"] = cubism_jvm
        if _valid_graal_path(source_launcher.get("graalVmPath")):
            launcher["graalVmPath"] = source_launcher["graalVmPath"]
        profile = source_launcher.get("memoryProfile")
        if isinstance(profile, str) and profile in MEMORY_PROFILES:
            launcher["memoryProfile"] = profile
        for field in BOOLEAN_LAUNCHER_FIELDS:
            if type(source_launcher.get(field)) is bool:
                launcher[field] = source_launcher[field]
    for field in LEGACY_ROOT_LAUNCHER_FIELDS:
        if field in doc and field not in launcher:
            value = doc[field]
            ok = (isinstance(value, str) and value in CUBISM_JVMS) \
                if field == "cubismJvm" else _valid_graal_path(value)
            if ok:
                launcher[field] = value
    if launcher:
        normalized["launcher"] = launcher
    return validate_v1(normalized)


def desired_disabled(doc, mode="full", unchecked=None, bundled_ids=BUNDLED_IDS):
    existing = list(doc.get("disabledPlugins", []))
    require_string_array(existing, "disabledPlugins")
    requested = set(unchecked or [])
    if not requested <= set(bundled_ids):
        raise ValueError("unchecked plugin is not bundled")
    if mode == "thin":
        # A thin install owns no bundled plugin, so it only prunes retired ids.
        return sorted(set(existing) - set(RETIRED_IDS))
    # A full install applies the page selection: a checked bundled id is enabled, an
    # unchecked one is disabled, and ids outside the bundle keep their current state.
    return sorted((set(existing) - set(bundled_ids) - set(RETIRED_IDS)) | requested)


def installer_write_config(mode, unchecked, existing_text, bundled_ids=BUNDLED_IDS):
    """Mirror pre-install SecConfig: validate/migrate then apply selection; installer updates only disabledPlugins."""
    current = None
    if existing_text is None:
        document = {
            "format": "turboism.runtime.config",
            "schemaVersion": CURRENT_SCHEMA,
            "worktreeId": "turboism-runtime",
            "pluginDirs": ["plugins"],
            "useTextIcon": False,
            "guide": {"showOnStartup": True},
            "launcher": {"cubismJvm": "graalvm"},
        }
    else:
        document = json.loads(existing_text)
        try:
            validate_v1(document)
            current = document
        except ValueError:
            document = normalize_v1(document)

    disabled = desired_disabled(document, mode, unchecked, bundled_ids)
    updated = dict(document)
    if disabled:
        updated["disabledPlugins"] = disabled
    else:
        updated.pop("disabledPlugins", None)
    validate_v1(updated)

    if current is not None and sorted(set(current.get("disabledPlugins", []))) == disabled:
        return existing_text
    return json.dumps(updated, ensure_ascii=False, separators=(",", ":")) + "\r\n"


PRE_NORMALIZE_BACKUP = "config.json.corrupt.bak"


def installer_write_config_with_backup(mode, unchecked, existing_text, bundled_ids=BUNDLED_IDS):
    """Mirror SecConfig pre-normalization backup: keep one snapshot only when normalization overwrites
    existing config.json. Returns (written text, backup bytes or None); neither current v1
    updates nor fresh template creation produce config.json.corrupt.bak."""
    output = installer_write_config(mode, unchecked, existing_text, bundled_ids)
    if existing_text is None:
        return output, None
    try:
        validate_v1(json.loads(existing_text))
    except ValueError:
        return output, existing_text
    return output, None


def nsis_jars_after(mode, prev_jars):
    """Mirror NSIS upgrade: Full retires legacy official JARs and installs all current plugins;
    Thin installer skips retirement and writes no new JARs (plugins/ stays intact)."""
    if mode in ("thin", "lite"):
        return sorted(prev_jars)
    retained = [module for module in prev_jars if module not in RETIRED_MODULES]
    if mode == "full":
        return sorted(set(retained) | set(BUNDLED_MODULES))
    return sorted(retained)


def uninstall_statements():
    """Return non-empty, non-comment Uninstall Section statements as (line_number, text) in order."""
    lines = INSTALLER_NSI.read_text(encoding="utf-8").splitlines()
    start = next(i for i, l in enumerate(lines) if l.strip() == 'Section "Uninstall"')
    end = next(i for i in range(start + 1, len(lines)) if lines[i].strip() == "SectionEnd")
    return [(i + 1, l.strip()) for i, l in enumerate(lines[start + 1:end])
            if l.strip() and not l.strip().startswith(";")]


def find_stmt(texts, pred):
    for i, t in enumerate(texts):
        if pred(t):
            return i
    return None


def check_nsis_retirement_contract():
    """NSIS upgrade must retire the four historical modules/ids together with the Java installer."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    common = (INSTALLER_NSI.parent / "cubism-launch-common.ps1").read_text(encoding="utf-8")
    configure = (INSTALLER_NSI.parent / "configure_turboism.ps1").read_text(encoding="utf-8")
    call = "-RetirePlugins"
    check("R1 NSIS invokes retirement through identity-verified helper", call in text)
    check("R1 NSIS extracts retirement helper from current staging",
          '${STAGING_DIR}/configure_turboism.ps1' in text
          and '${STAGING_DIR}/cubism-launch-common.ps1' in text
          and '$PLUGINSDIR\\Turboism-retire' in text)
    lines = text.splitlines()
    exec_index = next(i for i, line in enumerate(lines)
                      if "nsExec::ExecToLog" in line and call in line)
    guard = "\n".join(lines[exec_index:exec_index + 7])
    check("R1 NSIS retirement fails closed",
          "$0 != 0" in guard and "PluginRetireError" in guard and "Abort" in guard)
    check("R1 configurator exposes RetirePlugins mode",
          "[switch]$RetirePlugins" in configure
          and "Remove-TurboismRetiredPlugins" in configure)
    check("R1 retirement authorization reads embedded plugin.json id",
          "function Remove-TurboismRetiredPlugins" in common
          and "META-INF/turboism/plugin.json" in common)

    # packaging/retired-plugins.txt is the single authority for a retired id. Both scripts and both
    # Java sites must reference it, and the list content is the frozen regression set.
    retired_manifest = MANIFEST_PATH.parent / "retired-plugins.txt"
    raw = retired_manifest.read_text(encoding="utf-8").splitlines()
    bad = [l for l in raw
           if not re.fullmatch(r"dev\.turboism\.plugin\.[a-z0-9-]+", l.strip())]
    check("R1b every retired manifest entry is a valid plugin id", not bad, f"bad={bad[:3]}")
    entries = [l.strip() for l in raw]
    check("R1b retired manifest has no blank lines or comments",
          len(entries) == len(raw) and all(entries))
    check("R1b retired manifest has no duplicates and is ASCII sorted",
          len(set(entries)) == len(entries) and entries == sorted(entries))
    check("R1b retired manifest matches frozen regression set",
          sorted(entries) == sorted(RETIRED_IDS), f"n={len(entries)}")
    check("R1c NSIS staging carries retired manifest beside scripts",
          'File "/oname=retired-plugins.txt" "${STAGING_DIR}/retired-plugins.txt"' in text
          and 'Delete "$INSTDIR\\retired-plugins.txt"' in text)
    literal = re.compile(r'"(dev\.turboism\.plugin\.[a-z0-9-]+)"\s*,')
    check("R1c three former hardcoded sites reference the manifest",
          "Get-TurboismRetiredPluginIds" in common
          and "Get-TurboismRetiredPluginIds" in configure
          and not any(ident in literal.findall(common) + literal.findall(configure)
                      for ident in RETIRED_IDS))
    java_merge = (INSTALLER_NSI.parents[1] / "java-installer" / "listener-src" /
                  "dev" / "turboism" / "installer" / "ConfigMerge.java").read_text(encoding="utf-8")
    check("R1d ConfigMerge loads retired manifest from packaged resource",
          '"/turboism/retired-plugins.txt"' in java_merge
          and "loadRetiredPluginIds" in java_merge
          and all(ident not in literal.findall(java_merge)
                  for ident in RETIRED_IDS))
    gradle = (INSTALLER_NSI.parents[1] / "java-installer" /
              "installer.gradle.kts").read_text(encoding="utf-8")
    check("R1d retired manifest is packaged in listener/regression jar",
          '"packaging/retired-plugins.txt"' in gradle
          and 'include("retired-plugins.txt")' in gradle)
    contract = (INSTALLER_NSI.parents[2] / "runtime" / "src" / "main" / "java" /
                "dev" / "turboism" / "core" / "plugin" /
                "PluginJarContract.java").read_text(encoding="utf-8")
    check("R1e runtime admission boundary loads from the same manifest",
          '"/META-INF/turboism/retired-plugins.txt"' in contract
          and "loadRetiredPluginIds" in contract
          and all(ident not in literal.findall(contract)
                  for ident in RETIRED_IDS))


def check_config_migration_contract():
    """Config validation, migration, and plugin selection commit precede every payload write."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    configure = (INSTALLER_NSI.parent / "configure_turboism.ps1").read_text(encoding="utf-8")
    generated = (INSTALLER_NSI.parent / "plugin-sections.nsh").read_text(encoding="utf-8")
    config_start = text.index('Section "-WriteConfig" SecConfig')
    config_end = text.index("SectionEnd", config_start)
    section = text[config_start:config_end]
    check("CM1 config commit precedes every permanent payload section",
          config_start < text.index('Section "-CoreFiles" SecCore')
          and config_start < text.index('!include "plugin-sections.nsh"')
          and 'Section "-PluginPayload" SecPluginPayload' in generated
          and 'Section "-ManagedGraal"' not in text)
    check("CM2 NSIS uses the current staged helper rather than an installed prior version",
          '$PLUGINSDIR\\Turboism-config\\configure_turboism.ps1' in section
          and '${STAGING_DIR}/configure_turboism.ps1' in section
          and '${STAGING_DIR}/cubism-launch-common.ps1' in section)
    check("CM3 installer selection is explicit and complete",
          "Call SetBundledPluginIds" in section
          and "-ApplyInstallerSelection" in section
          and '-Variant "thin"' not in section
          and '-Variant "full"' in section
          and '-BundledPluginIds "$bundledPluginIds"' in section
          and "Function SetBundledPluginIds" in generated)
    exported = re.search(
        r'Function SetBundledPluginIds\s+StrCpy \$bundledPluginIds "([^"]+)"',
        generated,
    )
    check("CM3b bundled selection inventory matches release plugin manifest",
          exported is not None
          and len(exported.group(1).split(";")) == len(REAL_MODULES))
    check("CM4 config failure aborts before payload mutation",
          "ConfigMigrationError" in section and "Abort" in section
          and "-RetirePlugins" not in section)
    check("CM5 schemaVersion requires a JSON integer token",
          "function Get-RuntimeConfigSchemaVersion" in configure
          and "schemaVersion must be an integer token" in configure
          and "[decimal]$property.Value" not in configure)
    check("CM5b one legacy UTF-8 BOM is tolerated but a second is rejected",
          "$text[0] -eq [char]0xFEFF" in configure
          and "config.json contains more than one UTF-8 BOM" in configure)
    check("CM6 current and migrated documents use full v1 validation",
          "function Assert-RuntimeConfigV1" in configure
          and configure.count("Assert-RuntimeConfigV1") >= 3
          and "launcher.cubismJvm is invalid" in configure
          and "hooks.startup" in configure
          and 'Assert-RuntimeStringArray $pluginDirs.Value "pluginDirs"' in configure
          and "must be an array of strings" in configure)
    check("CM6b v1 validator admits every runtime schema field",
          all(name in configure for name in (
              "reduceAutoBackup", "meshTriangulationHashFix",
              "atlasTileBbox", "atlasCacheReuse", "meshTriangulationEdgeIndex",
              "atlasLoading", "triangulationSpatialIndex",
              "modelOpenAcceleration", "runtimeExportAcceleration",
          ))
          and '"zgc", "modelUpdateSkip", "incrementalUpdate"' in configure)
    check("CM6c merged documents with runtime boolean fields pass v1",
          validate_v1({
              "format": "turboism.runtime.config", "schemaVersion": 1,
              "worktreeId": "turboism-runtime", "pluginDirs": ["plugins"],
              "reduceAutoBackup": True, "meshTriangulationHashFix": False,
              "atlasTileBbox": True, "atlasCacheReuse": False,
              "meshTriangulationEdgeIndex": False, "atlasLoading": False,
              "triangulationSpatialIndex": False,
              "modelOpenAcceleration": True, "runtimeExportAcceleration": False,
              "launcher": {"cubismJvm": "bundled", "zgc": False,
                           "modelUpdateSkip": False, "incrementalUpdate": True, "uniformLocationCache": False},
          }) is not None)
    check("CM7 migration/selection publish atomically and fail closed",
          "[System.IO.File]::Replace($temporary, $configPath, $backup, $true)" in configure
          and "if ($published -and (Test-Path -LiteralPath $backup -PathType Leaf))" in configure
          and "CONFIG_SELECTION_FAILED" in configure
          and "ConfigMigrationError" in section)


def ps_function_body(text, name):
    """Return the body of a top-level PowerShell function up to the next top-level function."""
    start = text.index("function " + name)
    end = text.find("\nfunction ", start + 1)
    return text[start:] if end == -1 else text[start:end]


def check_pre_normalize_backup_contract():
    """N1: keep one config.json.corrupt.bak before normalization; current v1 and fresh install keep none."""
    configure = (INSTALLER_NSI.parent / "configure_turboism.ps1").read_text(encoding="utf-8")
    installer_root = INSTALLER_NSI.parents[1] / "java-installer"
    listener_src = installer_root / "listener-src" / "dev" / "turboism" / "installer"
    config_merge = (listener_src / "ConfigMerge.java").read_text(encoding="utf-8")
    listener = (listener_src / "TurboismInstallerListener.java").read_text(encoding="utf-8")

    save = ps_function_body(configure, "Save-RuntimeConfigPreNormalizeBackup")
    check("N1 PowerShell keeps a bounded pre-normalize image", all(
        fragment in save for fragment in (
            PRE_NORMALIZE_BACKUP,
            "Test-CubismNormalFile $configPath",
            "Test-CubismNormalFile $backup",
            "[System.IO.File]::Copy($configPath, $temporary, $false)",
            "[System.IO.File]::Replace($temporary, $backup, $null, $true)",
            "[System.IO.File]::Move($temporary, $backup)",
        )))
    check("N1 PowerShell image is best effort and never follows a link",
          "TURBOISM_CONFIG pre-normalize backup failed" in save
          and "skipped pre-normalize backup" in save
          and "Remove-Item -LiteralPath $temporary" in save)

    migration = ps_function_body(configure, "Invoke-RuntimeConfigMigration")
    check("N1 migration keeps the image before it rewrites config.json",
          "Save-RuntimeConfigPreNormalizeBackup" in migration
          and migration.index("Save-RuntimeConfigPreNormalizeBackup")
          < migration.index("Write-RuntimeConfigAtomic"))

    selection = ps_function_body(configure, "Invoke-InstallerPluginSelection")
    guarded = "if ($hadConfig -and -not $wasValid) { Save-RuntimeConfigPreNormalizeBackup }"
    saves = [i for i in range(len(selection)) if selection.startswith(guarded, i)]
    writes = [i for i in range(len(selection)) if selection.startswith("Write-RuntimeConfigAtomic", i)]
    check("N1 thin and full selection keep the image only for an invalid existing document",
          len(saves) == 2 and len(writes) == 2 and all(s < w for s, w in zip(saves, writes)))

    check("N1 Java installer keeps the same image before normalization overwrites",
          'CORRUPT_BACKUP_FILE = "config.json.corrupt.bak"' in config_merge
          and "static void preservePreNormalizeBackup(Path home)" in config_merge
          and "readBounded(config)" in config_merge
          and "StandardCopyOption.ATOMIC_MOVE" in config_merge
          and "if (existing && !current) {" in listener
          and listener.index("preservePreNormalizeBackup(home)")
          < listener.index("ConfigMerge.write(home, updated)"))


def check_java_installer_docs_contract():
    """Shipped docs describe plugin-only packages and the installer-owned selection update."""
    markdown = (INSTALLER_NSI.parents[1] / "java-installer" /
                "README-java-installer.md").read_text(encoding="utf-8")
    installed = (INSTALLER_NSI.parents[1] / "java-installer" /
                 "README.java-installer.txt").read_text(encoding="utf-8")
    combined = re.sub(r"\s+", " ", markdown + "\n" + installed)
    markdown_flat = re.sub(r"\s+", " ", markdown)
    installed_flat = re.sub(r"\s+", " ", installed)
    stale = (
        "Windows x64 Full installs the reviewed fx product payload",
        "matching reviewed managed fx runtime",
        "required plugin and managed fx payload packs",
    )
    check("DOC1 Java-installer docs contain no bundled managed-fx publication claim",
          all(fragment not in combined for fragment in stale))
    check("DOC2 Java-installer docs describe plugin-only Full and Thin modes",
          "Thin installs the complete plugin roster" in markdown_flat
          and "Full mode installs the agent and every approved release plugin" in installed_flat
          and "without an additional native runtime payload" in installed_flat)
    check("DOC3 rerunning the installer is documented as applying plugin selection",
          "Rerun the installer to apply a different plugin selection." in markdown_flat
          and "Rerun the installer to apply a different plugin selection." in installed_flat)


def check_managed_graal_installer_contract():
    """Installer-time Graal provisioning is opt-in, pinned, and Java-independent."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    bridge_path = INSTALLER_NSI.parent / "install-managed-graal.ps1"
    bridge = bridge_path.read_text(encoding="utf-8")
    check("GI1 managed Graal option defaults to later",
          "StrCpy $installManagedGraal 0" in text
          and "${NSD_Check} $GraalLaterRadio" in text)
    check("GI2 managed Graal choice is independent of variant",
          "${NSD_CreateRadioButton}" in text and "$(GraalNowChoice)" in text
          and "$(GraalLaterChoice)" in text)
    check("GI3 no install occurs unless selected",
          "${If} $installManagedGraal != 1" in text
          and "install-managed-graal.ps1" in text)
    graal_page = text.index("Page custom GraalCreate GraalLeave")
    install_page = text.index("Page custom GraalInstallCreate GraalInstallLeave")
    discovery_page = text.index("Page custom CubismDiscoveryCreate CubismDiscoveryLeave")
    check("GI4 managed Graal install is a dedicated page right after the strategy page",
          graal_page < install_page < discovery_page
          and install_page < text.index("Page custom LaunchOptionsCreate")
          and text.index("MUI_PAGE_DIRECTORY") < graal_page
          and 'Section "-ManagedGraal"' not in text)
    graal_create_start = text.index("Function GraalInstallCreate")
    graal_create_end = text.index("FunctionEnd", graal_create_start)
    graal_create = text[graal_create_start:graal_create_end]
    graal_begin_start = text.index("Function GraalInstallBegin")
    graal_begin_end = text.index("FunctionEnd", graal_begin_start)
    graal_begin = text[graal_begin_start:graal_begin_end]
    check("GI4b install page skips itself when install-later was chosen",
          "${If} $installManagedGraal != 1" in graal_create
          and "Abort" in graal_create)
    check("GI4c config migration preflight precedes the download worker",
          "-MigrateConfig" in graal_begin
          and graal_begin.index("-MigrateConfig") < graal_begin.index("ExecShell")
          and "install-managed-graal.ps1" in graal_begin
          and "-StatusFile" in graal_begin and "-CancelFile" in graal_begin)
    check("GI4d page polls the worker status file and never blocks the wizard",
          "${NSD_CreateTimer} GraalInstallPoll 250" in graal_begin
          and "SW_HIDE" in graal_begin
          and "Function GraalInstallPoll" in text
          and "FileReadUTF16LE $GraalInstallHandle $line" in text
          and "TURBOISM_GRAAL_STATUS_V1" in text
          and "graal-status.txt" in text and "graal-cancel.flag" in text)
    check("GI4e selected failure stays on the page and offers retry",
          "ManagedGraalInstallError" in text
          and "Function GraalInstallRetryClick" in text
          and "Function GraalInstallCancelClick" in text
          and "${NSD_OnBack} GraalInstallBack" in graal_create
          and "EnableWindow $GraalInstallNext 0" in graal_create)
    service = (INSTALLER_NSI.parents[2] / "runtime/src/main/java/dev/turboism/graal/ManagedGraalRuntimeService.java").read_text(encoding="utf-8")
    # packaging/managed-graal.json is the single source of the pinned value. Both consumers, the
    # PS1 by file name and Java by packaged resource path, must reference it, and the list content
    # matches the frozen regression set.
    graal_manifest = json.loads((MANIFEST_PATH.parent / "managed-graal.json").read_text(encoding="utf-8"))
    graal_pin = graal_manifest["platforms"]["windows-x64"]
    check("GI5 managed Graal pinned manifest contents match expected values",
          graal_manifest["format"] == "turboism.managed-graal"
          and graal_manifest["schemaVersion"] == 1
          and graal_manifest["graalVersion"] == "25.2.4"
          and graal_manifest["javaVersion"] == "25.0.4"
          and graal_pin["archiveName"] == "graalvm-community-jdk-25i2-25.0.4_windows-x64_bin.zip"
          and graal_pin["archiveBytes"] == 341299924
          and graal_pin["sha256"] == "789d2af1c06c3c24f402d2d4a711bdbb19b36f7d8c74afe6a959492fd121ef33"
          and graal_manifest["graalVersion"] in graal_pin["url"]
          and graal_pin["archiveName"] in graal_pin["url"])
    check("GI5 native installer reads the shared manifest instead of its own pins",
          "managed-graal.json" in bridge
          and "archiveBytes" not in bridge.split("Get-ManagedGraalManifest")[0])
    check("GI5 runtime service reads the bundled manifest instead of its own pins",
          '"/META-INF/turboism/managed-graal.json"' in service
          and "graalvm-community-jdk-25i2-25.0.4_windows-x64_bin.zip" not in service
          and "341_299_924" not in service and "341299924" not in service)
    # The interface constants and the build version directory must agree with the list, and the build script must read that directory.
    jvm_settings = (INSTALLER_NSI.parents[2] / "core/contract/src/main/java/dev/turboism/internal/core/CubismJvmSettingsService.java").read_text(encoding="utf-8")
    graal_build = (INSTALLER_NSI.parents[2] / "graal-host/build.gradle.kts").read_text(encoding="utf-8")
    catalog_graal = re.search(
        r'^graal\s*=\s*"([^"]+)"',
        (INSTALLER_NSI.parents[2] / "gradle/libs.versions.toml").read_text(encoding="utf-8"),
        re.MULTILINE,
    )
    check("GI5b contract constants and build script versions match manifest",
          ('MANAGED_GRAAL_VERSION = "%s"' % graal_manifest["graalVersion"]) in jvm_settings
          and ('MANAGED_JAVA_VERSION = "%s"' % graal_manifest["javaVersion"]) in jvm_settings
          and "val graalVersion = libs.versions.graal.get()" in graal_build
          and catalog_graal is not None and catalog_graal.group(1) == graal_manifest["graalVersion"])
    check("GI6 installation never discovers or launches Java",
          all(token not in bridge for token in (
              "Test-TurboismJava17", "Find-TurboismInstallerJava", "ProcessStartInfo",
              "ManagedGraalRuntimeCli", "turboism-agent.jar", "JAVA_HOME", "Get-CubismInstallations")))
    check("GI7 native installer records the path without changing the JVM preference",
          "graalVmPath" in bridge and "Save-ManagedGraalConfig" in bridge
          and "File]::Replace" in bridge)
    check("GI8 bridge avoids PowerShell's read-only HOME variable",
          not re.search(r"(?i)\$home\b", bridge))
    check("GI9 bridge persists managed Graal diagnostics",
          "managed-graal-install.log" in bridge
          and 'Join-Path $turboismHome "logs\\installer"' in bridge
          and "Add-Content -LiteralPath $LogPath" in bridge)
    check("GI10 native installer logs terminal outcomes without blocking pipe reads",
          "GRAAL_INSTALL_EXIT code=" in bridge and "GRAAL_INSTALL_EXCEPTION" in bridge
          and "StandardOutput.Peek" not in bridge)
    check("GI10b installer help no longer requires a preinstalled JVM or host probe",
          "Java 17" not in text and "and the isolated host are verified" not in text)
    graal_create = text[text.index("Function GraalCreate"):
                        text.index("FunctionEnd", text.index("Function GraalCreate"))]
    check("GI11a runtime strategy replaces the previous page header",
          '!insertmacro MUI_HEADER_TEXT "$(GraalPageTitle)" ""' in graal_create)
    check("GI11 Graal page rows are contiguous at 12pt",
          '${NSD_CreateLabel} 0 0 100% 24u "$(GraalPageTitle)"' in graal_create
          and '${NSD_CreateLabel} 0 24u 100% 42u "$(GraalPageDescription)"' in graal_create
          and '${NSD_CreateRadioButton} 0 67u 100% 16u "$(GraalNowChoice)"' in graal_create
          and '${NSD_CreateRadioButton} 0 84u 100% 16u "$(GraalLaterChoice)"' in graal_create
          and '${NSD_CreateLabel} 12u 101u 96% 42u "$(GraalProgressHint)"' in graal_create)
    graal_install_keys = (
        "GraalInstallTitle", "GraalInstallPreparing", "GraalStateDownloading",
        "GraalStateVerifying", "GraalStateExtracting", "GraalInstallReady",
        "GraalInstallAlready", "GraalInstallCancelled", "GraalInstallCancelling",
        "GraalInstallCancelText", "GraalInstallRetryText",
    )
    check("GI12 install page has English, Simplified Chinese, Japanese, and Korean text",
          all(text.count("LangString %s ${LANG_" % key) == 4 for key in graal_install_keys)
          and 'LangString GraalInstallTitle ${LANG_SIMPCHINESE} "Turboism 托管的 GraalVM"' in text)
    check("GI13 worker exposes the page's status and cancel protocol",
          'Write-ManagedGraalStatus ("STATE|" + $State + "|" + $Done + "|" + $Total)' in bridge
          and '"TURBOISM_GRAAL_STATUS_V1", $Record' in bridge
          and '[string]$StatusFile' in bridge and '[string]$CancelFile' in bridge
          and 'Write-ManagedGraalStatus ("EXIT|" + $result)' in bridge
          and 'Test-Path -LiteralPath $script:graalCancelPath' in bridge
          and "TURBOISM_GRAAL_STATUS_V1" in text)


def check_configurator_flow_contract():
    """Configurator is post-install, exact-versioned, resizable, logged and selection-bound."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    configure = (INSTALLER_NSI.parent / "configure_turboism.ps1").read_text(encoding="utf-8")
    common = (INSTALLER_NSI.parent / "cubism-launch-common.ps1").read_text(encoding="utf-8")
    check("CF1 launch choices precede payload installation",
          text.index("Page custom LaunchOptionsCreate LaunchOptionsLeave")
          < text.index("MUI_PAGE_INSTFILES"))
    directory_page = text.index("MUI_PAGE_DIRECTORY")
    discovery_page = text.index("Page custom CubismDiscoveryCreate CubismDiscoveryLeave")
    launch_page = text.index("Page custom LaunchOptionsCreate LaunchOptionsLeave")
    check("CF1b exact Cubism discovery is visible before launch choices and payload mutation",
          directory_page < discovery_page < launch_page < text.index("MUI_PAGE_INSTFILES"))
    discovery_start = text.index("Function CubismDiscoveryCreate")
    discovery_end = text.index("FunctionEnd", discovery_start)
    discovery_create = text[discovery_start:discovery_end]
    prepare_start = text.index("Function CubismDiscoveryPrepareFiles")
    discovery_prepare = text[prepare_start:text.index("FunctionEnd", prepare_start)]
    check("CF1c pre-install discovery stages the current exact verifier payload",
          "Call CubismDiscoveryPrepareFiles" in discovery_create
          and all(path in discovery_prepare for path in (
              '${STAGING_DIR}/turboism-agent.jar',
              '${STAGING_DIR}/configure_turboism.ps1',
              '${STAGING_DIR}/cubism-launch-common.ps1'))
          and "$PLUGINSDIR\\Turboism-discovery-$CubismDiscoveryGeneration" in discovery_prepare)
    scanner_exec = next(line for line in discovery_create.splitlines()
                        if "InstallerDiscoveryOutput" in line)
    check("CF1d pre-install discovery launches asynchronously without taking focus",
          scanner_exec.strip().startswith('ExecShell ""')
          and "SW_HIDE" in scanner_exec
          and "ExecWait" not in scanner_exec
          and "nsExec" not in scanner_exec)
    check("CF1e pre-install discovery disables Next before timer polling",
          discovery_create.index("EnableWindow $CubismDiscoveryNext 0")
          < discovery_create.index("${NSD_CreateTimer} CubismDiscoveryPoll 250"))
    poll_start = text.index("Function CubismDiscoveryPoll")
    poll_end = text.index("FunctionEnd", poll_start)
    poll = text[poll_start:poll_end]
    reader_start = text.index("Function CubismDiscoveryReadResult")
    reader = text[reader_start:text.index("FunctionEnd", reader_start)]
    fail_start = text.index("Function CubismDiscoveryFail")
    fail_end = text.index("FunctionEnd", fail_start)
    fail = text[fail_start:fail_end]
    back_start = text.index("Function CubismDiscoveryBack")
    back_end = text.index("FunctionEnd", back_start)
    back = text[back_start:back_end]
    check("CF1f discovery completion and recoverable failures release the wizard",
          "${NSD_KillTimer} CubismDiscoveryPoll" in poll
          and "Call CubismDiscoveryEnableNext" in poll
          and "${NSD_KillTimer} CubismDiscoveryPoll" in fail
          and "Call CubismDiscoveryEnableNext" in fail
          and "CubismDiscoveryTimeout" in poll)
    check("CF1g Back navigation invalidates the poll and restores shared Next",
          "${NSD_OnBack} CubismDiscoveryBack" in discovery_create
          and "${NSD_KillTimer} CubismDiscoveryPoll" in back
          and "EnableWindow $CubismDiscoveryNext 1" in back
          and "StrCpy $CubismDiscoveryComplete 0" in back)
    early_scan = configure.index("if (-not [string]::IsNullOrWhiteSpace($InstallerDiscoveryOutput))")
    check("CF1h configurator discovery mode is headless and precedes state/log/UI setup",
          "[string]$InstallerDiscoveryOutput" in configure
          and "Write-CubismInstallerDiscoveryReport" in configure[early_scan:configure.index("$statePath", early_scan)]
          and early_scan < configure.index("$statePath") < configure.index("Add-Type -AssemblyName System.Windows.Forms"))
    check("CF1i discovery exporter preserves exact admission and complete atomic publication",
          "function Write-CubismInstallerDiscoveryReport" in common
          and "Get-CubismDiscoveryRoots" in common
          and "Get-CubismInstallations" in common
          and "TURBOISM_CUBISM_SCAN_V1" in common
          and "$publish = $false" in common
          and "$publish = $true" in common
          and "if ($publish -and (Test-CubismNormalFile $temporary))" in common
          and "[System.IO.File]::Move($temporary, $output)" in common)
    check("CF1i2 discovery report preserves localized labels and non-ASCII paths",
          "[System.Text.UnicodeEncoding]::new($false, $true, $true)" in common
          and "FileReadUTF16LE $CubismDiscoveryHandle $line" in reader
          and "Call CubismDiscoveryReadResult" in poll)
    check("CF1i3 result parsing cannot overwrite the open report handle",
          'FileOpen $CubismDiscoveryHandle "$CubismDiscoveryResult" r' in reader
          and "FileClose $CubismDiscoveryHandle" in reader
          and "FileReadUTF16LE $0 $line" not in reader)
    discovery_keys = (
        "CubismDiscoveryTitle", "CubismDiscoveryScanning", "CubismDiscoveryComplete",
        "CubismDiscoveryNone", "CubismDiscoveryFailed", "CubismDiscoveryTimeout",
    )
    check("CF1j discovery page has English, Simplified Chinese, Japanese, and Korean text",
          all(text.count("LangString %s " % key) == 4 for key in discovery_keys)
          and all(('LangString CubismDiscoveryTitle ${LANG_%s}' % language) in text
                  for language in ("ENGLISH", "SIMPCHINESE", "JAPANESE", "KOREAN"))
          and 'LangString CubismDiscoveryTitle ${LANG_SIMPCHINESE} "Cubism 安装"' in text)
    check("CF1j1 discovery page avoids explanatory and implementation-defense wording",
          "CubismDiscoverySubtitle" not in text
          and '!insertmacro MUI_HEADER_TEXT "$(CubismDiscoveryTitle)" ""' in discovery_create
          and "精确身份" not in text
          and "Exact application JARs" not in text
          and 'LangString CubismDiscoveryScanning ${LANG_SIMPCHINESE} "正在扫描已安装的 Cubism 编辑器……"' in text)
    check("CF1j2 scaled discovery list exposes complete paths horizontally",
          "${NSD_AddStyle} $CubismDiscoveryList ${WS_HSCROLL}" in discovery_create
          and "${LB_SETHORIZONTALEXTENT} 8192" in reader)
    check("CF1k exact artifact probes and the entire pre-install worker are time-bounded",
          "$script:CubismArtifactProbeTimeoutMilliseconds = 20000" in common
          and "WaitForExit($script:CubismArtifactProbeTimeoutMilliseconds)" in common
          and "try { $process.Kill() }" in common
          and "[switch]$InstallerDiscoveryWorker" in configure
          and "$discoveryProcess.WaitForExit(105000)" in configure
          and "try { $discoveryProcess.Kill() }" in configure
          and '-FailureCode "TimeoutException"' in configure)
    check("CF1l Back/forward navigation reuses one discovery worker",
          "Var CubismDiscoveryStarted" in text
          and "${If} $CubismDiscoveryStarted == 1" in discovery_create
          and "StrCpy $CubismDiscoveryStarted 1" in discovery_create
          and discovery_create.count("InstallerDiscoveryOutput") == 1)
    check("CF1m every DISPLAY record contains only a bounded label and path",
          "-MaximumLength 520" in common
          and '[void]$lines.Add("DISPLAY|[$label] $root")' in common
          and "$candidate.Reason" not in common
          and " — $reason" not in common
          and "[int]$MaximumLength = 900" in common)
    # Final configuration must run during installation, not in .onInstSuccess:
    # that callback blocks the UI thread, so the wizard turns "Not Responding"
    # and an elevation prompt has no live owner window to attach to.
    final_section_start = text.index('Section "-FinalConfiguration" SecFinalConfiguration')
    final_section = text[final_section_start:text.index("SectionEnd", final_section_start)]
    success = text[text.index("Function RunFinalConfiguration"):
                   text.index("FunctionEnd", text.index("Function RunFinalConfiguration"))]
    check("CF2 final configuration runs as an install Section",
          final_section_start < text.index('Section "Uninstall"')
          and "Call RunFinalConfiguration" in final_section
          and "Function .onInstSuccess" not in text
          and final_section_start > text.index('Section "-StartMenuAndRegistry" SecStartMenuReg'))
    check("CF2 successful installation performs hidden headless initial configuration",
          success.count("nsExec::ExecToLog") == 5
          and "ExecWait" not in success
          and "-NonInteractive" in success
          and "-InitializeSelection" in success
          and "-EnableShortcuts" in success
          and "-DisableShortcuts" in success
          and "-IntegrateBat" in success
          and "-DisableBat" in success
          and "Exec '" not in success)
    check("CF2a every installer-time PowerShell console is hidden",
          "ExecWait" not in text
          and "ExecShell \"\" \"$SYSDIR\\WindowsPowerShell\\v1.0\\powershell.exe\"" in scanner_exec
          and "SW_HIDE" in scanner_exec
          and all(
              line.strip().startswith("nsExec::ExecToLog")
              or (line.strip().startswith('ExecShell ""') and "SW_HIDE" in line)
              for line in text.splitlines()
              if "powershell.exe" in line and line.strip().startswith(("Exec", "nsExec::"))))
    check("CF2-cache all final operations reuse the digest-bound middle scan",
          success.count("${CUBISM_DISCOVERY_HANDOFF}") == 5
          and "InstallerDiscoveryOutput" not in success
          and 'Goto InstallerConfigurationDone' in success
          and success.index('-IntegrateBat') < success.index('RMDir /r "$CubismDiscoveryWorkDir"')
          and '-InstallerDiscoverySnapshot "$CubismDiscoveryResult.json"' in text
          and '-InstallerDiscoverySnapshotSha256 "$CubismDiscoverySnapshotHash"' in text)
    check("CF2-cache reader validates the snapshot envelope before accepting it",
          '"SNAPSHOT|"' in reader
          and 'StrLen $0 $CubismDiscoverySnapshotHash' in reader
          and '${If} $0 != 64' in reader
          and '${For} $pos 0 63' in reader
          and '$CubismDiscoverySnapshotHash == ""' in reader
          and '${FileExists} "$CubismDiscoveryResult.json"' in reader)
    silent_start = text.index('Section "-SilentDiscovery"')
    silent = text[silent_start:text.index("SectionEnd", silent_start)]
    check("CF2-cache silent installation scans once before permanent configuration",
          'IfSilent 0 SilentDiscoveryDone' in silent
          and 'Call CubismDiscoveryPrepareFiles' in silent
          and 'Call CubismDiscoveryReadResult' in silent
          and silent.count('InstallerDiscoveryOutput') == 1
          and silent_start < text.index('Section "-WriteConfig"'))
    cached_start = common.index('function Read-CubismInstallerSnapshot')
    cached_reader = common[cached_start:common.index('function Write-CubismInstallerDiscoveryReport', cached_start)]
    check("CF2-cache snapshot import never enumerates drives or launches a version probe",
          all(name not in cached_reader for name in (
              'Get-CubismDiscoveryRoots', 'Get-CubismInstallations', 'Get-CubismVersionFromArtifact'))
          and 'Get-ConfiguratorCandidates -State $state' in configure)
    check("CF2-cache snapshot import never hashes the 14 MB agent or 45 MB host binaries",
          'Get-CubismSha256 $agent' not in cached_reader
          and 'Get-CubismSha256 $application' not in cached_reader
          and 'Get-CubismSha256 $java' not in cached_reader
          and 'verifierSha256 =' not in common
          and 'applicationSha256 =' not in common
          and 'javaSha256 =' not in common
          and "cne 'verifierSha256'" in cached_reader
          and "'applicationSha256', 'javaSha256'" in cached_reader)
    check("CF2b BAT integration elevates only the selected helper operation",
          "RequestExecutionLevel user" in text
          and "Start-Process" in configure
          and "-Verb RunAs" in configure
          and "$process.WaitForExit($script:ElevationTimeoutMilliseconds)" in configure
          and "$script:ElevationTimeoutMilliseconds = 60000" in configure
          and "-PassThru" in configure
          and "[switch]$Elevated" in configure
          and "ShellExecuteExW" in configure
          and "SEE_MASK_NOASYNC" in configure
          and "Test-ElevationCancelledException" in configure
          and "-OwnerHwnd $HWNDPARENT" in text
          and "AllowSetForegroundWindow" in text
          and "-RefreshReceipt" in text)
    core_start = text.index('Section "-CoreFiles" SecCore')
    core_section = text[core_start:text.index("SectionEnd", core_start)]
    check("CF2d installer clears stale bootstrap and update state before conditional receipt setup",
          "-ResetUpdateState" in core_section
          and core_section.index("-ResetUpdateState") < core_section.index("!ifdef BOOTSTRAP_RECEIPT_DIR")
          and '!define INITIALIZE_RECEIPT_SWITCH "-RefreshReceipt"' in text
          and '!define INITIALIZE_RECEIPT_SWITCH "-SkipReceipt"' in text
          and "${INITIALIZE_RECEIPT_SWITCH}" in success
          and "[switch]$ResetUpdateState" in configure
          and "[switch]$SkipReceipt" in configure
          and "Clear-TurboismFrameworkUpdateState" in configure
          and "function Clear-TurboismFrameworkUpdateState" in common
          and "function Remove-TurboismDirectoryTreeSafely" in common
          and "ReparsePoint" in common)
    disable_guard_start = configure.index("if ($DisableBat -and -not $Elevated)")
    integrate_guard_start = configure.index(
        "if ($IntegrateBat -and -not $Elevated)", disable_guard_start)
    disable_guard = configure[disable_guard_start:integrate_guard_start]
    check("CF2c fresh unchecked BAT choice exits without requesting UAC",
          "Read-CubismInstallationState" in disable_guard
          and "$disableBatState.BatIntegrations.Count -eq 0" in disable_guard
          and "exit 0" in disable_guard
          and disable_guard.index("exit 0")
          < disable_guard.index("Invoke-ElevatedConfiguratorMode")
          and "($IntegrateBat -or $DisableBat)" not in configure)
    check("CF3 main installer scales the frame and page content together",
          'SetFont "MS Shell Dlg" 12' in text
          and "MUI_CUSTOMFUNCTION_GUIINIT ResizeInstallerWindow" not in text
          and "Function ResizeInstallerWindow" not in text)
    check("CF4 configurator is large, resizable and maximizable",
          "ClientSize = New-Object System.Drawing.Size(1080, 900)" in configure
          and "MinimumSize = New-Object System.Drawing.Size(900, 720)" in configure
          and "$form.MaximizeBox = $true" in configure
          and "Anchor = 'Top, Bottom, Left, Right'" in configure)
    detector_source = (
        INSTALLER_NSI.parent.parent.parent
        / "runtime/src/main/java/dev/turboism/mapping/verification/CubismEditorReleaseDetector.java"
    ).read_text(encoding="utf-8")
    check("CF5 candidate selection resolves declared identity and admission from application artifacts",
          "Get-CubismVersionFromArtifact" in common
          and "CubismHostProbeCli" in common
          and ("HostArtifactDigest.from" in detector_source or "HostArtifactDigest.cached" in detector_source)
          and "Get-CubismVersionFromPath" not in common
          and "application artifacts are selectable" in common)
    check("CF6 BAT integration is selected in the configurator after candidates",
          "$batCheck" in configure
          and "Invoke-CubismBatIntegration" in configure
          and "SELECTION_SAVE" in configure)
    check("CF7 configurator writes actionable installer diagnostics",
          "configure-turboism.log" in configure
          and "CONFIGURATION_FAILED" in configure
          and "BAT_INTEGRATION_OK" in configure
          and 'Log: " + $installerLogPath' in configure)
    check("CF8 managed launch passes quoted JVM arguments through an ephemeral BAT",
          "function New-CubismManagedOptionsBat" in common
          and "managed Cubism JVM option contains an unsupported BAT character" in common
          and "Java environment option variables are deliberately" in common
          and '[Environment]::SetEnvironmentVariable($name, $null, "Process")' in common
          and "-Duser.dir=$root" in common
          and "-Dturboism.home=$canonicalHome" in common
          and '$agentOptions = $script:CubismAgentStartupOptions' in common
          and '$agentOptions += $script:CubismAgentLaunchTokenOption + $LaunchToken' in common
          and '"-javaagent:$Agent=$agentOptions"' in common
          and '-LaunchToken $LaunchToken' in common)


def check_jar_payload_contract():
    """Every permanent static NSIS payload entry is extracted only when SHA-256 differs."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    helper = (INSTALLER_NSI.parent / "install-jar-payload.ps1").read_text(encoding="utf-8")
    generator = (INSTALLER_NSI.parent / "assemble-release.sh").read_text(encoding="utf-8")
    generated_plugins = (INSTALLER_NSI.parent / "plugin-sections.nsh").read_text(encoding="utf-8")
    gradle = (INSTALLER_NSI.parents[1] / "java-installer" / "installer.gradle.kts").read_text(
        encoding="utf-8"
    )
    check("PAY1 helper verifies manifest SHA-256 before and after writing",
          "Test-PayloadFileUnchanged $destinationFile $entry.Hash" in helper
          and "Get-PayloadFileHash $sourceFile" in helper
          and "source checksum does not match its manifest" in helper
          and "destination checksum does not match after copy" in helper
          and 'Write-Output "SKIP|$($entry.Relative)"' in helper)
    check("PAY2 helper rejects empty, duplicate, and unsafe manifests",
          "Installer payload manifest is empty" in helper
          and "Installer payload manifest contains a duplicate path" in helper
          and "Installer payload path escapes its root" in helper
          and "Installer payload destination is a reparse point" in helper)
    check("PAY3 generator creates core and plugin checksum manifests",
          all(('write_checksum_manifest("payload-%s.sha256"' % category) in generator
              for category in ("core", "plugins"))
          and "f'plugins/{p[\"module\"]}.jar'" in generator)
    permanent_core = (
        "turboism-agent.jar", "install-jar-payload.ps1",
        "launch-cubism-turboism.bat", "launch-cubism-turboism.ps1",
        "configure_turboism.ps1", "cubism-launch-common.ps1",
        "install-managed-graal.ps1", "turboism.ico", "turboism.png",
        "README.txt", "README.zh.txt", "README.ja.txt", "LICENSE.txt",
        "EULA.en.txt", "EULA.zh-Hans.txt", "EULA.ja.txt", "EULA.ko.txt",
    )
    core_payload_source = generator[generator.index("core_payload = ["):
                                    generator.index("plugin_payload = [")]
    check("PAY4 every permanent core file is checksum-managed",
          all(('"%s"' % name) in core_payload_source for name in permanent_core)
          and 'stage / "graal" / "lib"' in core_payload_source)
    core_section = text[text.index('Section "-CoreFiles" SecCore'):
                        text.index("SectionEnd", text.index('Section "-CoreFiles" SecCore'))]
    check("PAY5 core plan precedes conditional extraction and verified copy",
          "payload-core.sha256" in core_section
          and "-PlanOnly" in core_section
          and "Call ExtractCorePayload" in core_section
          and core_section.index("-PlanOnly") < core_section.index("Call ExtractCorePayload")
          and '-SourceRoot "$PLUGINSDIR\\Turboism-core-payload"' in core_section)
    check("PAY6 permanent core files are not directly extracted to INSTDIR",
          all(('File "${STAGING_DIR}/%s"' % name) not in core_section
              for name in permanent_core)
          and 'File "${LICENSE_FILE}"' not in core_section)
    check("PAY7 plugin payload uses destination-relative manifest paths",
          "payload-plugins.sha256" in generated_plugins
          and '-DestinationRoot "$INSTDIR"' in generated_plugins
          and '$PLUGINSDIR\\Turboism-plugin-payload\\plugins' in generated_plugins
          and '-SourceRoot "$PLUGINSDIR\\Turboism-plugin-payload"' in generated_plugins)
    plugin_markers = re.findall(r'Turboism-plugin-plan\\(\d{4})\.need', generated_plugins)
    plugin_files = re.findall(
        r'File "/oname=([^\"]+\.jar)" "\$\{STAGING_DIR\}/plugins/[^\"]+"',
        generated_plugins,
    )
    check("PAY7b plugin markers form a complete manifest-aligned sequence",
          plugin_markers == [f"{index:04d}" for index in range(len(REAL_MODULES))]
          and len(plugin_files) == len(set(plugin_files)) == len(REAL_MODULES)
          and sorted(Path(name).stem for name in plugin_files) == sorted(REAL_MODULES))
    check("PAY8 generated extractors align marker indices and target names",
          "def append_extractor" in generator
          and "Path(relative).name" in generator
          and '${{FileExists}}' in generator
          and "ExtractCorePayload" in generator)
    check("PAY9 checksum helper is staged and uninstalled",
          gradle.count('from("packaging/windows-installer/install-jar-payload.ps1")') == 1
          and '"packaging/windows-installer/install-jar-payload.ps1"' in gradle
          and 'Delete "$INSTDIR\\install-jar-payload.ps1"' in text)
    check("PAY10 temporary extracted payloads are removed before finish",
          all(path in text for path in (
              'RMDir /r "$PLUGINSDIR\\Turboism-core-payload"',
              'RMDir /r "$PLUGINSDIR\\Turboism-core-plan"'))
          and 'RMDir /r "$PLUGINSDIR\\Turboism-plugin-payload"' in generated_plugins
          and 'RMDir /r "$PLUGINSDIR\\Turboism-plugin-plan"' in generated_plugins)


def check_plugin_selection_page_contract():
    """First-party plugin selection page renders meta and READMEs and filters payload install."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    generated = (INSTALLER_NSI.parent / "plugin-sections.nsh").read_text(encoding="utf-8")
    helper = (INSTALLER_NSI.parent / "install-jar-payload.ps1").read_text(encoding="utf-8")
    directory_page = text.index("!insertmacro MUI_PAGE_DIRECTORY")
    plugin_page = text.index("Page custom PluginSelectionCreate PluginSelectionLeave")
    graal_page = text.index("Page custom GraalCreate GraalLeave")
    check("PS1 plugin selection page is placed between Directory and Graal pages",
          directory_page < plugin_page < graal_page)
    check("PS2 .onInit initializes default plugin selections",
          "Call InitPluginSelectionDefaults" in text
          and "Function InitPluginSelectionDefaults" in generated
          and f'StrCpy $PluginTotalCount "{len(REAL_MODULES)}"' in generated)
    check("PS3 plugin selection page creates list, select/deselect buttons, checkbox, and RichEdit20W README viewer",
          "Function PluginSelectionCreate" in text
          and 'nsDialogs::CreateControl "RichEdit20W"' in text
          and "Function LoadPluginReadme" in text
          and "0x0461" in text
          and "0x0435" in text
          and "Call StagePluginReadmeFiles" in text
          and "Call PopulatePluginSelectionList" in text
          and "Call UpdatePluginSelectionDetails" in text)
    check("PS4 generated plugin sections stage localized RTF READMEs and write skip markers",
          "Function StagePluginReadmeFiles" in generated
          and "Function WritePluginSelectionSkipMarkers" in generated
          and "Call WritePluginSelectionSkipMarkers" in generated
          and "IntOp $1 $1 & 0xF000" in generated
          and "${If} $1 == 8192" in generated
          and "== 0x2000" not in generated
          and ".skip" in generated
          and 'Write-Output "SKIP_UNSELECTED|$($entry.Relative)"' in helper)
    check("PS5 native list items align text pointers for the installer target",
          '&i${NSIS_PTR_SIZE} 0, t r2' in text
          and '&i${NSIS_PTR_SIZE} 0xF000, p 0' in text
          and '&i${NSIS_PTR_SIZE} 3, p 0' in text)


def check_icon_contract():
    """The exact host-provided icon is staged into executables, shortcuts, and the form."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    configure = (INSTALLER_NSI.parent / "configure_turboism.ps1").read_text(encoding="utf-8")
    common = (INSTALLER_NSI.parent / "cubism-launch-common.ps1").read_text(encoding="utf-8")
    ico = ICON_DIR / "turboism.ico"
    png = ICON_DIR / "turboism.png"
    check("I1 exact ICO asset exists", ico.is_file() and ico.stat().st_size == 251547)
    check("I1 exact PNG asset exists", png.is_file() and png.stat().st_size == 3503132)
    import hashlib
    check("I2 exact ICO hash is pinned",
          hashlib.sha256(ico.read_bytes()).hexdigest()
          == "77c70e14edf3a88ba38dd43d6e0b0720f2e4aa8d527b03fba0493e33509d0899")
    check("I2 exact PNG hash is pinned",
          hashlib.sha256(png.read_bytes()).hexdigest()
          == "92cb49349cc27e6f96d33a37ff6ab4d0c00ccc738eb55b3165973d47c38eafd2")
    assemble = (INSTALLER_NSI.parent / "assemble-release.sh").read_text(encoding="utf-8")
    check("I3 installer and uninstaller use the icon",
          'Icon "${ICON_FILE}"' in text and 'UninstallIcon "${ICON_FILE}"' in text
          and 'MUI_ICON "${ICON_FILE}"' in text and 'MUI_UNICON "${ICON_FILE}"' in text
          and '-DICON_FILE="$repo_root/packaging/windows-installer/assets/turboism.ico"' in assemble)
    check("I4 installed icon assets are checksum-staged and removed",
          '("turboism.ico", stage / "turboism.ico")' in assemble
          and '("turboism.png", stage / "turboism.png")' in assemble
          and 'Delete "$INSTDIR\\turboism.ico"' in text
          and 'Delete "$INSTDIR\\turboism.png"' in text)
    check("I5 Start-menu shortcuts use the installed ICO", text.count('$INSTDIR\\turboism.ico') >= 4)
    check("I6 managed Cubism shortcuts use the installed ICO",
          '$shortcut.IconLocation = "$iconPath,0"' in common)
    check("I7 configurator uses and disposes the installed ICO",
          '$form.Icon = $formIcon' in configure
          and '$formIcon.Dispose()' in configure
          and '$form.Dispose()' in configure)


def check_launcher_and_shortcut_contract():
    """Windows launch scripts avoid $HOME and publish no-space shortcut names."""
    common = (INSTALLER_NSI.parent / "cubism-launch-common.ps1").read_text(encoding="utf-8")
    launcher = (INSTALLER_NSI.parent / "launch-cubism-turboism.ps1").read_text(encoding="utf-8")
    configure = (INSTALLER_NSI.parent / "configure_turboism.ps1").read_text(encoding="utf-8")
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    check("L1 production launcher avoids PowerShell's read-only HOME variable",
          not re.search(r"(?i)\$home\b", launcher + common))
    check("L2 generated managed shortcut names contain no spaces",
          'return "Turboism_Cubism_' in common and '"-D3D"' in common)
    check("L3 legacy managed shortcut names remain admitted for cleanup",
          "Turboism Cubism 5\\." in common)
    shortcut_match = re.search(
        r"\$nameOk\s*=\s*\$name\s*-match\s*'([^']+)'\s*-or\s*\$name\s*-match\s*'([^']+)'",
        common
    )
    pat1 = re.sub(r"\\p\{[A-Za-z]+\}", r"\\w", shortcut_match.group(1)) if shortcut_match else ""
    pat2 = re.sub(r"\\p\{[A-Za-z]+\}", r"\\w", shortcut_match.group(2)) if shortcut_match else ""
    check("L3b managed shortcut patterns admit all reviewed 5.0.00-5.3.05 versions",
          shortcut_match is not None
          and all(
              re.match(pat1, f"Turboism_Cubism_{v}_Live2D_Cubism_{v}-A1B2C3D4E5F6.lnk", re.I)
              and re.match(pat1, f"Turboism_Cubism_{v}_Live2D_Cubism_{v}-A1B2C3D4E5F6-D3D.lnk", re.I)
              and re.match(pat2, f"Turboism Cubism {v} [Live2D Cubism {v}-A1B2C3D4E5F6].lnk", re.I)
              and re.match(pat2, f"Turboism Cubism {v} [Live2D Cubism {v}-A1B2C3D4E5F6] - D3D.lnk", re.I)
              for v in ["5.0.00", "5.1.00", "5.1.03", "5.2.03", "5.3.02", "5.3.04", "5.3.05", "5.0", "5.2"]
          ))
    check("L4 installer Start Menu shortcut names contain no spaces",
          '"Turboism_Configurator"' in text
          and '"Turboism_Uninstall"' in text
          and '"Turboism_Launch_Cubism"' in text)
    legacy_start_menu = [
        "Turboism Configurator.lnk",
        "Uninstall Turboism.lnk",
        "Launch Cubism.lnk",
        "Turboism 配置器.lnk",
        "卸载 Turboism.lnk",
        "启动 Cubism.lnk",
        "Turboism 設定.lnk",
        "Turboism をアンインストール.lnk",
        "Cubism を起動.lnk",
    ]
    macro_start = text.index("!macro RemoveLegacyStartMenuShortcuts")
    macro_end = text.index("!macroend", macro_start)
    legacy_macro = text[macro_start:macro_end]
    start_menu = text[text.index('Section "-StartMenuAndRegistry"'):
                      text.index("SectionEnd", text.index('Section "-StartMenuAndRegistry"'))]
    uninstall = text[text.index('Section "Uninstall"'):
                     text.index("SectionEnd", text.index('Section "Uninstall"'))]
    check("L5 upgrades and uninstall share all 0.43.0 Start Menu names",
          all(('Delete "$SMPROGRAMS\\Turboism\\' + name + '"') in legacy_macro
              for name in legacy_start_menu)
          and "!insertmacro RemoveLegacyStartMenuShortcuts" in start_menu
          and "!insertmacro RemoveLegacyStartMenuShortcuts" in uninstall)
    check("L6 installer selects BAT integration by default without defensive help copy",
          "selected by default" in text
          and "默认已勾选" in text
          and "当前支持版本：5.0.00–5.3.05" in text
          and "Currently supported versions: 5.0.00–5.3.05" in text
          and "BatIntegrationHelp" not in text
          and "StrCpy $integrateCubismBat 1" in text
          and "$integrateCubismBat" in text
          and "$batCheck" in configure)
    check("L6b Start-menu and BAT controls are independent and reversible",
          "$createStartMenu" in text
          and "$integrateCubismBat" in text
          and "Disable-CubismShortcutIntegration" in common
          and "Restore-CubismBatIntegrations" in common)
    launch_options = text[text.index("Function LaunchOptionsCreate"):
                          text.index("FunctionEnd", text.index("Function LaunchOptionsLeave"))]
    check("L6d launch options are independent, localized, and tightly spaced",
          all(('LangString DesktopShortcutOption ${LANG_%s}' % lang) in text
              for lang in ("ENGLISH", "SIMPCHINESE", "JAPANESE", "KOREAN"))
          and '${NSD_CreateCheckbox} 0 30u 100% 18u "$(StartMenuOption)"' in launch_options
          and '${NSD_CreateCheckbox} 0 50u 100% 18u "$(DesktopShortcutOption)"' in launch_options
          and '${NSD_CreateCheckbox} 0 70u 100% 32u "$(BatIntegrationOption)"' in launch_options
          and "$DesktopShortcutCheckbox" in launch_options
          and "${NSD_GetState} $DesktopShortcutCheckbox $createDesktopShortcut" in launch_options
          and "StrCpy $createDesktopShortcut 1" in text
          and "${NSD_Check} $DesktopShortcutCheckbox" in launch_options)
    check("L6e installer title includes the configured Turboism version",
          'Name "Turboism ${VER}"' in text)
    check("L6f desktop shortcut creation, opt-out cleanup, and uninstall are symmetric",
          'CreateShortCut "$DESKTOP\\Turboism_Launch_Cubism.lnk"' in start_menu
          and start_menu.count('Delete "$DESKTOP\\Turboism_Launch_Cubism.lnk"') == 1
          and 'Delete "$DESKTOP\\Turboism_Launch_Cubism.lnk"' in uninstall
          and "${AndIf} $createDesktopShortcut == 0" in text)
    check("L6c fresh official BAT backups preserve exact source bytes",
          "[System.IO.File]::Copy($bat, $backup, $false)" in common
          and "Cubism BAT backup verification failed" in common
          and "[int64]$backupFile.Length -ne $originalLength" in common
          and "(Get-CubismSha256 $backup) -ine $originalHash" in common)
    check("L7 finish page offers opt-in launch and installation directory actions",
          "MUI_FINISHPAGE_RUN_FUNCTION LaunchTurboism" in text
          and "MUI_FINISHPAGE_RUN_TEXT \"$(FinishLaunchTurboismText)\"" in text
          and "MUI_FINISHPAGE_RUN_NOTCHECKED" in text
          and "MUI_FINISHPAGE_SHOWREADME_NOTCHECKED" in text
          and "MUI_FINISHPAGE_SHOWREADME_FUNCTION OpenInstallDirectory" in text
          and all(('LangString FinishLaunchTurboismText ${LANG_%s}' % lang) in text
                  for lang in ("ENGLISH", "SIMPCHINESE", "JAPANESE", "KOREAN"))
          and "FinishOpenFolderText" in text
          and 'Exec \'"$SYSDIR\\cmd.exe" /D /S /C ""$INSTDIR\\launch-cubism-turboism.bat""\'' in text
          and 'ExecShell "" "$INSTDIR\\launch-cubism-turboism.bat"' not in text
          and 'explorer.exe' in text)
    install_success = text[text.index("Function RunFinalConfiguration"):
                           text.index("FunctionEnd", text.index("Function RunFinalConfiguration"))]
    check("L7b discovery payload is removed after configuration and before the Finish page",
          'RMDir /r "$CubismDiscoveryWorkDir"' in install_success
          and 'StrCpy $CubismDiscoveryWorkDir ""' in install_success
          and install_success.index('-InitializeSelection')
              < install_success.index('-IntegrateBat')
              < install_success.index('InstallerConfigurationDone:')
              < install_success.index('RMDir /r "$CubismDiscoveryWorkDir"'))
    check("L8 managed launcher prints the Turboism banner before Cubism selection",
          "function Write-TurboismLauncherBanner" in launcher
          and "For you, a bouquet." in launcher
          and 'Join-Path $turboismHome "README.txt"' in launcher
          and launcher.index("\nWrite-TurboismLauncherBanner\n")
              < launcher.index("$cubism = Resolve-SelectedCandidate"))




def check_eula_contract():
    """AGPL-3.0-only acceptance remains first; a separate localized EULA is mandatory and packaged."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    eula_files = {
        "en": EULA_DIR / "EULA.en.txt",
        "zh": EULA_DIR / "EULA.zh-Hans.txt",
        "ja": EULA_DIR / "EULA.ja.txt",
        "ko": EULA_DIR / "EULA.ko.txt",
    }
    for locale, path in eula_files.items():
        check("EULA %s exists and is non-empty" % locale,
              path.is_file() and path.stat().st_size > 500, str(path))
    en = eula_files["en"].read_text(encoding="utf-8")
    zh = eula_files["zh"].read_text(encoding="utf-8")
    ja = eula_files["ja"].read_text(encoding="utf-8")
    ko = eula_files["ko"].read_text(encoding="utf-8")

    def clean_md_eula(content: str) -> str:
        body = re.sub(r"^\[.*?\]\(.*?\).*?\n\n", "", content)
        body = re.sub(r"^#{1,6}\s+", "", body, flags=re.MULTILINE)
        return body.replace("**", "").replace("  \n", "\n")

    root_eula = (EULA_DIR.parent.parent / "EULA.md").read_text(encoding="utf-8")
    derived_root = clean_md_eula(root_eula)
    check("root EULA is English and matches packaging copy exactly",
          derived_root == en)
    check("root EULA is version 2.2 and final",
          "Version 2.2" in root_eula
          and "Release date: 2026-09-23" in root_eula
          and "DRAFT" not in root_eula)
    check("root EULA retains the concise license, independence, and liability notice",
          "TURBOISM END USER RUNTIME STATEMENT AND DISCLAIMER" in root_eula
          and "AGPL-3.0-only" in root_eula
          and "not an official Cubism product" in root_eula
          and "data loss, file corruption" in root_eula
          and "cannot legally be excluded" in root_eula
          and "The Simplified Chinese text is authoritative" in root_eula)

    docs_zh_eula = (EULA_DIR.parent.parent / "docs/zh/EULA.md").read_text(encoding="utf-8")
    derived_zh = clean_md_eula(docs_zh_eula)
    check("docs zh EULA is authoritative and packaging copy is derived exactly",
          derived_zh == zh)
    check("docs zh EULA is version 2.2 and final",
          "版本：2.2" in docs_zh_eula
          and "发布日期：2026-09-23" in docs_zh_eula
          and "草案" not in docs_zh_eula)
    check("docs zh EULA retains the concise license, independence, and liability notice",
          "TURBOISM 最终用户运行声明与免责声明" in docs_zh_eula
          and "AGPL-3.0-only" in docs_zh_eula
          and "并非 Cubism 官方产品" in docs_zh_eula
          and "数据丢失、文件损坏" in docs_zh_eula
          and "不排除依法不得免除的责任" in docs_zh_eula
          and "本版本以简体中文文本为正式文本" in docs_zh_eula)

    docs_en_eula = (EULA_DIR.parent.parent / "docs/en/EULA.md").read_text(encoding="utf-8")
    docs_ja_eula = (EULA_DIR.parent.parent / "docs/ja/EULA.md").read_text(encoding="utf-8")
    docs_ko_eula = (EULA_DIR.parent.parent / "docs/ko/EULA.md").read_text(encoding="utf-8")
    check("docs en EULA matches packaging English copy", clean_md_eula(docs_en_eula) == en)
    check("docs ja EULA matches packaging Japanese copy", clean_md_eula(docs_ja_eula) == ja)
    check("docs ko EULA matches packaging Korean copy", clean_md_eula(docs_ko_eula) == ko)

    check("localized notices identify Simplified Chinese as authoritative",
          "The Simplified Chinese text is authoritative" in en
          and "本版本以简体中文文本为正式文本" in zh
          and "簡体字中国語文を正文とします" in ja
          and "중국어 간체 본문이 정본이며" in ko)
    first = text.index('!insertmacro MUI_PAGE_LICENSE "${LICENSE_FILE}"')
    statement = text.index('!insertmacro MUI_PAGE_LICENSE "$(EulaFile)"')
    acknowledgements_page = text.index(
        "Page custom EulaAcknowledgementsCreate EulaAcknowledgementsLeave")
    directory_page = text.index("!insertmacro MUI_PAGE_DIRECTORY")
    check("EULA statement and acknowledgements are separate pages after AGPL-3.0-only",
          first < statement < acknowledgements_page < directory_page)
    check("Mode page and Components page are removed",
          "Page custom ModeCreate ModeLeave" not in text
          and "!insertmacro MUI_PAGE_COMPONENTS" not in text)
    check("Simplified Chinese AGPL-3.0-only acceptance consistently uses 我同意",
          'LangString LicenseBottomText ${LANG_SIMPCHINESE} "如果您同意 AGPL-3.0-only，请勾选下方复选框后继续。"' in text
          and 'LangString LicenseAcceptText ${LANG_SIMPCHINESE} "我同意 AGPL-3.0-only"' in text
          and '我接受 AGPL-3.0-only' not in text)
    statement_declaration = text[first + len(
        '!insertmacro MUI_PAGE_LICENSE "${LICENSE_FILE}"'):statement]
    check("EULA statement page keeps the complete localized scrollable body",
          'MUI_LICENSEPAGE_TEXT_TOP "$(EulaTopText)"' in statement_declaration
          and "MUI_LICENSEPAGE_CHECKBOX" not in statement_declaration
          and "MUI_PAGE_CUSTOMFUNCTION_SHOW" not in statement_declaration
          and "MUI_PAGE_CUSTOMFUNCTION_LEAVE" not in statement_declaration)
    check("EULA top text does not mention next page confirmations and is localized across languages",
          'LangString EulaTopText ${LANG_SIMPCHINESE} "请阅读下方完整的 Turboism 最终用户运行声明与免责声明。"' in text
          and 'LangString EulaTopText ${LANG_ENGLISH} "Review the full Turboism End User Runtime Statement and Disclaimer below."' in text
          and 'LangString EulaTopText ${LANG_JAPANESE} "以下の Turboism エンドユーザー運用声明および免責事項の全文を確認してください。"' in text
          and 'LangString EulaTopText ${LANG_KOREAN} "아래에서 Turboism 최종 사용자 실행 고지 및 면책 조항 전문을 확인하세요."' in text
          and "四项必选确认位于下一页" not in text
          and "The four required acknowledgements are on the next page" not in text)
    check("Simplified Chinese EULA agree button consistently uses 我同意(I)",
          '!define MUI_LICENSEPAGE_BUTTON "$(EulaAgreeButtonText)"' in statement_declaration
          and 'LangString EulaAgreeButtonText ${LANG_SIMPCHINESE} "我同意(&I)"' in text
          and '我接受(&I)' not in text)
    acknowledgements = (
        "我确认 Turboism 是独立第三方项目，并非 Live2D 官方产品。",
        "我确认使用 Cubism 仍需合法、有效的授权；Turboism 不提供、替代或绕过 Cubism 的许可校验。",
        "我确认我已理解：由我启动或授权的插件、脚本、MCP、API 和自动化操作可能修改、覆盖或删除工程内容，并将自行保留独立备份。",
        "我确认我已理解：Turboism 是按现状提供的开源项目，不保证持续兼容、无错误或成功恢复。",
    )
    create_start = text.index("Function EulaAcknowledgementsCreate")
    create_end = text.index("FunctionEnd", create_start)
    create = text[create_start:create_end]
    leave_start = text.index("Function EulaAcknowledgementsLeave")
    leave_end = text.index("FunctionEnd", leave_start)
    leave = text[leave_start:leave_end]
    check("NSIS acknowledgement page shows four standard independent checkboxes",
          all(value in text for value in acknowledgements)
          and create.count("${NSD_CreateCheckbox}") == 4
          and all("EulaAck%dCheckbox" % index in create for index in range(1, 5)))
    check("NSIS acknowledgement rows are compact, contiguous, and fully visible",
          '${NSD_CreateCheckbox} 0 0 100% 24u "$(EulaAck1)"' in create
          and '${NSD_CreateCheckbox} 0 24u 100% 30u "$(EulaAck2)"' in create
          and '${NSD_CreateCheckbox} 0 54u 100% 38u "$(EulaAck3)"' in create
          and '${NSD_CreateCheckbox} 0 92u 100% 30u "$(EulaAck4)"' in create)
    check("NSIS reads and requires all four acknowledgement states",
          leave.count("${NSD_GetState}") == 4
          and all("$EulaAck%dState" % index in leave for index in range(1, 5))
          and "$(EulaRequired)" in leave
          and "Abort" in leave)
    check("NSIS preserves acknowledgement choices across Back navigation",
          "${NSD_OnBack} EulaAcknowledgementsSave" in create
          and "Function EulaAcknowledgementsSave" in text
          and all("$EulaAck%dState" % index in create for index in range(1, 5)))
    check("NSIS does not attach acknowledgement controls to the MUI License page",
          "CreateEulaAcknowledgement" not in text
          and "EulaUpdateNext" not in text
          and "Function EulaShow" not in text
          and "Function EulaLeave" not in text
          and "EulaNativeAccept" not in text)
    check("NSIS EULA files are localized",
          all(('LicenseLangString EulaFile ${LANG_%s}' % lang) in text
              for lang in ("ENGLISH", "SIMPCHINESE", "JAPANESE", "KOREAN")))
    on_init = text[text.index("Function .onInit"):
                  text.index("FunctionEnd", text.index("Function .onInit"))]
    check("NSIS registers Korean and offers a language-selection dialog before the welcome page",
          '!insertmacro MUI_LANGUAGE "Korean"' in text
          and "!define MUI_LANGDLL_ALLLANGUAGES" in text
          and "!insertmacro MUI_LANGDLL_DISPLAY" in on_init)
    check("Korean installer UI uses the Korean EULA",
          'LicenseLangString EulaFile ${LANG_KOREAN} "${EULA_DIR}/EULA.ko.txt"' in text)
    plugin_sections = (INSTALLER_NSI.parent / "plugin-sections.nsh").read_text(encoding="utf-8")
    check("generated plugin sections localize display names and descriptions in Korean",
          all(('LangString PLUGIN_NAME_%s ${LANG_KOREAN}' % pid) in plugin_sections
              for pid in ("dev_turboism_plugin_webdav", "dev_turboism_plugin_mcp"))
          and all(('LangString PLUGIN_DESC_%s ${LANG_KOREAN}' % pid) in plugin_sections
                  for pid in ("dev_turboism_plugin_webdav", "dev_turboism_plugin_mcp")))
    generator = (INSTALLER_NSI.parent / "assemble-release.sh").read_text(encoding="utf-8")
    check("NSIS license pages use BOM-prefixed UTF-8 EULA copies",
          'printf \'\\xef\\xbb\\xbf\'' in generator
          and 'mkdir -p "$generated/eula"' in generator
          and '-DEULA_DIR="$generated/eula"' in generator
          and '-DEULA_DIR="$repo_root/packaging/eula"' not in generator
          and 'EULA.$lang.txt' in generator)
    core_payload = generator[generator.index("core_payload = ["):
                             generator.index("plugin_payload = [")]
    uninstall_start = text.index('Section "Uninstall"')
    uninstall_end = text.index("SectionEnd", uninstall_start)
    uninstall = text[uninstall_start:uninstall_end]
    for name in ("EULA.en.txt", "EULA.zh-Hans.txt", "EULA.ja.txt", "EULA.ko.txt"):
        check("NSIS checksum-packages %s" % name,
              ('("%s", stage / "%s")' % (name, name)) in core_payload)
        check("NSIS uninstalls %s" % name,
              ('Delete "$INSTDIR\\%s"' % name) in uninstall)


def check_graal_fallback_contract():
    """Managed launch must recover from a missing saved/default GraalVM choice.

    Explicit ``-CubismJava`` input remains strict, while the persisted/default
    preference may fall back to the selected Cubism installation's bundled
    Java and must show the official installation URL.
    """
    common = (INSTALLER_NSI.parent / "cubism-launch-common.ps1").read_text(encoding="utf-8")
    launcher = (INSTALLER_NSI.parent / "launch-cubism-turboism.ps1").read_text(encoding="utf-8")
    check("G1 nullable GraalVM discovery helper exists",
          "function Find-CubismGraalJava" in common and 'return ""' in common)
    check("G1b discovery accepts GraalVM metadata across vendors and Java versions",
          "function Test-CubismCompatibleGraalJava" in common
          and 'GRAALVM_VERSION="[^"\\r\\n]+"' in common
          and 'JAVA_VERSION="[^"\\r\\n]+"' in common
          and 'IMPLEMENTOR="GraalVM Community"' not in common
          and 'GRAALVM_VERSION="25\\.2\\.4"' not in common
          and 'JAVA_VERSION="25\\.0\\.4"' not in common)
    discovery = common[common.index("function Find-CubismGraalJava"):
                       common.index("function Resolve-CubismGraalJava")]
    check("G1c custom GraalVM path is consulted after automatic discovery",
          "function Read-CubismGraalVmPath" in common
          and '$configured = Read-CubismGraalVmPath -TurboismHome $TurboismHome' in discovery
          and discovery.index('$candidates += (Join-Path $TurboismHome "graalvm\\bin\\java.exe")')
              < discovery.index('$configured = Read-CubismGraalVmPath'))
    check("G1d custom path accepts the documented bin directory level",
          '(Split-Path -Leaf $path) -ieq "bin"' in discovery
          and 'Join-Path $path "java.exe"' in discovery)
    check("G1e missing optional custom path is strict-mode safe",
          '$launcherProperty.Value.PSObject.Properties["graalVmPath"]' in common
          and '$null -eq $pathProperty' in common)
    check("G2 explicit Cubism Java override remains strict for missing paths",
          "function Resolve-CubismGraalJava" in common
          and "no GraalVM java.exe is available" in common
          and "Resolve-CubismGraalJava -TurboismHome $turboismHome -ExplicitJava $CubismJava" in launcher
          and "Test-CubismNormalFile $explicit" in common)
    fallback = launcher[launcher.index('else {\n    $javaOverride = Find-CubismGraalJava'):
                        launcher.index('Write-Host ($M.Jvm', launcher.index('else {\n    $javaOverride = Find-CubismGraalJava'))]
    check("G3 saved/default GraalVM falls back to bundled mode",
          '$cubismJvm = "bundled"' in fallback and "Write-Warning $M.GraalFallback" in fallback)
    check("G4 fallback warning links the official GraalVM page",
          "https://www.graalvm.org/downloads/" in launcher)


def check_uninstall_config_option():
    """Uninstall confirm page keep-config checkbox must belong to the MUI page and default checked."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    show_start = text.index("Function un.ConfirmShow")
    show_end = text.index("FunctionEnd", show_start)
    show = text[show_start:show_end]
    leave_start = text.index("Function un.ConfirmLeave")
    leave_end = text.index("FunctionEnd", leave_start)
    leave = text[leave_start:leave_end]
    uninstall_start = text.index('Section "Uninstall"')
    uninstall_end = text.index("SectionEnd", uninstall_start)
    uninstall = text[uninstall_start:uninstall_end]

    check("UC1 checkbox belongs to the MUI uninstall page",
          'p $mui.UnConfirmPage, i ${UN_KEEP_CONFIG_CONTROL_ID}' in show)
    check("UC1 checkbox is not attached to the wizard parent",
          'p $HWNDPARENT, i ${UN_KEEP_CONFIG_CONTROL_ID}' not in show)
    check("UC2 keep-config option defaults checked",
          "StrCpy $unKeepConfig 1" in show
          and '${BM_SETCHECK} ${BST_CHECKED} 0' in show)
    # The page is laid out by NSIS itself, so fixed pixel geometry collided with the
    # directory label and path edit box that MUI draws on the same rows.
    create = show[show.index("user32::CreateWindowEx"):]
    create = create[:create.index("\n")]
    check("UC2b checkbox geometry derives from the directory row",
          'user32::GetClientRect(p $mui.UnConfirmPage' in show
          and 'user32::GetWindowRect(p $mui.UnConfirmPage.Directory,' in show
          and 'user32::GetWindowRect(p $mui.UnConfirmPage.DirectoryText,' in show
          and 'i $R3, i $R6, i $R7, i $R4' in create)
    check("UC2c checkbox row follows the measured directory edit box",
          'IntOp $R4 $R2 - $R1' in show
          and 'IntOp $R5 $R4 / ${UN_KEEP_CONFIG_GAP_DIVISOR}' in show
          and 'IntOp $R6 $R2 + $R5' in show)
    check("UC2d checkbox inherits the page font",
          '${WM_GETFONT} 0 0 $unCfgFont' in show
          and '${WM_SETFONT} $unCfgFont 1' in show)
    check("UC2e checkbox handle comes back through a register",
          "p .r0'" in create
          and 'StrCpy $unCfgCheckbox $0' in show)
    check("UC3 leave callback reads checkbox state directly",
          '${BM_GETCHECK} 0 0 $unKeepConfig' in leave)
    check("UC4 config is deleted only when keep is unchecked",
          '${If} $unKeepConfig == 0' in uninstall
          and 'Delete "$INSTDIR\\config.json"' in uninstall)
    check("UC4b keep restores user config/data directories after runtime cleanup",
          'CreateDirectory "$INSTDIR\\config"' in uninstall
          and 'CreateDirectory "$INSTDIR\\data"' in uninstall)
    check("UC5 localized label describes retention",
          all(('LangString UnKeepConfigLabel ${LANG_%s}' % lang) in text
              for lang in ("ENGLISH", "SIMPCHINESE", "JAPANESE", "KOREAN"))
          and "UnDeleteConfigLabel" not in text
          and "$unDeleteConfig" not in text)
    # The user documentation must match the real semantics of the uninstall confirmation page: the
    # checkbox label keeps config.json and is checked by default. The en/zh/ja templates previously
    # described a deletion, the opposite of the implementation.
    readme_dir = INSTALLER_NSI.parent
    readme_semantics = (
        ("README.en.txt.template", "\"Keep config.json (user configuration)\"", "Also delete config.json"),
        ("README.zh.txt.template", "「保留 config.json（用户配置）」", "同时删除 config.json"),
        ("README.ja.txt.template", "保持する」チェック", "（ユーザー設定）も"),
        ("README.ko.txt.template", "\"config.json(사용자 설정) 유지\"", "(사용자 설정)도 삭제"),
    )
    for name, keeps, deletes in readme_semantics:
        body = (readme_dir / name).read_text(encoding="utf-8")
        check("UC5b %s documents the retention checkbox" % name,
              keeps in body and deletes not in body)


def check_uninstall_postcondition():
    """Uninstall fail-closed order regression: cleanup call/$0 non-zero guard -> fail closed if
    state file still exists -> only then allow DeleteRegKey and payload deletion; LICENSE
    deletion must use the exact baseline name."""
    stmts = uninstall_statements()
    texts = [t for _, t in stmts]
    cleanup = find_stmt(texts, lambda t: "nsExec::ExecToLog" in t and "-Cleanup" in t)
    check("U1 managed cleanup call exists", cleanup is not None)
    if cleanup is None:
        return
    guard0 = find_stmt(texts, lambda t: t == "${If} $0 != 0")
    check("U1 cleanup precedes $0 non-zero guard", guard0 is not None and guard0 > cleanup)
    check("U1 $0 guard fails closed (ShortcutCleanupFailure + Abort)",
          guard0 is not None
          and any("ShortcutCleanupFailure" in t for t in texts[guard0:guard0 + 3])
          and "Abort" in texts[guard0:guard0 + 3])
    if guard0 is None:
        return
    state = find_stmt(texts, lambda t: "${FileExists}" in t and "cubism-installations.json" in t)
    check("U2 state file fail-closed guard exists", state is not None)
    if state is None:
        return
    check("U2 state guard follows $0 guard", state > guard0)
    check("U2 state guard fails closed (ShortcutCleanupFailure + Abort)",
          any("ShortcutCleanupFailure" in t for t in texts[state:state + 3])
          and "Abort" in texts[state:state + 3])
    regkey = find_stmt(texts, lambda t: t.startswith("DeleteRegKey"))
    payload = find_stmt(texts, lambda t: t.startswith('Delete "$INSTDIR\\'))
    check("U3 state guard precedes DeleteRegKey", regkey is not None and state < regkey)
    check("U3 state guard precedes payload deletion", payload is not None and state < payload)
    lic_txt = find_stmt(texts, lambda t: t == 'Delete "$INSTDIR\\LICENSE.txt"')
    lic_legacy = find_stmt(texts, lambda t: t == 'Delete "$INSTDIR\\LICENSE"')
    check("U4 delete exact LICENSE.txt and legacy LICENSE leftover",
          lic_txt is not None and lic_legacy is not None and lic_txt < lic_legacy)

    # U5: the plugins directory uses an allowlist delete with the same semantics as the IzPack
    # uninstaller. It deletes only the JARs the installer deployed, which the generated
    # un.DeleteInstallerPluginJars names, keeps an unknown file and a third-party JAR, and removes
    # the directory only when it is empty. Restoring RMDir /r is forbidden.
    generated = (INSTALLER_NSI.parent / "plugin-sections.nsh").read_text(encoding="utf-8")
    check("U5 plugins directory forbids recursive delete",
          not any('RMDir /r "$INSTDIR\\plugins"' in t for t in texts))
    check("U5 plugins allowlist call precedes empty dir removal",
          any("Call un.DeleteInstallerPluginJars" in t for t in texts)
          and any(t == 'RMDir "$INSTDIR\\plugins"' for t in texts))
    check("U5 generator emits uninstall allowlist function",
          "Function un.DeleteInstallerPluginJars" in generated
          and all(('Delete "$INSTDIR\\plugins\\%s.jar"' % m) in generated
                  for m in REAL_MODULES)
          and 'RMDir /r "$INSTDIR\\plugins"' not in generated)


def main():
    a, b, c = BUNDLED_IDS

    # T1: Full with every plugin selected and no existing config yields the template without disabledPlugins
    out = installer_write_config("full", [], None)
    doc = json.loads(out)
    check("T1 template fields", doc["worktreeId"] == "turboism-runtime" and doc["pluginDirs"] == ["plugins"]
          and doc["format"] == "turboism.runtime.config" and doc["schemaVersion"] == 1
          and doc["launcher"] == {"cubismJvm": "graalvm"} and doc["useTextIcon"] is False
          and doc["guide"] == {"showOnStartup": True})
    check("T1 no disabledPlugins", "disabledPlugins" not in doc)

    # T2: Full with 2 plugins unselected and no existing config writes the list in ascending order
    out = installer_write_config("full", [c, a], None)
    doc = json.loads(out)
    check("T2 ascending order", doc["disabledPlugins"] == [a, c], str(doc.get("disabledPlugins")))
    # T2b: the JSON array shape holds no extra quote around the brackets, quotes each item once, and separates items with ","
    expected_fragment = '"disabledPlugins":["' + a + '","' + c + '"]'
    check('T2b JSON array shape (no extra quotes, "," separated)', expected_fragment in out,
          "fragment=%s" % expected_fragment + " out=%s" % out[:160])

    # T10: the onboarding state must survive an installer upgrade, because a lost state shows the once-only onboarding again.
    current_guide = ('{"format":"turboism.runtime.config","schemaVersion":1,'
                     '"worktreeId":"guide-test","disabledPlugins":[],'
                     '"guide":{"showOnStartup":false}}')
    out = installer_write_config("full", [a], current_guide)
    check("T10 current v1 update preserves guide state",
          json.loads(out)["guide"] == {"showOnStartup": False})
    legacy_guide = '{"worktreeId":"legacy-guide","guide":{"showOnStartup":false},"future":1}'
    out = installer_write_config("full", [], legacy_guide)
    check("T10 normalization preserves guide state",
          json.loads(out)["guide"] == {"showOnStartup": False})
    out = installer_write_config("full", [], '{"guide":{"showOnStartup":"no"}}')
    check("T10 invalid guide state dropped", "guide" not in json.loads(out))

    # T3: a Full install applies the current selection: a checked bundled plugin is enabled
    # and an unrelated disabled id survives.
    existing = json.dumps({"format": "turboism.runtime.config", "schemaVersion": 1,
                           "worktreeId": "user-runtime", "pluginDirs": ["custom"],
                           "disabledPlugins": [a, UNRELATED], "logLevel": "DEBUG", "useTextIcon": True}, indent=2)
    out = installer_write_config("full", [], existing)
    doc = json.loads(out)
    check("T3 current schema checked bundled plugin enabled",
          doc["disabledPlugins"] == [UNRELATED], str(doc.get("disabledPlugins")))
    check("T3 current schema preserves other user fields",
          doc["worktreeId"] == "user-runtime" and doc["pluginDirs"] == ["custom"]
          and doc["logLevel"] == "DEBUG" and doc["useTextIcon"] is True)

    # T3b: a default all-checked Full install no longer keeps acp disabled.
    acp_bundled = BUNDLED_IDS + [ACP_ID]
    acp_disabled = json.dumps({"format": "turboism.runtime.config", "schemaVersion": 1,
                               "worktreeId": "acp-default",
                               "disabledPlugins": [ACP_ID, UNRELATED]}, indent=2)
    out = installer_write_config("full", [], acp_disabled, bundled_ids=acp_bundled)
    doc = json.loads(out)
    check("T3b default full enables acp",
          ACP_ID not in doc.get("disabledPlugins", []), str(doc.get("disabledPlugins")))
    check("T3b default full keeps an unrelated disabled plugin",
          UNRELATED in doc.get("disabledPlugins", []), str(doc.get("disabledPlugins")))

    # T3c: an unchecked bundled plugin is still disabled.
    out = installer_write_config("full", [ACP_ID], None, bundled_ids=acp_bundled)
    doc = json.loads(out)
    check("T3c unchecked acp is disabled", doc["disabledPlugins"] == [ACP_ID],
          str(doc.get("disabledPlugins")))

    # T4: a Thin update keeps the existing disabledPlugins and also keeps an unrelated disabled entry and the other fields.
    out = installer_write_config("thin", [], existing)
    doc = json.loads(out)
    check("T4 Thin update preserves existing disabledPlugins",
          doc["disabledPlugins"] == sorted([a, UNRELATED]))
    check("T4 Thin update preserves other user fields",
          doc["worktreeId"] == "user-runtime" and doc["pluginDirs"] == ["custom"])

    # T5: fresh Thin install writes no disabledPlugins.
    out = installer_write_config("thin", [], None)
    doc = json.loads(out)
    check("T5 fresh thin has no disabledPlugins", "disabledPlugins" not in doc or doc["disabledPlugins"] == [],
          str(doc.get("disabledPlugins")))

    # T6: a schema-less v0 document migrates to v1, keeps known settings, and applies the selection.
    legacy = json.dumps({"worktreeId": "legacy-runtime", "pluginDirs": ["custom"],
                         "disabledPlugins": [a, UNRELATED], "logLevel": "DEBUG",
                         "useTextIcon": True, "cubismJvm": "bundled"})
    out = installer_write_config("full", [], legacy)
    doc = json.loads(out)
    check("T6 v0 migrates to current schema",
          doc["format"] == "turboism.runtime.config" and doc["schemaVersion"] == 1)
    check("T6 v0 preserves user settings",
          doc["worktreeId"] == "legacy-runtime" and doc["pluginDirs"] == ["custom"]
          and doc["logLevel"] == "DEBUG" and doc["useTextIcon"] is True)
    check("T6 v0 checked bundled plugin enabled", doc["disabledPlugins"] == [UNRELATED],
          str(doc.get("disabledPlugins")))
    check("T6 v0 JVM field migrated into launcher", doc["launcher"]["cubismJvm"] == "bundled")

    # T7: normalization drops an unknown field instead of failing closed.
    out = installer_write_config("full", [], '{"legacyUnknown":true}')
    doc = json.loads(out)
    check("T7 unknown field dropped and normalized to v1",
          "legacyUnknown" not in doc and doc["schemaVersion"] == 1
          and doc["format"] == "turboism.runtime.config")

    # T8: a future or illegal schemaVersion normalizes to the current version instead of refusing the installation.
    out = installer_write_config("full", [],
                                 '{"format":"turboism.runtime.config","schemaVersion":2,'
                                 '"worktreeId":"future-test","futureField":true}')
    doc = json.loads(out)
    check("T8 future schema normalized to v1",
          doc["schemaVersion"] == 1 and "futureField" not in doc
          and doc["worktreeId"] == "future-test")

    for label, schema in (("string", '"1"'), ("float", "1.0")):
        out = installer_write_config(
                "full", [],
                '{"format":"turboism.runtime.config","schemaVersion":' + schema
                + ',"worktreeId":"token-test"}')
        doc = json.loads(out)
        check("T8 schemaVersion " + label + " token normalized",
              doc["schemaVersion"] == 1 and doc["worktreeId"] == "token-test")

    # T8b: only a document that explicitly declares another product format still fails closed.
    try:
        installer_write_config("full", [],
                               '{"format":"other.runtime.config","schemaVersion":1}')
        check("T8b foreign format fails closed", False)
    except ValueError:
        check("T8b foreign format fails closed", True)

    # T9: a current v1 document normalizes both an unknown field and an illegal known value, so a legal value carries over and the rest is dropped.
    invalid_current = ('{"format":"turboism.runtime.config","schemaVersion":1,'
                       '"worktreeId":"current-test","futureCurrentField":true,'
                       '"logLevel":"DEBUG"}')
    out = installer_write_config("full", [a], invalid_current)
    doc = json.loads(out)
    check("T9 current schema unknown field dropped",
          "futureCurrentField" not in doc
          and doc["worktreeId"] == "current-test" and doc["logLevel"] == "DEBUG"
          and doc["disabledPlugins"] == [a])
    for label, legacy_invalid, field in (
        ("logLevel", '{"logLevel":"BOGUS"}', "logLevel"),
        ("safeMode type", '{"safeMode":"false"}', "safeMode"),
        ("useTextIcon type", '{"useTextIcon":"false"}', "useTextIcon"),
        ("launcher enum", '{"cubismJvm":"other"}', "launcher"),
        ("pluginDirs type", '{"pluginDirs":"plugins"}', None),
    ):
        out = installer_write_config("full", [], legacy_invalid)
        doc = json.loads(out)
        if field is None:
            ok = doc["pluginDirs"] == ["plugins"]
        elif field == "launcher":
            ok = "launcher" not in doc or "cubismJvm" not in doc.get("launcher", {})
        else:
            ok = field not in doc
        check("T9 invalid " + label + " dropped and normalized", ok and doc["schemaVersion"] == 1)

    # T9b: a normalization that overwrites an existing config.json keeps one pre-normalization mirror, while a current v1 document and a fresh install produce none.
    out, backup = installer_write_config_with_backup("full", [a], invalid_current)
    check("T9b normalization preserves pre-normalize backup",
          backup == invalid_current and "futureCurrentField" not in out)
    out, backup = installer_write_config_with_backup("full", [c], existing)
    check("T9b current v1 selection update creates no backup", backup is None and out != existing)
    out, backup = installer_write_config_with_backup("full", [], legacy)
    check("T9b v0 migration preserves pre-normalize backup", backup == legacy and json.loads(out)["schemaVersion"] == 1)
    out, backup = installer_write_config_with_backup("full", [], None)
    check("T9b fresh install creates no backup", backup is None)

    # ---- Plugin payload inventory simulation of the hidden payload section and the checkbox semantics ----
    # TI1: a fresh partial Full install with {a, c} unselected installs every JAR and writes disabledPlugins=[a,c]
    jars = nsis_jars_after("full", [])
    check("TI1 Full installs all JARs", jars == BUNDLED_MODULES, str(jars))
    out = installer_write_config("full", [a, c], None)
    doc = json.loads(out)
    check("TI1 disabledPlugins == unchecked", doc["disabledPlugins"] == [a, c], str(doc.get("disabledPlugins")))

    # TI2: a later Full update lays every JAR and applies the current selection, so the two
    # previously unchecked bundled plugins become enabled again.
    out = installer_write_config("full", [], out)
    doc = json.loads(out)
    jars = nsis_jars_after("full", jars)
    check("TI2 updated JAR inventory complete", jars == BUNDLED_MODULES, str(jars))
    check("TI2 update re-enables checked bundled plugins",
          doc.get("disabledPlugins", []) == [], str(doc.get("disabledPlugins")))

    # TI2b: a legacy v0 config also has its checked bundled id enabled.
    existing = '{"disabledPlugins": ["' + a + '","' + UNRELATED + '"]}'
    out = installer_write_config("full", [], existing)
    doc = json.loads(out)
    check("TI2b v0 migration enables checked bundled plugin", doc["disabledPlugins"] == [UNRELATED],
          str(doc.get("disabledPlugins")))

    # TI3: fresh Thin -> no plugin JARs; no disabledPlugins
    jars = nsis_jars_after("thin", [])
    check("TI3 fresh Thin has no plugin JARs", jars == [], str(jars))
    out = installer_write_config("thin", [], None)
    doc = json.loads(out)
    check("TI3 fresh Thin has no disabledPlugins", "disabledPlugins" not in doc or doc["disabledPlugins"] == [], str(doc.get("disabledPlugins")))

    # TI4: Full to Thin writes no new JAR, keeps the plugins directory unchanged, and keeps the existing disabledPlugins.
    jars = nsis_jars_after("thin", BUNDLED_MODULES)
    check("TI4 Full->Thin writes no new JARs (existing JARs stay on disk)", jars == BUNDLED_MODULES, str(jars))
    existing = json.dumps({"format": "turboism.runtime.config", "schemaVersion": 1,
                           "worktreeId": "turboism-runtime", "pluginDirs": ["plugins"],
                           "disabledPlugins": [a]})
    out = installer_write_config("thin", [], existing)
    doc = json.loads(out)
    check("TI4 Full->Thin preserves existing disabledPlugins", doc["disabledPlugins"] == [a])

    # TI5: in an NSIS upgrade, a Thin upgrade deletes no plugin, and a Full upgrade retires the historical module and lays down the full set.
    jars = nsis_jars_after("thin", RETIRED_MODULES + BUNDLED_MODULES + ["third-party"])
    check("TI5 Thin upgrade keeps all existing plugin JARs (including unknown and third-party JARs)",
          jars == sorted(RETIRED_MODULES + BUNDLED_MODULES + ["third-party"]), str(jars))
    jars = nsis_jars_after("full", RETIRED_MODULES + ["third-party"])
    check("TI5 Full upgrade retires historical JARs, installs full set, and keeps unknown JARs",
          jars == sorted(BUNDLED_MODULES + ["third-party"]), str(jars))

    # Output validity: schemaVersion/format intact
    for label, out in [("T3", out)]:
        doc = json.loads(out)
        assert doc["format"] == "turboism.runtime.config" and doc["schemaVersion"] == 1

    # Keep the offline mirror aligned with both installer enum validators.
    template = json.loads((Path(__file__).parent / "config.template.json").read_text())
    for profile in sorted(MEMORY_PROFILES):
        candidate = json.loads(json.dumps(template))
        candidate["launcher"]["memoryProfile"] = profile
        check("memoryProfile accepted " + profile, validate_v1(candidate) == candidate)
    for profile in (None, 42, True, "", "balanced4G", "extreme16g"):
        candidate = json.loads(json.dumps(template))
        candidate["launcher"]["memoryProfile"] = profile
        try:
            validate_v1(candidate)
        except ValueError:
            check("invalid memoryProfile rejected " + repr(profile), True)
        else:
            check("invalid memoryProfile rejected " + repr(profile), False)

    check_nsis_retirement_contract()
    check_config_migration_contract()
    check_pre_normalize_backup_contract()
    check_java_installer_docs_contract()
    check_managed_graal_installer_contract()
    check_configurator_flow_contract()
    check_jar_payload_contract()
    check_plugin_selection_page_contract()
    check_icon_contract()
    check_launcher_and_shortcut_contract()
    check_eula_contract()
    check_graal_fallback_contract()
    check_uninstall_config_option()
    check_uninstall_postcondition()
    print("config merge + payload simulation + uninstall postcondition verified: all cases ok")


if __name__ == "__main__":
    main()
