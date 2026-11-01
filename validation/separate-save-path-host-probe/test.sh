#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
bash validation/separate-save-path-host-probe/build.sh
worktree_id="$(bash scripts/dev/worktree-id.sh)"
shopt -s nullglob
sdk_jars=("build/worktree/$worktree_id/sdk/libs/"sdk-*.jar)
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 17 -cp "${sdk_jars[0]}:build/separate-save-path-host-validation-probe.jar" -d "$out" \
  validation/separate-save-path-host-probe/test/dev/turboism/validation/separatesavepath/SeparateSavePathProbeTest.java
java -cp "$out:${sdk_jars[0]}:build/separate-save-path-host-validation-probe.jar" \
  dev.turboism.validation.separatesavepath.SeparateSavePathProbeTest "$out/fixtures" \
  validation/separate-save-path-host-probe/fixtures/save-directory-history.properties
