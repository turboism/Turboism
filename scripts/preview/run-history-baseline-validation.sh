#!/usr/bin/env bash
# Thin wrapper: launch Cubism 5.3.02 with the production history-panel
# plugin plus the test-only history probes, via the generic host-validation
# runner (official BAT only). Probes live outside the formal bundle.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="research-cubism-history-manager-20260801"
bundle="$repo_root/build/manual-test/$worktree_id/windows-history-baseline"
run_label="${1:-history-baseline-r1}"
extra_flags=()
if [ "${1:-}" = "--dry-run" ]; then
  run_label="history-baseline-r1"
  extra_flags=(--dry-run)
fi
local_evidence="$repo_root/build/host-validation/history-baseline/5302"

[ -f "$bundle/turboism-agent.jar" ] || { echo "bundle missing: $bundle" >&2; exit 1; }

args=(--name history-baseline --version 5302
  --bundle-root "$bundle"
  --agent "$bundle/turboism-agent.jar"
  --home-config "$bundle/home-config.json"
  --fixture-remote /home/local-user/TurboismValidation/tab-topbar-regression-20260729-151717/5.3.02/model/fixture.cmo3
  --fixture-sha256 57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4
  --require-fixture-unchanged
  --ready-marker "Plugin load complete"
  --result-file logs/dev.turboism.validation.history-float/history-float.txt
  --run-label "$run_label"
  --local-evidence-dir "$local_evidence"
  --result-timeout 420
  --agent-host-class com.live2d.cubism.view.CEMainFrameCtrl
)
for plugin in "$bundle"/plugins/*.jar; do
  args+=(--plugin "$plugin")
done
# Test-only validation probes are injected at run time; they are not part
# of the formal bundle (never shipped with production plugins).
for probe in "$repo_root/build/manual-test/$worktree_id/validation-probes"/*.jar; do
  args+=(--plugin "$probe")
done
exec bash scripts/preview/run-cubism-host-validation.sh "${args[@]}" "${extra_flags[@]}"
