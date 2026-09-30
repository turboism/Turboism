#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
sdk_jar="$(find "build/worktree/$worktree_id/sdk/libs" -maxdepth 1 -name 'sdk-*.jar' -type f | head -1)"
[ -f "$sdk_jar" ] && [ -f build/mcp-host-validation-probe.jar ] || { echo 'Build SDK and probe first' >&2; exit 1; }
output="$(mktemp -d)"
trap 'rm -rf "$output"' EXIT
classpath="build/mcp-host-validation-probe.jar:$sdk_jar"
javac --release 17 -cp "$classpath" -d "$output" validation/mcp-host-probe/test/CoreOwnedMocStartupFailureSelfTest.java
java -cp "$output:$classpath" CoreOwnedMocStartupFailureSelfTest
