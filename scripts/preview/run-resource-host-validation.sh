#!/usr/bin/env bash
# Capability arguments only; common runner owns official launch, isolation and cleanup.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/preview/host-validation-env.sh"
mode=${1:-}
case "$mode" in
  plain) profile=false ;;
  profile) profile=true ;;
  -h|--help) printf 'Usage: %s <plain|profile> [short-label] [generic runner options]\n' "$0"; exit 0 ;;
  *) printf 'Usage: %s <plain|profile> [short-label] [generic runner options]\n' "$0"; exit 2 ;;
esac
shift
label="$mode"
if [[ $# -gt 0 && "$1" != --* ]]; then label="$1"; shift; fi
worktree_id="$(bash "$repo_root/scripts/dev/worktree-id.sh")"
bundle="$repo_root/build/preview/$worktree_id"
observer_python="$(python3 -I -S -c 'import pathlib, sys; print(pathlib.Path(sys.executable).resolve(strict=True))')"
exec bash "$repo_root/scripts/preview/run-cubism-host-validation.sh" \
  --name native-resource --version 5302 --run-label "$label" \
  --bundle-root "$bundle" --agent "$bundle/turboism-agent.jar" \
  --home-config "$repo_root/testing/host-validation/texture-upload/config.json" \
  --aux-agent "$repo_root/build/resource-host-validation-exerciser.jar" \
  --require-fixture-unchanged \
  --ready-marker 'Turboism Developer Preview started' \
  --trigger state/resource-workload/start.flag --result-file state/resource-workload/result.properties \
  --home-file "$repo_root/scripts/test/measure-task-memory.py:validation/measure-task-memory.py" \
  --home-file "$repo_root/scripts/test/host_resource_counters.py:validation/host_resource_counters.py" \
  --home-file "$repo_root/scripts/test/host_memory_identity.py:validation/host_memory_identity.py" \
  --remote-pre-launch "$repo_root/scripts/test/start-task-memory-observer.sh" \
  --remote-pre-launch-background --remote-pre-launch-arg "$observer_python" \
  --jvm-option '-Dturboism.optimization.warpPositionProjection=false' \
  --jvm-option '-Dturboism.optimization.imageArchiveReuse=false' \
  --jvm-option '-Dturboism.optimization.floatArrayParseCache=false' \
  --jvm-option '-Dturboism.optimization.textureUploadPreparation=false' \
  --jvm-option "-Dturboism.validation.resource.profile=$profile" \
  --jvm-option '-Dturboism.validation.textureUpload.memoryIdleSeconds=300' \
  --jvm-option '-Dturboism.validation.textureUpload.loadingTrace=true' \
  --jvm-option '-Dturboism.validation.textureUpload.home={HOME}' \
  --jvm-option '-Dturboism.validation.imageArchive.home={HOME}' \
  --jvm-option '-Dturboism.validation.imageArchive.fixtureName={FIXTURE_NAME}' \
  --ready-timeout 600 --result-timeout 1200 --exit-timeout 120 \
  "$@"
