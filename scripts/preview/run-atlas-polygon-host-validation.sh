#!/usr/bin/env bash
# Atlas dalsoo-polygon E2E adapter for the generic exact-host runner.
#
# Phase a (default): fixture copy -> atlas editor -> native auto-layout with the
#   dalsoo algorithm -> write-back assertions -> undo/redo -> save.
# Phase b: reopen a phase-A saved fixture copy and assert the persisted plan
#   equals the phase-A post-layout state hash.
#
# usage:
#   run-atlas-polygon-host-validation.sh <5203|5302|5303> [run-label] [runner-options...]
#   run-atlas-polygon-host-validation.sh <version> <label> --phase b \
#       --fixture <phase-A-saved.cmo3> --expected-plan-sha <sha256>
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-atlas-polygon-host-validation.sh <5203|5302|5303> [run-label] [--phase b --fixture F --expected-plan-sha S] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
case "$version" in
  5203|5302|5303) ;;
  *) echo "unsupported version: $version (admitted: 5203, 5302, 5303)" >&2; exit 2 ;;
esac
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

phase="a"
fixture_override=""
expected_plan_sha=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --phase) phase="$2"; shift 2 ;;
    --fixture) fixture_override="$2"; shift 2 ;;
    --expected-plan-sha) expected_plan_sha="$2"; shift 2 ;;
    --) shift; break ;;
    *) break ;;
  esac
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
preview_root="$repo_root/build/preview/$worktree_id"
dalsoo_jar="$(ls "$repo_root"/build/worktree/"$worktree_id"/atlas-dalsoo-polygon/libs/atlas-dalsoo-polygon-*.jar 2>/dev/null | head -1)"
probe_jar="$repo_root/build/atlas-polygon-validation-exerciser.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

[ -f "$preview_root/turboism-agent.jar" ] || { echo 'error: build previewBundle first' >&2; exit 2; }
[ -n "$dalsoo_jar" ] && [ -f "$dalsoo_jar" ] || { echo 'error: build :plugins:atlas-dalsoo-polygon:jar first' >&2; exit 2; }
[ -f "$probe_jar" ] || { echo 'error: run validation/atlas-polygon-host-probe/build.sh first' >&2; exit 2; }

jvm_options=(
  '-Dturboism.validation.exitOnComplete=true'
  "-Dturboism.validation.hostVersion=$version"
  "-Dturboism.validation.atlasPolygon.phase=$phase"
  '-Dturboism.validation.atlasPolygon.fixture={FIXTURE}'
)
fixture_args=()
if [ "$phase" = "b" ]; then
  [ -n "$fixture_override" ] && [ -f "$fixture_override" ] \
    || { echo 'error: --phase b requires --fixture <phase-A-saved.cmo3>' >&2; exit 2; }
  [ -n "$expected_plan_sha" ] \
    || { echo 'error: --phase b requires --expected-plan-sha <sha256>' >&2; exit 2; }
  fixture_args=(--fixture-local "$fixture_override" --fixture-name "atlas-polygon-saved.cmo3")
  jvm_options+=("-Dturboism.validation.atlasPolygon.expectedPlanSha=$expected_plan_sha")
else
  turboism_select_fixture "$version" || exit 2
  fixture_args=(--fixture-remote "$fixture_src" --fixture-sha256 "$fixture_sha256"
    --fixture-name "atlas-polygon-fixture.cmo3")
fi

runner_args=(
  --name atlas-polygon
  --version "$version"
  --run-label "$run_label-phase-$phase"
  --bundle-root "$preview_root"
  --agent "$preview_root/turboism-agent.jar"
  --plugin "$dalsoo_jar:atlas-dalsoo-polygon.jar"
  --plugin "$probe_jar:atlas-polygon-validation-exerciser.jar"
)
runner_args+=("${fixture_args[@]}")
for opt in "${jvm_options[@]}"; do
  runner_args+=(--jvm-option "$opt")
done

exec bash "$runner" \
  "${runner_args[@]}" \
  --ready-marker 'ATLAS_POLYGON_PROBE_READY' \
  --ready-marker 'Atlas Dalsoo polygon packing enabled' \
  --ready-marker 'Plugin load complete' \
  --trigger 'state/dev.turboism.validation.atlas-polygon/exerciser.flag' \
  --result-file 'state/atlas-polygon-validation-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'ATLAS_POLYGON_PROBE_RESULT status=FAIL' \
  --failure-marker 'ATLAS_POLYGON_PROBE_FLAG_TIMEOUT' \
  --ready-timeout 300 \
  --result-timeout 900 \
  --exit-timeout 120 \
  "$@"
