#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
if git_root="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel 2>/dev/null)"; then
  REPO_ROOT="${git_root}"
else
  REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd -P)"
fi

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

WT_ID="$(${REPO_ROOT}/scripts/dev/worktree-id.sh)"

# Build output must be isolated to build/worktree/<id>/<module>
BUILD_DIR="${REPO_ROOT}/build/worktree/${WT_ID}"
[ -d "${BUILD_DIR}" ] || fail "build directory should be under build/worktree/${WT_ID}"

# Shared latest aliases must not exist
for forbidden in "${REPO_ROOT}/turboism-agent-latest.jar" "${REPO_ROOT}/turboism-sdk-latest.jar"; do
  [ ! -e "${forbidden}" ] || fail "shared latest alias must not exist: ${forbidden}"
done

# User production directories must not be written
for forbidden in "${HOME}/.turboism"; do
  [ ! -e "${forbidden}" ] || fail "must not write user production directory: ${forbidden}"
done

# Worktree artifact naming must include the worktree ID
for module in sdk runtime demo testframework tests; do
  artifact="${BUILD_DIR}/${module}/libs"
  [ -d "${artifact}" ] || continue

  shopt -s nullglob
  jars=("${artifact}"/*.jar)
  shopt -u nullglob

  [ ${#jars[@]} -gt 0 ] || continue

  for jar in "${jars[@]}"; do
    name=$(basename -- "${jar}")
    if [[ "${name}" != *"-${WT_ID}.jar" ]]; then
      fail "artifact ${module}/build/libs/${name} does not include worktree ID ${WT_ID}"
    fi
  done
  echo "PASS: ${module} artifact includes worktree ID"
done

echo "PASS: worktree artifact boundary"
