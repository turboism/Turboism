#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd -P)"
RUNNER="$REPO_ROOT/scripts/preview/run-cubism-host-validation.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/turboism-host-project-copy.XXXXXX")"
fail() { echo "FAIL: $1" >&2; exit 1; }
cleanup() { rm -rf -- "$TMP"; }
trap cleanup EXIT

BUNDLE="$TMP/bundle"
mkdir -p "$BUNDLE"
printf 'x\n' > "$BUNDLE/agent.jar"
printf 'x\n' > "$BUNDLE/plugin.jar"
printf 'x\n' > "$BUNDLE/source model.cmo3"
printf 'x\n' > "$BUNDLE/source.psd"

COMMON=(bash "$RUNNER" --version 5302 --bundle-root "$BUNDLE"
  --agent "$BUNDLE/agent.jar" --plugin "$BUNDLE/plugin.jar" --result-marker never --dry-run
  --ssh-host test@example.invalid --ssh-key "$BUNDLE/agent.jar"
  --golden-prefix /remote/golden --remote-root /remote/tasks --proton-runner /remote/proton)

first="$(TURBOISM_HOST_VALIDATION_RUN_NONCE=000001 "${COMMON[@]}" \
  --name recent-preview --run-label smoke \
  --fixture-local "$BUNDLE/source model.cmo3" \
  --jvm-option '-Dprobe.fixture={FIXTURE_NAME}')"
second="$(TURBOISM_HOST_VALIDATION_RUN_NONCE=000002 "${COMMON[@]}" \
  --name recent-preview --run-label smoke \
  --fixture-local "$BUNDLE/source model.cmo3")"

first_task="$(grep '^taskId=' <<<"$first" | cut -d= -f2-)"
second_task="$(grep '^taskId=' <<<"$second" | cut -d= -f2-)"
first_name="$(grep '^fixtureName=' <<<"$first" | cut -d= -f2-)"
first_path="$(grep '^fixturePath=' <<<"$first" | cut -d= -f2-)"

[ -n "$first_task" ] || fail 'first task ID missing'
[ -n "$second_task" ] || fail 'second task ID missing'
[ "$first_task" != "$second_task" ] || fail 'concurrent-capable task IDs collided'
[ "$first_name" = "$first_task.cmo3" ] || fail "derived fixture name mismatch: $first_name"
[ "${first_path##*/}" = "$first_name" ] || fail 'fixture path does not use the derived purpose name'
grep -Fq -- "validationFixtureNameJvmOption=-Dturboism.validation.fixtureName=$first_name" <<<"$first" \
  || fail 'automatic fixture-name JVM property missing'
grep -Fq -- "jvmOption.0=-Dprobe.fixture=$first_name" <<<"$first" \
  || fail 'fixture-name placeholder was not expanded'

explicit="$("${COMMON[@]}" --name psd-clip-mask --run-label matrix \
  --fixture-local "$BUNDLE/source.psd" --fixture-name clipmask.psd)"
explicit_task="$(grep '^taskId=' <<<"$explicit" | cut -d= -f2-)"
explicit_name="$(grep '^fixtureName=' <<<"$explicit" | cut -d= -f2-)"
[ "$explicit_name" = "$explicit_task-clipmask.psd" ] \
  || fail 'explicit fixture suffix was not purpose-prefixed'
grep -Fq -- 'always prefixed with the generated validation run ID' <<<"$(bash "$RUNNER" --help)" \
  || fail 'usage does not document purpose-prefixed project copies'

if grep -Eq 'TURBOISM_HOST_VALIDATION_LOCK_FILE|turboism-cubism-host-validation\.lock|flock -n' "$RUNNER"; then
  fail 'generic runner still serializes all host validations with a local lock'
fi

concurrent_one="$TMP/concurrent-one.out"
concurrent_two="$TMP/concurrent-two.out"
"${COMMON[@]}" --name concurrent-a --fixture-local "$BUNDLE/source model.cmo3" >"$concurrent_one" &
pid_one=$!
"${COMMON[@]}" --name concurrent-b --fixture-local "$BUNDLE/source model.cmo3" >"$concurrent_two" &
pid_two=$!
wait "$pid_one"
wait "$pid_two"
[ -s "$concurrent_one" ] && [ -s "$concurrent_two" ] || fail 'parallel dry runs did not both complete'

echo 'PASS: host validation project-copy naming check'
