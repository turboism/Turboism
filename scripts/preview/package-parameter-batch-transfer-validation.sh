#!/usr/bin/env bash
set -euo pipefail

# Assembles the task-local parameter-batch-transfer host validation bundle:
# turboism-agent.jar (with the visibleWhen runtime change), the production
# parameter-batch-transfer plugin jar, the probe exerciser jar, a README, and
# SHA256SUMS. Nothing here is part of the production preview bundle or product
# build.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
./gradlew previewBundle :plugins:parameter-batch-transfer:jar --console=plain

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-parameter-batch-transfer-validation}"
preview_root="$repo_root/build/preview/$worktree_id"
pbt_jars=("$repo_root"/build/worktree/"$worktree_id"/parameter-batch-transfer/libs/parameter-batch-transfer-*.jar)

if [ ! -f "$preview_root/turboism-agent.jar" ]; then
  printf 'error: preview agent not found: %s\n' "$preview_root/turboism-agent.jar" >&2
  printf 'run: ./gradlew previewBundle\n' >&2
  exit 1
fi
if [ "${#pbt_jars[@]}" -eq 0 ] || [ ! -f "${pbt_jars[0]}" ]; then
  printf 'error: parameter-batch-transfer plugin jar not found under build/worktree/%s/parameter-batch-transfer/libs/\n' "$worktree_id" >&2
  printf 'run: ./gradlew :plugins:parameter-batch-transfer:jar\n' >&2
  exit 1
fi
probe_jar="$repo_root/build/parameter-batch-transfer-host-validation-probe.jar"
if [ ! -f "$probe_jar" ]; then
  printf 'error: probe jar not found: %s\n' "$probe_jar" >&2
  printf 'run: bash validation/parameter-batch-transfer-host-probe/build.sh\n' >&2
  exit 1
fi

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/logs" "$bundle_root/state"
cp "$preview_root/turboism-agent.jar" "$bundle_root/turboism-agent.jar"
# Pick the newest jar; stale snapshots from previous builds may coexist.
pbt_jar="$(ls -t "${pbt_jars[@]}" 2>/dev/null | head -1)"
cp "$pbt_jar" "$bundle_root/plugins/parameter-batch-transfer.jar"
cp "$probe_jar" "$bundle_root/plugins/parameter-batch-transfer-host-validation-probe.jar"
printf '# Parameter Batch Transfer Host Validation\n\nTask-local bundle for the exact-host validation of the parameter-batch-transfer plugin.\n' > "$bundle_root/README.md"

(
  cd "$bundle_root"
  sha256sum \
    turboism-agent.jar \
    plugins/parameter-batch-transfer.jar \
    plugins/parameter-batch-transfer-host-validation-probe.jar \
    README.md > SHA256SUMS.txt
)

printf '[package] Windows parameter-batch-transfer validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
