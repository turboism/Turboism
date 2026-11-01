#!/usr/bin/env bash
set -euo pipefail

# Offline contract check for the shared probe terminal rule. Compiles the shared assertion
# recorder with its self check and runs it; no Cubism host, SDK jar or network is involved.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

base=validation/shared
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -d "$out" \
  "$base/src/dev/turboism/validation/shared/verdict/ProbeTerminal.java" \
  "$base/src/dev/turboism/validation/shared/verdict/ProbeAssertions.java" \
  "$base/test/dev/turboism/validation/shared/verdict/ProbeTerminalSelfCheck.java"
java -cp "$out" dev.turboism.validation.shared.verdict.ProbeTerminalSelfCheck
