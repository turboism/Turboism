#!/usr/bin/env bash
# Exact-host validation adapter for 5.3.04 Extended Interpolation Optimization.
#
# Launches the official Cubism 5.3.04 Editor via the generic runner lifecycle
# with task-scoped CoW isolation, the tested preview bundle, and the dedicated
# ExtendedInterpolationHostProbe.
#
# NOTE: Host execution must only be scheduled through the managed FIFO queue;
# do not invoke the Editor Java main directly or bypass admission.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Source host validation environment helpers and parse .env
# shellcheck source=../../scripts/preview/host-validation-env.sh
source "$repo_root/scripts/preview/host-validation-env.sh"

version="5304"
if [ "$#" -gt 0 ] && [[ "$1" =~ ^[0-9]{4}$ ]]; then
  version="$1"
  shift
fi

if [ "$version" != "5304" ]; then
  echo "error: extended-interpolation-host is dedicated to 5.3.04 (given: $version)" >&2
  exit 2
fi

label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  label="$1"
  shift
fi

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" bash "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/preview/$worktree_id"
if [ ! -d "$bundle_root" ] && [ -d "$repo_root/build/preview/perf-cubism-extended-interpolation" ]; then
  bundle_root="$repo_root/build/preview/perf-cubism-extended-interpolation"
fi
agent_jar="$bundle_root/turboism-agent.jar"
probe_jar="$repo_root/build/extended-interpolation-host-probe.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

# Build bundle on-demand only when explicitly requested (TURBOISM_BUILD_PREVIEW_BUNDLE=1).
# Avoid automatic parallel Gradle builds during performance measurements.
if [ "${TURBOISM_BUILD_PREVIEW_BUNDLE:-0}" = "1" ]; then
  echo "[ei-runner] Building preview bundle on request..."
  (cd "$repo_root" && ./gradlew --no-daemon --max-workers=1 previewBundle)
fi

if [ ! -f "$agent_jar" ]; then
  echo "error: preview bundle agent missing at: $agent_jar" >&2
  echo "       ensure build/preview/$worktree_id is prepared" >&2
  echo "       (pass TURBOISM_BUILD_PREVIEW_BUNDLE=1 to build if no timing is in progress)" >&2
  exit 1
fi

if [ ! -f "$probe_jar" ]; then
  echo "[ei-runner] Probe jar not found; triggering local build..."
  bash "$repo_root/validation/extended-interpolation-host/build.sh"
fi

if [ ! -f "$probe_jar" ]; then
  echo "error: probe jar missing at: $probe_jar" >&2
  exit 1
fi

# Forward caller options directly (fixture, dry-run, display, run-label, etc.)
# Follows the standard runner protocol of run-warp-position-host-validation.sh:
# common runner owns official launch, isolation, fixture staging, and cleanup.
exec bash "$runner" \
  --name extended-interpolation \
  --version "$version" \
  --run-label "$label" \
  --bundle-root "$bundle_root" \
  --agent "$agent_jar" \
  --aux-agent "$probe_jar" \
  --require-fixture-unchanged \
  --ready-marker 'Turboism Developer Preview started' \
  --trigger state/extended-interpolation/start.flag \
  --result-file state/extended-interpolation/result.properties \
  --result-pass-line "status=SMOKE_PASS_PARTIAL_ACCEPTANCE" \
  --result-fail-line "status=FAIL" \
  --jvm-option "-Dturboism.optimization.meshInterpolation=true" \
  --jvm-option "-Dturboism.validation.extendedInterpolation.enabled=true" \
  --jvm-option "-Dturboism.validation.extendedInterpolation.home={HOME}" \
  --jvm-option "-Dturboism.validation.extendedInterpolation.fixtureName={FIXTURE_NAME}" \
  --ready-timeout 600 \
  --result-timeout 1200 \
  --exit-timeout 120 \
  "$@"
