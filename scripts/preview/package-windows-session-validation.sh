#!/usr/bin/env bash
set -euo pipefail

# Assembles the task-local multi-probe session validation bundle:
# turboism-agent.jar, the session coordinator plugin, every group-S read-only
# probe jar (plus the clipmask-viewer production plugin the exerciser drives),
# a README, and SHA256SUMS. The session plan is generated per job by
# scripts/preview/run-cubism-host-validation-session.sh and staged through the
# runner's --home-file channel; nothing here is part of the production
# preview bundle or product build.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-session-validation}"
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
probe_jars=(
  session-coordinator.jar
  texture-relations-host-probe.jar
  psd-export-observation-session-probe.jar
  status-bar-host-validation-exerciser.jar
  clipmask-viewer-validation-exerciser.jar
  separate-save-path-host-validation-probe.jar
  mcp-host-validation-probe.jar
)
for jar_name in "${probe_jars[@]}"; do
  if [ ! -f "$repo_root/build/$jar_name" ]; then
    printf 'error: probe jar not found: %s\n' "$repo_root/build/$jar_name" >&2
    printf 'run: bash validation/session-coordinator/build.sh && bash validation/texture-relations-host-probe/build.sh\n'
    printf '     bash validation/texture-relations-host-probe/build.sh --export-observation-session\n'
    printf '     bash validation/status-bar-host-probe/build.sh && bash validation/clipmask-viewer-host-probe/build.sh\n'
    printf '     bash validation/separate-save-path-host-probe/build.sh\n'
    printf '     TURBOISM_VALIDATION_MOC_FIXTURE=<pinned Haru.moc3> bash validation/mcp-host-probe/build.sh\n' >&2
    exit 1
  fi
done
# The owned-MOC step reads its pinned fixture from inside the probe jar.
if ! jar tf "$repo_root/build/mcp-host-validation-probe.jar" | grep -Fq 'validation/moc/Haru.moc3'; then
  printf 'error: mcp-host-validation-probe.jar lacks validation/moc/Haru.moc3;\n' >&2
  printf 'error: rebuild with TURBOISM_VALIDATION_MOC_FIXTURE pointing at the pinned fixture\n' >&2
  exit 1
fi

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/logs" "$bundle_root/state"
cp "$preview_root/turboism-agent.jar" "$bundle_root/turboism-agent.jar"
cp "${clipmask_jars[0]}" "$bundle_root/plugins/clipmask-viewer.jar"
for jar_name in "${probe_jars[@]}"; do
  cp "$repo_root/build/$jar_name" "$bundle_root/plugins/$jar_name"
done
cp "$repo_root/scripts/preview/README-session-validation.md" "$bundle_root/README.md"

(
  cd "$bundle_root"
  sha256sum \
    turboism-agent.jar \
    plugins/clipmask-viewer.jar \
    "${probe_jars[@]/#/plugins/}" \
    README.md > SHA256SUMS.txt
)

printf '[package] Windows multi-probe session validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
