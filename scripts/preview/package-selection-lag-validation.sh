#!/usr/bin/env bash
set -euo pipefail

# Assembles the task-local selection-lag diagnostic bundle:
# turboism-agent.jar, representative production plugin jars (parameter,
# context-menu), the probe JAR, a README, and SHA256SUMS. Nothing here is part
# of the production preview bundle or product build.
#
# Note: :plugins:core is intentionally NOT staged here: its plugin id
# "turboism.core" is a Runtime-reserved identity and the loader rejects it as
# PLUGIN_RESERVED_ID. The runtime already owns the built-in turboism.core.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/selection-lag-validation}"
preview_root="$repo_root/build/preview/$worktree_id"

plugin_specs=(
  "parameter:parameter"
  "context-menu:context-menu"
)

if [ ! -f "$preview_root/turboism-agent.jar" ]; then
  printf 'error: preview agent not found: %s\n' "$preview_root/turboism-agent.jar" >&2
  printf 'run: ./gradlew previewBundle\n' >&2
  exit 1
fi
for spec in "${plugin_specs[@]}"; do
  module="${spec%%:*}"
  name="${spec#*:}"
  jars=("$repo_root"/build/worktree/"$worktree_id"/"$module"/libs/"$name"-*.jar)
  if [ "${#jars[@]}" -eq 0 ] || [ ! -f "${jars[0]}" ]; then
    printf 'error: %s plugin jar not found under build/worktree/%s/%s/libs/\n' \
      "$name" "$worktree_id" "$module" >&2
    printf 'run: ./gradlew :plugins:%s:jar\n' "$module" >&2
    exit 1
  fi
done
probe_jar="$repo_root/build/selection-lag-probe.jar"
if [ ! -f "$probe_jar" ]; then
  printf 'error: selection-lag probe jar not found: %s\n' "$probe_jar" >&2
  printf 'run: bash validation/selection-lag-probe/build.sh\n' >&2
  exit 1
fi

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/logs" "$bundle_root/state"
cp "$preview_root/turboism-agent.jar" "$bundle_root/turboism-agent.jar"
for spec in "${plugin_specs[@]}"; do
  module="${spec%%:*}"
  name="${spec#*:}"
  jars=("$repo_root"/build/worktree/"$worktree_id"/"$module"/libs/"$name"-*.jar)
  cp "${jars[0]}" "$bundle_root/plugins/$name.jar"
done
cp "$probe_jar" "$bundle_root/plugins/selection-lag-probe.jar"
cp "$repo_root/validation/selection-lag-probe/README.md" "$bundle_root/README.md"

(
  cd "$bundle_root"
  sha256sum \
    turboism-agent.jar \
    plugins/parameter.jar \
    plugins/context-menu.jar \
    plugins/selection-lag-probe.jar \
    README.md > SHA256SUMS.txt
)

printf '[package] selection-lag validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
