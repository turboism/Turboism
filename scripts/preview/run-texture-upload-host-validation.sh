#!/usr/bin/env bash
# Capability-only arguments; the existing common runner owns the entire host lifecycle.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/preview/host-validation-env.sh"
case ${1:-} in
  off) enabled=false; shadow=false ;;
  on) enabled=true; shadow=false ;;
  shadow) enabled=true; shadow=true ;;
  -h|--help) printf 'Usage: %s <off|on|shadow> [run-label] [generic runner options]\n' "$0"; exit 0 ;;
  *) printf 'Expected off, on or shadow mode\n' >&2; exit 2 ;;
esac
run_label=$1; shift
if [[ $# -gt 0 && "$1" != --* ]]; then run_label=$1; shift; fi
worktree_id="$(bash "$repo_root/scripts/dev/worktree-id.sh")"
bundle="$repo_root/build/preview/$worktree_id"
fixture_args=()
if [[ -n "$TURBOISM_HOST_VALIDATION_FIXTURE_5302" ]]; then
  turboism_select_fixture 5302
  fixture_args=(--fixture-remote "$fixture_src" --fixture-sha256 "$fixture_sha256")
fi
ready_args=()
if [[ "$enabled" == true ]]; then ready_args=(--ready-marker 'TURBOISM_TEXTURE_UPLOAD_PREPARATION installation=COMPLETE'); fi
interactive=false
for option in "$@"; do [[ "$option" != --interactive ]] || interactive=true; done
probe_args=(--aux-agent "$repo_root/build/texture-upload-host-validation-exerciser.jar"
  --require-fixture-unchanged --trigger state/texture-upload/start.flag --result-file state/texture-upload/result.properties)
if [[ "$interactive" == true ]]; then
  [[ "$shadow" == false ]] || { printf 'shadow is validation-only; use on/off with --interactive\n' >&2; exit 2; }
  probe_args=()
fi
exec bash "$repo_root/scripts/preview/run-cubism-host-validation.sh" \
  --name texture-upload-preparation --version 5302 --run-label "$run_label" \
  --bundle-root "$bundle" --agent "$bundle/turboism-agent.jar" \
  --home-config "$repo_root/testing/host-validation/texture-upload/config.json" \
  "${fixture_args[@]}" "${probe_args[@]}" \
  --ready-marker 'Turboism Developer Preview started' "${ready_args[@]}" \
  --failure-marker 'TURBOISM_TEXTURE_UPLOAD_PREPARATION installation=FAILED' \
  --jvm-option "-Dturboism.optimization.textureUploadPreparation=$enabled" \
  --jvm-option '-Dturboism.optimization.floatArrayParseCache=false' \
  --jvm-option '-Dturboism.optimization.imageArchiveReuse=false' \
  --jvm-option "-Dturboism.validation.textureUpload.shadow=$shadow" \
  --jvm-option '-Dturboism.validation.textureUpload.home={HOME}' \
  --jvm-option '-Dturboism.validation.imageArchive.home={HOME}' \
  --jvm-option '-Dturboism.validation.imageArchive.fixtureName={FIXTURE_NAME}' \
  --ready-timeout 600 --result-timeout 1200 --exit-timeout 120 \
  "$@"
