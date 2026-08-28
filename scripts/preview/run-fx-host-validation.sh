#!/usr/bin/env bash
# Exact Cubism validation for Turboism with fx using Pi's Codex subscription.
# Usage: bash scripts/preview/run-fx-host-validation.sh [run-label] [--version 5203|5302] [--manual] [runner-options...]
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TURBOISM_ENV_FILE="${TURBOISM_ENV_FILE:-$repo_root/.env}"
export TURBOISM_ENV_FILE
# shellcheck source=host-validation-env.sh
source "$repo_root/scripts/preview/host-validation-env.sh"

run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label=$1
  shift
fi
host_version=5302
manual=0
explicit_result_timeout=''
runner_options=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --manual)
      manual=1
      shift
      ;;
    --version)
      [ "$#" -ge 2 ] || {
        printf '%s\n' 'fx host validation: --version requires 5203 or 5302' >&2
        exit 2
      }
      host_version=$2
      shift 2
      ;;
    --version=*)
      host_version=${1#*=}
      shift
      ;;
    --result-timeout)
      [ "$#" -ge 2 ] || {
        printf '%s\n' 'fx host validation: --result-timeout requires seconds' >&2
        exit 2
      }
      explicit_result_timeout=$2
      shift 2
      ;;
    --result-timeout=*)
      explicit_result_timeout=${1#*=}
      shift
      ;;
    *)
      runner_options+=("$1")
      shift
      ;;
  esac
done
result_timeout=${explicit_result_timeout:-480}
if [ "$manual" = 1 ]; then
  result_timeout=${explicit_result_timeout:-21600}
  runner_options+=(--keep-prefix)
fi
[[ "$result_timeout" =~ ^[1-9][0-9]*$ ]] || {
  printf '%s\n' 'fx host validation: result timeout must be a positive integer' >&2
  exit 2
}
runner_options+=(--result-timeout "$result_timeout")
case "$host_version" in
  5203) java_executable='C:\Program Files\Live2D Cubism 5.2\app\jre\bin\java.exe' ;;
  5302) java_executable='C:\Program Files\Live2D Cubism 5.3\app\jre\bin\java.exe' ;;
  *)
    printf 'fx host validation: unsupported Cubism version: %s\n' "$host_version" >&2
    exit 2
    ;;
esac
turboism_select_fixture "$host_version"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-fx-host-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
plugin_args=(
  --plugin "$bundle_root/plugins/mcp.jar:mcp.jar"
  --plugin "$bundle_root/plugins/turboism-with-fx.jar:turboism-with-fx.jar"
)
result_args=(
  --result-file 'state/manual-validation-complete.properties'
  --result-pass-line 'status=PASS'
  --result-fail-line 'status=FAIL'
)
if [ "$manual" = 0 ]; then
  plugin_args+=(
    --plugin "$bundle_root/plugins/fx-host-validation-probe.jar:fx-host-validation-probe.jar"
  )
  result_args=(
    --ready-marker 'FX_HOST_PROBE_READY'
    --failure-marker 'FX_HOST_RESULT status=FAIL'
    --result-file 'state/fx-host-validation-result.properties'
    --result-pass-line 'status=PASS'
    --result-fail-line 'status=FAIL'
  )
fi

exec bash "$runner" \
  --name fx-host \
  --version "$host_version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  "${plugin_args[@]}" \
  --home-file "$bundle_root/bridge/fx-validation-bridge.jar:state/dev.turboism.plugin.turboism-with-fx/fx-validation-bridge.jar" \
  --home-file "$bundle_root/broker/fx_validation_broker.py:state/dev.turboism.validation.fx-host/fx_validation_broker.py" \
  --remote-pre-launch "$repo_root/scripts/preview/fx-validation-remote-pre-launch.sh" \
  --remote-pre-cleanup "$repo_root/scripts/preview/fx-validation-remote-pre-cleanup.sh" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --jvm-option '-Dturboism.fx.validation.bridgeClassPath={HOME}\state\dev.turboism.plugin.turboism-with-fx\fx-validation-bridge.jar' \
  --jvm-option '-Dturboism.fx.validation.bridgeConfig={HOME}\state\dev.turboism.plugin.turboism-with-fx\fx-validation-bridge.properties' \
  --jvm-option '-Dturboism.fx.validation.bridge=true' \
  --jvm-option "-Dturboism.validation.fxExecutable=$java_executable" \
  "${result_args[@]}" \
  --ready-timeout 360 \
  --exit-timeout 180 \
  "${runner_options[@]}"
