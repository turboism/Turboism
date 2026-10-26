#!/usr/bin/env bash
# MCP adapter for the generic exact-host runner.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/preview/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-mcp-host-validation.sh $(turboism_task_versions_usage mcp) [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
turboism_require_task_version mcp "$version" || exit 2
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi
case " $* " in
  *freeFixture=true*)
    # The free-fixture variant deliberately exercises the shared fixture's
    # FREE-limit preparation; keep it on the versioned selection.
    turboism_select_fixture "$version"
    ;;
  *)
    # The standard variant needs a Glue-bearing fixture for the glue authoring
    # assertions; the shared versioned fixture may carry none. Prefer the
    # task-scoped override, then reuse the clipmask mask/Glue-bearing fixture,
    # and only then fall back to the versioned selection.
    if [ -n "${TURBOISM_HOST_VALIDATION_FIXTURE_MCP:-}" ]; then
      fixture_src="$TURBOISM_HOST_VALIDATION_FIXTURE_MCP"
      fixture_sha256="${TURBOISM_HOST_VALIDATION_FIXTURE_MCP_SHA256:-}"
      [ -n "$fixture_sha256" ] \
        || { echo "error: TURBOISM_HOST_VALIDATION_FIXTURE_MCP set but TURBOISM_HOST_VALIDATION_FIXTURE_MCP_SHA256 missing" >&2; exit 2; }
    elif [ -n "${TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK:-}" ]; then
      fixture_src="$TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK"
      fixture_sha256="${TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK_SHA256:-}"
      [ -n "$fixture_sha256" ] \
        || { echo "error: TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK set but TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK_SHA256 missing" >&2; exit 2; }
    else
      turboism_select_fixture "$version"
    fi
    ;;
esac
client_python="$(python3 -I -c 'import pathlib, sys; print(pathlib.Path(sys.executable).resolve())')"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-mcp-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
native_inputs=()
for option in "$@"; do
  if [ "$option" = '-Dturboism.validation.nativeObservation=true' ] || [ "$option" = '-Dturboism.validation.nativeEditing=true' ]; then
    [ "$version" = 5303 ] || { echo 'error: native observation requires 5303' >&2; exit 2; }
    native_inputs=(--home-file "$bundle_root/native-observe/probe.jar:native-observe/probe.jar"
                   --home-file "$bundle_root/native-observe/runtime.json:native-observe/runtime.json")
  fi
done

exec bash "$runner" \
  --name mcp \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$bundle_root/plugins/mcp.jar:mcp.jar" \
  --plugin "$bundle_root/plugins/mcp-host-validation-probe.jar:mcp-host-validation-probe.jar" \
  --client-script "$repo_root/scripts/preview/mcp-host-validation-client.py:mcp-host-validation-client.py" \
  --client-python "$client_python" \
  "${native_inputs[@]}" \
  --fixture-host "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --jvm-option '-Dturboism.validation.fixture={FIXTURE}' \
  --jvm-option '-Dturboism.mcp.port=0' \
  --jvm-option '-Dturboism.mcp.requestsPerMinute=600' \
  --ready-marker 'MCP host validation probe initialized' \
  --ready-marker 'Turboism MCP server started on the local loopback interface' \
  --ready-marker 'Plugin load complete' \
  --result-file 'state/mcp-host-validation.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'Turboism MCP startup failed' \
  --ready-timeout 360 \
  --result-timeout 900 \
  --exit-timeout 240 \
  "$@"
