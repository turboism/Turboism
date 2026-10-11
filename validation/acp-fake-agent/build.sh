#!/usr/bin/env bash
set -euo pipefail

# Builds the test-only scripted ACP fake agent used by the `acp` exact-host
# validation capability. The jar is self-contained: the fake agent sources are
# compiled against the SDK jar and the SDK JSON classes are merged in, because
# the product launches it with a single-jar classpath on the Cubism bundled JRE
# (java.base + java.net.http only; no jdk.compiler).
#
#   build.sh           build build/acp-fake-agent.jar
#   build.sh selftest  build, then run the subprocess selftest
#
# The artifact is test-only and never ships in release packaging.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

action="build"
if [ "${1:-}" = "selftest" ]; then
  action="selftest"
fi

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
sdk_jar="$(find "build/worktree/$worktree_id/sdk/libs" -maxdepth 1 -name 'sdk-*.jar' -type f | head -1)"
if [ -z "$sdk_jar" ] || [ ! -f "$sdk_jar" ]; then
  echo "error: sdk jar not found; run :sdk:jar first" >&2
  exit 1
fi
sdk_jar="$repo_root/$sdk_jar"

base="validation/acp-fake-agent"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -cp "$sdk_jar" -d "$out" \
  "$base/src/acp.java" \
  "$base/src/acpagent/"*.java

# Merge the SDK JSON classes so the deployed jar satisfies the product's
# single-classpath launch (`-cp <bridgeClassPath> acp`).
(cd "$out" && jar xf "$sdk_jar" dev/turboism/sdk/json)

output="$repo_root/build/acp-fake-agent.jar"
jar cf "$output" -C "$out" .
if ! jar tf "$output" | grep -Fq 'acpagent/FakeAgent.class'; then
  echo "error: fake agent jar is missing acpagent/FakeAgent.class" >&2
  exit 1
fi
if ! jar tf "$output" | grep -Fq 'dev/turboism/sdk/json/Json.class'; then
  echo "error: fake agent jar is missing the merged SDK JSON classes" >&2
  exit 1
fi
echo "[fake-agent] $output"
sha256sum "$output"

if [ "$action" = "selftest" ]; then
  mcp_jar="$(find "build/worktree/$worktree_id/mcp/libs" -maxdepth 1 -name 'mcp-*.jar' -type f | head -1)"
  if [ -z "$mcp_jar" ] || [ ! -f "$mcp_jar" ]; then
    echo "error: mcp plugin jar not found; run :plugins:mcp:jar first" >&2
    exit 1
  fi
  mcp_jar="$repo_root/$mcp_jar"
  java_bin="$(dirname "$(readlink -f "$(command -v java)")")/java"
  python3 "$base/selftest/run_selftest.py" "$output" "$mcp_jar" "$java_bin"
fi
