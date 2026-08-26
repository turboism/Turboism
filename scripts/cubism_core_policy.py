#!/usr/bin/env python3
"""Validate the complete Cubism Core public-member classification policy.

The policy is compact and reviewable: ordered rules classify every exact public
member, while a SHA-256 roster binds the resulting classification of the full
5.2/5.3.02 surface. New, removed, or reclassified members fail the gate until
both the rules and roster digest are reviewed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from cubism_core_api import InventoryError, load_inventory  # noqa: E402

FORMAT = "turboism.cubism-core.member-policy"
SCHEMA_VERSION = 1
STATUS = "DRAFT"

CATEGORIES = {
    "CONSTANT",
    "CONSTRUCTION",
    "DIAGNOSTIC",
    "EVALUATION",
    "MODEL_READ",
    "MODEL_WRITE",
    "OWNED_MODEL",
    "RUNTIME_INTERNAL",
    "TYPE_METADATA",
}
EXPOSURES = {"INTERNAL", "MODEL", "OWNED_MODEL"}
LIFECYCLES = {"NONE", "BEFORE_ON_AFTER"}
MATCH_FIELDS = {"kind", "owner", "ownerIn", "name", "nameIn", "namePrefix"}
RULE_FIELDS = {"id", "match", "category", "exposure", "lifecycle"}

CORE = "com.live2d.sdk.cubism.core."
DEFAULT_RULES: list[dict[str, Any]] = [
    {
        "id": "public-field",
        "match": {"kind": "field"},
        "category": "CONSTANT",
        "exposure": "INTERNAL",
    },
    {
        "id": "public-constructor",
        "match": {"kind": "constructor"},
        "category": "CONSTRUCTION",
        "exposure": "INTERNAL",
    },
    {
        "id": "logger-interface",
        "match": {"owner": CORE + "ICubismLogger"},
        "category": "RUNTIME_INTERNAL",
        "exposure": "INTERNAL",
    },
    {
        "id": "global-logger",
        "match": {
            "owner": CORE + "Live2DCubismCore",
            "nameIn": ["getLogger", "setLogger"],
        },
        "category": "RUNTIME_INTERNAL",
        "exposure": "INTERNAL",
    },
    {
        "id": "native-lifetime",
        "match": {"nameIn": ["close", "getNativeHandle"]},
        "category": "RUNTIME_INTERNAL",
        "exposure": "INTERNAL",
    },
    {
        "id": "runtime-version-probe",
        "match": {
            "owner": CORE + "Live2DCubismCore",
            "name": "getVersion",
        },
        "category": "DIAGNOSTIC",
        "exposure": "INTERNAL",
    },
    {
        "id": "model-property-write",
        "match": {"nameIn": ["setOpacity", "setValue"]},
        "category": "MODEL_WRITE",
        "exposure": "MODEL",
        "lifecycle": "BEFORE_ON_AFTER",
    },
    {
        "id": "model-evaluation",
        "match": {
            "owner": CORE + "CubismModel",
            "nameIn": ["partialUpdate", "resetDrawableDynamicFlags", "update"],
        },
        "category": "EVALUATION",
        "exposure": "MODEL",
        "lifecycle": "BEFORE_ON_AFTER",
    },
    {
        "id": "partial-update-factor-reset",
        "match": {
            "owner": CORE + "CubismPartialUpdateModelFactor",
            "namePrefix": "reset",
        },
        "category": "EVALUATION",
        "exposure": "MODEL",
        "lifecycle": "BEFORE_ON_AFTER",
    },
    {
        "id": "partial-update-factor-create",
        "match": {
            "owner": CORE + "CubismModel",
            "name": "createPartialUpdateModelFactor",
        },
        "category": "EVALUATION",
        "exposure": "MODEL",
    },
    {
        "id": "owned-moc",
        "match": {"owner": CORE + "CubismMoc"},
        "category": "OWNED_MODEL",
        "exposure": "OWNED_MODEL",
    },
    {
        "id": "moc-byte-diagnostic",
        "match": {
            "owner": CORE + "Live2DCubismCore",
            "nameIn": ["getLatestMocVersion", "getMocVersion", "hasMocConsistency"],
        },
        "category": "OWNED_MODEL",
        "exposure": "OWNED_MODEL",
    },
    {
        "id": "type-method",
        "match": {
            "nameIn": ["getNumber", "toString", "toType", "valueOf", "values"]
        },
        "category": "TYPE_METADATA",
        "exposure": "MODEL",
    },
    {
        "id": "type-owner",
        "match": {
            "ownerIn": [
                CORE + "CubismAlphaBlendType",
                CORE + "CubismColorBlendType",
                CORE + "CubismCoreVersion",
                CORE + "CubismParameters$ParameterType",
            ]
        },
        "category": "TYPE_METADATA",
        "exposure": "MODEL",
    },
    {
        "id": "model-read",
        "match": {"namePrefix": ["find", "get", "is"]},
        "category": "MODEL_READ",
        "exposure": "MODEL",
    },
]


def fail(message: str) -> None:
    raise InventoryError(message)


def compact_json(document: Any) -> str:
    return json.dumps(
        document,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ) + "\n"


def version_key(version: str) -> tuple[int, ...]:
    try:
        return tuple(int(part) for part in version.split("."))
    except ValueError as exc:
        raise InventoryError(f"invalid Cubism version: {version!r}") from exc


def member_identity(owner: str, member: dict[str, Any]) -> tuple[str, str, str, str]:
    return owner, member["kind"], member["name"], member["descriptor"]


def observed_members(
    inventories: Sequence[dict[str, Any]],
) -> tuple[dict[tuple[str, str, str, str], set[str]], dict[str, dict[str, int]]]:
    observed: dict[tuple[str, str, str, str], set[str]] = defaultdict(set)
    summaries: dict[str, dict[str, int]] = {}
    for inventory in inventories:
        version = inventory["cubismVersion"]
        if version in summaries:
            fail(f"duplicate inventory version: {version}")
        summaries[version] = dict(inventory["summary"])
        for class_entry in inventory["classes"]:
            for member in class_entry["members"]:
                observed[member_identity(class_entry["name"], member)].add(version)
    return observed, summaries


def _matches_scalar(actual: str, expected: Any, path: str) -> bool:
    if not isinstance(expected, str) or not expected:
        fail(f"{path} must be a non-empty string")
    return actual == expected


def _matches_list(actual: str, expected: Any, path: str) -> bool:
    if not isinstance(expected, list) or not expected:
        fail(f"{path} must be a non-empty array")
    if any(not isinstance(value, str) or not value for value in expected):
        fail(f"{path} values must be non-empty strings")
    if expected != sorted(set(expected)):
        fail(f"{path} values must be unique and sorted")
    return actual in expected


def _matches_prefix(actual: str, expected: Any, path: str) -> bool:
    prefixes = [expected] if isinstance(expected, str) else expected
    if not isinstance(prefixes, list) or not prefixes:
        fail(f"{path} must be a non-empty string or array")
    if any(not isinstance(value, str) or not value for value in prefixes):
        fail(f"{path} values must be non-empty strings")
    if len(prefixes) > 1 and prefixes != sorted(set(prefixes)):
        fail(f"{path} values must be unique and sorted")
    return any(actual.startswith(prefix) for prefix in prefixes)


def rule_matches(
    rule: dict[str, Any],
    identity: tuple[str, str, str, str],
    path: str,
) -> bool:
    owner, kind, name, _descriptor = identity
    match = rule["match"]
    if not isinstance(match, dict) or not match:
        fail(f"{path}.match must be a non-empty object")
    unknown = set(match) - MATCH_FIELDS
    if unknown:
        fail(f"{path}.match has unknown fields: {sorted(unknown)}")
    checks: list[bool] = []
    for field, expected in match.items():
        value_path = f"{path}.match.{field}"
        if field == "kind":
            checks.append(_matches_scalar(kind, expected, value_path))
        elif field == "owner":
            checks.append(_matches_scalar(owner, expected, value_path))
        elif field == "ownerIn":
            checks.append(_matches_list(owner, expected, value_path))
        elif field == "name":
            checks.append(_matches_scalar(name, expected, value_path))
        elif field == "nameIn":
            checks.append(_matches_list(name, expected, value_path))
        elif field == "namePrefix":
            checks.append(_matches_prefix(name, expected, value_path))
    return all(checks)


def validate_rules(rules: Any) -> list[dict[str, Any]]:
    if not isinstance(rules, list) or not rules:
        fail("member policy rules must be a non-empty array")
    ids: set[str] = set()
    validated: list[dict[str, Any]] = []
    for index, rule in enumerate(rules):
        path = f"rules[{index}]"
        if not isinstance(rule, dict):
            fail(f"{path} must be an object")
        unknown = set(rule) - RULE_FIELDS
        required = {"id", "match", "category", "exposure"}
        missing = required - set(rule)
        if unknown:
            fail(f"{path} has unknown fields: {sorted(unknown)}")
        if missing:
            fail(f"{path} is missing fields: {sorted(missing)}")
        rule_id = rule["id"]
        if not isinstance(rule_id, str) or not rule_id:
            fail(f"{path}.id must be a non-empty string")
        if rule_id in ids:
            fail(f"duplicate policy rule id: {rule_id}")
        ids.add(rule_id)
        if rule["category"] not in CATEGORIES:
            fail(f"{path}.category is invalid")
        if rule["exposure"] not in EXPOSURES:
            fail(f"{path}.exposure is invalid")
        lifecycle = rule.get("lifecycle", "NONE")
        if lifecycle not in LIFECYCLES:
            fail(f"{path}.lifecycle is invalid")
        if lifecycle == "NONE" and "lifecycle" in rule:
            fail(f"{path}.lifecycle must be omitted when NONE")
        # Validate matcher shape even if no current member reaches this rule.
        rule_matches(rule, ("owner", "method", "name", "()V"), path)
        validated.append(rule)
    return validated


def classify_members(
    rules: Sequence[dict[str, Any]],
    inventories: Sequence[dict[str, Any]],
) -> tuple[list[dict[str, Any]], dict[str, dict[str, int]]]:
    observed, summaries = observed_members(inventories)
    classified: list[dict[str, Any]] = []
    rule_hits: Counter[str] = Counter()
    for identity in sorted(observed):
        selected: dict[str, Any] | None = None
        for index, rule in enumerate(rules):
            if rule_matches(rule, identity, f"rules[{index}]"):
                selected = rule
                break
        if selected is None:
            owner, kind, name, descriptor = identity
            fail(f"unclassified public member: {owner}#{name}{descriptor} ({kind})")
        rule_hits[selected["id"]] += 1
        owner, kind, name, descriptor = identity
        classified.append(
            {
                "owner": owner,
                "kind": kind,
                "name": name,
                "descriptor": descriptor,
                "versions": sorted(observed[identity], key=version_key),
                "category": selected["category"],
                "exposure": selected["exposure"],
                "lifecycle": selected.get("lifecycle", "NONE"),
                "rule": selected["id"],
            }
        )
    unused = [rule["id"] for rule in rules if rule_hits[rule["id"]] == 0]
    if unused:
        fail(f"member policy has unused rules: {unused}")
    return classified, summaries


def roster_sha256(classified: Sequence[dict[str, Any]]) -> str:
    digest = hashlib.sha256()
    for member in classified:
        digest.update(
            (
                "|".join(
                    [
                        member["owner"],
                        member["kind"],
                        member["name"],
                        member["descriptor"],
                        ",".join(member["versions"]),
                        member["category"],
                        member["exposure"],
                        member["lifecycle"],
                        member["rule"],
                    ]
                )
                + "\n"
            ).encode("utf-8")
        )
    return digest.hexdigest()


def expected_summary(
    classified: Sequence[dict[str, Any]],
    summaries: dict[str, dict[str, int]],
) -> dict[str, Any]:
    return {
        "classifiedRosterSha256": roster_sha256(classified),
        "uniqueMemberCount": len(classified),
        "versions": {
            version: summaries[version]
            for version in sorted(summaries, key=version_key)
        },
    }


def bootstrap(inventories: Sequence[dict[str, Any]]) -> dict[str, Any]:
    rules = validate_rules(DEFAULT_RULES)
    classified, summaries = classify_members(rules, inventories)
    return {
        "format": FORMAT,
        "rules": rules,
        "schemaVersion": SCHEMA_VERSION,
        "status": STATUS,
        "summary": expected_summary(classified, summaries),
    }


def load_policy(path: Path) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise InventoryError(f"unable to load member policy {path}: {exc}") from exc
    if not isinstance(document, dict):
        fail("member policy root must be an object")
    return document


def validate_policy(
    policy: dict[str, Any],
    inventories: Sequence[dict[str, Any]],
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    expected_fields = {"format", "schemaVersion", "status", "summary", "rules"}
    unknown = set(policy) - expected_fields
    missing = expected_fields - set(policy)
    if unknown:
        fail(f"member policy has unknown fields: {sorted(unknown)}")
    if missing:
        fail(f"member policy is missing fields: {sorted(missing)}")
    if policy["format"] != FORMAT:
        fail(f"member policy format must be {FORMAT!r}")
    if policy["schemaVersion"] != SCHEMA_VERSION:
        fail(f"member policy schemaVersion must be {SCHEMA_VERSION}")
    if policy["status"] != STATUS:
        fail(f"member policy status must be {STATUS!r}")
    rules = validate_rules(policy["rules"])
    classified, summaries = classify_members(rules, inventories)
    expected = expected_summary(classified, summaries)
    if policy["summary"] != expected:
        fail(
            "member policy summary/roster digest does not match inventories and rules: "
            f"expected {expected}, got {policy['summary']}"
        )
    return policy, classified


def java_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=True)


def render_java_catalog(
    policy: dict[str, Any],
    classified: Sequence[dict[str, Any]],
) -> str:
    rows = []
    for member in classified:
        rows.append(
            "\\t".join(
                [
                    member["owner"],
                    member["kind"],
                    member["name"],
                    member["descriptor"],
                    ",".join(member["versions"]),
                    member["category"],
                    member["exposure"],
                    member["lifecycle"],
                    member["rule"],
                ]
            )
        )
    data = "\n".join(rows)
    digest = policy["summary"]["classifiedRosterSha256"]
    return f'''package dev.turboism.adapter.cubism.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Generated complete Cubism Core public-member catalog.
 *
 * <p>Source: compatibility/cubism/core-api/policy/cubism-core-member-policy.json.
 * Do not edit by hand. The catalog contains names and descriptors only; no Core
 * class, reflection object, method handle, or native handle crosses this boundary.</p>
 */
final class GeneratedCorePublicApiCatalog {{

    static final String CLASSIFIED_ROSTER_SHA256 = {java_string(digest)};

    enum Category {{
        CONSTANT,
        CONSTRUCTION,
        DIAGNOSTIC,
        EVALUATION,
        MODEL_READ,
        MODEL_WRITE,
        OWNED_MODEL,
        RUNTIME_INTERNAL,
        TYPE_METADATA
    }}

    enum Exposure {{ INTERNAL, MODEL, OWNED_MODEL }}

    enum Lifecycle {{ NONE, BEFORE_ON_AFTER }}

    record Member(
        String owner,
        String kind,
        String name,
        String descriptor,
        List<String> versions,
        Category category,
        Exposure exposure,
        Lifecycle lifecycle,
        String ruleId
    ) {{
        Member {{
            owner = requireText(owner, "owner");
            kind = requireText(kind, "kind");
            name = requireText(name, "name");
            descriptor = requireText(descriptor, "descriptor");
            versions = List.copyOf(versions);
            if (versions.isEmpty()) {{
                throw new IllegalArgumentException("versions must not be empty");
            }}
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(exposure, "exposure");
            Objects.requireNonNull(lifecycle, "lifecycle");
            ruleId = requireText(ruleId, "ruleId");
        }}

        boolean supports(final String version) {{
            return versions.contains(Objects.requireNonNull(version, "version"));
        }}
    }}

    private static final String DATA = """
{data}
        """;

    private static final List<Member> MEMBERS = parse();

    private GeneratedCorePublicApiCatalog() {{
    }}

    static List<Member> members() {{
        return MEMBERS;
    }}

    static Optional<Member> find(
        final String version,
        final String owner,
        final String name,
        final String descriptor
    ) {{
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        return MEMBERS.stream()
            .filter(member -> member.supports(version))
            .filter(member -> member.owner().equals(owner))
            .filter(member -> member.name().equals(name))
            .filter(member -> member.descriptor().equals(descriptor))
            .findFirst();
    }}

    private static List<Member> parse() {{
        final List<Member> members = new ArrayList<>();
        DATA.lines()
            .filter(line -> !line.isBlank())
            .forEach(line -> members.add(parse(line)));
        return List.copyOf(members);
    }}

    private static Member parse(final String line) {{
        final String[] columns = line.split("\\t", -1);
        if (columns.length != 9) {{
            throw new IllegalStateException(
                "Generated Core member row must have exactly 9 columns."
            );
        }}
        return new Member(
            columns[0],
            columns[1],
            columns[2],
            columns[3],
            List.copyOf(Arrays.asList(columns[4].split(","))),
            Category.valueOf(columns[5]),
            Exposure.valueOf(columns[6]),
            Lifecycle.valueOf(columns[7]),
            columns[8]
        );
    }}

    private static String requireText(final String value, final String name) {{
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {{
            throw new IllegalArgumentException(name + " must not be blank");
        }}
        return value;
    }}
}}
'''


def render_report(policy: dict[str, Any], classified: Sequence[dict[str, Any]]) -> str:
    category_counts = Counter(member["category"] for member in classified)
    exposure_counts = Counter(member["exposure"] for member in classified)
    lifecycle_counts = Counter(member["lifecycle"] for member in classified)
    version_counts: dict[str, Counter[str]] = defaultdict(Counter)
    rule_counts = Counter(member["rule"] for member in classified)
    for member in classified:
        for version in member["versions"]:
            version_counts[version][member["category"]] += 1

    lines = [
        "# Cubism Core Complete Member Policy",
        "",
        "- Status: `DRAFT`",
        "- Format: `turboism.cubism-core.member-policy` v1",
        "- Runtime authorization: **none**; this is classification evidence only.",
        f"- Classified roster SHA-256: `{policy['summary']['classifiedRosterSha256']}`",
        "",
        "Every observed public field, constructor, and method is classified by exactly one first-matching ordered rule. A new, removed, or reclassified member changes the roster digest and fails the gate.",
        "",
        "## Summary",
        "",
        f"- Unique public members across supported versions: **{len(classified)}**",
        "- Cubism 5.2: **158 callables / 19 fields**",
        "- Cubism 5.3.02: **194 callables / 43 fields**",
        "",
        "### Categories",
        "",
        "| Category | Unique members | 5.2 | 5.3.02 |",
        "| --- | ---: | ---: | ---: |",
    ]
    for category in sorted(category_counts):
        lines.append(
            f"| `{category}` | {category_counts[category]} | "
            f"{version_counts['5.2'][category]} | "
            f"{version_counts['5.3.02'][category]} |"
        )
    lines.extend(
        [
            "",
            "### Exposure",
            "",
            "| Exposure | Unique members |",
            "| --- | ---: |",
        ]
    )
    for exposure in sorted(exposure_counts):
        lines.append(f"| `{exposure}` | {exposure_counts[exposure]} |")
    lines.extend(
        [
            "",
            "### Lifecycle",
            "",
            "| Lifecycle | Unique members |",
            "| --- | ---: |",
        ]
    )
    for lifecycle in sorted(lifecycle_counts):
        lines.append(f"| `{lifecycle}` | {lifecycle_counts[lifecycle]} |")

    lines.extend(
        [
            "",
            "## Ordered classification rules",
            "",
            "| Order | Rule | Category | Exposure | Lifecycle | Members |",
            "| ---: | --- | --- | --- | --- | ---: |",
        ]
    )
    for index, rule in enumerate(policy["rules"], start=1):
        lines.append(
            f"| {index} | `{rule['id']}` | `{rule['category']}` | "
            f"`{rule['exposure']}` | `{rule.get('lifecycle', 'NONE')}` | "
            f"{rule_counts[rule['id']]} |"
        )

    lines.extend(
        [
            "",
            "## Lifecycle-enabled methods",
            "",
            "| Owner | Method | Descriptor | Versions | Category |",
            "| --- | --- | --- | --- | --- |",
        ]
    )
    for member in classified:
        if member["lifecycle"] != "BEFORE_ON_AFTER":
            continue
        lines.append(
            f"| `{member['owner'].rsplit('.', 1)[-1]}` | `{member['name']}` | "
            f"`{member['descriptor']}` | {', '.join(member['versions'])} | "
            f"`{member['category']}` |"
        )

    lines.extend(
        [
            "",
            "## Runtime-internal methods",
            "",
            "| Owner | Member | Descriptor | Versions |",
            "| --- | --- | --- | --- |",
        ]
    )
    for member in classified:
        if member["category"] != "RUNTIME_INTERNAL":
            continue
        lines.append(
            f"| `{member['owner'].rsplit('.', 1)[-1]}` | `{member['name']}` | "
            f"`{member['descriptor']}` | {', '.join(member['versions'])} |"
        )

    lines.extend(
        [
            "",
            "## Meanings",
            "",
            "- `MODEL_READ`: model or evaluated-state reads exposed through Turboism model objects.",
            "- `MODEL_WRITE`: natural setters; Editor-attached models use Editor-native entries.",
            "- `EVALUATION`: model update/evaluation operations.",
            "- `OWNED_MODEL`: user-supplied or Turboism-owned Core model workflows only.",
            "- `TYPE_METADATA`: normalized type/version metadata.",
            "- `DIAGNOSTIC`: framework admission/runtime diagnostics.",
            "- `CONSTANT` and `CONSTRUCTION`: raw Core implementation surface used to build wrappers.",
            "- `RUNTIME_INTERNAL`: native lifetime, native handles, and global logger behavior.",
            "",
            "`BEFORE_ON_AFTER` marks methods governed by the unified simple lifecycle; it does not authorize a production hook by itself.",
            "",
        ]
    )
    return "\n".join(lines)


def inventory_documents(arguments: argparse.Namespace) -> list[dict[str, Any]]:
    documents = [load_inventory(path) for path in arguments.inventory]
    if len(documents) < 2:
        fail("at least two inventory versions are required")
    return documents


def command_bootstrap(arguments: argparse.Namespace) -> int:
    sys.stdout.write(compact_json(bootstrap(inventory_documents(arguments))))
    return 0


def command_validate(arguments: argparse.Namespace) -> int:
    policy, classified = validate_policy(
        load_policy(arguments.policy), inventory_documents(arguments)
    )
    print(
        f"OK {arguments.policy}: {len(classified)} unique public members classified "
        f"({policy['summary']['classifiedRosterSha256']})"
    )
    return 0


def command_render_java(arguments: argparse.Namespace) -> int:
    policy, classified = validate_policy(
        load_policy(arguments.policy), inventory_documents(arguments)
    )
    rendered = render_java_catalog(policy, classified)
    if arguments.check:
        try:
            committed = arguments.output.read_text(encoding="utf-8")
        except OSError as exc:
            fail(f"unable to read generated Java catalog {arguments.output}: {exc}")
        if committed != rendered:
            fail(f"generated Java catalog has drifted: {arguments.output}")
        print(f"OK {arguments.output}: generated Java catalog is current")
        return 0
    arguments.output.write_text(rendered, encoding="utf-8")
    print(f"WROTE {arguments.output}")
    return 0


def command_render(arguments: argparse.Namespace) -> int:
    policy, classified = validate_policy(
        load_policy(arguments.policy), inventory_documents(arguments)
    )
    rendered = render_report(policy, classified)
    if arguments.check:
        try:
            committed = arguments.output.read_text(encoding="utf-8")
        except OSError as exc:
            fail(f"unable to read generated report {arguments.output}: {exc}")
        if committed != rendered:
            fail(f"generated report has drifted: {arguments.output}")
        print(f"OK {arguments.output}: generated report is current")
        return 0
    arguments.output.write_text(rendered, encoding="utf-8")
    print(f"WROTE {arguments.output}")
    return 0


def add_inventory_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--inventory",
        type=Path,
        action="append",
        required=True,
        help="exact public API inventory; repeat for every supported version",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    bootstrap_parser = commands.add_parser("bootstrap")
    add_inventory_arguments(bootstrap_parser)
    bootstrap_parser.set_defaults(handler=command_bootstrap)

    validate_parser = commands.add_parser("validate")
    validate_parser.add_argument("--policy", type=Path, required=True)
    add_inventory_arguments(validate_parser)
    validate_parser.set_defaults(handler=command_validate)

    java_parser = commands.add_parser("render-java")
    java_parser.add_argument("--policy", type=Path, required=True)
    java_parser.add_argument("--output", type=Path, required=True)
    java_parser.add_argument("--check", action="store_true")
    add_inventory_arguments(java_parser)
    java_parser.set_defaults(handler=command_render_java)

    render_parser = commands.add_parser("render")
    render_parser.add_argument("--policy", type=Path, required=True)
    render_parser.add_argument("--output", type=Path, required=True)
    render_parser.add_argument("--check", action="store_true")
    add_inventory_arguments(render_parser)
    render_parser.set_defaults(handler=command_render)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    try:
        arguments = build_parser().parse_args(argv)
        return arguments.handler(arguments)
    except InventoryError as exc:
        print(f"Cubism Core member policy error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
