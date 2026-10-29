#!/usr/bin/env python3
"""Self-test for check_throwable_containment.py: table-driven matrix of compliant and violating shapes."""
from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

CHECKER = Path(__file__).resolve().parent / "check_throwable_containment.py"

# Template wrapping a catch snippet in a valid Java class
TEMPLATE = """package dev.turboism.sample;

public final class Sample {
    void run() {
        %s
    }
}
"""

# Test matrix: (name, relative_path, body, expected_violation_or_none)
CASES: list[tuple[str, str, str, str | None]] = [
    # Compliant baseline shapes
    (
        "guarded_helper",
        "runtime/src/main/java/dev/turboism/sample/Guarded.java",
        "try { work(); } catch (Throwable failure) { FatalErrors.rethrowIfFatal(failure); diagnose(failure); }",
        None,
    ),
    (
        "sdk_fatal_first_clause",
        "sdk/src/main/java/dev/turboism/sample/Clause.java",
        "try { work(); } catch (ThreadDeath | VirtualMachineError fatal) { throw fatal; } catch (Throwable failure) { diagnose(failure); }",
        None,
    ),
    (
        "exempt_marker_with_reason",
        "runtime/src/main/java/dev/turboism/sample/Exempt.java",
        "try { work(); } catch (Throwable failure) {\n// @containment-exempt: causal merge\nmerge(failure);\n}",
        None,
    ),
    (
        "cleanup_raw_throw_unconditional",
        "runtime/src/main/java/dev/turboism/sample/CleanupRawThrow.java",
        "try { work(); } catch (final RuntimeException | Error failure) { cleanup(); throw failure; }",
        None,
    ),
    (
        "error_guarded_with_helper",
        "runtime/src/main/java/dev/turboism/sample/ErrorGuarded.java",
        "try { work(); } catch (Error failure) { FatalErrors.rethrowIfFatal(failure); diagnose(failure); }",
        None,
    ),
    (
        "commented_non_standard_fatal_clause_ignored",
        "runtime/src/main/java/dev/turboism/sample/CommentedNonStandard.java",
        "// catch (ThreadDeath | VirtualMachineError fatal) { throw fatal; }\nwork();",
        None,
    ),
    (
        "runtime_exempt_fatal_clause_passes",
        "runtime/src/main/java/dev/turboism/sample/ExemptFatalClause.java",
        "try { work(); } catch (ThreadDeath | VirtualMachineError fatal) {\n// @containment-exempt: precisely rethrown\nthrow fatal;\n}",
        None,
    ),
    # Violating shapes: general uncontained catches
    (
        "violating_throwable",
        "runtime/src/main/java/dev/turboism/sample/Violating.java",
        "try { work(); } catch (Throwable failure) { diagnose(failure); }",
        "unguarded catch (Throwable failure)",
    ),
    (
        "violating_error",
        "runtime/src/main/java/dev/turboism/sample/ErrorViolating.java",
        "try { work(); } catch (Error failure) { diagnose(failure); }",
        "unguarded catch (Error failure)",
    ),
    (
        "violating_multicatch",
        "runtime/src/main/java/dev/turboism/sample/MultiCatchViolating.java",
        "try { work(); } catch (RuntimeException | Error failure) { diagnose(failure); }",
        "unguarded catch (RuntimeException | Error failure)",
    ),
    (
        "violating_final",
        "runtime/src/main/java/dev/turboism/sample/FinalViolating.java",
        "try { work(); } catch (final Throwable failure) { diagnose(failure); }",
        "unguarded catch (Throwable failure)",
    ),
    (
        "non_standard_clause_in_runtime",
        "runtime/src/main/java/dev/turboism/sample/NonStandardClause.java",
        "try { work(); } catch (ThreadDeath | VirtualMachineError fatal) { throw fatal; } catch (Throwable failure) { diagnose(failure); }",
        "runtime/bootstrap sources must use FatalErrors.rethrowIfFatal",
    ),
    (
        "lone_vme_in_sdk",
        "sdk/src/main/java/dev/turboism/sample/LoneVme.java",
        "try { work(); } catch (VirtualMachineError fatal) { throw fatal; } catch (Throwable failure) { diagnose(failure); }",
        "non-runtime sources must catch both ThreadDeath and VirtualMachineError",
    ),
    # Loophole 1: conditional rethrowIfFatal
    (
        "conditional_helper_violating",
        "runtime/src/main/java/dev/turboism/sample/CondHelper.java",
        "try { work(); } catch (Error failure) { if (enabled) { FatalErrors.rethrowIfFatal(failure); } diagnose(failure); }",
        "unguarded catch (Error failure)",
    ),
    # Loophole 2: lambda-deferred helper
    (
        "lambda_deferred_helper_violating",
        "runtime/src/main/java/dev/turboism/sample/LambdaHelper.java",
        "try { work(); } catch (Error failure) { Runnable r = () -> FatalErrors.rethrowIfFatal(failure); diagnose(failure); }",
        "unguarded catch (Error failure)",
    ),
    # Loophole 3: early return before throw
    (
        "early_return_before_throw_violating",
        "runtime/src/main/java/dev/turboism/sample/EarlyReturn.java",
        "try { work(); } catch (Error failure) { if (ignored) return; cleanup(); throw failure; }",
        "unguarded catch (Error failure)",
    ),
    # Loophole 4: string literal exemption marker
    (
        "string_literal_exempt_violating",
        "runtime/src/main/java/dev/turboism/sample/StringExempt.java",
        'try { work(); } catch (Error failure) { log("@containment-exempt: pretend reason"); }',
        "unguarded catch (Error failure)",
    ),
    # Loophole 5: unrelated preceding comment marker
    (
        "unrelated_preceding_comment_violating",
        "runtime/src/main/java/dev/turboism/sample/PrecedingExempt.java",
        "// @containment-exempt: unrelated comment\ntry { work(); } catch (Error failure) { diagnose(failure); }",
        "unguarded catch (Error failure)",
    ),
    # Loophole 6: non-runtime fatal-first clause that swallows fatal
    (
        "swallowing_fatal_first_violating",
        "sdk/src/main/java/dev/turboism/sample/SwallowFatal.java",
        "try { work(); } catch (ThreadDeath | VirtualMachineError fatal) { log(fatal); } catch (Throwable failure) { diagnose(failure); }",
        "unguarded catch (Throwable failure)",
    ),
    # Conditional fatal-first clause in non-runtime
    (
        "conditional_fatal_first_violating",
        "sdk/src/main/java/dev/turboism/sample/CondFatal.java",
        "try { work(); } catch (ThreadDeath | VirtualMachineError fatal) { if (enabled) throw fatal; } catch (Throwable failure) { log(failure); }",
        "unguarded catch (Throwable failure)",
    ),
    # Cast throw can turn fatal into ClassCastException
    (
        "cast_throw_violating",
        "runtime/src/main/java/dev/turboism/sample/CastThrow.java",
        "try { work(); } catch (Throwable failure) { throw (RuntimeException) failure; }",
        "unguarded catch (Throwable failure)",
    ),
    # Exemption marker inside nested lambda/block after executable code
    (
        "exempt_in_nested_lambda_violating",
        "runtime/src/main/java/dev/turboism/sample/LambdaExempt.java",
        "try { work(); } catch (Error failure) { Runnable r = () -> {\n// @containment-exempt: fake in lambda\n};\n }",
        "unguarded catch (Error failure)",
    ),
    # Wrong variable name passed to rethrowIfFatal
    (
        "wrong_variable_violating",
        "runtime/src/main/java/dev/turboism/sample/WrongVar.java",
        "try { work(); } catch (Throwable failure) { FatalErrors.rethrowIfFatal(other); diagnose(failure); }",
        "unguarded catch (Throwable failure)",
    ),
    # Next catch helper mis-passing
    (
        "next_catch_mispassing_violating",
        "runtime/src/main/java/dev/turboism/sample/NextCatch.java",
        "try { work(); } catch (Error failure) { diagnose(failure); } catch (Exception failure) { FatalErrors.rethrowIfFatal(failure); }",
        "unguarded catch (Error failure)",
    ),
    # Nested helper mis-passing
    (
        "nested_helper_mispassing_violating",
        "runtime/src/main/java/dev/turboism/sample/NestedHelper.java",
        "try { work(); } catch (Throwable failure) { try { cleanup(); } catch (Throwable inner) { FatalErrors.rethrowIfFatal(inner); } diagnose(failure); }",
        "unguarded catch (Throwable failure)",
    ),
    # Conditional throw violating
    (
        "conditional_throw_violating",
        "runtime/src/main/java/dev/turboism/sample/CondThrow.java",
        "try { work(); } catch (Throwable failure) { if (shouldRethrow()) { throw failure; } }",
        "unguarded catch (Throwable failure)",
    ),
    # Comment-disguised helper violating
    (
        "comment_helper_violating",
        "runtime/src/main/java/dev/turboism/sample/CommentHelper.java",
        "try { work(); } catch (Throwable failure) { // FatalErrors.rethrowIfFatal(failure);\ndiagnose(failure); }",
        "unguarded catch (Throwable failure)",
    ),
    # Text block disguised helper violating
    (
        "text_block_helper_violating",
        "runtime/src/main/java/dev/turboism/sample/TextBlockHelper.java",
        'try { work(); } catch (Error failure) { String s = """\nFatalErrors.rethrowIfFatal(failure);\n"""; diagnose(failure); }',
        "unguarded catch (Error failure)",
    ),
]


def run(root: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(CHECKER), str(root)],
        capture_output=True,
        text=True,
    )


def seed(root: Path, relative: str, body: str) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    content = TEMPLATE % body if not body.startswith("package") else body
    target.write_text(content, encoding="utf-8")


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        # repo_root() anchors on this marker; without it the checker would scan the real repo.
        (root / "gradle").mkdir(parents=True, exist_ok=True)
        (root / "gradle/common-java.gradle.kts").write_text("", encoding="utf-8")

        for name, rel_path, body, expected_violation in CASES:
            target = root / rel_path
            seed(root, rel_path, body)
            result = run(root)
            target.unlink()

            if expected_violation is None:
                if result.returncode != 0:
                    print(f"FAIL: case '{name}' must pass, but failed:\n{result.stdout}\n{result.stderr}")
                    return 1
            else:
                if result.returncode == 0:
                    print(f"FAIL: case '{name}' must fail closed, but passed")
                    return 1
                if expected_violation not in result.stdout:
                    print(
                        f"FAIL: case '{name}' violation message missing expected substring:\n"
                        f"Expected: {expected_violation}\nGot stdout:\n{result.stdout}"
                    )
                    return 1

        print(f"check_throwable_containment self-test passed ({len(CASES)} cases)")
        return 0


if __name__ == "__main__":
    sys.exit(main())
