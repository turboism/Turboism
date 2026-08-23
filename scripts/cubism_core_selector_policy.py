#!/usr/bin/env python3
"""Validate and generate the Cubism Core authorized selector contract."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from cubism_core_api import InventoryError, decode_json  # noqa: E402

FORMAT = "turboism.cubism-core.selector-policy"
SCHEMA_VERSION = 1
STATUS = "DRAFT"
ROLES = {"VERSION_PROBE", "STRUCTURAL"}
CONSTANT_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]*$")


def fail(message: str) -> None:
    raise InventoryError(message)


def compact_json(document: Any) -> str:
    return json.dumps(
        document,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ) + "\n"


def load_json(path: Path) -> dict[str, Any]:
    try:
        document = decode_json(path.read_text(encoding="utf-8"), str(path))
    except OSError as exc:
        raise InventoryError(f"unable to read {path}: {exc}") from exc
    if not isinstance(document, dict):
        fail(f"{path}: root must be an object")
    return document


def version_key(version: str) -> tuple[int, ...]:
    try:
        return tuple(int(part) for part in version.split("."))
    except ValueError as exc:
        raise InventoryError(f"invalid Cubism version: {version!r}") from exc


def require_text(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value:
        fail(f"{path} must be a non-empty string")
    return value


def require_string_list(value: Any, path: str) -> list[str]:
    if not isinstance(value, list) or not value:
        fail(f"{path} must be a non-empty array")
    result = [require_text(item, f"{path}[]") for item in value]
    if result != sorted(set(result), key=version_key if path.endswith("profiles") else None):
        fail(f"{path} must be unique and sorted")
    return result


def validate_pack(pack: dict[str, Any], source: Path) -> tuple[str, dict[str, dict[str, Any]]]:
    version = require_text(pack.get("cubismVersion"), f"{source}.cubismVersion")
    entries = pack.get("entries")
    if not isinstance(entries, list) or not entries:
        fail(f"{source}.entries must be a non-empty array")
    aliases: dict[str, dict[str, Any]] = {}
    for index, entry in enumerate(entries):
        path = f"{source}.entries[{index}]"
        if not isinstance(entry, dict):
            fail(f"{path} must be an object")
        alias = require_text(entry.get("name"), f"{path}.name")
        if alias in aliases:
            fail(f"{source}: duplicate selector alias {alias}")
        if entry.get("kind") not in {"class", "method"}:
            fail(f"{path}.kind must be class or method")
        if require_text(entry.get("profile"), f"{path}.profile") != f"cubism-{version}":
            fail(f"{path}.profile does not match Cubism version")
        aliases[alias] = entry
    return version, aliases


def load_packs(paths: Sequence[Path]) -> dict[str, dict[str, dict[str, Any]]]:
    packs: dict[str, dict[str, dict[str, Any]]] = {}
    for path in paths:
        version, aliases = validate_pack(load_json(path), path)
        if version in packs:
            fail(f"duplicate selector pack version: {version}")
        packs[version] = aliases
    if len(packs) < 2:
        fail("at least two selector pack versions are required")
    return packs


def validate_policy_shape(policy: dict[str, Any]) -> list[dict[str, Any]]:
    expected = {
        "format",
        "schemaVersion",
        "status",
        "adapterSliceId",
        "capabilityIds",
        "profiles",
        "selectors",
        "summary",
    }
    unknown = set(policy) - expected
    missing = expected - set(policy)
    if unknown:
        fail(f"selector policy has unknown fields: {sorted(unknown)}")
    if missing:
        fail(f"selector policy is missing fields: {sorted(missing)}")
    if policy["format"] != FORMAT:
        fail(f"selector policy format must be {FORMAT!r}")
    if policy["schemaVersion"] != SCHEMA_VERSION:
        fail(f"selector policy schemaVersion must be {SCHEMA_VERSION}")
    if policy["status"] != STATUS:
        fail(f"selector policy status must be {STATUS!r}")
    require_text(policy["adapterSliceId"], "adapterSliceId")
    require_string_list(policy["capabilityIds"], "capabilityIds")

    profiles = policy["profiles"]
    if not isinstance(profiles, dict) or not profiles:
        fail("profiles must be a non-empty object")
    for version, profile in profiles.items():
        version_key(version)
        if not isinstance(profile, dict) or set(profile) != {"providerId"}:
            fail(f"profiles.{version} must contain only providerId")
        require_text(profile["providerId"], f"profiles.{version}.providerId")

    selectors = policy["selectors"]
    if not isinstance(selectors, list) or not selectors:
        fail("selectors must be a non-empty array")
    constants: set[str] = set()
    aliases: set[str] = set()
    validated: list[dict[str, Any]] = []
    for index, selector in enumerate(selectors):
        path = f"selectors[{index}]"
        if not isinstance(selector, dict):
            fail(f"{path} must be an object")
        required = {"constant", "alias", "role", "profiles"}
        if set(selector) != required:
            fail(f"{path} fields must be exactly {sorted(required)}")
        constant = require_text(selector["constant"], f"{path}.constant")
        if not CONSTANT_PATTERN.fullmatch(constant):
            fail(f"{path}.constant is not a Java constant identifier")
        if constant in constants:
            fail(f"duplicate selector constant: {constant}")
        constants.add(constant)
        alias = require_text(selector["alias"], f"{path}.alias")
        if alias in aliases:
            fail(f"duplicate selector alias: {alias}")
        aliases.add(alias)
        if selector["role"] not in ROLES:
            fail(f"{path}.role is invalid")
        selector_profiles = require_string_list(
            selector["profiles"], f"{path}.profiles"
        )
        if any(version not in profiles for version in selector_profiles):
            fail(f"{path}.profiles contains an undeclared profile")
        validated.append(selector)
    return validated


def classify_selector_roster(
    policy: dict[str, Any],
    packs: dict[str, dict[str, dict[str, Any]]],
) -> list[dict[str, Any]]:
    selectors = validate_policy_shape(policy)
    declared_profiles = set(policy["profiles"])
    if set(packs) != declared_profiles:
        fail(
            "selector policy profile set does not match mapping packs: "
            f"policy={sorted(declared_profiles)}, packs={sorted(packs)}"
        )

    selector_by_alias = {selector["alias"]: selector for selector in selectors}
    pack_aliases = set().union(*(set(entries) for entries in packs.values()))
    if set(selector_by_alias) != pack_aliases:
        missing = sorted(pack_aliases - set(selector_by_alias))
        extra = sorted(set(selector_by_alias) - pack_aliases)
        fail(f"selector policy/pack alias mismatch: missing={missing}, extra={extra}")

    roster: list[dict[str, Any]] = []
    for alias in sorted(selector_by_alias):
        selector = selector_by_alias[alias]
        observed_profiles = sorted(
            [version for version, entries in packs.items() if alias in entries],
            key=version_key,
        )
        if selector["profiles"] != observed_profiles:
            fail(
                f"selector profile coverage mismatch for {alias}: "
                f"expected {observed_profiles}, got {selector['profiles']}"
            )
        reference: tuple[str, str, str] | None = None
        for version in observed_profiles:
            entry = packs[version][alias]
            shape = (
                entry["kind"],
                require_text(entry.get("runtime"), f"{alias}.runtime"),
                entry.get("descriptor", ""),
            )
            if reference is None:
                reference = shape
            elif shape != reference:
                fail(f"selector runtime shape differs across profiles for {alias}")
        assert reference is not None
        roster.append(
            {
                "constant": selector["constant"],
                "alias": alias,
                "role": selector["role"],
                "profiles": observed_profiles,
                "kind": reference[0],
                "runtime": reference[1],
                "descriptor": reference[2],
            }
        )
    return roster


def roster_sha256(roster: Sequence[dict[str, Any]]) -> str:
    digest = hashlib.sha256()
    for selector in roster:
        digest.update(
            (
                "|".join(
                    [
                        selector["constant"],
                        selector["alias"],
                        selector["role"],
                        ",".join(selector["profiles"]),
                        selector["kind"],
                        selector["runtime"],
                        selector["descriptor"],
                    ]
                )
                + "\n"
            ).encode("utf-8")
        )
    return digest.hexdigest()


def expected_summary(
    roster: Sequence[dict[str, Any]],
    packs: dict[str, dict[str, dict[str, Any]]],
) -> dict[str, Any]:
    return {
        "selectorCount": len(roster),
        "selectorRosterSha256": roster_sha256(roster),
        "versions": {
            version: {"entryCount": len(packs[version])}
            for version in sorted(packs, key=version_key)
        },
    }


def normalize_policy(
    policy: dict[str, Any],
    packs: dict[str, dict[str, dict[str, Any]]],
) -> dict[str, Any]:
    normalized = copy.deepcopy(policy)
    roster = classify_selector_roster(normalized, packs)
    normalized["summary"] = expected_summary(roster, packs)
    return normalized


def validate_policy(
    policy: dict[str, Any],
    packs: dict[str, dict[str, dict[str, Any]]],
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    roster = classify_selector_roster(policy, packs)
    expected = expected_summary(roster, packs)
    if policy["summary"] != expected:
        fail(
            "selector policy summary/roster digest mismatch: "
            f"expected {expected}, got {policy['summary']}"
        )
    return policy, roster


def java_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=True)


def set_expression(constants: Sequence[str], indent: str = "        ") -> str:
    if not constants:
        return "Set.of()"
    body = (",\n" + indent).join(constants)
    return "Set.of(\n" + indent + body + "\n    )"


def render_java(policy: dict[str, Any], roster: Sequence[dict[str, Any]]) -> str:
    by_constant = sorted(roster, key=lambda selector: selector["constant"])
    profiles = sorted(policy["profiles"], key=version_key)
    version_probe = [
        selector["constant"]
        for selector in by_constant
        if selector["role"] == "VERSION_PROBE"
    ]
    common_structural = [
        selector["constant"]
        for selector in by_constant
        if selector["role"] == "STRUCTURAL"
        and set(selector["profiles"]) == set(profiles)
    ]
    required = {
        version: [
            selector["constant"]
            for selector in by_constant
            if version in selector["profiles"]
        ]
        for version in profiles
    }
    structural_methods = {
        version: [
            selector["constant"]
            for selector in by_constant
            if version in selector["profiles"]
            and selector["role"] == "STRUCTURAL"
            and selector["kind"] == "method"
        ]
        for version in profiles
    }

    lines = [
        "package dev.turboism.mapping.verification.selector;",
        "",
        "import java.util.Objects;",
        "import java.util.Optional;",
        "import java.util.Set;",
        "",
        "/**",
        " * Generated exact selector contract for the admitted Cubism Core slice.",
        " *",
        " * <p>Source: cubism-ref/core-api/policy/cubism-core-selector-policy.json.",
        " * Do not edit by hand. This class contains stable aliases only and cannot",
        " * create or authorize verified evidence.</p>",
        " */",
        "public final class CorePublicApiSelectorContract {",
        "",
        f"    public static final String SELECTOR_ROSTER_SHA256 = {java_string(policy['summary']['selectorRosterSha256'])};",
        f"    public static final String ADAPTER_SLICE_ID = {java_string(policy['adapterSliceId'])};",
        "    public static final Set<String> CAPABILITY_IDS = "
        + set_expression([java_string(value) for value in policy["capabilityIds"]], "        ")
        + ";",
        "",
    ]
    for selector in by_constant:
        lines.append(
            f"    public static final String {selector['constant']} = "
            f"{java_string(selector['alias'])};"
        )
    lines.append("")
    for version in profiles:
        identifier = version.replace(".", "_")
        lines.append(
            f"    public static final String ARTIFACT_PROFILE_{identifier} = "
            f"{java_string(version)};"
        )
    lines.extend(
        [
            "    public static final Set<String> SUPPORTED_ARTIFACT_PROFILES = "
            + set_expression(
                [f"ARTIFACT_PROFILE_{version.replace('.', '_')}" for version in profiles],
                "        ",
            )
            + ";",
            "",
            "    public static final Set<String> VERSION_PROBE_ALIASES = "
            + set_expression(version_probe, "        ")
            + ";",
            "",
            "    public static final Set<String> COMMON_STRUCTURAL_ALIASES = "
            + set_expression(common_structural, "        ")
            + ";",
            "",
        ]
    )
    for version in profiles:
        identifier = version.replace(".", "_")
        lines.extend(
            [
                f"    public static final Set<String> REQUIRED_ALIASES_{identifier} = "
                + set_expression(required[version], "        ")
                + ";",
                "",
                f"    public static final Set<String> STRUCTURAL_METHOD_ALIASES_{identifier} = "
                + set_expression(structural_methods[version], "        ")
                + ";",
                "",
            ]
        )
    lines.extend(
        [
            "    private CorePublicApiSelectorContract() {",
            "    }",
            "",
            "    public static Optional<Set<String>> requiredAliasesFor(",
            "        final String artifactProfile",
            "    ) {",
            "        Objects.requireNonNull(artifactProfile, \"artifactProfile\");",
            "        return switch (artifactProfile) {",
        ]
    )
    for version in profiles:
        identifier = version.replace(".", "_")
        lines.append(
            f"            case ARTIFACT_PROFILE_{identifier} -> "
            f"Optional.of(REQUIRED_ALIASES_{identifier});"
        )
    lines.extend(
        [
            "            default -> Optional.empty();",
            "        };",
            "    }",
            "",
            "    public static Optional<Set<String>> structuralMethodAliasesFor(",
            "        final String artifactProfile",
            "    ) {",
            "        Objects.requireNonNull(artifactProfile, \"artifactProfile\");",
            "        return switch (artifactProfile) {",
        ]
    )
    for version in profiles:
        identifier = version.replace(".", "_")
        lines.append(
            f"            case ARTIFACT_PROFILE_{identifier} -> "
            f"Optional.of(STRUCTURAL_METHOD_ALIASES_{identifier});"
        )
    lines.extend(
        [
            "            default -> Optional.empty();",
            "        };",
            "    }",
            "",
            "    public static Optional<String> providerIdFor(",
            "        final String artifactProfile",
            "    ) {",
            "        Objects.requireNonNull(artifactProfile, \"artifactProfile\");",
            "        return switch (artifactProfile) {",
        ]
    )
    for version in profiles:
        identifier = version.replace(".", "_")
        provider = policy["profiles"][version]["providerId"]
        lines.append(
            f"            case ARTIFACT_PROFILE_{identifier} -> "
            f"Optional.of({java_string(provider)});"
        )
    lines.extend(
        [
            "            default -> Optional.empty();",
            "        };",
            "    }",
            "}",
            "",
        ]
    )
    return "\n".join(lines)


def pack_documents(arguments: argparse.Namespace) -> dict[str, dict[str, dict[str, Any]]]:
    return load_packs(arguments.pack)


def command_bootstrap(arguments: argparse.Namespace) -> int:
    policy = load_json(arguments.policy)
    sys.stdout.write(compact_json(normalize_policy(policy, pack_documents(arguments))))
    return 0


def command_validate(arguments: argparse.Namespace) -> int:
    policy, roster = validate_policy(
        load_json(arguments.policy), pack_documents(arguments)
    )
    print(
        f"OK {arguments.policy}: {len(roster)} selectors "
        f"({policy['summary']['selectorRosterSha256']})"
    )
    return 0


def command_render_java(arguments: argparse.Namespace) -> int:
    policy, roster = validate_policy(
        load_json(arguments.policy), pack_documents(arguments)
    )
    rendered = render_java(policy, roster)
    if arguments.check:
        try:
            committed = arguments.output.read_text(encoding="utf-8")
        except OSError as exc:
            fail(f"unable to read generated selector contract {arguments.output}: {exc}")
        if committed != rendered:
            fail(f"generated selector contract has drifted: {arguments.output}")
        print(f"OK {arguments.output}: generated selector contract is current")
        return 0
    arguments.output.write_text(rendered, encoding="utf-8")
    print(f"WROTE {arguments.output}")
    return 0


def add_pack_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--pack",
        type=Path,
        action="append",
        required=True,
        help="Core selector mapping pack; repeat for every supported version",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    bootstrap_parser = commands.add_parser("bootstrap")
    bootstrap_parser.add_argument("--policy", type=Path, required=True)
    add_pack_arguments(bootstrap_parser)
    bootstrap_parser.set_defaults(handler=command_bootstrap)

    validate_parser = commands.add_parser("validate")
    validate_parser.add_argument("--policy", type=Path, required=True)
    add_pack_arguments(validate_parser)
    validate_parser.set_defaults(handler=command_validate)

    render_parser = commands.add_parser("render-java")
    render_parser.add_argument("--policy", type=Path, required=True)
    render_parser.add_argument("--output", type=Path, required=True)
    render_parser.add_argument("--check", action="store_true")
    add_pack_arguments(render_parser)
    render_parser.set_defaults(handler=command_render_java)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    try:
        arguments = build_parser().parse_args(argv)
        return arguments.handler(arguments)
    except InventoryError as exc:
        print(f"Cubism Core selector policy error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
