#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="${TURBOISM_WORKTREE_ID:-$(bash "$repo_root/scripts/dev/worktree-id.sh")}"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-core-acquisition-validation}"
preview_agent="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
probe_source_dir="$repo_root/validation/core-acquisition-probe"
probe_builder="$probe_source_dir/build.sh"
probe_jar="$repo_root/build/core-acquisition-probe-agent.jar"

missing=0
if [ ! -f "$preview_agent" ]; then
  printf 'error: current-worktree preview agent not found: %s\n' "$preview_agent" >&2
  printf 'run: ./gradlew previewBundle\n' >&2
  missing=1
fi
if [ ! -d "$probe_source_dir" ] || [ ! -f "$probe_builder" ]; then
  printf 'error: Core acquisition probe source/build contract is missing: %s\n' "$probe_source_dir" >&2
  printf 'prerequisite: make validation/core-acquisition-probe available in this worktree\n' >&2
  printf 'run: bash validation/core-acquisition-probe/build.sh\n' >&2
  missing=1
fi
if [ ! -f "$probe_jar" ]; then
  printf 'error: Core acquisition probe agent not found: %s\n' "$probe_jar" >&2
  printf 'run: bash validation/core-acquisition-probe/build.sh\n' >&2
  missing=1
fi
[ "$missing" = 0 ] || exit 1

if [ -f "$probe_jar" ] && [ -d "$probe_source_dir" ]; then
  stale_probe_source="$(find "$probe_source_dir" -type f -newer "$probe_jar" -print -quit)"
  if [ -n "$stale_probe_source" ]; then
    printf 'error: Core acquisition probe agent is older than source: %s\n' "$stale_probe_source" >&2
    printf 'artifact: %s\n' "$probe_jar" >&2
    printf 'run: bash validation/core-acquisition-probe/build.sh\n' >&2
    exit 1
  fi
fi

command -v jar >/dev/null 2>&1 || {
  printf 'error: jar command is required to validate the probe agent\n' >&2
  exit 1
}
manifest_dir="$(mktemp -d)"
trap 'rm -rf "$manifest_dir"' EXIT
if ! (cd "$manifest_dir" && jar xf "$probe_jar" META-INF/MANIFEST.MF); then
  printf 'error: probe artifact is not a readable JAR: %s\n' "$probe_jar" >&2
  printf 'run: bash validation/core-acquisition-probe/build.sh\n' >&2
  exit 1
fi
if ! grep -Eiq '^Premain-Class:' "$manifest_dir/META-INF/MANIFEST.MF"; then
  printf 'error: probe artifact is not a Java agent with Premain-Class: %s\n' "$probe_jar" >&2
  printf 'run: bash validation/core-acquisition-probe/build.sh\n' >&2
  exit 1
fi

rm -rf "$bundle_root"
mkdir -p "$bundle_root/logs" "$bundle_root/state"
cp "$preview_agent" "$bundle_root/turboism-agent.jar"
cp "$probe_jar" "$bundle_root/core-acquisition-probe-agent.jar"
(
  cd "$bundle_root"
  sha256sum turboism-agent.jar core-acquisition-probe-agent.jar > SHA256SUMS.txt
)

printf '[package] Windows Core acquisition validation bundle: %s\n' "$bundle_root"
printf '  turboism-agent.jar (%s bytes)\n' "$(stat -c %s "$bundle_root/turboism-agent.jar")"
printf '  core-acquisition-probe-agent.jar (%s bytes)\n' "$(stat -c %s "$bundle_root/core-acquisition-probe-agent.jar")"
printf '  SHA256SUMS.txt\n'
printf '[package] prerequisites: ./gradlew previewBundle; bash validation/core-acquisition-probe/build.sh\n'
