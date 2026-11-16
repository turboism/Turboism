#!/usr/bin/env bash
set -euo pipefail

# Mutation fixtures are independent builds, not the enclosing CI product candidate.
# Do not inherit its release identity into sandbox Gradle invocations.
unset TURBOISM_NIGHTLY_VERSION TURBOISM_BUILD_NUMBER TURBOISM_SOURCE_REVISION TURBOISM_BUILD_VERSION TURBOISM_BUILD_CHANNEL ORG_GRADLE_PROJECT_turboismRelease

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd -P)"
TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/turboism-mapping-review-wrapper.XXXXXX")"
CAPTURE_DIR="${TEMP_ROOT}/capture"
trap 'rm -rf -- "$TEMP_ROOT"' EXIT

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

mkdir -p "${TEMP_ROOT}/scripts/dev" "$CAPTURE_DIR"
cp "${REPO_ROOT}/scripts/dev/mapping-review.sh" "${TEMP_ROOT}/scripts/dev/mapping-review.sh"
cp "${REPO_ROOT}/scripts/dev/worktree-id.sh" "${TEMP_ROOT}/scripts/dev/worktree-id.sh"
cp "${REPO_ROOT}/scripts/dev/worktree-id-lib.sh" "${TEMP_ROOT}/scripts/dev/worktree-id-lib.sh"

cat > "${TEMP_ROOT}/gradlew" <<'GRADLEW'
#!/usr/bin/env bash
set -euo pipefail

args_file=""
for argument in "$@"; do
  case "$argument" in
    -PturboismMappingReviewArgsFile=*) args_file="${argument#*=}" ;;
    -PturboismMappingReviewArgs=*)
      echo "legacy flattened args property must not be used" >&2
      exit 1
      ;;
  esac
done
[ -n "$args_file" ] || { echo "missing args-file property" >&2; exit 1; }
[ -f "$args_file" ] || { echo "argument file disappeared before Gradle read it" >&2; exit 1; }
printf '%s\n' "$args_file" > "${CAPTURE_DIR}/args-file-path"
cp -- "$args_file" "${CAPTURE_DIR}/encoded-args"
printf '%s\n' "$@" > "${CAPTURE_DIR}/gradle-args"
rm -f -- "$args_file"
GRADLEW
chmod +x "${TEMP_ROOT}/gradlew"

artifact="${TEMP_ROOT}/artifact with spaces (candidate) #1.jar"
pack="packs/with spaces (draft) #1.json"
wrapper_stdout="${TEMP_ROOT}/wrapper.stdout"
TURBOISM_WORKTREE_ID="m15-wrapper-test" \
  CAPTURE_DIR="$CAPTURE_DIR" \
  "${TEMP_ROOT}/scripts/dev/mapping-review.sh" generate \
    --artifact "$artifact" \
    --pack "$pack" \
    --semantic-name "fixture target #1" >"$wrapper_stdout"

expected_output="build/worktree/m15-wrapper-test/mapping-review"
grep -Fx -- "worktreeId=m15-wrapper-test" "$wrapper_stdout" >/dev/null \
  || fail "wrapper stdout did not report the resolved worktree ID"
grep -Fx -- "output=$expected_output" "$wrapper_stdout" >/dev/null \
  || fail "wrapper stdout did not report the relative isolated output path"
if grep -F -- "$TEMP_ROOT" "$wrapper_stdout" >/dev/null || grep -F -- "$artifact" "$wrapper_stdout" >/dev/null; then
  fail "wrapper stdout leaked an absolute worktree or artifact path"
fi

mapfile -t encoded_args < "${CAPTURE_DIR}/encoded-args"
expected=(
  "generate"
  "--root"
  "$TEMP_ROOT"
  "--artifact"
  "$artifact"
  "--pack"
  "$pack"
  "--semantic-name"
  "fixture target #1"
)

[ "${#encoded_args[@]}" -eq "${#expected[@]}" ] || fail "expected ${#expected[@]} encoded arguments, found ${#encoded_args[@]}"
for index in "${!expected[@]}"; do
  decoded="$(printf '%s' "${encoded_args[$index]}" | base64 -d)"
  [ "$decoded" = "${expected[$index]}" ] || fail "decoded argument $index did not round-trip"
done

captured_args_file="$(< "${CAPTURE_DIR}/args-file-path")"
[ ! -e "$captured_args_file" ] || fail "wrapper did not remove temporary argument file"
grep -Fx -- "mappingReview" "${CAPTURE_DIR}/gradle-args" >/dev/null || fail "wrapper did not invoke mappingReview"
grep -Fx -- "-PturboismWorktreeId=m15-wrapper-test" "${CAPTURE_DIR}/gradle-args" >/dev/null || fail "wrapper did not pass resolved worktree ID"
grep -F -- '-PturboismMappingReviewArgsFile=' "${CAPTURE_DIR}/gradle-args" >/dev/null || fail "wrapper did not pass the args-file property"

rm -f -- "${CAPTURE_DIR}/args-file-path" "${CAPTURE_DIR}/encoded-args" "${CAPTURE_DIR}/gradle-args"
cat > "${TEMP_ROOT}/gradlew" <<'GRADLEW_FAIL'
#!/usr/bin/env bash
set -euo pipefail
args_file=""
for argument in "$@"; do
  case "$argument" in
    -PturboismMappingReviewArgsFile=*) args_file="${argument#*=}" ;;
  esac
done
[ -n "$args_file" ] || exit 9
printf '%s\n' "$args_file" > "${CAPTURE_DIR}/failed-args-file-path"
exit 17
GRADLEW_FAIL
chmod +x "${TEMP_ROOT}/gradlew"

set +e
TURBOISM_WORKTREE_ID="m15-wrapper-test" \
  CAPTURE_DIR="$CAPTURE_DIR" \
  "${TEMP_ROOT}/scripts/dev/mapping-review.sh" generate \
    --artifact "$artifact" \
    --pack "$pack" \
    --semantic-name "fixture target #1" >/dev/null 2>&1
failed_status=$?
set -e
[ "$failed_status" -eq 17 ] || fail "wrapper did not preserve Gradle failure status"
failed_args_file="$(< "${CAPTURE_DIR}/failed-args-file-path")"
for _ in $(seq 1 50); do
  [ ! -e "$failed_args_file" ] && break
  sleep 0.02
done
[ ! -e "$failed_args_file" ] || fail "wrapper did not remove argument file after Gradle failure"

help_marker="${TEMP_ROOT}/gradle-must-not-run"
cat > "${TEMP_ROOT}/gradlew" <<GRADLEW_HELP
#!/usr/bin/env bash
touch "$help_marker"
exit 99
GRADLEW_HELP
chmod +x "${TEMP_ROOT}/gradlew"
for help_args in "--help" "generate --help" "apply --help"; do
  # shellcheck disable=SC2086
  TURBOISM_WORKTREE_ID="m15-wrapper-test" "${TEMP_ROOT}/scripts/dev/mapping-review.sh" $help_args >/dev/null
  [ ! -e "$help_marker" ] || fail "wrapper help accessed Gradle for: $help_args"
done

set +e
TURBOISM_WORKTREE_ID="m15-wrapper-test" "${TEMP_ROOT}/scripts/dev/mapping-review.sh" >/dev/null 2>&1
no_args_status=$?
set -e
[ "$no_args_status" -eq 2 ] || fail "wrapper with no arguments must return usage status 2"
[ ! -e "$help_marker" ] || fail "wrapper with no arguments accessed Gradle"

rm -f -- "${TEMP_ROOT}/gradlew"
for help_args in "--help" "generate --help" "apply --help"; do
  # shellcheck disable=SC2086
  TURBOISM_WORKTREE_ID="m15-wrapper-test" "${TEMP_ROOT}/scripts/dev/mapping-review.sh" $help_args >/dev/null \
    || fail "wrapper help required a Gradle wrapper for: $help_args"
done
set +e
TURBOISM_WORKTREE_ID="m15-wrapper-test" "${TEMP_ROOT}/scripts/dev/mapping-review.sh" >/dev/null 2>&1
no_gradle_no_args_status=$?
set -e
[ "$no_gradle_no_args_status" -eq 2 ] || fail "wrapper no-args usage without Gradle must return status 2"

real_gradle_case() {
  local name="$1" expected="$2" kind="$3" args_path log status
  args_path="$(mktemp "${TMPDIR:-/tmp}/turboism-mapping-review-gradle.XXXXXX")"
  log="$(mktemp "${TMPDIR:-/tmp}/turboism-mapping-review-gradle-log.XXXXXX")"
  create_args_fixture "$kind" "$args_path"
  set +e
  "$REPO_ROOT/gradlew" -q mappingReview --offline --no-daemon -p "$REPO_ROOT" \
    "-Dorg.gradle.jvmargs=-Xmx1g" \
    "-PturboismMappingReviewArgsFile=$args_path" >"$log" 2>&1
  status=$?
  set -e
  [ "$status" -ne 0 ] || { cat "$log" >&2; fail "real Gradle args-file mutation unexpectedly passed: $name"; }
  grep -qiF -- "$expected" "$log" || { cat "$log" >&2; fail "wrong real Gradle args-file failure: $name"; }
  if grep -F -- "$args_path" "$log" >/dev/null; then
    cat "$log" >&2; fail "real Gradle args-file failure leaked its absolute path: $name"
  fi
  verify_args_fixture_cleanup "$name" "$kind" "$args_path"
  rm -f "$log"
}

create_args_fixture() {
  local kind="$1" args_path="$2" target
  case "$kind" in
    symlink)
      target="${args_path}.target"; printf 'LS1oZWxwCg==\n' > "$target"; rm -f "$args_path"; ln -s "$target" "$args_path" ;;
    fifo) rm -f "$args_path"; mkfifo "$args_path" ;;
    emptydir) rm -f "$args_path"; mkdir "$args_path" ;;
    nonemptydir) rm -f "$args_path"; mkdir "$args_path"; printf x > "$args_path/keep" ;;
    oversize) python3 - "$args_path" <<'PY'
from pathlib import Path
import sys
Path(sys.argv[1]).write_bytes(b'A' * (1024 * 1024 + 1))
PY
      ;;
    lines) python3 - "$args_path" <<'PY'
from pathlib import Path
import sys
Path(sys.argv[1]).write_text('YQ==\n' * 257, encoding='ascii')
PY
      ;;
    line) python3 - "$args_path" <<'PY'
from pathlib import Path
import sys
Path(sys.argv[1]).write_bytes(b'A' * (128 * 1024 + 1))
PY
      ;;
    decoded) python3 - "$args_path" <<'PY'
from pathlib import Path
import base64, sys
Path(sys.argv[1]).write_bytes(base64.b64encode(b'x' * (64 * 1024 + 1)) + b'\n')
PY
      ;;
    utf8) printf '//4=\n' > "$args_path" ;;
    canonical) printf 'YQ\n' > "$args_path" ;;
    crlf) printf 'YQ==\r\n' > "$args_path" ;;
    *) fail "unknown real Gradle args-file case: $kind" ;;
  esac
}

verify_args_fixture_cleanup() {
  local name="$1" kind="$2" args_path="$3" target="${args_path}.target"
  case "$kind" in
    symlink)
      [ -L "$args_path" ] || fail "unowned symlink pathname was deleted"
      [ -f "$target" ] || fail "symlink cleanup removed the target"
      rm -f "$args_path" "$target" ;;
    fifo) [ -p "$args_path" ] || fail "unowned FIFO pathname was deleted"; rm -f "$args_path" ;;
    emptydir) [ -d "$args_path" ] || fail "empty directory pathname was deleted"; rmdir "$args_path" ;;
    nonemptydir) [ -f "$args_path/keep" ] || fail "non-empty directory or content was deleted"; rm -rf "$args_path" ;;
    oversize) [ -f "$args_path" ] || fail "pre-open oversized file was deleted without confirmed ownership"; rm -f "$args_path" ;;
    *) [ ! -e "$args_path" ] && [ ! -L "$args_path" ] || fail "owned regular args-file was not safely cleaned: $name" ;;
  esac
}

# Exercise the real root build.gradle.kts mappingReview task wiring concurrently with the fixture run.
root_val_log="${TEMP_ROOT}/root-mapping-review.log"
real_gradle_case 'strict UTF-8' 'Invalid UTF-8' utf8 >"$root_val_log" 2>&1 &
root_pid=$!

RACE_FIXTURE="${TEMP_ROOT}/race-fixture"
mkdir -p "$RACE_FIXTURE/buildSrc/src/main/java/dev/turboism/gradle/internal"
production_helper="$REPO_ROOT/buildSrc/src/main/java/dev/turboism/gradle/internal/MappingReviewArgsFile.java"
fixture_helper="$RACE_FIXTURE/buildSrc/src/main/java/dev/turboism/gradle/internal/MappingReviewArgsFile.java"
cp "$production_helper" "$fixture_helper"
# Instrument only the temporary production-helper copy. Every anchor is exact and single-use;
# the script fails closed if the helper changes, then reverses the insertions to prove provenance.
python3 - "$production_helper" "$fixture_helper" <<'PY'
from pathlib import Path
import sys

production_path = Path(sys.argv[1])
fixture_path = Path(sys.argv[2])
production = production_path.read_text(encoding="utf-8")
instrumented = production
edits = [
    (
        "import java.util.List;\n",
        "import java.util.List;\nimport java.util.function.Consumer;\n",
    ),
    (
        "    public static List<String> readAndDelete(Path path) {\n"
        "        Object ownedKey = null;\n",
        "    public static List<String> readAndDelete(Path path) {\n"
        "        return readAndDelete(path, ignored -> { });\n"
        "    }\n\n"
        "    static List<String> readAndDelete(Path path, Consumer<String> phaseObserver) {\n"
        "        Object ownedKey = null;\n",
    ),
    (
        "\n            byte[] bytes;\n",
        "\n            phaseObserver.accept(\"checked-before-open\");\n"
        "            byte[] bytes;\n",
    ),
    (
        "                ownedKey = beforeKey;\n"
        "                ownedSize = openedSize;\n\n",
        "                ownedKey = beforeKey;\n"
        "                ownedSize = openedSize;\n"
        "                phaseObserver.accept(\"opened\");\n\n",
    ),
    (
        "        } finally {\n"
        "            try {\n"
        "                if (ownedKey != null && ownedSize != null) {\n",
        "        } finally {\n"
        "            try {\n"
        "                phaseObserver.accept(\"before-cleanup\");\n"
        "                if (ownedKey != null && ownedSize != null) {\n",
    ),
    (
        "                    if (sameOwnedFile(current, ownedKey, ownedSize)) {\n"
        "                        BasicFileAttributes confirmed = safeAttributes(path);\n",
        "                    if (sameOwnedFile(current, ownedKey, ownedSize)) {\n"
        "                        phaseObserver.accept(\"cleanup-identity-confirmed\");\n"
        "                        BasicFileAttributes confirmed = safeAttributes(path);\n",
    ),
]
for anchor, replacement in edits:
    count = instrumented.count(anchor)
    if count != 1:
        raise SystemExit(f"race fixture instrumentation anchor count must be 1, found {count}: {anchor!r}")
    instrumented = instrumented.replace(anchor, replacement, 1)

recovered = instrumented
for anchor, replacement in reversed(edits):
    count = recovered.count(replacement)
    if count != 1:
        raise SystemExit(f"race fixture inserted block count must be 1, found {count}: {replacement!r}")
    recovered = recovered.replace(replacement, anchor, 1)
if recovered != production:
    raise SystemExit("race fixture differs from the production helper outside controlled instrumentation points")
fixture_path.write_text(instrumented, encoding="utf-8")
PY
cat > "$RACE_FIXTURE/buildSrc/src/main/java/dev/turboism/gradle/internal/MappingReviewArgsRaceBridge.java" <<'JAVA'
package dev.turboism.gradle.internal;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public final class MappingReviewArgsRaceBridge {
    private MappingReviewArgsRaceBridge() {
    }

    public static List<String> readAndDelete(Path path) {
        return MappingReviewArgsFile.readAndDelete(path);
    }

    public static List<String> readAndDelete(Path path, Consumer<String> phaseObserver) {
        return MappingReviewArgsFile.readAndDelete(path, phaseObserver);
    }
}
JAVA
printf '%s\n' 'rootProject.name = "mapping-review-args-race-fixture"' > "$RACE_FIXTURE/settings.gradle"
cat > "$RACE_FIXTURE/buildSrc/build.gradle" <<'BUILDSRC_BUILD'
apply plugin: 'java-library'
dependencies { implementation gradleApi() }
BUILDSRC_BUILD
cat > "$RACE_FIXTURE/build.gradle" <<'RACE_BUILD'
import dev.turboism.gradle.internal.MappingReviewArgsRaceBridge
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

def validationManifest = providers.gradleProperty('validationManifest')
def raceManifest = providers.gradleProperty('raceManifest')
def resultDir = providers.gradleProperty('resultDir')

tasks.register('consumeArgsCases') {
  doLast {
    def outDir = file(resultDir.get()).toPath()
    Files.createDirectories(outDir)
    Files.readAllLines(file(validationManifest.get()).toPath()).findAll { !it.isBlank() }.each { line ->
      def parts = line.split('\t', 2)
      def id = parts[0]
      def argsFile = file(parts[1]).toPath()
      try {
        MappingReviewArgsRaceBridge.readAndDelete(argsFile)
        Files.writeString(outDir.resolve("val-${id}.status"), '0')
        Files.writeString(outDir.resolve("val-${id}.log"), '')
      } catch (Throwable failure) {
        Files.writeString(outDir.resolve("val-${id}.status"), '1')
        Files.writeString(outDir.resolve("val-${id}.log"), failure.message ?: failure.toString())
      }
    }
    Files.readAllLines(file(raceManifest.get()).toPath()).findAll { !it.isBlank() }.each { line ->
      def parts = line.split('\t', 3)
      def id = parts[0]
      def argsFile = file(parts[1]).toPath()
      def hookDir = file(parts[2]).toPath()
      try {
        MappingReviewArgsRaceBridge.readAndDelete(argsFile) { phase ->
          Files.createDirectories(hookDir)
          Files.writeString(hookDir.resolve(phase), 'ready', StandardOpenOption.CREATE_NEW)
          def release = hookDir.resolve("${phase}.continue")
          for (int i = 0; i < 3000; i++) {
            if (Files.exists(release, LinkOption.NOFOLLOW_LINKS)) return
            Thread.sleep(10)
          }
          throw new GradleException('Mapping review argument fixture hook timed out.')
        }
        Files.writeString(outDir.resolve("race-${id}.status"), '0')
        Files.writeString(outDir.resolve("race-${id}.log"), '')
      } catch (Throwable failure) {
        Files.writeString(outDir.resolve("race-${id}.status"), '1')
        Files.writeString(outDir.resolve("race-${id}.log"), failure.message ?: failure.toString())
      }
    }
  }
}
RACE_BUILD

FIXTURE_STATE="${TEMP_ROOT}/fixture-state"
mkdir -p "$FIXTURE_STATE/results"
validation_manifest="$FIXTURE_STATE/validation-manifest.tsv"
race_manifest="$FIXTURE_STATE/race-manifest.tsv"
: > "$validation_manifest"
: > "$race_manifest"

val_names=()
val_kinds=()
val_expecteds=()
val_paths=()

register_validation_case() {
  local name="$1" expected="$2" kind="$3" idx args_path
  idx="${#val_names[@]}"
  args_path="$(mktemp "${TMPDIR:-/tmp}/turboism-mapping-review-val.XXXXXX")"
  create_args_fixture "$kind" "$args_path"
  val_names+=("$name")
  val_kinds+=("$kind")
  val_expecteds+=("$expected")
  val_paths+=("$args_path")
  printf '%s\t%s\n' "$idx" "$args_path" >> "$validation_manifest"
}

register_validation_case 'symlink' 'NOFOLLOW regular file' symlink
register_validation_case 'FIFO' 'NOFOLLOW regular file' fifo
register_validation_case 'empty directory' 'NOFOLLOW regular file' emptydir
register_validation_case 'non-empty directory' 'NOFOLLOW regular file' nonemptydir
register_validation_case 'file byte bound' 'byte limit' oversize
register_validation_case 'line count bound' 'line limit' lines
register_validation_case 'encoded line bound' 'encoded line limit' line
register_validation_case 'decoded argument bound' 'decoded byte limit' decoded
register_validation_case 'strict UTF-8' 'Invalid UTF-8' utf8
register_validation_case 'canonical Base64' 'Non-canonical Base64' canonical
register_validation_case 'LF-only lines' 'LF line endings' crlf

race_names=()
race_phases=()
race_expecteds=()
race_mutations=()
race_expect_statuses=()
race_paths=()
race_hooks=()

register_race_case() {
  local name="$1" phase="$2" expected="$3" mutation="$4" expect_status="$5" idx args_path hook_dir
  idx="${#race_names[@]}"
  args_path="$(mktemp "${TMPDIR:-/tmp}/turboism-mapping-review-race.XXXXXX")"
  hook_dir="$(mktemp -d "${TMPDIR:-/tmp}/turboism-mapping-review-hooks.XXXXXX")"
  printf 'LS1oZWxw\n' > "$args_path"
  case "$phase" in
    checked-before-open) : ;;
    opened) : > "$hook_dir/checked-before-open.continue" ;;
    cleanup-identity-confirmed)
      : > "$hook_dir/checked-before-open.continue"
      : > "$hook_dir/opened.continue"
      : > "$hook_dir/before-cleanup.continue" ;;
  esac
  race_names+=("$name")
  race_phases+=("$phase")
  race_expecteds+=("$expected")
  race_mutations+=("$mutation")
  race_expect_statuses+=("$expect_status")
  race_paths+=("$args_path")
  race_hooks+=("$hook_dir")
  printf '%s\t%s\t%s\n' "$idx" "$args_path" "$hook_dir" >> "$race_manifest"
}

register_race_case 'check/open symlink replacement' checked-before-open 'identity changed while opening' symlink fail
register_race_case 'same-size pathname replacement' opened 'changed during bounded read' same-size fail
register_race_case 'read-time growth' opened 'exceeds the byte limit while reading' grow fail
register_race_case 'cleanup competitor replacement' cleanup-identity-confirmed '' same-size pass

fixture_gradle_log="$FIXTURE_STATE/gradle.log"
"$REPO_ROOT/gradlew" -q consumeArgsCases --offline --no-daemon -p "$RACE_FIXTURE" \
  "-Dorg.gradle.jvmargs=-Xmx1g" \
  "-PvalidationManifest=$validation_manifest" \
  "-PraceManifest=$race_manifest" \
  "-PresultDir=$FIXTURE_STATE/results" >"$fixture_gradle_log" 2>&1 &
pid=$!

race_replacements=()
for idx in "${!race_names[@]}"; do
  name="${race_names[$idx]}"
  phase="${race_phases[$idx]}"
  mutation="${race_mutations[$idx]}"
  args_path="${race_paths[$idx]}"
  hook_dir="${race_hooks[$idx]}"
  for _ in $(seq 1 3000); do
    [ -e "$hook_dir/$phase" ] && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.01
  done
  [ -e "$hook_dir/$phase" ] || { cat "$fixture_gradle_log" >&2; kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fail "race hook was not reached: $name"; }
  replacement=""
  case "$mutation" in
    symlink)
      replacement="${args_path}.replacement"; printf 'LS1oZWxw\n' > "$replacement"; rm -f "$args_path"; ln -s "$replacement" "$args_path" ;;
    same-size)
      replacement="${args_path}.replacement"; printf 'LS1oZWxw\n' > "$replacement"; mv -f "$replacement" "$args_path" ;;
    grow) python3 - "$args_path" <<'PY'
from pathlib import Path
import sys
with Path(sys.argv[1]).open('ab') as f:
    f.write(b'A' * (1024 * 1024 + 1))
PY
      ;;
  esac
  race_replacements+=("$replacement")
  : > "$hook_dir/$phase.continue"
  if [ "$phase" = opened ]; then : > "$hook_dir/before-cleanup.continue"; fi
done

wait "$pid" || { cat "$fixture_gradle_log" >&2; fail "combined fixture Gradle run failed unexpectedly"; }
wait "$root_pid" || { cat "$root_val_log" >&2; fail "root mappingReview check failed"; }

for idx in "${!val_names[@]}"; do
  name="${val_names[$idx]}"
  kind="${val_kinds[$idx]}"
  expected="${val_expecteds[$idx]}"
  args_path="${val_paths[$idx]}"
  status="$(< "$FIXTURE_STATE/results/val-$idx.status")"
  log="$FIXTURE_STATE/results/val-$idx.log"
  [ "$status" -ne 0 ] || { cat "$log" >&2; fail "args-file mutation unexpectedly passed: $name"; }
  grep -qiF -- "$expected" "$log" || { cat "$log" >&2; fail "wrong args-file failure: $name"; }
  if grep -F -- "$args_path" "$log" >/dev/null; then
    cat "$log" >&2; fail "args-file failure leaked its absolute path: $name"
  fi
  verify_args_fixture_cleanup "$name" "$kind" "$args_path"
done

for idx in "${!race_names[@]}"; do
  name="${race_names[$idx]}"
  expected="${race_expecteds[$idx]}"
  mutation="${race_mutations[$idx]}"
  expect_status="${race_expect_statuses[$idx]}"
  args_path="${race_paths[$idx]}"
  hook_dir="${race_hooks[$idx]}"
  replacement="${race_replacements[$idx]}"
  status="$(< "$FIXTURE_STATE/results/race-$idx.status")"
  log="$FIXTURE_STATE/results/race-$idx.log"
  if [ "$expect_status" = fail ]; then
    [ "$status" -ne 0 ] || fail "race mutation unexpectedly passed: $name"
    grep -qiF -- "$expected" "$log" || { cat "$log" >&2; fail "wrong race failure: $name"; }
  else
    [ "$status" -eq 0 ] || { cat "$log" >&2; fail "cleanup race unexpectedly failed: $name"; }
  fi
  if grep -F -- "$args_path" "$log" >/dev/null; then cat "$log" >&2; fail "race failure leaked absolute path: $name"; fi
  case "$mutation" in
    symlink) [ -L "$args_path" ] && [ -f "$replacement" ] || fail "symlink race replacement was deleted"; rm -f "$args_path" "$replacement" ;;
    same-size) [ -f "$args_path" ] || fail "same-size race replacement was deleted"; rm -f "$args_path" ;;
    grow) rm -f "$args_path" ;;
  esac
  rm -rf "$hook_dir"
done

if command -v base64 >/dev/null 2>&1; then
  PATH="/usr/bin:/bin"
fi

echo "PASS: mapping-review wrapper and Gradle args-file gates preserve argv, enforce bounds/encoding/NOFOLLOW, avoid path logs, and clean safely"
