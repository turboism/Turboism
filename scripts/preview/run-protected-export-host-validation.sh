#!/usr/bin/env bash
# Exact-host evidence run for the protected-export wiring.
#
# This is the only check that can show the contributed option inside the real
# Embedded-Model Export Settings dialog and exercise the decision gate like a user:
# an unchecked confirmation must continue the native export path (recorded, then dismissed
# so no file is written), a checked candidate must be rejected without a continuation window,
# and a cancel must reach the bridge cleanup. The probe wraps the loader-neutral bridge
# callbacks in counting delegates so a failure shows which link in the chain broke.
#
# The candidate plugin is staged from the local build into the run's own Turboism home. It is not
# added to the preview bundle or the release plugin list: this wrapper is the only thing that ships
# it anywhere.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-protected-export-host-validation.sh <5203|5302|5303> [run-label] [phase] [runner-options...]" >&2
  echo "  phase: dialog (default) | copy-binding | flatten | export | dirty-export | export-native | census | atlas-fixture | expect-reject | comma combinations" >&2
  exit 2
fi

version="$1"
shift
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

# Which probe phases to run inside the one host session. 'dialog' is the M1 export-settings
# evidence; 'copy-binding' is the M2 disposable-copy bind/restore evidence.
phase='dialog'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  phase="$1"
  shift
fi
case "$phase" in
  dialog | copy-binding | flatten | export | dirty-export | export-native \
    | census | atlas-fixture | census,atlas-fixture | expect-reject \
    | dialog,copy-binding \
    | copy-binding,flatten | dialog,copy-binding,flatten) ;;
  *)
    echo "error: unknown probe phase '$phase' (expected dialog, copy-binding, flatten, export, dirty-export, export-native, census, atlas-fixture, expect-reject, or a comma combination)" >&2
    exit 2
    ;;
esac

# Only the reviewed 5.2.03, 5.3.02 and 5.3.03 artifacts are admitted by the export-settings
# host profile, so a run against any other build would produce a misleading failure rather
# than evidence.
case "$version" in
  5203 | 5302 | 5303) ;;
  *)
    echo "error: the export-settings hook is admitted for 5203/5302/5303 only; '$version' must not be probed" >&2
    exit 2
    ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="${TURBOISM_WORKTREE_ID:-$(bash "$repo_root/scripts/dev/worktree-id.sh")}"
bundle_root="$repo_root/build/preview/$worktree_id"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
probe_jar="$repo_root/build/protected-export-host-probe-agent.jar"

build=1
args=()
for argument in "$@"; do
  if [ "$argument" = '--no-build' ]; then
    build=0
  else
    args+=("$argument")
  fi
done

if [ "$build" = 1 ]; then
  # Rebuilt by default: a stale agent jar would prove something about code that is no longer here.
  echo 'building preview bundle, candidate plugin, and probe'
  (cd "$repo_root" && ./gradlew --quiet previewBundle :plugins:protected-export:jar)
fi

turboism_select_fixture "$version" || exit 2

plugin_jar="$repo_root/build/worktree/$worktree_id/protected-export/libs/protected-export-0.43.9-SNAPSHOT-$worktree_id.jar"
agent_jar="$bundle_root/turboism-agent.jar"
[ -f "$plugin_jar" ] || { echo "error: candidate plugin jar missing: $plugin_jar" >&2; exit 2; }
[ -f "$agent_jar" ] || { echo "error: bundle agent jar missing: $agent_jar" >&2; exit 2; }

# The probe build runs the offline rule self-check before it is allowed to produce a jar.
bash "$repo_root/validation/protected-export-host-probe/build.sh" >/dev/null
[ -f "$probe_jar" ] || { echo "error: probe jar missing: $probe_jar" >&2; exit 2; }

exec_runner() {
  bash "$runner" \
    --name protected-export \
    --version "$version" \
    --run-label "$run_label" \
    --bundle-root "$bundle_root" \
    --agent "$agent_jar" \
    --plugin "$plugin_jar:protected-export.jar" \
    --aux-agent "$probe_jar:protected-export-host-probe-agent.jar" \
    --fixture-remote "$fixture_src" \
    --fixture-sha256 "$fixture_sha256" \
    --require-fixture-unchanged \
    --jvm-option "-Dturboism.validation.protectedExport.exitOnComplete=true" \
    --jvm-option "-Dturboism.validation.protectedExport.phase=$phase" \
    --result-file "$probe_result_path" \
    --result-pass-line 'status=PASS' \
    --result-fail-line 'status=FAIL' \
    --agent-timeout 600 \
    --ready-timeout 300 \
    --result-timeout 1500 \
    --exit-timeout 120 \
    "${args[@]}"
}

# The probe writes its result and its raw dialog trees into the run's own Turboism home, which the
# runner reads but does not archive. Recover them afterwards so the evidence survives review.
probe_result_path='state/dev.turboism.validation.protectedexport/host-validation-result.properties'
set +e
exec_runner
status=$?
set -e

probe_state_root="${TURBOISM_HOST_VALIDATION_REMOTE_ROOT:-}/protected-export/$version-$run_label"
if [ -n "${TURBOISM_HOST_VALIDATION_REMOTE_ROOT:-}" ] && [ -d "$probe_state_root" ]; then
  newest="$(find "$probe_state_root" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' 2>/dev/null \
    | sort -n | tail -1 | cut -d' ' -f2-)"
  source_dir="$newest/turboism-home/state/dev.turboism.validation.protectedexport"
  if [ -n "$newest" ] && [ -d "$source_dir" ]; then
    destination="$repo_root/build/host-validation/protected-export/$version/$(basename "$newest")/probe"
    mkdir -p "$destination"
    cp -a "$source_dir/." "$destination/"
    echo "probe evidence=$destination"
  fi
fi

exit "$status"
