#!/usr/bin/env bash
# Semantic-history adapter for the generic exact-host runner (official BAT only).
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-history-baseline-validation.sh <5203|5302> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
run_label='semantic-history-r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-history-panel-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
local_evidence="$repo_root/build/host-validation/semantic-history/$version"
evidence_hook="$repo_root/scripts/preview/collect-history-validation-evidence.sh"

required=(
  "$bundle_root/turboism-agent.jar"
  "$bundle_root/config.json"
  "$bundle_root/plugins/history-panel.jar"
  "$bundle_root/plugins/history-seed-validation-probe.jar"
  "$bundle_root/plugins/history-validation-probe.jar"
  "$evidence_hook"
)
for artifact in "${required[@]}"; do
  [ -f "$artifact" ] || {
    printf 'error: history validation bundle artifact missing: %s\n' "$artifact" >&2
    exit 1
  }
done

exec bash "$runner" \
  --name semantic-history \
  --execution-mode "${TURBOISM_HOST_VALIDATION_EXECUTION_MODE:-local}" \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --home-config "$bundle_root/config.json" \
  --plugin "$bundle_root/plugins/history-panel.jar:history-panel.jar" \
  --plugin "$bundle_root/plugins/history-seed-validation-probe.jar:history-seed-validation-probe.jar" \
  --plugin "$bundle_root/plugins/history-validation-probe.jar:history-validation-probe.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --ready-marker 'History seed validation probe initialized' \
  --ready-marker 'Read-only history manager validation probe initialized' \
  --ready-marker 'Plugin load complete' \
  --remote-pre-cleanup "$evidence_hook" \
  --result-file 'data/dev.turboism.validation.history-seed/history-seed.jsonl' \
  --result-pass-line '{"type":"summary","status":"PASS"}' \
  --result-fail-line '{"type":"summary","status":"FAIL"}' \
  --ready-timeout 300 \
  --result-timeout 600 \
  --exit-timeout 300 \
  --local-evidence-dir "$local_evidence" \
  "$@"
