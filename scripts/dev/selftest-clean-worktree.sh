#!/usr/bin/env bash
# Negative self-test for scripts/dev/clean-worktree.sh.
#
# Builds throwaway repositories under a temporary directory and proves that the
# reclamation script refuses to destroy work it cannot prove is disposable:
#   1. dirty worktree            -> non-zero, directory and branch kept
#   2. unmerged branch           -> non-zero, directory and branch kept
#   3. merged branch, clean tree -> zero, directory and branch reclaimed
#   4. --force on dirty+unmerged -> zero, both reclaimed
#   5. invalid worktree id       -> non-zero, nothing removed
#   6. --dry-run on a dirty tree -> zero, nothing removed
#
# The real repository and the real worktree parent are never touched.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/turboism-clean-worktree-selftest.XXXXXX")"
TOTAL=0
FAILED=0

fail() {
  echo "FAIL: $*" >&2
  FAILED=$((FAILED + 1))
}

pass() {
  echo "ok: $*"
}

cleanup() {
  rm -rf "${WORK_ROOT}"
}
trap cleanup EXIT

# $1 = case name, creates ${WORK_ROOT}/$1/{repo,turboism-worktrees}
make_fixture() {
  local name="$1"
  local root="${WORK_ROOT}/${name}"
  mkdir -p "${root}"
  git -C "${root}" init -q repo
  git -C "${root}/repo" config user.email selftest@example.com
  git -C "${root}/repo" config user.name selftest
  mkdir -p "${root}/repo/scripts/dev"
  cp "${SCRIPT_DIR}/clean-worktree.sh" "${SCRIPT_DIR}/worktree-id-lib.sh" "${root}/repo/scripts/dev/"
  echo base >"${root}/repo/f.txt"
  git -C "${root}/repo" add -A
  git -C "${root}/repo" commit -qm "fixture base"
  printf '%s\n' "${root}"
}

add_worktree() {
  local root="$1"
  local id="$2"
  git -C "${root}/repo" branch "worktree/${id}"
  git -C "${root}/repo" worktree add -q "${root}/turboism-worktrees/${id}" "worktree/${id}"
}

worktree_dir() {
  printf '%s/turboism-worktrees/%s' "$1" "$2"
}

branch_exists() {
  local root="$1"
  local id="$2"
  git -C "${root}/repo" rev-parse --verify --quiet "worktree/${id}" >/dev/null
}

run_gate() {
  local root="$1"
  shift
  ( cd "${root}/repo" && TURBOISM_SKIP_HOST_CLEANUP=1 bash scripts/dev/clean-worktree.sh "$@" )
}

# ---------- 1. dirty worktree is refused ----------
TOTAL=$((TOTAL + 1))
root="$(make_fixture dirty)"
add_worktree "${root}" "wt-dirty"
echo "uncommitted" >>"$(worktree_dir "${root}" wt-dirty)/f.txt"
if run_gate "${root}" wt-dirty >"${WORK_ROOT}/dirty.log" 2>&1; then
  fail "dirty worktree: expected non-zero exit"
elif [ ! -d "$(worktree_dir "${root}" wt-dirty)" ]; then
  fail "dirty worktree: directory was removed"
elif ! branch_exists "${root}" wt-dirty; then
  fail "dirty worktree: branch was deleted"
elif ! grep -q "uncommitted or untracked changes" "${WORK_ROOT}/dirty.log"; then
  fail "dirty worktree: refusal reason not reported"
else
  pass "dirty worktree refused, directory and branch preserved"
fi

# ---------- 2. unmerged branch is refused even with a clean tree ----------
TOTAL=$((TOTAL + 1))
root="$(make_fixture unmerged)"
add_worktree "${root}" "wt-unmerged"
(
  cd "$(worktree_dir "${root}" wt-unmerged)"
  echo "pending work" >pending.txt
  git add pending.txt
  git commit -qm "unmerged work"
)
if run_gate "${root}" wt-unmerged >"${WORK_ROOT}/unmerged.log" 2>&1; then
  fail "unmerged branch: expected non-zero exit"
elif [ ! -d "$(worktree_dir "${root}" wt-unmerged)" ]; then
  fail "unmerged branch: worktree directory was removed"
elif ! branch_exists "${root}" wt-unmerged; then
  fail "unmerged branch: branch was deleted"
elif ! grep -q "not merged into any of" "${WORK_ROOT}/unmerged.log"; then
  fail "unmerged branch: refusal reason not reported"
else
  pass "unmerged branch refused, worktree and branch preserved"
fi

# ---------- 3. clean, merged worktree is reclaimed ----------
TOTAL=$((TOTAL + 1))
root="$(make_fixture merged)"
add_worktree "${root}" "wt-merged"
if ! run_gate "${root}" wt-merged >"${WORK_ROOT}/merged.log" 2>&1; then
  fail "merged worktree: expected zero exit, output: $(cat "${WORK_ROOT}/merged.log")"
elif [ -d "$(worktree_dir "${root}" wt-merged)" ]; then
  fail "merged worktree: directory still present"
elif branch_exists "${root}" wt-merged; then
  fail "merged worktree: branch still present"
else
  pass "clean merged worktree reclaimed"
fi

# ---------- 4. --force reclaims dirty and unmerged state ----------
TOTAL=$((TOTAL + 1))
root="$(make_fixture forced)"
add_worktree "${root}" "wt-forced"
(
  cd "$(worktree_dir "${root}" wt-forced)"
  echo "pending work" >pending.txt
  git add pending.txt
  git commit -qm "unmerged work"
  echo "uncommitted" >>f.txt
)
if ! run_gate "${root}" wt-forced --force >"${WORK_ROOT}/forced.log" 2>&1; then
  fail "--force: expected zero exit, output: $(cat "${WORK_ROOT}/forced.log")"
elif [ -d "$(worktree_dir "${root}" wt-forced)" ]; then
  fail "--force: directory still present"
elif branch_exists "${root}" wt-forced; then
  fail "--force: branch still present"
else
  pass "--force reclaimed a dirty, unmerged worktree"
fi

# ---------- 5. invalid id is refused before any path is touched ----------
TOTAL=$((TOTAL + 1))
root="$(make_fixture invalid)"
mkdir -p "${WORK_ROOT}/invalid/escape-target"
if run_gate "${root}" "../escape-target" >"${WORK_ROOT}/invalid.log" 2>&1; then
  fail "invalid id: expected non-zero exit"
elif [ ! -d "${WORK_ROOT}/invalid/escape-target" ]; then
  fail "invalid id: escaped path was removed"
elif ! grep -q "Invalid worktree ID" "${WORK_ROOT}/invalid.log"; then
  fail "invalid id: validation message not reported"
else
  pass "invalid worktree id refused"
fi

# ---------- 6. --dry-run never removes anything ----------
TOTAL=$((TOTAL + 1))
root="$(make_fixture dryrun)"
add_worktree "${root}" "wt-dryrun"
echo "uncommitted" >>"$(worktree_dir "${root}" wt-dryrun)/f.txt"
if ! run_gate "${root}" wt-dryrun --dry-run >"${WORK_ROOT}/dryrun.log" 2>&1; then
  fail "--dry-run: expected zero exit, output: $(cat "${WORK_ROOT}/dryrun.log")"
elif [ ! -d "$(worktree_dir "${root}" wt-dryrun)" ]; then
  fail "--dry-run: directory was removed"
elif ! branch_exists "${root}" wt-dryrun; then
  fail "--dry-run: branch was deleted"
else
  pass "--dry-run changed nothing"
fi

echo "clean-worktree safety self-test: $((TOTAL - FAILED))/${TOTAL} cases passed"
[ "${FAILED}" -eq 0 ]
