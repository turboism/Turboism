#!/usr/bin/env bash
# Runs shellcheck over every tracked *.sh file at error severity only.
# Warnings stay advisory; the gate blocks on real error-class findings.
# A missing shellcheck binary fails the gate: an unavailable linter cannot prove
# the shell scripts are error-clean, so skipping would be a fail-open pass.
# Install it with `apt-get install shellcheck` (Debian/Ubuntu) or
# `brew install shellcheck` (macOS); CI installs it via apt.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SHELLCHECK_BIN="${SHELLCHECK_BIN:-shellcheck}"
if ! command -v "$SHELLCHECK_BIN" >/dev/null 2>&1; then
  echo "error: shellcheck ('${SHELLCHECK_BIN}') is required by checkShellLint but was not found on PATH." >&2
  echo "error: install it (apt-get install shellcheck / brew install shellcheck) or set SHELLCHECK_BIN to its path." >&2
  exit 1
fi

mapfile -d '' -t files < <(git ls-files -z '*.sh')
if [ "${#files[@]}" -eq 0 ]; then
  echo "error: no tracked shell scripts found; run this gate from the repository root." >&2
  exit 1
fi

"$SHELLCHECK_BIN" -S error -- "${files[@]}"
echo "OK: shellcheck -S error clean over ${#files[@]} script(s)."
