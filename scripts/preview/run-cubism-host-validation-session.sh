#!/usr/bin/env bash
# Multi-probe session adapter for the generic exact-host runner: one Cubism
# launch runs every read-only group-S probe in sequence. The session
# coordinator plugin (dev.turboism.validation.session) reads the staged
# state/session/plan.json, writes each step's trigger flag, polls the step's
# result file, archives it into state/session/steps/, health-checks between
# steps and publishes the aggregate state/session/session-result.properties.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-cubism-host-validation-session.sh $(turboism_task_versions_usage session-standard) [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
turboism_require_task_version session-standard "$version" || exit 2
core_tuple="$(turboism_expected_core_tuple "$version")" || exit 2
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

if [ -n "${TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK:-}" ]; then
  # The session is a single-fixture single-launch superset of the standalone
  # tasks: its clipmask-viewer step needs the mask-bearing revision of the
  # shared fixture family (the shared fixture carries zero clip-mask records
  # and the step terminates BLOCKED). Every other session step is read-only
  # against the same document, so the task-scoped override is preferred
  # whenever it is configured, mirroring run-clipmask-viewer-host-validation.sh.
  fixture_src="$TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK"
  fixture_sha256="${TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK_SHA256:-}"
  [ -n "$fixture_sha256" ] \
    || { echo "error: TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK set but TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK_SHA256 missing" >&2; exit 2; }
else
  turboism_select_fixture "$version" || exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/preview/$worktree_id"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

# Steps are ordered read-only first; each entry is
# "name|spec-task|trigger|result|timeoutSeconds|triggerPropertyLines".
steps=()
plugins=(
  "$repo_root/build/session-coordinator.jar:session-coordinator.jar"
  "$repo_root/build/texture-relations-host-probe.jar:texture-relations-host-probe.jar"
  "$repo_root/build/clipmask-viewer-validation-exerciser.jar:clipmask-viewer-validation-exerciser.jar"
  "$repo_root/build/separate-save-path-host-validation-probe.jar:separate-save-path-host-validation-probe.jar"
  "$repo_root/build/mcp-host-validation-probe.jar:mcp-host-validation-probe.jar"
)
shopt -s nullglob
clipmask_plugin=("$repo_root"/build/worktree/"$worktree_id"/clipmask-viewer/libs/clipmask-viewer-*.jar)
steps+=("texture-relations|texture-relations|state/session/go/dev.turboism.validation.textures.flag|state/dev.turboism.validation.textures/relations-result.properties|300|")
if turboism_task_supports_version psd-export-observation "$version"; then
  steps+=("psd-export-observation|psd-export-observation|state/session/go/dev.turboism.validation.textures.exportobs.flag|state/dev.turboism.validation.textures.exportobs/relations-result.properties|420|turboism.validation.textures.exportObservation=true")
  plugins+=("$repo_root/build/psd-export-observation-session-probe.jar:psd-export-observation-session-probe.jar")
fi
if turboism_task_supports_version status-bar "$version"; then
  steps+=("status-bar|status-bar|state/session/go/dev.turboism.validation.statusbar.flag|state/dev.turboism.validation.statusbar/matrix-result.txt|300|")
  plugins+=("$repo_root/build/status-bar-host-validation-exerciser.jar:status-bar-host-validation-exerciser.jar")
fi
steps+=("clipmask-viewer|clipmask-viewer|state/dev.turboism.validation.clipmask-viewer/exerciser.flag|state/clipmask-viewer-validation-result.properties|900|")
steps+=("separate-save-path|separate-save-path|state/session/go/dev.turboism.validation.separatesavepath.flag|state/dev.turboism.validation.separatesavepath/host-validation-result.properties|300|")
steps+=("core-owned-moc|core-owned-moc|state/session/go/dev.turboism.validation.mcp.flag|state/core-owned-moc-result.properties|300|")
plugins+=("${clipmask_plugin[0]:-MISSING}:clipmask-viewer.jar")

# The aggregate timeout covers one cold start plus every step budget.
result_timeout=$((600 + ${#steps[@]} * 30))
for step in "${steps[@]}"; do
  result_timeout=$((result_timeout + $(cut -d'|' -f5 <<<"$step")))
done

plan_file="$(mktemp)"
trap 'rm -f -- "$plan_file"' EXIT
python3 - "$plan_file" "$version" "${steps[@]/#/STEP=}" <<'PY'
import json
import sys

target = sys.argv[1]
version = sys.argv[2]
steps = []
for raw in sys.argv[3:]:
    assert raw.startswith("STEP=")
    name, task, trigger, result, timeout, properties = raw[5:].split("|")
    props = {}
    for pair in properties.split(","):
        if pair:
            key, _, value = pair.partition("=")
            props[key] = value
    steps.append({
        "name": name,
        "spec": f"{task}:{version}@standard" if task != "core-owned-moc" else f"{task}:{version}",
        "trigger": trigger,
        "result": result,
        "timeoutSeconds": int(timeout),
        "triggerProperties": props,
    })
plan = {
    "format": "turboism.validation.session-plan",
    "schemaVersion": 1,
    "session": "standard",
    "hostVersion": version,
    "startupTimeoutSeconds": 600,
    "steps": steps,
}
with open(target, "w", encoding="utf-8") as stream:
    stream.write(json.dumps(plan, indent=1) + "\n")
PY

missing=0
for spec in "${plugins[@]}"; do
  jar_path="${spec%%:*}"
  if [ ! -f "$jar_path" ]; then
    echo "error: probe jar missing: $jar_path" >&2
    missing=1
  fi
done
# The owned-MOC step reads its pinned fixture from inside the probe jar; a jar
# built without TURBOISM_VALIDATION_MOC_FIXTURE would fail that step on-host.
if [ "$missing" = 0 ] \
    && ! jar tf "$repo_root/build/mcp-host-validation-probe.jar" | grep -Fq 'validation/moc/Haru.moc3'; then
  echo "error: mcp-host-validation-probe.jar lacks validation/moc/Haru.moc3;" >&2
  echo "error: rebuild with TURBOISM_VALIDATION_MOC_FIXTURE pointing at the pinned fixture" >&2
  missing=1
fi
if [ "$missing" = 1 ]; then
  echo "error: build the probe jars first (validation/*/build.sh, :plugins:clipmask-viewer:jar)" >&2
  exit 1
fi

plugin_args=()
for spec in "${plugins[@]}"; do
  plugin_args+=(--plugin "$spec")
done

# Every -assisted variant across the manifest pins the combined Mesa/deferred
# option off itself; the session's pinned-off baseline is appended only for
# names the forwarded args do not declare, so a declaring variant cannot
# collide with the default at the runner's duplicate-name validation.
# conditional-runner-args: mesa_glthread turboism.optimization.mesaGlThread
mesa_env_declared=0
mesa_jvm_declared=0
option_name=''
for arg in "$@"; do
  case "$option_name" in
    --linux-env) [[ "$arg" == mesa_glthread=* ]] && mesa_env_declared=1 ;;
    --jvm-option) [[ "$arg" == -Dturboism.optimization.mesaGlThread=* ]] && mesa_jvm_declared=1 ;;
  esac
  option_name="$arg"
done
session_mesa_defaults=()
[ "$mesa_jvm_declared" = 1 ] || session_mesa_defaults+=(--jvm-option '-Dturboism.optimization.mesaGlThread=false')
[ "$mesa_env_declared" = 1 ] || session_mesa_defaults+=(--linux-env 'mesa_glthread=false')

exec bash "$runner" \
  --name session-standard --version "$version" --run-label "$run_label" \
  --bundle-root "$bundle_root" --agent "$bundle_root/turboism-agent.jar" \
  "${plugin_args[@]}" \
  --home-file "$plan_file:state/session/plan.json" \
  --fixture-host "$fixture_src" --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.session=true' \
  --jvm-option '-Dturboism.validation.textures.runId={TASK_ID}' \
  --jvm-option "-Dturboism.validation.textures.expectedVersion=$version" \
  --jvm-option '-Dturboism.validation.ownedMocOnly=true' \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --jvm-option "-Dturboism.validation.expectedCoreVersion=$core_tuple" \
  --jvm-option '-Dturboism.validation.fixture={FIXTURE}' \
  --jvm-option '-Dturboism.validation.separateSavePath.expectEnabled=false' \
  "${session_mesa_defaults[@]}" \
  --ready-marker 'SESSION_BEGIN' \
  --result-file 'state/session/session-result.properties' \
  --result-pass-line 'status=PASS' --result-fail-line 'status=FAIL' \
  --result-timeout "$result_timeout" --exit-timeout 240 \
  "$@"
