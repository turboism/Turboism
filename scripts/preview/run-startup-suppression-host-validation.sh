#!/usr/bin/env bash
# Thin wrapper for exact-host validation of Turboism startup suppression
# (skip splash / skip update check / skip information) on Cubism 5.3.02 and
# 5.2.03. Uses the generic runner only; never launches Cubism's java.exe.
#
# Usage:
#   run-startup-suppression-host-validation.sh <5302|5203> [run-label] [generic-runner-options...]
set -euo pipefail

version="${1:?usage: run-startup-suppression-host-validation.sh <5302|5203> [run-label] [options...]}"
run_label="${2:-r1}"
shift 2 2>/dev/null || shift 1 2>/dev/null || true

case "$version" in
  5302)
    fixture_src='<local-home>/Documents/测试 混合模式.cmo3'
    fixture_sha256='57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4'
    ;;
  5203)
    fixture_src='<local-home>/TurboismPartValidation/part52-official/part-opacity-fixture-52-final.cmo3'
    fixture_sha256='331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028'
    ;;
  *)
    echo "error: version must be 5302 or 5203" >&2
    exit 2
    ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
probe_jar="$repo_root/build/startup-suppression-host-validation-exerciser.jar"
home_config="$repo_root/validation/startup-suppression-host-probe/home-config.json"
bundle_root="$repo_root/build/manual-test/$worktree_id/startup-suppression-validation"

for required in "$agent_jar" "$probe_jar" "$home_config"; do
  if [ ! -f "$required" ]; then
    echo "error: missing required artifact: $required" >&2
    exit 2
  fi
done

exec bash "$repo_root/scripts/preview/run-cubism-host-validation.sh" \
  --name startup-suppression \
  --version "$version" \
  --bundle-root "$bundle_root" \
  --agent "$agent_jar" \
  --home-config "$home_config" \
  --plugin "$probe_jar:startup-suppression-host-validation-exerciser.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --ready-marker 'STARTUP_SUPPRESSION_VALIDATION_READY' \
  --failure-marker 'STARTUP_SUPPRESSION_VALIDATION_RESULT status=FAIL' \
  --result-file 'state/dev.turboism.validation.startup/startup-suppression-result.properties' \
  --result-pass-line 'terminal=PASS' \
  --result-fail-line 'terminal=FAIL' \
  --run-label "$run_label" \
  "$@"
