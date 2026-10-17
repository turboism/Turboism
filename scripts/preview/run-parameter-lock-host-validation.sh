#!/usr/bin/env bash
# Production parameter-lock acceptance through the managed exact-host queue.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo 'usage: run-parameter-lock-host-validation.sh <version> [matrix|persist-write|persist-read] [run-label] [runner-options...]' >&2
  exit 2
fi
version="$1"
shift
turboism_is_reviewed_version "$version" || { echo 'error: unreviewed host version' >&2; exit 2; }
mode=matrix
run_label=r1
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then mode="$1"; shift; fi
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then run_label="$1"; shift; fi
case "$mode" in
  matrix|persist-write|persist-read) ;;
  *) echo 'error: unsupported parameter-lock validation mode' >&2; exit 2 ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
preview_root="$repo_root/build/preview/$worktree_id"
shopt -s nullglob
lock_jars=("$repo_root"/build/worktree/"$worktree_id"/parameter-lock/libs/parameter-lock-*.jar)
[ "${#lock_jars[@]}" -eq 1 ] || { echo 'error: build a unique :plugins:parameter-lock:jar first' >&2; exit 2; }
probe_jar="$repo_root/build/parameter-lock-host-validation-probe.jar"
startup_jar="$repo_root/build/native-startup-assistance-agent.jar"
for artifact in "$preview_root/turboism-agent.jar" "$probe_jar" "$startup_jar"; do
  [ -f "$artifact" ] || { echo "error: required artifact missing: $artifact" >&2; exit 2; }
done
turboism_select_fixture "$version" || exit 2

exec bash "$repo_root/scripts/preview/run-cubism-host-validation.sh" \
  --name parameter-lock \
  --version "$version" \
  --run-label "$run_label-$mode" \
  --bundle-root "$preview_root" \
  --agent "$preview_root/turboism-agent.jar" \
  --plugin "${lock_jars[0]}:parameter-lock.jar" \
  --plugin "$probe_jar:parameter-lock-host-validation-probe.jar" \
  --aux-agent "$startup_jar:native-startup-assistance-agent.jar" \
  --fixture-host "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --fixture-name parameter-lock-fixture.cmo3 \
  --require-fixture-unchanged \
  --jvm-option "-Dturboism.parameterLockValidation.mode=$mode" \
  --jvm-option '-Dturboism.parameterLockValidation.fixture={FIXTURE}' \
  --jvm-option '-Dturboism.validation.startupAssistance=true' \
  --jvm-option '-Dturboism.optimization.mesaGlThread=false' \
  --linux-env mesa_glthread=false \
  --ready-marker PARAMETER_LOCK_PROBE_READY \
  --ready-marker 'Plugin load complete' \
  --trigger state/dev.turboism.validation.parameter-lock/exerciser.flag \
  --result-file state/parameter-lock-host-result.properties \
  --result-pass-line status=PASS \
  --result-fail-line status=FAIL \
  --ready-timeout 300 \
  --result-timeout 900 \
  --exit-timeout 120 \
  "$@"
