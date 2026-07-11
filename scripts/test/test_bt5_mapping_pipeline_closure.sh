#!/usr/bin/env bash
set -euo pipefail

ROOT="${TURBOISM_BT5_ROOT:-$(git rev-parse --show-toplevel)}"
fail() { printf 'BT5 mapping pipeline closure: %s\n' "$*" >&2; exit 1; }
require_file() { [[ -f "$ROOT/$1" ]] || fail "missing required file: $1"; }
require_text() { grep -F -- "$2" "$ROOT/$1" >/dev/null || fail "missing required contract text in $1: $2"; }

required=(
  docs/adr/0020-draft-mapping-update-pipeline.md
  docs/schema/mapping-update-candidate-v1.md
  docs/schema/mapping-update-review-v1.md
  docs/schema/mapping-update-diff-v1.md
  docs/migration/bytecode-tooling-bt0-baseline-report.md
  docs/migration/bytecode-tooling-bt1-scope-manifest.md
  docs/migration/bytecode-tooling-bt2-scanner-report.md
  docs/migration/bytecode-tooling-bt3-review-apply-report.md
  docs/migration/bytecode-tooling-bt4-asm-admission-record.md
  docs/migration/bytecode-tooling-bt5-closure-report.md
  docs/migration/plans/bytecode-tooling-and-hook-readiness-plan.md
  gradle/verification-metadata.xml
  scripts/test/test_bt0_bytecode_hook_boundary.sh
  scripts/test/test_asm_supply_chain_admission.sh
  scripts/test/test_mapping_review_wrapper_args.sh
)
for file in "${required[@]}"; do require_file "$file"; done

for format in candidate review diff; do
  base="testframework/src/main/resources/fixtures/schema/mapping-update-${format}-v1"
  [[ -d "$ROOT/$base/valid" && -d "$ROOT/$base/invalid" ]] || fail "missing fixture directories: $base"
  [[ $(find "$ROOT/$base/valid" -type f | wc -l) -ge 1 ]] || fail "missing valid fixture: $base"
  [[ $(find "$ROOT/$base/invalid" -type f | wc -l) -ge 3 ]] || fail "need at least three invalid fixtures: $base"
done

require_text docs/adr/0020-draft-mapping-update-pipeline.md 'Status: Proposed'
require_text docs/adr/0020-draft-mapping-update-pipeline.md 'APPROVED'
require_text docs/migration/bytecode-tooling-bt5-closure-report.md 'ORACLE-APPROVED UNCOMMITTED FINAL DIFF'
require_text docs/migration/bytecode-tooling-bt5-closure-report.md '`mapping-pipeline-closed`'
require_text docs/schema/mapping-update-candidate-v1.md 'UPDATE_CLASS_RUNTIME'
require_text docs/schema/mapping-update-candidate-v1.md 'selectedTarget.owner'
require_text docs/schema/mapping-update-review-v1.md 'exact bytes'
require_text docs/schema/mapping-update-diff-v1.md 'does not participate in authorization'
require_text docs/migration/bytecode-tooling-bt4-asm-admission-record.md 'Oracle-approved as `bytecode-dependency-admitted`'
require_text docs/migration/bytecode-tooling-bt4-asm-admission-record.md 'SHA-256 checksums only'

if grep -Eq '<trusted-(artifacts|keys)>|<trusting group=|<ignored-keys>|<pgp>' "$ROOT/gradle/verification-metadata.xml"; then
  fail 'dependency verification metadata contains forbidden trust/signature policy'
fi
if grep -RIE '/workspace/|/root/|/home/' \
    "$ROOT/docs/adr/0020-draft-mapping-update-pipeline.md" \
    "$ROOT/docs/mapping" \
    "$ROOT/docs/schema/mapping-update-"*.md \
    "$ROOT/docs/migration/bytecode-tooling-"*.md \
    "$ROOT/docs/migration/plans/bytecode-tooling-and-hook-readiness-plan.md" >/dev/null; then
  fail 'new formal BT/mapping documentation contains a machine-local absolute path'
fi
if grep -RIE 'dependencyLocking|lockAllConfigurations|activateDependencyLocking' \
    "$ROOT/build.gradle.kts" "$ROOT/settings.gradle.kts" "$ROOT/runtime/build.gradle.kts" "$ROOT/gradle" >/dev/null; then
  fail 'dependency locking is forbidden for this change'
fi
if grep -E 'turboismMappingReviewArgsTestHookDir|testHook' "$ROOT/build.gradle.kts" >/dev/null; then
  fail 'formal mappingReview build task exposes a test-only race control surface'
fi

main_sources="$ROOT/runtime/src/main"
if grep -RIE 'net\.bytebuddy|ClassFileTransformer|java\.lang\.instrument|org\.objectweb\.asm\.ClassWriter' "$main_sources" >/dev/null; then
  fail 'production source contains a forbidden transformer/backend API'
fi
if grep -RIE 'ArtifactSnapshotHook|ApplySafetyHook|PublicationSafetyHook' "$main_sources" >/dev/null; then
  fail 'production source contains a test-only hook seam'
fi

# With no production overload, a plain-text fact gate is sufficient and Kotlin parsing is unnecessary.
check_args_entrypoint_facts() {
  local candidate_root="$1" build_file helper_file expected_call
  build_file="$candidate_root/build.gradle.kts"
  helper_file="$candidate_root/buildSrc/src/main/java/dev/turboism/gradle/internal/MappingReviewArgsFile.java"
  expected_call='setArgs(MappingReviewArgsFile.readAndDelete(file(cliArgsFile.get()).toPath()))'
  [[ -f "$build_file" && -f "$helper_file" ]] || return 1
  [[ $(grep -Fxc -- "        $expected_call" "$build_file") -eq 1 ]] || return 1
  [[ $(grep -Fo -- 'readAndDelete' "$build_file" | wc -l) -eq 1 ]] || return 1
  [[ $(grep -Fxc -- '    public static List<String> readAndDelete(Path path) {' "$helper_file") -eq 1 ]] || return 1
  [[ $(grep -Fo -- 'readAndDelete(' "$helper_file" | wc -l) -eq 1 ]] || return 1
  ! grep -RIE 'java\.util\.function\.Consumer|\bConsumer\b|phaseObserver|observer overload|test[ -]?(hook|observer)|RaceBridge' \
    "$candidate_root/buildSrc/src/main" >/dev/null
}
check_args_entrypoint_facts "$ROOT" \
  || fail 'production mappingReview must have exactly one canonical readAndDelete(Path) call and no observer/test seam'

# Self-mutation proofs cover the facts that previously motivated the brittle Kotlin lexer.
mutation_root="$(mktemp -d "${TMPDIR:-/tmp}/turboism-bt5-entrypoint-mutation.XXXXXX")"
trap 'rm -rf -- "$mutation_root"' EXIT
python3 - "$ROOT" "$mutation_root" <<'PY'
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1])
out = Path(sys.argv[2])
call = "setArgs(MappingReviewArgsFile.readAndDelete(file(cliArgsFile.get()).toPath()))"
helper_observer = "    public static List<String> readAndDelete(Path path, Consumer<String> phaseObserver) { return List.of(); }\n"
for name in ("wrong-receiver", "extra-call", "helper-observer", "helper-consumer"):
    target = out / name
    (target / "buildSrc/src/main/java/dev/turboism/gradle/internal").mkdir(parents=True)
    shutil.copy2(root / "build.gradle.kts", target / "build.gradle.kts")
    shutil.copy2(
        root / "buildSrc/src/main/java/dev/turboism/gradle/internal/MappingReviewArgsFile.java",
        target / "buildSrc/src/main/java/dev/turboism/gradle/internal/MappingReviewArgsFile.java",
    )

wrong = out / "wrong-receiver/build.gradle.kts"
wrong.write_text(wrong.read_text(encoding="utf-8").replace(call, call.replace("MappingReviewArgsFile", "OtherMappingReviewArgsFile"), 1), encoding="utf-8")
extra = out / "extra-call/build.gradle.kts"
extra.write_text(extra.read_text(encoding="utf-8") + "\n// additional production reference\nMappingReviewArgsFile.readAndDelete(file(\"extra\").toPath())\n", encoding="utf-8")
observer = out / "helper-observer/buildSrc/src/main/java/dev/turboism/gradle/internal/MappingReviewArgsFile.java"
observer.write_text(observer.read_text(encoding="utf-8").replace("    private static List<String> decode", helper_observer + "\n    private static List<String> decode", 1), encoding="utf-8")
consumer = out / "helper-consumer/buildSrc/src/main/java/dev/turboism/gradle/internal/MappingReviewArgsFile.java"
consumer.write_text(consumer.read_text(encoding="utf-8").replace("import java.util.List;", "import java.util.List;\nimport java.util.function.Consumer;", 1), encoding="utf-8")
PY
for mutation in wrong-receiver extra-call helper-observer helper-consumer; do
  if check_args_entrypoint_facts "$mutation_root/$mutation"; then
    fail "self-mutation unexpectedly passed: $mutation"
  fi
done

# The wrapper must report a stable relative output key, never WT_ROOT.
require_text scripts/dev/mapping-review.sh "printf 'output=%s\\n' \"\$OUTPUT_DIR\""
if grep -F 'output=%s\n' "$ROOT/scripts/dev/mapping-review.sh" | grep -F 'WT_ROOT' >/dev/null; then
  fail 'wrapper output logging includes WT_ROOT'
fi

# The protected automated tranche plan is owned by its own branch/plan and must be clean.
git -C "$ROOT" diff --quiet HEAD -- docs/migration/plans/automated-tranche-completion-plan.md \
  || fail 'protected automated tranche plan is modified'

printf '%s\n' 'PASS: BT5 mapping pipeline closure gate'
