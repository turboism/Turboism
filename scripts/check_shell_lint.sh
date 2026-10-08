#!/usr/bin/env bash
# Runs shellcheck over every tracked *.sh file at error severity only.
# Warnings stay advisory; the gate blocks on real error-class findings.
# Without a shellcheck binary the check skips (CI installs it via apt).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SHELLCHECK_BIN="${SHELLCHECK_BIN:-shellcheck}"
if ! command -v "$SHELLCHECK_BIN" >/dev/null 2>&1; then
  echo "shellcheck not found on PATH; skipping shell lint gate." >&2
  exit 0
fi

mapfile -t files < <(git ls-files '*.sh')
if [ "${#files[@]}" -eq 0 ]; then
  echo "no tracked shell scripts found." >&2
  exit 0
fi

"$SHELLCHECK_BIN" -S error -- "${files[@]}"
echo "OK: shellcheck -S error clean over ${#files[@]} script(s)."
