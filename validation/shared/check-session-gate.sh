#!/usr/bin/env bash
set -euo pipefail

# Offline contract check for the shared trigger gate. Compiles SessionGate with its self
# check and runs it; no Cubism host, SDK jar or real timeout is involved.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

base=validation/shared
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -d "$out" \
  "$base/src/dev/turboism/validation/SessionGate.java" \
  "$base/test/dev/turboism/validation/SessionGateSelfCheck.java"
java -cp "$out" dev.turboism.validation.SessionGateSelfCheck
