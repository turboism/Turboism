#!/usr/bin/env python3
"""Generate the Cubism Editor compatibility matrix from reviewed repository data.

Single source of truth: every version set in the emitted artifacts is parsed
from data that already gates runtime admission — never hand-written:

- ``build/generated/verification/*.json`` — static verification records;
  a capability is *verified* on a version when its record admits it there.
- ``scripts/verification-sources/families/*.json`` — verification-source
  family roster and record membership.
- ``compatibility/cubism/reviewed-releases.json`` — the canonical reviewed version list.
- ``@CubismEditor`` annotations across ``sdk/src/main/java`` — SDK surface
  availability (type and accessor/method level).
- ``EditorCommand`` / ``EditorParameterizedCommand`` / ``EditorFileCommand``
  enum version sets — only commands admitted on a strict subset are listed.
- Selector contracts and host profiles in ``runtime/`` and
  ``scripts/verification-sources/templates`` (``SUPPORTED_VERSIONS`` /
  ``SUPPORTED_CUBISM_VERSIONS`` sets declared next to a literal capability id,
  plus ``ClassPinTable.load`` pin tables) — the runtime admission layer that
  narrows verified capabilities. Contracts that only *reference* another
  contract's capability constant are variant/reduced forms and never narrow.
- ``plugins/*/src/main/resources/META-INF/turboism/plugin.json`` plus plugin
  Java sources — each first-party plugin's declared capabilities and the SDK
  surfaces it actually references, which determine the versions on which the
  plugin's features can resolve.
- ``plugins/*/.../*-coverage.json`` — per-endpoint SDK coverage manifests
  (for example the MCP SDK coverage table).
- ``validation/cubism-version-matrix/campaign-jobs.json`` — latest FINAL
  real-host result per task x version, mapped onto plugins and capability
  groups through the task alias tables below.

Outputs (all deterministic — no timestamps — so ``--check`` byte-compares):

- ``compatibility/cubism/compatibility-matrix.json`` — machine-readable
  matrix for the docs site and plugin directory.
- ``compatibility/cubism/plugins{,_zh,_ja,_ko}.md`` — user view: one row per
  first-party plugin with its supported range and per-version feature deltas.
- ``compatibility/cubism/capabilities{,_zh,_ja,_ko}.md`` — developer view:
  capability-group x version matrix, SDK/command constraint tables, runtime
  admission pins, and the monotonicity note.
- A ``<!-- compatibility:begin --> ... <!-- compatibility:end -->`` block in
  every ``plugins/*/README*.md`` compatibility section.
- ``plugins/*/src/main/resources/META-INF/turboism/cubism-versions.json`` —
  the same derived supported-version set packaged inside each first-party
  plugin JAR as the machine-readable declaration third-party plugins may
  ship by hand.

The only hand-maintained tables here are *name* mappings (plugin capability
ids -> verification capability ids, campaign task names -> plugins/groups,
and feature label translations). They never carry version data: versions are
always resolved from the sources above, so a version-coverage change flows
through regeneration without touching this file.

Usage:
  generate_compatibility_matrix.py [--root REPO]
  generate_compatibility_matrix.py --check [--root REPO]
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

_SCRIPTS_DIR = str(Path(__file__).resolve().parent)
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

from compat_matrix import (
    BLOCK_BEGIN,
    BLOCK_END,
    BOUNDARY_TRIAGE,
    CAMPAIGN_INVENTORY,
    CAMPAIGN_JOBS,
    CAPABILITY_GROUP_RULES,
    CLASS_PINS_DIR,
    COMMAND_ENUMS,
    DECLARED_CAPABILITY_ALIASES,
    DEV_STRINGS,
    DOCS_DIR,
    FEATURE_LABELS,
    GENERATOR_VERSION,
    GROUP_LABELS,
    INTERCEPTOR,
    LANGUAGES,
    MATRIX_FORMAT,
    MATRIX_JSON,
    NON_FUNCTIONAL_PREFIXES,
    PENDING_TRIAGE,
    PLUGIN_DESCRIPTOR,
    README_HEADINGS,
    README_LOCALE,
    REVIEWED_HOST_ARTIFACTS,
    RUNTIME_MAIN,
    SDK_MAIN,
    STRINGS,
    TASK_GROUP_ALIASES,
    TASK_PLUGIN_ALIASES,
    TEMPLATES_DIR,
    VERIFICATION_DIR,
    VERSION_BOUNDARIES,
    VERSION_RE,
    _display_fallback,
    _feature_text,
    _format_official_link,
    _gap_suffix,
    _has_label,
    _host_cell,
    _load_boundary_entries,
    _nature_text,
    _note,
    _parse_annotation_args,
    _strip_java,
    _type_spans,
    admitted_versions,
    build_matrix,
    capability_group,
    classify_gap,
    compact_ranges,
    index_official_timeline,
    load_campaign,
    load_capabilities,
    load_class_pins,
    load_commands,
    load_contract_gates,
    load_families,
    load_monotonicity_exceptions,
    load_official_timeline,
    load_plugins,
    load_required_capabilities,
    load_reviewed_versions,
    load_runtime_gates,
    load_version_boundaries,
    localized,
    parse_sdk_annotations,
    readme_block,
    render_all,
    render_capabilities_doc,
    render_market_plugins,
    render_plugins_doc,
    resolve_declared_capability,
    sort_versions,
    task_base,
    update_readme,
    version_key,
)
from compat_matrix.constants import REPO_ROOT

__all__ = [
    "BLOCK_BEGIN",
    "BLOCK_END",
    "BOUNDARY_TRIAGE",
    "CAMPAIGN_INVENTORY",
    "CAMPAIGN_JOBS",
    "CAPABILITY_GROUP_RULES",
    "CLASS_PINS_DIR",
    "COMMAND_ENUMS",
    "DECLARED_CAPABILITY_ALIASES",
    "DEV_STRINGS",
    "DOCS_DIR",
    "FEATURE_LABELS",
    "GENERATOR_VERSION",
    "GROUP_LABELS",
    "INTERCEPTOR",
    "LANGUAGES",
    "MATRIX_FORMAT",
    "MATRIX_JSON",
    "NON_FUNCTIONAL_PREFIXES",
    "PENDING_TRIAGE",
    "PLUGIN_DESCRIPTOR",
    "README_HEADINGS",
    "README_LOCALE",
    "REPO_ROOT",
    "REVIEWED_HOST_ARTIFACTS",
    "RUNTIME_MAIN",
    "SDK_MAIN",
    "STRINGS",
    "TASK_GROUP_ALIASES",
    "TASK_PLUGIN_ALIASES",
    "TEMPLATES_DIR",
    "VERIFICATION_DIR",
    "VERSION_BOUNDARIES",
    "VERSION_RE",
    "_display_fallback",
    "_feature_text",
    "_format_official_link",
    "_gap_suffix",
    "_has_label",
    "_host_cell",
    "_load_boundary_entries",
    "_nature_text",
    "_note",
    "_parse_annotation_args",
    "_strip_java",
    "_type_spans",
    "admitted_versions",
    "build_matrix",
    "capability_group",
    "classify_gap",
    "compact_ranges",
    "index_official_timeline",
    "load_campaign",
    "load_capabilities",
    "load_class_pins",
    "load_commands",
    "load_contract_gates",
    "load_families",
    "load_monotonicity_exceptions",
    "load_official_timeline",
    "load_plugins",
    "load_required_capabilities",
    "load_reviewed_versions",
    "load_runtime_gates",
    "load_version_boundaries",
    "localized",
    "main",
    "parse_sdk_annotations",
    "readme_block",
    "render_all",
    "render_capabilities_doc",
    "render_market_plugins",
    "render_plugins_doc",
    "resolve_declared_capability",
    "sort_versions",
    "task_base",
    "update_readme",
    "version_key",
]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    outputs = render_all(root)
    if args.check:
        stale = [
            rel
            for rel, content in sorted(outputs.items())
            if not (root / rel).is_file()
            or (root / rel).read_text(encoding="utf-8") != content
        ]
        if stale:
            sys.stderr.write(
                "compatibility artifacts are stale; regenerate with "
                "`python3 scripts/generate_compatibility_matrix.py`:\n"
            )
            for rel in stale:
                sys.stderr.write(f"  {rel}\n")
            return 1
        return 0
    for rel, content in sorted(outputs.items()):
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    sys.stderr.write(f"wrote {len(outputs)} files\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
