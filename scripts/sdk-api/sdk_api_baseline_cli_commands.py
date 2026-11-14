"""SDK API baseline CLI command implementations."""
from __future__ import annotations

import argparse
import difflib
import itertools
import json
import re
from pathlib import Path

from sdk_api_baseline import BaselineError, GENERATOR_VERSION, SCHEMA_VERSION, canonical_dump, sha256_bytes
from sdk_api_baseline_cli_io import COMMIT_RE, FORMAT, load_baseline, write_output
from turboism_dev.java_source import strip_java_noise

CLASS_RECORD = re.compile(r"^class\tname=(?P<name>[^\t]+)")
SOURCE_IDENTIFIER = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
PLUGIN_SOURCE_SUFFIX = "src/main/java"


def verify_current(args: argparse.Namespace) -> None:
    expected, actual = args.current.read_bytes(), args.input.read_bytes()
    if expected != actual:
        raise BaselineError(
            "public SDK surface differs from sdk/api-contracts/current.txt\n" + _surface_diff(expected, actual)
            + "\nRefresh with ./gradlew updateSdkApiCurrent and review the diff."
        )
    print("SDK API current surface matches sdk/api-contracts/current.txt")
    if args.plugins is not None or args.no_consumer_list is not None:
        if args.plugins is None or args.no_consumer_list is None:
            raise BaselineError("--plugins and --no-consumer-list must be supplied together")
        _verify_plugin_consumers(actual, args.plugins, args.no_consumer_list)


def _surface_diff(expected: bytes, actual: bytes) -> str:
    diff = difflib.diff_bytes(
        difflib.unified_diff, expected.splitlines(keepends=True), actual.splitlines(keepends=True),
        fromfile=b"sdk/api-contracts/current.txt", tofile=b"build/reports/sdk-api/current.txt", n=2,
    )
    lines = list(itertools.islice(diff, 61))
    detail = b"".join(lines[:60]).decode("utf-8", errors="replace")
    if len(lines) > 60:
        detail += "\n(diff truncated; compare the two files for the full diff)\n"
    return detail


def _verify_plugin_consumers(surface: bytes, plugins: Path, allow_list: Path) -> None:
    """Keep the count of public SDK types without a first-party plugin consumer from growing."""
    public = _public_types(surface)
    referenced = _plugin_identifiers(plugins)
    unconsumed = {name for name in public if name.rsplit(".", 1)[-1] not in referenced}
    committed = _committed_allow_list(allow_list)
    grown, pruned = sorted(unconsumed - committed), sorted(committed - unconsumed)
    if grown or pruned:
        details = [f"plugin-consumer guard failed for {allow_list}"]
        details += [f"+ {name} has no first-party plugin consumer; add one or keep the type out of the SDK" for name in grown]
        details += [f"- {name} now has a first-party plugin consumer; delete its line" for name in pruned]
        raise BaselineError("\n".join(details))
    print(f"Plugin-consumer guard holds: {len(unconsumed)} public SDK types have no first-party plugin consumer")


def _public_types(surface: bytes) -> set[str]:
    names = set()
    for line in surface.decode("utf-8").splitlines():
        match = CLASS_RECORD.match(line)
        if match is None or "$" in match.group("name"):
            continue
        name = match.group("name").replace("/", ".")
        if not name.endswith("package-info"):
            names.add(name)
    return names


def _plugin_identifiers(plugins: Path) -> set[str]:
    """Collect identifiers a plugin source really references.

    Comments and string literals are blanked first: a type name that only appears in
    prose or a literal never links the type, so counting it would hide a public SDK type
    that no first-party plugin consumes.
    """
    identifiers: set[str] = set()
    for source in sorted(plugins.glob(f"*/{PLUGIN_SOURCE_SUFFIX}/**/*.java")):
        identifiers.update(SOURCE_IDENTIFIER.findall(strip_java_noise(source.read_text(encoding="utf-8"))))
    return identifiers


def _committed_allow_list(allow_list: Path) -> set[str]:
    names = set()
    for line in allow_list.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            names.add(stripped)
    return names


def capture(args: argparse.Namespace) -> None:
    if not COMMIT_RE.fullmatch(args.commit):
        raise BaselineError("commit must be exactly 40 lowercase hexadecimal characters")
    dump, artifact_sha, artifact_size = canonical_dump(args.input, args.package_prefix)
    write_output(args.output, canonical_json(_baseline_value(args, dump, artifact_sha, artifact_size)))


def canonical_json(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _baseline_value(args, dump, artifact_sha, artifact_size):
    return {
        "artifact": {"sha256": artifact_sha, "size": artifact_size},
        "canonicalDump": {"lineCount": len(dump.decode("utf-8").splitlines()), "sha256": sha256_bytes(dump)},
        "commit": args.commit,
        "format": FORMAT,
        "generatorVersion": GENERATOR_VERSION,
        "role": args.role,
        "schemaVersion": SCHEMA_VERSION,
    }


def verify(args: argparse.Namespace, exact: bool) -> None:
    baseline = load_baseline(args.baseline)
    _verify_expected_commit(args, baseline)
    reference_dump = _verify_reference_binding(args, baseline)
    _verify_records(args, baseline, reference_dump, exact)


def _verify_expected_commit(args, baseline):
    if args.expected_commit and baseline["commit"] != args.expected_commit:
        raise BaselineError(f"baseline is bound to {baseline['commit']}, expected {args.expected_commit}")


def _verify_reference_binding(args, baseline):
    dump, artifact_sha, artifact_size = canonical_dump(args.reference_input, args.package_prefix)
    # The historical reconstruction is not byte-reproducible across build
    # environments (JDK/Gradle zip metadata differ while the canonical API
    # content is identical — same size, same canonical dump). The reviewed
    # binding is therefore the canonical API surface, not raw archive bytes.
    canonical = baseline["canonicalDump"]
    if sha256_bytes(dump) != canonical["sha256"] or len(dump.decode("utf-8").splitlines()) != canonical["lineCount"]:
        raise BaselineError(_artifact_binding_mismatch(baseline["artifact"], artifact_sha, artifact_size))
    return dump


def _artifact_binding_mismatch(expected, actual_sha, actual_size):
    return f"reviewed reference artifact binding mismatch: expected {expected['sha256']}/{expected['size']}, found {actual_sha}/{actual_size}"


def _verify_records(args, baseline, reference_dump, exact):
    if args.input.resolve() == args.reference_input.resolve():
        current_dump = reference_dump
    else:
        current_dump, _artifact_sha, _artifact_size = canonical_dump(args.input, args.package_prefix)
    baseline_lines, current_lines = _record_lines(reference_dump, current_dump)
    removed, added = sorted(set(baseline_lines) - set(current_lines)), sorted(set(current_lines) - set(baseline_lines))
    if removed or (exact and added):
        raise BaselineError(_record_failure(removed, added, exact))
    # exact: the records comparison above already requires the live canonical
    # dump to equal the reviewed reference dump byte-for-byte; raw archive
    # bytes are environment-dependent and are not part of the reviewed surface.
    _print_success(exact, baseline_lines, current_lines, added)


def _record_lines(reference_dump, current_dump):
    return reference_dump.decode("utf-8").splitlines()[2:], current_dump.decode("utf-8").splitlines()[2:]


def _record_failure(removed, added, exact):
    mode = "exact" if exact else "compatible"
    details = [f"{mode} API verification failed"]
    if removed:
        details += [f"removed or changed baseline records ({len(removed)}):", *["- " + item for item in removed[:20]]]
    if exact and added:
        details += [f"added records not present in exact baseline ({len(added)}):", *["+ " + item for item in added[:20]]]
    return "\n".join(details)


def _print_success(exact, baseline_lines, current_lines, added):
    mode = "exact" if exact else "compatible"
    print(f"SDK API baseline {mode} verification passed: baseline={len(baseline_lines)} current={len(current_lines)} additions={len(added)}")
