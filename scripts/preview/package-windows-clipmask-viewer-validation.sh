#!/usr/bin/env bash
set -euo pipefail

# Assembles the task-local clipmask-viewer host validation bundle:
# turboism-agent.jar, the production clipmask-viewer plugin jar, the probe
# exerciser jar, a README, and SHA256SUMS. Nothing here is part of the
# production preview bundle or product build.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-clipmask-viewer-validation}"
preview_root="$repo_root/build/preview/$worktree_id"
clipmask_jars=("$repo_root"/build/worktree/"$worktree_id"/clipmask-viewer/libs/clipmask-viewer-*.jar)

if [ ! -f "$preview_root/turboism-agent.jar" ]; then
  printf 'error: preview agent not found: %s\n' "$preview_root/turboism-agent.jar" >&2
  printf 'run: ./gradlew previewBundle\n' >&2
  exit 1
fi
if [ "${#clipmask_jars[@]}" -eq 0 ] || [ ! -f "${clipmask_jars[0]}" ]; then
  printf 'error: clipmask-viewer plugin jar not found under build/worktree/%s/clipmask-viewer/libs/\n' "$worktree_id" >&2
  printf 'run: ./gradlew :plugins:clipmask-viewer:jar\n' >&2
  exit 1
fi
probe_jar="$repo_root/build/clipmask-viewer-validation-exerciser.jar"
if [ ! -f "$probe_jar" ]; then
  printf 'error: clipmask-viewer probe jar not found: %s\n' "$probe_jar" >&2
  printf 'run: bash validation/clipmask-viewer-host-probe/build.sh\n' >&2
  exit 1
fi

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/logs" "$bundle_root/state"
cp "$preview_root/turboism-agent.jar" "$bundle_root/turboism-agent.jar"
cp "${clipmask_jars[0]}" "$bundle_root/plugins/clipmask-viewer.jar"
cp "$probe_jar" "$bundle_root/plugins/clipmask-viewer-validation-exerciser.jar"
cp "$repo_root/scripts/preview/README-clipmask-viewer-validation.md" "$bundle_root/README.md"

(
  cd "$bundle_root"
  sha256sum \
    turboism-agent.jar \
    plugins/clipmask-viewer.jar \
    plugins/clipmask-viewer-validation-exerciser.jar \
    README.md > SHA256SUMS.txt
)

printf '[package] Windows clipmask-viewer validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
