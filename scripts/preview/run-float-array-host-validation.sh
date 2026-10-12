#!/usr/bin/env bash
# Feature arguments only; the common runner owns every launch/isolation/cleanup step.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/preview/host-validation-env.sh"
mode=${1:-}
case "$mode" in
  off) enabled=false; shadow=false ;;
  on) enabled=true; shadow=false ;;
  shadow) enabled=true; shadow=true ;;
  -h|--help) printf 'Usage: %s <off|on|shadow> [%s] [run-label] [generic runner options]\n' "$0" "$(turboism_task_versions_usage float-array)"; exit 0 ;;
  *) printf 'Usage: %s <off|on|shadow> [%s] [run-label] [generic runner options]\n' "$0" "$(turboism_task_versions_usage float-array)" >&2; exit 2 ;;
esac
shift
version="$TURBOISM_HOST_VALIDATION_DEFAULT_VERSION"
if [[ $# -gt 0 && "$1" =~ ^[0-9]{4}$ ]]; then
  version="$1"
  shift
fi
turboism_require_task_version float-array "$version" || exit 2
run_label="$mode"
if [[ $# -gt 0 && "$1" != --* ]]; then run_label="$1"; shift; fi
worktree_id="$(bash "$repo_root/scripts/dev/worktree-id.sh")"
bundle="$repo_root/build/preview/$worktree_id"
fixture_args=()
if turboism_select_fixture "$version" 2>/dev/null; then
  fixture_args=(--fixture-remote "$fixture_src" --fixture-sha256 "$fixture_sha256")
fi
ready_args=()
if [[ "$enabled" == true ]]; then
  ready_args=(--ready-marker 'TURBOISM_FLOAT_ARRAY_PARSE_CACHE installation=COMPLETE')
fi
exec bash "$repo_root/scripts/preview/run-cubism-host-validation.sh" \
  --name float-array-parse-cache --version "$version" --run-label "$run_label" \
  --bundle-root "$bundle" --agent "$bundle/turboism-agent.jar" \
  --home-config "$repo_root/testing/host-validation/float-array/config.json" \
  --aux-agent "$repo_root/build/float-array-host-validation-exerciser.jar" \
  "${fixture_args[@]}" --require-fixture-unchanged \
  --ready-marker 'Turboism Developer Preview started' "${ready_args[@]}" \
  --failure-marker 'TURBOISM_FLOAT_ARRAY_PARSE_CACHE installation=FAILED' \
  --trigger state/float-array/start.flag --result-file state/float-array/result.properties \
  --jvm-option "-Dturboism.optimization.floatArrayParseCache=$enabled" \
  --jvm-option '-Dturboism.optimization.imageArchiveReuse=false' \
  --jvm-option "-Dturboism.validation.floatArray.shadow=$shadow" \
  --jvm-option '-Dturboism.validation.floatArray.home={HOME}' \
  --jvm-option '-Dturboism.validation.imageArchive.home={HOME}' \
  --jvm-option '-Dturboism.validation.imageArchive.fixtureName={FIXTURE_NAME}' \
  --ready-timeout 600 --result-timeout 1200 --exit-timeout 120 \
  "$@"
