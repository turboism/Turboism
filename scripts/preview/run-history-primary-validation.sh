#!/usr/bin/env bash
# Hook-free semantic-history capability wrapper for the generic exact-host Runner.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-history-primary-validation.sh <5203|5302|5303> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
run_label='semantic-history-primary-r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-history-panel-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
local_evidence="$repo_root/build/host-validation/semantic-history-primary/$version"

required=(
  "$bundle_root/turboism-agent.jar"
  "$bundle_root/config.json"
  "$bundle_root/plugins/history-panel.jar"
  "$bundle_root/plugins/history-seed-validation-probe.jar"
)
for artifact in "${required[@]}"; do
  [ -f "$artifact" ] || {
    printf 'error: history primary bundle artifact missing: %s\n' "$artifact" >&2
    exit 1
  }
done

exec bash "$runner" \
  --name semantic-history-primary \
  --transport "${TURBOISM_HOST_VALIDATION_TRANSPORT:-local}" \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --home-config "$bundle_root/config.json" \
  --plugin "$bundle_root/plugins/history-panel.jar:history-panel.jar" \
  --plugin "$bundle_root/plugins/history-seed-validation-probe.jar:history-seed-validation-probe.jar" \
  --fixture-host "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --ready-marker 'History seed validation probe initialized' \
  --ready-marker 'Plugin load complete' \
  --result-file 'data/dev.turboism.validation.history-seed/history-seed.jsonl' \
  --result-pass-line '{"type":"summary","status":"PASS"}' \
  --result-fail-line '{"type":"summary","status":"FAIL"}' \
  --ready-timeout 300 \
  --result-timeout 600 \
  --exit-timeout 300 \
  --local-evidence-dir "$local_evidence" \
  "$@"
