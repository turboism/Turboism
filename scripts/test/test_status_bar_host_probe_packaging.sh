#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/validation/status-bar-host-probe/src" "$work/scripts/dev" "$work/build/worktree/test/sdk/libs"
cp "$repo_root/validation/status-bar-host-probe/build.sh" "$work/validation/status-bar-host-probe/build.sh"
printf '#!/bin/sh\nprintf "test\\n"\n' > "$work/scripts/dev/worktree-id.sh"
chmod +x "$work/scripts/dev/worktree-id.sh"
: > "$work/build/worktree/test/sdk/libs/sdk-test.jar"
if bash "$work/validation/status-bar-host-probe/build.sh" > "$work/output" 2>&1; then
  echo 'FAIL: missing declared catalog accepted' >&2
  exit 1
fi
grep -F 'declared probe i18n catalog is missing' "$work/output" >/dev/null
printf 'PASS: status-bar probe rejects missing i18n catalog before compilation\n'
