#!/usr/bin/env bash
# Reclaims one managed worktree and its host-validation artifacts.
#
# Safety contract:
#   - A dirty worktree is never deleted implicitly; the script fails and keeps it.
#   - A branch with commits missing from every merge target is never deleted
#     implicitly; the script fails and keeps it.
#   - A detached HEAD whose commit is missing from every merge target would be
#     lost with the directory; the script fails and keeps it.
#   - --force is the only bypass for those refusals, and it is explicit,
#     per-invocation, and echoed before each destructive step.
#   - The directory must resolve to a location inside the worktree parent
#     directory. A path that resolves elsewhere (symlink, '..') is refused even
#     with --force, and a symlink is only ever unlinked, never followed.
#   - The branch is proven deletable before the first side effect, so the script
#     never removes the directory and then fails on the branch.
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

MERGE_TARGETS=()
[ -n "${MERGED_INTO}" ] && MERGE_TARGETS+=("${MERGED_INTO}")
MERGE_TARGETS+=("HEAD" "main" "master" "origin/main" "origin/master" "origin/HEAD")

# Prints the first merge target that contains $1 and succeeds; fails if none do.
contained_in_merge_target() {
  local rev="$1" target
  for target in "${MERGE_TARGETS[@]}"; do
    git rev-parse --verify --quiet "${target}" >/dev/null 2>&1 || continue
    if git merge-base --is-ancestor "${rev}" "${target}"; then
      printf '%s\n' "${target}"
      return 0
    fi
  done
  return 1
}

# `git worktree list` reports physical paths, so compare against the resolved
# form; a lexical path containing '..' would never match a registered entry.
WORKTREE_PARENT_REAL=""
if [ -d "${WORKTREE_PARENT}" ]; then
  WORKTREE_PARENT_REAL="$(cd "${WORKTREE_PARENT}" && pwd -P)"
fi
WORKTREE_IS_LINK=0
[ -L "${WORKTREE_DIR}" ] && WORKTREE_IS_LINK=1
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
if [ -e "${WORKTREE_DIR}" ] || [ "${WORKTREE_IS_LINK}" -eq 1 ]; then
  WORKTREE_EXISTS=1

  # The resolved directory must sit strictly inside the worktree parent. This
  # holds for --force too: it is what keeps a symlink or '..' from steering the
  # removal at an unrelated tree.
  if [ -z "${WORKTREE_PARENT_REAL}" ] || [ ! -d "${WORKTREE_DIR}" ]; then
    echo "error: ${WORKTREE_DIR} does not resolve to a directory inside ${WORKTREE_PARENT}; refusing." >&2
    exit 1
  fi
  case "${WORKTREE_DIR_REAL}" in
    "${WORKTREE_PARENT_REAL}"/?*) ;;
    *)
      echo "error: ${WORKTREE_DIR} resolves to ${WORKTREE_DIR_REAL}, outside ${WORKTREE_PARENT_REAL}; refusing even with --force." >&2
      exit 1
      ;;
  esac

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

    # A detached HEAD has no branch to keep its commits reachable once the
    # directory is gone.
    if ! git -C "${WORKTREE_DIR}" symbolic-ref -q HEAD >/dev/null 2>&1; then
      DETACHED_HEAD="$(git -C "${WORKTREE_DIR}" rev-parse HEAD 2>/dev/null || true)"
      if [ -n "${DETACHED_HEAD}" ] && ! contained_in_merge_target "${DETACHED_HEAD}" >/dev/null; then
        if [ "${FORCE}" -ne 1 ]; then
          echo "error: worktree ${WORKTREE_DIR} is on a detached HEAD (${DETACHED_HEAD}) that is not merged into any of:" >&2
          printf '  %s\n' "${MERGE_TARGETS[@]}" >&2
          echo "error: refusing to remove it. Merge or branch the commit first, or pass --force." >&2
          exit 1
        fi
        echo "warning: --force given; detached HEAD ${DETACHED_HEAD} is not merged and will become unreachable." >&2
      fi
    fi
  fi

  # A symlink is never registered under its own name: treat it as unmanaged so
  # only the link itself can ever be removed.
  if [ "${WORKTREE_IS_LINK}" -eq 0 ] && git worktree list --porcelain | grep -Fxq "worktree ${WORKTREE_DIR_REAL}"; then
    WORKTREE_REGISTERED=1
  elif [ "${FORCE}" -ne 1 ]; then
    echo "error: ${WORKTREE_DIR} is not a registered worktree of ${REPO_ROOT}." >&2
    echo "error: refusing to delete an unmanaged directory without --force." >&2
    exit 1
  fi

  if [ "${WORKTREE_REGISTERED}" -eq 1 ] \
    && git worktree list --porcelain | awk -v wt="worktree ${WORKTREE_DIR_REAL}" '
      $0 == wt { found = 1; next }
      found && /^locked/ { locked = 1 }
      found && $0 == "" { exit }
      END { exit locked ? 0 : 1 }'; then
    echo "error: worktree ${WORKTREE_DIR} is locked; run 'git worktree unlock' first. Removal would fail after side effects." >&2
    exit 1
  fi
fi

BRANCH_EXISTS=0
BRANCH_MERGE_TARGET=""
if git rev-parse --verify --quiet "refs/heads/${BRANCH}" >/dev/null 2>&1; then
  BRANCH_EXISTS=1
  BRANCH_MERGE_TARGET="$(contained_in_merge_target "refs/heads/${BRANCH}" || true)"
  if [ -z "${BRANCH_MERGE_TARGET}" ] && [ "${FORCE}" -ne 1 ]; then
    echo "error: branch ${BRANCH} has commits that are not merged into any of:" >&2
    printf '  %s\n' "${MERGE_TARGETS[@]}" >&2
    echo "error: refusing to delete it. Merge the branch first, or pass --force." >&2
    exit 1
  fi

  # `git branch -d`/`-D` refuse a branch checked out in another worktree. Prove
  # that cannot happen now, before the directory is gone.
  CHECKED_OUT_ELSEWHERE="$(git worktree list --porcelain | awk -v ref="branch refs/heads/${BRANCH}" -v self="${WORKTREE_DIR_REAL}" '
    /^worktree / { path = substr($0, 10) }
    $0 == ref && path != self { print path }')"
  if [ -n "${CHECKED_OUT_ELSEWHERE}" ]; then
    echo "error: branch ${BRANCH} is checked out in another worktree: ${CHECKED_OUT_ELSEWHERE}" >&2
    echo "error: refusing to start; the branch could not be deleted afterwards." >&2
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
    if [ "${WORKTREE_IS_LINK}" -eq 1 ]; then
      echo "warning: --force given; ${WORKTREE_DIR} is a symlink, unlinking it and leaving its target untouched." >&2
      rm -f -- "${WORKTREE_DIR}"
    else
      echo "warning: --force given; ${WORKTREE_DIR} is not a registered worktree, deleting the directory." >&2
      rm -rf -- "${WORKTREE_DIR_REAL}"
    fi
  fi
fi

if [ "${BRANCH_EXISTS}" -eq 1 ]; then
  # `git branch -d` only consults HEAD and the upstream, while the precondition
  # accepted any merge target. The precondition already proved the branch
  # merged (or --force asked for the loss), so delete without a second opinion.
  git branch -D "${BRANCH}"
fi

echo "Worktree ${WORKTREE_ID} cleaned."
