#!/usr/bin/env bash
set -euo pipefail

# Assembles the task-local edit-protocol host validation bundle:
# turboism-agent.jar (with the 36-method edit API bridge), the probe
# plugin jar, the external stdlib WebSocket client, a README, and
# SHA256SUMS. Nothing here is part of the production preview bundle or
# product build.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
./gradlew previewBundle --console=plain

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-edit-protocol-validation}"
preview_root="$repo_root/build/preview/$worktree_id"

if [ ! -f "$preview_root/turboism-agent.jar" ]; then
  printf 'error: preview agent not found: %s\n' "$preview_root/turboism-agent.jar" >&2
  printf 'run: ./gradlew previewBundle\n' >&2
  exit 1
fi
probe_jar="$repo_root/build/edit-protocol-host-validation-probe.jar"
if [ ! -f "$probe_jar" ]; then
  printf 'error: probe jar not found: %s\n' "$probe_jar" >&2
  printf 'run: bash validation/protocol-edit-compat-host-probe/build.sh\n' >&2
  exit 1
fi
client_script="$repo_root/validation/protocol-edit-compat-host-probe/client/protocol_edit_host_probe.py"
if [ ! -f "$client_script" ]; then
  printf 'error: client script not found: %s\n' "$client_script" >&2
  exit 1
fi

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/client" "$bundle_root/logs" "$bundle_root/state"
cp "$preview_root/turboism-agent.jar" "$bundle_root/turboism-agent.jar"
cp "$probe_jar" "$bundle_root/plugins/edit-protocol-host-validation-probe.jar"
cp "$client_script" "$bundle_root/client/protocol-edit-host-probe.py"
printf '# Edit Protocol Host Validation\n\nTask-local bundle for the exact-host validation of the Cubism 5.4-compatible external edit API protocol bridge (spec 050 T3) on Cubism 5.2.03/5.3.02/5.3.03.\n' > "$bundle_root/README.md"

(
  cd "$bundle_root"
  sha256sum \
    turboism-agent.jar \
    plugins/edit-protocol-host-validation-probe.jar \
    client/protocol-edit-host-probe.py \
    README.md > SHA256SUMS.txt
)

printf '[package] Windows edit-protocol validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
