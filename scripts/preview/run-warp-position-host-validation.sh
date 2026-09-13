#!/usr/bin/env bash
# Capability arguments only; common runner owns official launch, isolation and cleanup.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/preview/host-validation-env.sh"
mode=${1:-}
case "$mode" in
  off) enabled=false; shadow=false ;;
  on) enabled=true; shadow=false ;;
  shadow) enabled=true; shadow=true ;;
  -h|--help) printf 'Usage: %s <off|on|shadow> [run-label] [generic runner options]\n' "$0"; exit 0 ;;
  *) printf 'Usage: %s <off|on|shadow> [run-label] [generic runner options]\n' "$0"; exit 2 ;;
esac
shift
label="$mode"
if [[ $# -gt 0 && "$1" != --* ]]; then label="$1"; shift; fi
worktree_id="$(bash "$repo_root/scripts/dev/worktree-id.sh")"
bundle="$repo_root/build/preview/$worktree_id"
ready=()
if [[ "$enabled" == true ]]; then ready=(--ready-marker 'TURBOISM_WARP_POSITION_PROJECTION installation=COMPLETE'); fi
exec bash "$repo_root/scripts/preview/run-cubism-host-validation.sh" \
  --name warp-position-projection --version 5302 --run-label "$label" \
  --bundle-root "$bundle" --agent "$bundle/turboism-agent.jar" \
  --home-config "$repo_root/testing/host-validation/texture-upload/config.json" \
  --aux-agent "$repo_root/build/warp-position-host-validation-exerciser.jar" \
  --require-fixture-unchanged \
  --ready-marker 'Turboism Developer Preview started' "${ready[@]}" \
  --failure-marker 'TURBOISM_WARP_POSITION_PROJECTION installation=FAILED' \
  --trigger state/warp-projection/start.flag --result-file state/warp-projection/result.properties \
  --jvm-option "-Dturboism.optimization.warpPositionProjection=$enabled" \
  --jvm-option '-Dturboism.optimization.imageArchiveReuse=false' \
  --jvm-option '-Dturboism.optimization.floatArrayParseCache=false' \
  --jvm-option '-Dturboism.optimization.textureUploadPreparation=false' \
  --jvm-option "-Dturboism.validation.warpProjection.shadow=$shadow" \
  --jvm-option '-Dturboism.validation.warpProjection.home={HOME}' \
  --jvm-option '-Dturboism.validation.imageArchive.home={HOME}' \
  --jvm-option '-Dturboism.validation.imageArchive.fixtureName={FIXTURE_NAME}' \
  --ready-timeout 600 --result-timeout 1200 --exit-timeout 120 \
  "$@"
