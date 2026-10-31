#!/usr/bin/env bash
# Reclaims one managed worktree and its host-validation artifacts.
#
# Safety contract:
#   - A dirty worktree is never deleted implicitly; the script fails and keeps it.
#   - A branch with commits missing from every merge target is never deleted
#     implicitly; the script fails and keeps it.
#   - --force is the only bypass for either refusal, and it is explicit,
#     per-invocation, and echoed before each destructive step.
#   - Host-artifact cleanup runs after the preconditions and before directory
#     removal, and fails closed.
#
# Both refusals are evaluated before any side effect, so a rejected run leaves
# the worktree, its branch, and its host artifacts untouched.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

usage() {
  cat >&2 <<'EOF'
usage: clean-worktree.sh <worktree-id> [--dry-run] [--force] [--merged-into <ref>]

  --dry-run            report the planned actions without changing anything
  --force              allow removing a dirty worktree and deleting an unmerged branch
  --merged-into <ref>  extra merge target consulted by the branch-deletion check
EOF
}

if [ "$#" -lt 1 ]; then
  usage
  exit 1
fi

WORKTREE_ID=""
DRY_RUN=0
FORCE=0
MERGED_INTO=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --force)
      FORCE=1
      shift
      ;;
    --merged-into)
      [ "$#" -ge 2 ] || { usage; exit 1; }
      MERGED_INTO="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "error: unknown option: $1" >&2
      usage
      exit 1
      ;;
    *)
      if [ -n "${WORKTREE_ID}" ]; then
        echo "error: exactly one worktree id is accepted" >&2
        usage
        exit 1
      fi
      WORKTREE_ID="$1"
      shift
      ;;
  esac
done

if [ -z "${WORKTREE_ID}" ]; then
  usage
  exit 1
fi

# Validate the ID against the shared worktree-id rules before it is ever used in
# a path: an unchecked ID such as '../../x' would otherwise escape the parent.
# shellcheck source=worktree-id-lib.sh
. "${SCRIPT_DIR}/worktree-id-lib.sh"
validate_id "${WORKTREE_ID}" || exit 1

WORKTREE_PARENT="${REPO_ROOT}/../turboism-worktrees"
WORKTREE_DIR="${WORKTREE_PARENT}/${WORKTREE_ID}"
BRANCH="worktree/${WORKTREE_ID}"

# Every git command below is repository-relative; never rely on the caller's cwd.
cd "${REPO_ROOT}"

# `git worktree list` reports physical paths, so compare against the resolved
# form; a lexical path containing '..' would never match a registered entry.
WORKTREE_DIR_REAL="${WORKTREE_DIR}"
if [ -d "${WORKTREE_DIR}" ]; then
  WORKTREE_DIR_REAL="$(cd "${WORKTREE_DIR}" && pwd -P)"
fi

if [ "${DRY_RUN}" -eq 1 ]; then
  echo "[DRY-RUN] Would remove worktree: ${WORKTREE_DIR}"
  echo "[DRY-RUN] Would delete branch: ${BRANCH}"
  if [ -x "${SCRIPT_DIR}/cleanup-host-artifacts.sh" ]; then
    "${SCRIPT_DIR}/cleanup-host-artifacts.sh" --worktree "${WORKTREE_ID}" --dry-run
  fi
  exit 0
fi

# ---------- Preconditions: evaluated before any side effect ----------
WORKTREE_EXISTS=0
WORKTREE_REGISTERED=0
if [ -e "${WORKTREE_DIR}" ]; then
  WORKTREE_EXISTS=1
  if git -C "${WORKTREE_DIR}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    DIRTY="$(git -C "${WORKTREE_DIR}" status --porcelain)"
    if [ -n "${DIRTY}" ]; then
      if [ "${FORCE}" -ne 1 ]; then
        echo "error: worktree ${WORKTREE_DIR} has uncommitted or untracked changes:" >&2
        printf '%s\n' "${DIRTY}" | sed 's/^/  /' >&2
        echo "error: refusing to remove it. Commit or stash the changes, or pass --force." >&2
        exit 1
      fi
      echo "warning: --force given; uncommitted changes in ${WORKTREE_DIR} will be discarded." >&2
    fi
  fi

  if git worktree list --porcelain | grep -Fxq "worktree ${WORKTREE_DIR_REAL}"; then
    WORKTREE_REGISTERED=1
  elif [ "${FORCE}" -ne 1 ]; then
    echo "error: ${WORKTREE_DIR} is not a registered worktree of ${REPO_ROOT}." >&2
    echo "error: refusing to delete an unmanaged directory without --force." >&2
    exit 1
  fi
fi

BRANCH_EXISTS=0
BRANCH_MERGE_TARGET=""
if git rev-parse --verify "${BRANCH}" >/dev/null 2>&1; then
  BRANCH_EXISTS=1
  MERGE_TARGETS=()
  [ -n "${MERGED_INTO}" ] && MERGE_TARGETS+=("${MERGED_INTO}")
  MERGE_TARGETS+=("HEAD" "main" "master" "origin/main" "origin/master" "origin/HEAD")
  for target in "${MERGE_TARGETS[@]}"; do
    git rev-parse --verify --quiet "${target}" >/dev/null 2>&1 || continue
    if git merge-base --is-ancestor "${BRANCH}" "${target}"; then
      BRANCH_MERGE_TARGET="${target}"
      break
    fi
  done
  if [ -z "${BRANCH_MERGE_TARGET}" ] && [ "${FORCE}" -ne 1 ]; then
    echo "error: branch ${BRANCH} has commits that are not merged into any of:" >&2
    printf '  %s\n' "${MERGE_TARGETS[@]}" >&2
    echo "error: refusing to delete it. Merge the branch first, or pass --force." >&2
    exit 1
  fi
fi

# ---------- Side effects ----------
# External host-validation artifacts come first: a failure here must not leave
# the worktree removed while its prefixes are orphaned outside the repository.
if [ "${TURBOISM_SKIP_HOST_CLEANUP:-0}" != "1" ] && [ -x "${SCRIPT_DIR}/cleanup-host-artifacts.sh" ]; then
  echo "Cleaning host validation artifacts for worktree ${WORKTREE_ID}..."
  if ! "${SCRIPT_DIR}/cleanup-host-artifacts.sh" --worktree "${WORKTREE_ID}"; then
    echo "error: host artifact cleanup failed for ${WORKTREE_ID}; worktree left untouched." >&2
    exit 1
  fi
fi

if [ "${WORKTREE_EXISTS}" -eq 1 ]; then
  if [ "${WORKTREE_REGISTERED}" -eq 1 ]; then
    if [ "${FORCE}" -eq 1 ]; then
      git worktree remove --force "${WORKTREE_DIR_REAL}"
    else
      git worktree remove "${WORKTREE_DIR_REAL}"
    fi
  else
    echo "warning: --force given; ${WORKTREE_DIR} is not a registered worktree, deleting the directory." >&2
    rm -rf "${WORKTREE_DIR_REAL}"
  fi
fi

if [ "${BRANCH_EXISTS}" -eq 1 ]; then
  if [ "${FORCE}" -eq 1 ]; then
    git branch -D "${BRANCH}"
  else
    git branch -d "${BRANCH}"
  fi
fi

echo "Worktree ${WORKTREE_ID} cleaned."
