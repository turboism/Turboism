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

# Build output must be isolated to build/worktree/<id>/<gradle-project-name>
BUILD_DIR="${REPO_ROOT}/build/worktree/${WT_ID}"
[ -d "${BUILD_DIR}" ] || fail "build directory should be under build/worktree/${WT_ID}"

# Shared latest aliases must not exist anywhere in repository-managed outputs.
while IFS= read -r forbidden; do
  fail "shared latest alias must not exist: ${forbidden}"
done < <(
  find "${REPO_ROOT}" \
    -path "${REPO_ROOT}/.git" -prune -o \
    -type f \( -name '*latest*.jar' -o -name 'turboism-agent-latest.jar' -o -name 'turboism-sdk-latest.jar' \) \
    -print
)

# User production directories must not be written.
for forbidden in "${HOME}/.turboism"; do
  [ ! -e "${forbidden}" ] || fail "must not write user production directory: ${forbidden}"
done

# Worktree artifact naming must include the worktree ID for every Gradle project,
# including official plugin subprojects added after M1.
checked=0
while IFS= read -r libs_dir; do
  shopt -s nullglob
  jars=("${libs_dir}"/*.jar)
  shopt -u nullglob

  [ ${#jars[@]} -gt 0 ] || continue

  rel="${libs_dir#"${BUILD_DIR}/"}"
  project="${rel%/libs}"
  for jar in "${jars[@]}"; do
    name=$(basename -- "${jar}")
    if [[ "${name}" != *"-${WT_ID}.jar" ]]; then
      fail "artifact ${project}/libs/${name} does not include worktree ID ${WT_ID}"
    fi
  done
  checked=$((checked + 1))
  echo "PASS: ${project} artifact includes worktree ID"
done < <(find "${BUILD_DIR}" -mindepth 2 -maxdepth 2 -type d -name libs | sort)

[ "${checked}" -gt 0 ] || fail "no jar artifact directories found under ${BUILD_DIR}"

echo "PASS: worktree artifact boundary (${checked} project artifact directories checked)"
