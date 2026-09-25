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

# Interface members are public without the keyword: implicit, default and static
# methods are all public API, while a private helper is not. Only the class-level
# Javadoc exists, so implicit(), defaulted() and utility() must be reported.
INTERFACE_GAPS = """package dev.turboism.sample;

/** Documented contract. */
public interface Contract {

    void implicit();

    default int defaulted() {
        return 1;
    }

    static void utility() {
    }

    private void hidden() {
    }
}
"""

DOCUMENTED_INTERFACE = """package dev.turboism.sample;

/** Documented contract. */
public interface Contract {

    /** Documented method. */
    void implicit();

    /** Documented method. */
    default int defaulted() {
        return 1;
    }

    private void hidden() {
    }

    /** Documented nested type. */
    class Nested {
    }
}
"""

# An ordinary block comment is not a doc comment; neither the type nor the
# method may count it as Javadoc.
BLOCK_COMMENTS = """package dev.turboism.sample;

/* An ordinary block comment is not Javadoc. */
public final class Blocked {

    /* Also not Javadoc. */
    public int value() {
        return 1;
    }
}
"""

# Comment-shaped text inside a string literal is not documentation.
STRING_LITERAL = """package dev.turboism.sample;

/** Documented. */
public final class Stringy {

    static final String FAKE = "/** not documentation */";

    public int value() {
        return 1;
    }
}
"""

# Javadoc belongs to the declaration it precedes; first()'s comment does not
# cover second().
PRIOR_MEMBER_DOC = """package dev.turboism.sample;

/** Documented. */
public final class Prior {

    /** Only documents first(). */
    public int first() {
        return 1;
    }

    public int second() {
        return 2;
    }
}
"""

# A public nested type inside a public top-level type is public API: both the
# undocumented nested type and its undocumented public method must be reported.
NESTED_GAPS = """package dev.turboism.sample;

/** Documented outer. */
public final class Outer {

    public static final class Inner {

        public int value() {
            return 1;
        }
    }
}
"""

# Nested types inside an interface are implicitly public static.
INTERFACE_NESTED_GAP = """package dev.turboism.sample;

/** Documented contract. */
public interface Contract {

    class Nested {

        public int value() {
            return 1;
        }
    }
}
"""

# Package-private declarations are not public API; their members are not
# publicly reachable either, even when declared public.
PACKAGE_PRIVATE = """package dev.turboism.sample;

final class Internal {

    public int value() {
        return 1;
    }

    public static class Nested {

        public int inner() {
            return 2;
        }
    }
}
"""

# The @Override exemption also applies to the java.lang.Override spelling.
QUALIFIED_OVERRIDE = """package dev.turboism.sample;

/** Documented. */
public final class Qualified {

    @java.lang.Override
    public String toString() {
        return "q";
    }
}
"""

# Generic signatures and annotations may span multiple lines; a real Javadoc
# comment still documents the declaration.
MULTILINE_DOCUMENTED = """package dev.turboism.sample;

import java.util.List;
import java.util.Map;

/** Documented. */
public final class Generics {

    /** Documented. */
    public <
            T extends Comparable<T>,
            R extends List<T>>
        Map<T, R> convert(
            T input,
            R other) {
        return null;
    }

    /** Documented. */
    @SuppressWarnings(
        "unchecked")
    @Deprecated
    public int value() {
        return 0;
    }
}
"""

# An undocumented public method whose signature spans lines must still be found.
MULTILINE_UNDOCUMENTED = """package dev.turboism.sample;

/** Documented. */
public final class Generics {

    public <
            T extends Comparable<T>>
        T pick(
            T input) {
        return input;
    }
}
"""

# Annotation-type elements are implicitly public interface methods.
ANNOTATION_ELEMENT_GAP = """package dev.turboism.sample;

/** Documented annotation. */
public @interface Marker {

    String value();
}
"""

# Enum, record and sealed declarations follow the same rule: the type and its
# public methods need Javadoc; fields and constructors are not new targets.
JAVA17_SHAPES = """package dev.turboism.sample;

/** Documented. */
public enum Level {
    /** Documented. */
    HIGH,
    /** Documented. */
    LOW;

    /** Documented. */
    public int rank() {
        return 0;
    }
}
"""

RECORD_GAP = """package dev.turboism.sample;

/** Documented. */
public record Point(int x, int y) {

    public int sum() {
        return x + y;
    }
}
"""

SEALED_TREE = """package dev.turboism.sample;

/** Documented. */
public sealed interface Shape permits Circle {

    /** Documented. */
    void draw();
}

final class Circle implements Shape {

    @Override
    public void draw() {
    }
}
"""

BROKEN_SOURCE = """package dev.turboism.sample;

public final class Broken {
    public int value( {
}
"""


def run(
    root: Path,
    rules: str,
    extra: list[str] | None = None,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(CHECKER), str(root), "--rules", rules] + (extra or []),
        capture_output=True,
        text=True,
        env=env,
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


def case_interface_implicit_default_and_static_methods(root: Path) -> None:
    """Interface methods are public without the keyword; a private helper is not."""
    write(root, "sdk/src/main/java/dev/turboism/sample/Contract.java", INTERFACE_GAPS)
    result = run(root, "javadoc")
    assert result.returncode == 1, "undocumented interface methods must fail"
    assert result.stdout.count("undocumented public method") == 3
    assert "undocumented public method implicit: sdk/src/main/java/dev/turboism/sample/Contract.java:6" in result.stdout
    assert "undocumented public method defaulted: sdk/src/main/java/dev/turboism/sample/Contract.java:8" in result.stdout
    assert "undocumented public method utility: sdk/src/main/java/dev/turboism/sample/Contract.java:12" in result.stdout
    assert "hidden" not in result.stdout


def case_documented_interface(root: Path) -> None:
    """Documented implicit/default members pass; private interface helpers need no doc."""
    write(root, "sdk/src/main/java/dev/turboism/sample/Contract.java", DOCUMENTED_INTERFACE)
    result = run(root, "javadoc")
    assert result.returncode == 0, f"documented interface must pass, got:\n{result.stdout}"


def case_block_comment_is_not_javadoc(root: Path) -> None:
    """Ordinary /* ... */ comments must not satisfy the Javadoc requirement."""
    write(root, "sdk/src/main/java/dev/turboism/sample/Blocked.java", BLOCK_COMMENTS)
    result = run(root, "javadoc")
    assert result.returncode == 1, "block comments must not count as Javadoc"
    assert "undocumented public type Blocked: sdk/src/main/java/dev/turboism/sample/Blocked.java:4" in result.stdout
    assert "undocumented public method value: sdk/src/main/java/dev/turboism/sample/Blocked.java:7" in result.stdout


def case_string_literal_is_not_documentation(root: Path) -> None:
    write(root, "sdk/src/main/java/dev/turboism/sample/Stringy.java", STRING_LITERAL)
    result = run(root, "javadoc")
    assert result.returncode == 1, "comment text inside a string must not count as Javadoc"
    assert "undocumented public method value" in result.stdout


def case_javadoc_is_not_shared_across_declarations(root: Path) -> None:
    write(root, "sdk/src/main/java/dev/turboism/sample/Prior.java", PRIOR_MEMBER_DOC)
    result = run(root, "javadoc")
    assert result.returncode == 1, "a prior member's Javadoc must not cover the next declaration"
    assert "undocumented public method second" in result.stdout
    assert "undocumented public method first" not in result.stdout


def case_nested_public_type(root: Path) -> None:
    write(root, "sdk/src/main/java/dev/turboism/sample/Outer.java", NESTED_GAPS)
    result = run(root, "javadoc")
    assert result.returncode == 1, "undocumented public nested type must fail"
    assert "undocumented public type Inner: sdk/src/main/java/dev/turboism/sample/Outer.java:6" in result.stdout
    assert "undocumented public method value: sdk/src/main/java/dev/turboism/sample/Outer.java:8" in result.stdout


def case_interface_implicit_nested_type(root: Path) -> None:
    write(root, "sdk/src/main/java/dev/turboism/sample/Contract.java", INTERFACE_NESTED_GAP)
    result = run(root, "javadoc")
    assert result.returncode == 1, "implicitly public nested type in an interface must fail"
    assert "undocumented public type Nested: sdk/src/main/java/dev/turboism/sample/Contract.java:6" in result.stdout


def case_package_private_members_are_not_public_api(root: Path) -> None:
    """Members of non-public types are not publicly reachable and need no Javadoc."""
    write(root, "sdk/src/main/java/dev/turboism/sample/Internal.java", PACKAGE_PRIVATE)
    result = run(root, "javadoc")
    assert result.returncode == 0, f"non-public declarations must not be required, got:\n{result.stdout}"


def case_qualified_override_is_exempt(root: Path) -> None:
    write(root, "sdk/src/main/java/dev/turboism/sample/Qualified.java", QUALIFIED_OVERRIDE)
    result = run(root, "javadoc")
    assert result.returncode == 0, f"java.lang.Override must keep the exemption, got:\n{result.stdout}"


def case_multiline_signature_documented(root: Path) -> None:
    write(root, "sdk/src/main/java/dev/turboism/sample/Generics.java", MULTILINE_DOCUMENTED)
    result = run(root, "javadoc")
    assert result.returncode == 0, f"multi-line declarations with Javadoc must pass, got:\n{result.stdout}"


def case_multiline_signature_undocumented(root: Path) -> None:
    write(root, "sdk/src/main/java/dev/turboism/sample/Generics.java", MULTILINE_UNDOCUMENTED)
    result = run(root, "javadoc")
    assert result.returncode == 1, "a multi-line undocumented signature must fail"
    assert "undocumented public method pick: sdk/src/main/java/dev/turboism/sample/Generics.java:6" in result.stdout


def case_annotation_type_element(root: Path) -> None:
    write(root, "sdk/src/main/java/dev/turboism/sample/Marker.java", ANNOTATION_ELEMENT_GAP)
    result = run(root, "javadoc")
    assert result.returncode == 1, "undocumented annotation element must fail"
    assert "undocumented public method value: sdk/src/main/java/dev/turboism/sample/Marker.java:6" in result.stdout


def case_java17_declarations(root: Path) -> None:
    """Documented enum/record/sealed shapes pass; an undocumented record method fails."""
    write(root, "sdk/src/main/java/dev/turboism/sample/Level.java", JAVA17_SHAPES)
    write(root, "sdk/src/main/java/dev/turboism/sample/Shape.java", SEALED_TREE)
    result = run(root, "javadoc")
    assert result.returncode == 0, f"documented Java 17 shapes must pass, got:\n{result.stdout}"
    write(root, "sdk/src/main/java/dev/turboism/sample/Point.java", RECORD_GAP)
    result = run(root, "javadoc")
    assert result.returncode == 1, "undocumented record method must fail"
    assert "undocumented public method sum" in result.stdout


def case_parse_error_fails_closed(root: Path) -> None:
    """Unparseable source means the scan cannot be trusted: fail, never zero-gap."""
    write(root, "sdk/src/main/java/dev/turboism/sample/Broken.java", BROKEN_SOURCE)
    result = run(root, "javadoc")
    assert result.returncode != 0, "a parse error must not produce a passing result"
    assert result.returncode != 1 or "Broken.java" not in result.stdout
    assert "Broken.java" in result.stderr


def case_parse_error_fails_closed_in_report(root: Path) -> None:
    write(root, "sdk/src/main/java/dev/turboism/sample/Broken.java", BROKEN_SOURCE)
    result = run(root, "javadoc", extra=["--report"])
    assert result.returncode != 0, "--report must not mask an unparseable tree as a clean scan"
    assert "Broken.java" in result.stderr


def case_missing_jdk_fails_closed(root: Path) -> None:
    """Without a usable JDK the javadoc rule cannot run and must fail closed."""
    env = {"PATH": "/nonexistent-jdk", "TMPDIR": tempfile.gettempdir()}
    result = run(root, "javadoc", env=env)
    assert result.returncode != 0, "a missing JDK must fail closed"
    assert "javac" in result.stderr or "JDK" in result.stderr


def case_missing_jdk_fails_closed_in_report(root: Path) -> None:
    env = {"PATH": "/nonexistent-jdk", "TMPDIR": tempfile.gettempdir()}
    result = run(root, "javadoc", extra=["--report"], env=env)
    assert result.returncode != 0, "--report must not mask a missing JDK as a clean scan"


def case_report_lists_real_findings(root: Path) -> None:
    write(root, "sdk/src/main/java/dev/turboism/sample/Contract.java", INTERFACE_GAPS)
    result = run(root, "javadoc", extra=["--report"])
    assert result.returncode == 0, f"report mode with findings must exit 0, got:\n{result.stderr}"
    assert "javadoc: 3 finding(s)" in result.stdout


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


def case_version_token_inside_type(root: Path) -> None:
    """A version token embedded anywhere in the name must fail, not only at the suffix."""
    stems = (
        "Cubism52WorkspaceHostProvider",
        "VerifiedCubism5303TextureAtlasLayoutProvider",
        "EditorPartInspector52SelectorContract",
        "ThingManifest520",
        "ThingManifest5203",
        "ThingManifest5302",
        "Manifest5303Thing",
    )
    for stem in stems:
        write(
            root,
            f"runtime/src/main/java/dev/turboism/sample/{stem}.java",
            f"package dev.turboism.sample;\n\n/** Doc. */\npublic final class {stem} {{ }}\n",
        )
    result = run(root, "naming")
    assert result.returncode == 1, "embedded version tokens must fail"
    assert result.stdout.count("encodes a Cubism version") == len(stems)


def case_non_version_digits_pass(root: Path) -> None:
    """Digit runs that are not Cubism-version-shaped must not be flagged."""
    for stem in (
        "Point2",
        "M12ReadSnapshotSource",
        "Utf8PluginCatalog",
        "Sha256Thing",
        "Manifest52030Overflow",
    ):
        write(
            root,
            f"runtime/src/main/java/dev/turboism/sample/{stem}.java",
            f"package dev.turboism.sample;\n\n/** Doc. */\npublic final class {stem} {{ }}\n",
        )
    result = run(root, "naming")
    assert result.returncode == 0, f"non-version digit names must pass, got:\n{result.stdout}"


def case_retired_asset_token(root: Path) -> None:
    write(root, "compatibility/cubism/mapping-packs/draft/cubism-5.3.02-m14-thing.json", "{}\n")
    result = run(root, "assets")
    assert result.returncode == 1, "retired governance token must fail"
    assert "retired governance token" in result.stdout


def case_unknown_rule(root: Path) -> None:
    result = run(root, "nonsense")
    assert result.returncode == 2, "unknown rule must fail closed"


def run_ratchet(root: Path, maximum: int | None = None) -> subprocess.CompletedProcess[str]:
    command = [sys.executable, str(CHECKER), str(root), "--ratchet"]
    if maximum is not None:
        command += ["--backlog-maximum", str(maximum)]
    return subprocess.run(command, capture_output=True, text=True)


def case_ratchet_blocks_new_undocumented_api(root: Path) -> None:
    """A tree with more findings than the maximum must fail."""
    for index in range(3):
        write(
            root,
            f"sdk/src/main/java/dev/turboism/sample/Gap{index}.java",
            f"package dev.turboism.sample;\n\npublic final class Gap{index} {{ }}\n",
        )
    result = run_ratchet(root, maximum=2)
    assert result.returncode == 1, "exceeding the maximum must fail"
    assert "new undocumented public API" in result.stdout


def case_ratchet_holds_when_backlog_matches(root: Path) -> None:
    """Sitting exactly at the maximum passes."""
    write(
        root,
        "sdk/src/main/java/dev/turboism/sample/Gap.java",
        "package dev.turboism.sample;\n\npublic final class Gap { }\n",
    )
    result = run_ratchet(root, maximum=1)
    assert result.returncode == 0, f"holding at the maximum must pass, got:\n{result.stdout}"


def case_ratchet_demands_lowering_when_backlog_shrinks(root: Path) -> None:
    """Dropping below the maximum must demand it be lowered, so the ratchet keeps holding."""
    result = run_ratchet(root, maximum=5)
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
    case_interface_implicit_default_and_static_methods,
    case_documented_interface,
    case_block_comment_is_not_javadoc,
    case_string_literal_is_not_documentation,
    case_javadoc_is_not_shared_across_declarations,
    case_nested_public_type,
    case_interface_implicit_nested_type,
    case_package_private_members_are_not_public_api,
    case_qualified_override_is_exempt,
    case_multiline_signature_documented,
    case_multiline_signature_undocumented,
    case_annotation_type_element,
    case_java17_declarations,
    case_parse_error_fails_closed,
    case_parse_error_fails_closed_in_report,
    case_missing_jdk_fails_closed,
    case_missing_jdk_fails_closed_in_report,
    case_report_lists_real_findings,
    case_duplicated_digest,
    case_version_suffixed_type,
    case_version_token_inside_type,
    case_non_version_digits_pass,
    case_retired_asset_token,
    case_unknown_rule,
    case_ratchet_blocks_new_undocumented_api,
    case_ratchet_holds_when_backlog_matches,
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
