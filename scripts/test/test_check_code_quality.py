#!/usr/bin/env python3
"""Self-test for check_code_quality.py: every rule must fail closed on a seeded violation."""
from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

CHECKER = Path(__file__).resolve().parent / "check_code_quality.py"
DIGEST = "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"

DOCUMENTED_TYPE = """package dev.turboism.sample;

/** Documented sample. */
public final class Sample {

    /** Documented method. */
    public int value() {
        return 1;
    }

    @Override
    public String toString() {
        return "sample";
    }
}
"""

UNDOCUMENTED_TYPE = """package dev.turboism.sample;

public final class Undocumented {

    /** Documented method. */
    public int value() {
        return 1;
    }
}
"""

UNDOCUMENTED_METHOD = """package dev.turboism.sample;

/** Documented sample. */
public final class Partial {

    public int value() {
        return 1;
    }
}
"""


def run(root: Path, rules: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(CHECKER), str(root), "--rules", rules],
        capture_output=True,
        text=True,
    )


def write(root: Path, relative: str, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def case_clean_baseline(root: Path) -> None:
    write(root, "sdk/src/main/java/dev/turboism/sample/Sample.java", DOCUMENTED_TYPE)
    result = run(root, "javadoc,digests,naming,assets")
    assert result.returncode == 0, f"clean tree must pass, got:\n{result.stdout}"
    assert "@Override" not in result.stdout


def case_undocumented_type(root: Path) -> None:
    write(root, "sdk/src/main/java/dev/turboism/sample/Undocumented.java", UNDOCUMENTED_TYPE)
    result = run(root, "javadoc")
    assert result.returncode == 1, "undocumented public type must fail"
    assert "undocumented public type Undocumented" in result.stdout


def case_undocumented_method(root: Path) -> None:
    write(root, "sdk/src/main/java/dev/turboism/sample/Partial.java", UNDOCUMENTED_METHOD)
    result = run(root, "javadoc")
    assert result.returncode == 1, "undocumented public method must fail"
    assert "undocumented public method value" in result.stdout


def case_duplicated_digest(root: Path) -> None:
    write(
        root,
        "runtime/src/main/java/dev/turboism/sample/Copy.java",
        f'package dev.turboism.sample;\n\n/** Doc. */\npublic final class Copy {{\n'
        f'    static final String X = "{DIGEST}";\n}}\n',
    )
    result = run(root, "digests")
    assert result.returncode == 1, "restated reviewed digest must fail"
    assert "reviewed host digest restated" in result.stdout


def case_version_suffixed_type(root: Path) -> None:
    write(
        root,
        "runtime/src/main/java/dev/turboism/sample/ThingManifest52.java",
        "package dev.turboism.sample;\n\n/** Doc. */\npublic final class ThingManifest52 { }\n",
    )
    result = run(root, "naming")
    assert result.returncode == 1, "version-suffixed type name must fail"
    assert "encodes a Cubism version" in result.stdout


def case_retired_asset_token(root: Path) -> None:
    write(root, "cubism-ref/mapping-packs/draft/cubism-5.3.02-m14-thing.json", "{}\n")
    result = run(root, "assets")
    assert result.returncode == 1, "retired governance token must fail"
    assert "retired governance token" in result.stdout


def case_unknown_rule(root: Path) -> None:
    result = run(root, "nonsense")
    assert result.returncode == 2, "unknown rule must fail closed"


def run_ratchet(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(CHECKER), str(root), "--ratchet"],
        capture_output=True,
        text=True,
    )


def case_ratchet_blocks_new_undocumented_api(root: Path) -> None:
    """A tree with more findings than the recorded maximum must fail."""
    for index in range(2000):
        write(
            root,
            f"sdk/src/main/java/dev/turboism/sample/Gap{index}.java",
            f"package dev.turboism.sample;\n\npublic final class Gap{index} {{ }}\n",
        )
    result = run_ratchet(root)
    assert result.returncode == 1, "exceeding the recorded backlog must fail"
    assert "new undocumented public API" in result.stdout


def case_ratchet_demands_lowering_when_backlog_shrinks(root: Path) -> None:
    """A fully documented tree is below the recorded maximum and must demand it be lowered."""
    result = run_ratchet(root)
    assert result.returncode == 1, "a shrunken backlog must demand the maximum be lowered"
    assert "lower" in result.stdout.lower()


def case_ratchet_still_enforces_other_rules(root: Path) -> None:
    """Ratchet mode relaxes javadoc only; the other rules stay absolute."""
    write(
        root,
        "runtime/src/main/java/dev/turboism/sample/Copy.java",
        f'package dev.turboism.sample;\n\n/** Doc. */\npublic final class Copy {{\n'
        f'    static final String X = "{DIGEST}";\n}}\n',
    )
    result = run_ratchet(root)
    assert result.returncode == 1, "a digest violation must fail even in ratchet mode"
    assert "reviewed host digest restated" in result.stdout


CASES = (
    case_clean_baseline,
    case_undocumented_type,
    case_undocumented_method,
    case_duplicated_digest,
    case_version_suffixed_type,
    case_retired_asset_token,
    case_unknown_rule,
    case_ratchet_blocks_new_undocumented_api,
    case_ratchet_demands_lowering_when_backlog_shrinks,
    case_ratchet_still_enforces_other_rules,
)


def main() -> int:
    for case in CASES:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            # Every case starts from a clean, passing tree.
            write(root, "sdk/src/main/java/dev/turboism/sample/Sample.java", DOCUMENTED_TYPE)
            case(root)
        print(f"ok {case.__name__}")
    print(f"\nPASS: {len(CASES)} code-quality checker selftests")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
