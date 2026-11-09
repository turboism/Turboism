#!/usr/bin/env bash
# Exact-host wrapper for the Auto Mesh framework validation probe.
#
# The probe drives the real native Auto Mesh Generate funnel, so the run needs a real
# Editor. Fixture paths come from the ignored repository `.env`, exactly like the other
# wrappers; the private model is never committed.
set -euo pipefail

# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-automesh-framework-host-validation.sh $(turboism_task_versions_usage automesh) [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
turboism_require_task_version automesh "$version" || exit 2
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle="$repo_root/build/manual-test/$worktree_id/automesh-framework-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
evidence_hook="$repo_root/scripts/preview/collect-automesh-dump.sh"
turboism_select_fixture "$version" || exit 2
fixture="$fixture_src"

[ -f "$bundle/turboism-agent.jar" ] || { echo "bundle missing: $bundle" >&2; exit 1; }
[ -f "$bundle/plugins/turbo-mesh.jar" ] || { echo "turbo-mesh plugin missing: $bundle" >&2; exit 1; }
[ -f "$bundle/plugins/automesh-framework-validation-probe.jar" ] \
  || { echo "auto mesh probe missing: $bundle" >&2; exit 1; }
[ -f "$evidence_hook" ] || { echo "evidence hook missing: $evidence_hook" >&2; exit 1; }

# The dump lives inside the task home so the runner collects it with the rest of the
# evidence. The probe writes it through {HOME}, and the offline renderer reads the copy
# under the collected evidence directory.
mesh_ids="${TURBOISM_AUTOMESH_MESH_IDS:-ArtMesh342,hfa10}"
multi_keyform_ids="${TURBOISM_AUTOMESH_MULTI_KEYFORM_IDS:-}"

exec bash "$runner" \
  --name automesh \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle" \
  --agent "$bundle/turboism-agent.jar" \
  --home-config "$bundle/home-config.json" \
  --plugin "$bundle/plugins/turbo-mesh.jar:turbo-mesh.jar" \
  --plugin "$bundle/plugins/automesh-framework-validation-probe.jar:automesh-framework-validation-probe.jar" \
  --fixture-remote "$fixture" \
  --fixture-sha256 "$fixture_sha256" \
  --jvm-option "-Dturboism.validation.hostVersion=$version" \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --jvm-option "-Dturboism.automeshValidation.meshIds=$mesh_ids" \
  --jvm-option "-Dturboism.automeshValidation.multiKeyformMeshIds=$multi_keyform_ids" \
  --jvm-option '-Dturboism.automeshValidation.dumpDir={HOME}\automesh-dump' \
  --jvm-option "-Dturboism.automeshValidation.customSettleTimeoutSeconds=${TURBOISM_AUTOMESH_SETTLE_SECONDS:-30}" \
  --remote-pre-cleanup "$evidence_hook" \
  --ready-marker 'AUTOMESH_FRAMEWORK_PROBE_READY' \
  --ready-marker 'Plugin load complete' \
  --result-file 'state/dev.turboism.validation.automesh-framework/automesh-framework-validation.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --result-fail-line 'status=BLOCKED' \
  --result-fail-line 'status=SKIPPED' \
  --require-assertions \
  --failure-marker 'AUTOMESH_FRAMEWORK_RESULT status=FAIL' \
  --ready-timeout 300 \
  --result-timeout 1800 \
  --exit-timeout 120 \
  "$@"
