#!/usr/bin/env bash
# Exact-host adapter for the test-only update-check validation exerciser.
#
# The probe performs no injection: the production runtime makes its real request against the
# deployed release API, and the probe records both the decision the production store committed
# and the text the user actually sees in the running host's Swing component tree.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-update-check-host-validation.sh <5203|5302|5303> [run-label] [new|current|safe|demo] [runner-options...]" >&2
  exit 2
fi

version="$1"
shift
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi
mode='new'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  mode="$1"
  shift
fi

case "$version" in
  5203|5302|5303) ;;
  *)
    echo "update-check validation: unsupported Cubism version: $version" >&2
    exit 2
    ;;
esac

case "$mode" in
  new|current|safe|demo) ;;
  *)
    echo "update-check validation: mode must be new, current, safe or demo: $mode" >&2
    exit 2
    ;;
esac

# The published stable identity the checker is compared against. The presentation is a native
# canvas hint, so there is no Swing label to predict here; only the build the hint must name.
# These are the deployed values the wrapper was reviewed with; a newer release must be re-pinned
# deliberately, and a stale pin is expected to fail loudly rather than pass quietly.
published_version="${TURBOISM_UPDATE_CHECK_PUBLISHED_VERSION:-0.43.10}"
published_build="${TURBOISM_UPDATE_CHECK_PUBLISHED_BUILD:-4}"

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/preview/$worktree_id"
agent_jar="$bundle_root/turboism-agent.jar"
probe_jar="$repo_root/build/update-check-host-validation-exerciser.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

for required in "$runner" "$agent_jar" "$probe_jar"; do
  [ -f "$required" ] || { echo "update-check validation: required input missing: $required" >&2; exit 2; }
done

# The installed identity comes from the packaged bundle itself, never from a guess, so the mode
# guards below compare what was actually built against the published pin.
identity="$(unzip -p "$agent_jar" META-INF/turboism/framework-version.properties)"
installed_version="$(printf '%s\n' "$identity" | sed -n 's/^version=//p')"
installed_display="$(printf '%s\n' "$identity" | sed -n 's/^displayVersion=//p')"
if [ -z "$installed_version" ] || [ -z "$installed_display" ]; then
  echo "update-check validation: the bundle does not embed a framework identity" >&2
  exit 2
fi
installed_core="$(printf '%s' "$installed_version" | sed -E 's/^((0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)).*$/\1/')"

require_core_at_least() {
  # True when $installed_core is numerically >= $published_version, compared component by component.
  local installed=() published=()
  IFS=. read -r -a installed <<<"$installed_core"
  IFS=. read -r -a published <<<"$published_version"
  for index in 0 1 2; do
    if [ "${installed[index]:-0}" -gt "${published[index]:-0}" ]; then return 0; fi
    if [ "${installed[index]:-0}" -lt "${published[index]:-0}" ]; then return 1; fi
  done
  return 0
}

case "$mode" in
  new)
    if require_core_at_least; then
      echo "update-check validation: mode 'new' needs a bundle older than $published_version," >&2
      echo "  but this bundle embeds $installed_version." >&2
      echo "  Rebuild it first: ./gradlew previewBundle -PturboismVersion=<older>" >&2
      exit 2
    fi
    expected_build="$published_build"
    ;;
  current)
    if ! require_core_at_least || [ "$installed_core" != "$published_version" ]; then
      echo "update-check validation: mode 'current' needs a bundle at $published_version," >&2
      echo "  but this bundle embeds $installed_version." >&2
      exit 2
    fi
    expected_build=''
    ;;
  safe)
    expected_build=''
    ;;
  demo)
    if require_core_at_least; then
      echo "update-check validation: mode 'demo' needs a bundle older than $published_version," >&2
      echo "  but this bundle embeds $installed_version." >&2
      echo "  Rebuild it first: ./gradlew previewBundle -PturboismVersion=<older>" >&2
      exit 2
    fi
    expected_build="$published_build"
    ;;
esac

# The demo session is meant to be looked at and clicked. After the probe reports its handoff the
# runner waits `exit-timeout` for the launcher to exit before stopping the process tree, and the
# demo probe deliberately never exits, so **exit-timeout is the session length**. Give it the full
# budget instead of the automated runs' tight grace period, or the window disappears while someone
# is still looking at it.
result_timeout=420
exit_timeout=120
if [ "$mode" = demo ]; then
  demo_seconds="${TURBOISM_UPDATE_CHECK_DEMO_SECONDS:-1800}"
  result_timeout="$demo_seconds"
  exit_timeout="$demo_seconds"
fi

common_args=(
  --name update-check
  --version "$version"
  --run-label "$run_label-$mode"
  --bundle-root "$bundle_root"
  --agent "$agent_jar"
  --plugin "$probe_jar:update-check-host-validation-exerciser.jar"
  --fixture-local "$fixture_src"
  --fixture-sha256 "$fixture_sha256"
  --require-fixture-unchanged
  --jvm-option "-Dturboism.validation.updateCheck.mode=$mode"
  --jvm-option "-Dturboism.validation.updateCheck.expectBuild=$expected_build"
  --result-file 'state/dev.turboism.validation.updatecheck/update-check-result.txt'
  --result-pass-line 'status=PASS'
  --result-fail-line 'status=FAIL'
  --ready-timeout 360
  --result-timeout "$result_timeout"
  --exit-timeout "$exit_timeout"
  --transport local
)
if [ "$mode" = safe ]; then
  common_args+=(--home-config "$repo_root/scripts/preview/update-check-safe-mode-config.json")
fi

printf 'update-check validation: mode=%s installed=%s expectedBuild="%s"\n' \
  "$mode" "$installed_version" "$expected_build" >&2
if [ "$mode" = demo ]; then
  printf 'update-check validation: demo leaves the editor running for %ss; close it when done.\n' \
    "$demo_seconds" >&2
fi

exec bash "$runner" "${common_args[@]}" "$@"
