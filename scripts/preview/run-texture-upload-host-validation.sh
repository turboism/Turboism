#!/usr/bin/env bash
# Capability-only arguments; the existing common runner owns the entire host lifecycle.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/preview/host-validation-env.sh"
case ${1:-} in
  off) enabled=false; shadow=false ;;
  on) enabled=true; shadow=false ;;
  shadow) enabled=true; shadow=true ;;
  -h|--help) printf 'Usage: %s <off|on|shadow> [%s] [run-label] [generic runner options]\n' "$0" "$(turboism_task_versions_usage texture-upload)"; exit 0 ;;
  *) printf 'Expected off, on or shadow mode\n' >&2; exit 2 ;;
esac
run_label=$1; shift
version="$TURBOISM_HOST_VALIDATION_DEFAULT_VERSION"
if [[ $# -gt 0 && "$1" =~ ^[0-9]{4}$ ]]; then
  version="$1"
  shift
fi
turboism_require_task_version texture-upload "$version" || exit 2
if [[ $# -gt 0 && "$1" != --* ]]; then run_label=$1; shift; fi
worktree_id="$(bash "$repo_root/scripts/dev/worktree-id.sh")"
bundle="$repo_root/build/preview/$worktree_id"
# Use the same frozen owner pin as the product; 5.0/5.1, 5.2 and 5.3 factories differ.
factory_owner="$(python3 - "$bundle/turboism-agent.jar" "$(turboism_dotted_version "$version")" <<'PY'
import json, re, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as agent:
    data = json.loads(agent.read('dev/turboism/adapter/cubism/class-pins/texture-upload-preparation.json'))
# Compact tables state one `default` block plus per-version overrides; the legacy
# flat shape keys the versions directly. Both resolve to the same class list.
if 'default' in data:
    pins = data.get('overrides', {}).get(sys.argv[2], data['default'])
else:
    pins = data.get(sys.argv[2], [])
if len(pins) != 1:
    raise SystemExit('texture validation requires one reviewed factory class')
owner = pins[0]
if not re.fullmatch(r'com/live2d/graphics3d/shader/[A-Za-z_$][A-Za-z0-9_$]*', owner):
    raise SystemExit('invalid reviewed texture factory owner')
print(owner.replace('/', '.'))
PY
)"
fixture_args=()
if turboism_select_fixture "$version" 2>/dev/null; then
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
  --name texture-upload-preparation --version "$version" --run-label "$run_label" \
  --bundle-root "$bundle" --agent "$bundle/turboism-agent.jar" \
  --home-config "$repo_root/testing/host-validation/texture-upload/config.json" \
  "${fixture_args[@]}" "${probe_args[@]}" \
  --ready-marker 'Turboism Developer Preview started' "${ready_args[@]}" \
  --failure-marker 'TURBOISM_TEXTURE_UPLOAD_PREPARATION installation=FAILED' \
  --jvm-option "-Dturboism.optimization.textureUploadPreparation=$enabled" \
  --jvm-option '-Dturboism.optimization.floatArrayParseCache=false' \
  --jvm-option '-Dturboism.optimization.imageArchiveReuse=false' \
  --jvm-option "-Dturboism.validation.textureUpload.shadow=$shadow" \
  --jvm-option "-Dturboism.validation.textureUpload.factoryOwner=$factory_owner" \
  --jvm-option '-Dturboism.validation.textureUpload.home={HOME}' \
  --jvm-option '-Dturboism.validation.imageArchive.home={HOME}' \
  --jvm-option '-Dturboism.validation.imageArchive.fixtureName={FIXTURE_NAME}' \
  --ready-timeout 600 --result-timeout 1200 --exit-timeout 120 \
  "$@"
