#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 17 -d "$out/classes" \
  "$repo_root/validation/settings-page-probe/src/dev/turboism/validation/settingspage/TaskConfigFixture.java" \
  "$repo_root/validation/settings-page-probe/selfcheck/dev/turboism/validation/settingspage/TaskConfigFixtureSelfCheck.java"
java -cp "$out/classes" dev.turboism.validation.settingspage.TaskConfigFixtureSelfCheck "$out/fixtures"
