#!/usr/bin/env bash
# Edit-protocol adapter for the generic exact-host runner.
#
# An external Python stdlib WebSocket client replays the official Cubism 5.4
# external-application edit API wire sequence against the host's own loopback
# service on port 22033: RegisterPlugin -> token persistence -> approval
# polling -> EditBegin -> edit operations -> EditEnd, covering S1-S6 plus a
# native 1.0.x regression stage. The in-host probe plugin enables the service
# via the verified EXTERNAL_APP_SETTING typed command, pre-seeds the client
# token into CExternalAppAuthManager and answers the Turboism edit-approval
# dialog under the approval.mode kill-switch file.
#
# The runner records terminal PASS/FAIL from the result properties file.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-edit-protocol-host-validation.sh <5203|5302|5303> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
client_python="$(python3 -I -c 'import pathlib, sys; print(pathlib.Path(sys.executable).resolve())')"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-edit-protocol-validation"
probe_jar="$repo_root/build/edit-protocol-host-validation-probe.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

exec bash "$runner" \
  --name edit-protocol \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$probe_jar:edit-protocol-host-validation-probe.jar" \
  --client-script "$repo_root/validation/protocol-edit-compat-host-probe/client/protocol_edit_host_probe.py:protocol-edit-host-probe.py" \
  --client-python "$client_python" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --jvm-option '-Dturboism.validation.hostVersion='$version \
  --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
  --jvm-option '-Dturboism.editProtocol.validation.enabled=true' \
  --ready-marker 'Plugin load complete' \
  --ready-marker 'EDIT_PROTOCOL_PROBE_READY' \
  --result-file 'state/edit-protocol-host-validation-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'EDIT_PROTOCOL_SERVICE_TIMEOUT' \
  --failure-marker 'EDIT_PROTOCOL_PROBE_DISABLED' \
  --ready-timeout 360 \
  --result-timeout 1200 \
  --exit-timeout 240 \
  "$@"
