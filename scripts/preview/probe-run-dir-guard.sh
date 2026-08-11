#!/usr/bin/env bash
# Guards the task-scoped probe run directory: bounded RUN_ID, no symlink
# escape, no reuse of an existing candidate, no deletion anywhere.
#
# Sourceable helper for run-cubism-performance-probe.sh:
#   source probe-run-dir-guard.sh
#   run=$(probe_run_dir TASK_DIR RUN_ID) || exit $?
#
# Rules: RUN_ID is a bounded single path component; the canonicalized run path
# must stay below the canonicalized task runs root; the runs root and the
# candidate must not be symlinks (walked literally); a pre-existing candidate
# (directory, file, or symlink) is rejected so runs are never reused or
# deleted. Never deletes anything itself.
#
# Self-test (no host, no real task layout):
#   bash probe-run-dir-guard.sh --self-test

PROBE_RUN_ID_PATTERN='^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'

probe_run_id_is_valid() {
  [[ "$1" =~ $PROBE_RUN_ID_PATTERN ]]
}

# Lexical-only canonicalization: absolute, dot segments removed, no symlink
# resolution and no filesystem access.
normalize_path() {
  local path=$1 out="" part
  case "$path" in
    /*) ;;
    *) path="$PWD/$path" ;;
  esac
  IFS='/' read -ra parts <<< "$path"
  for part in "${parts[@]}"; do
    case "$part" in
      "" | ".") ;;
      "..") out=${out%/*} ;;
      *) out="$out/$part" ;;
    esac
  done
  [[ -n "$out" ]] || out="/"
  printf '%s\n' "$out"
}

# Prints the canonical run directory or exits nonzero with a reason on stderr.
probe_run_dir() {
  local task_dir run_id
  task_dir=${1:-}
  run_id=${2:-}
  if [[ -z "$task_dir" || -z "$run_id" ]]; then
    echo "usage: probe_run_dir TASK_DIR RUN_ID" >&2
    return 2
  fi
  probe_run_id_is_valid "$run_id" || {
    echo "invalid RUN_ID: $run_id" >&2
    return 2
  }
  local task_abs literal_runs literal_candidate probe task_runs run
  task_abs=$(normalize_path "$task_dir")
  literal_runs="$task_abs/runs"
  literal_candidate="$literal_runs/$run_id"
  probe=$literal_runs
  while [[ "$probe" != "/" ]]; do
    [[ ! -L "$probe" ]] || {
      echo "run path component is a symlink: $probe" >&2
      return 4
    }
    probe=$(dirname "$probe")
  done
  [[ ! -L "$literal_candidate" ]] || {
    echo "run directory must not be a symlink: $literal_candidate" >&2
    return 5
  }
  [[ ! -e "$literal_candidate" ]] || {
    echo "run directory already exists; refusing to reuse: $literal_candidate" >&2
    return 6
  }
  task_runs=$(normalize_path "$literal_runs")
  run=$(normalize_path "$literal_candidate")
  case "$run" in
    "$task_runs/"*) ;;
    *)
      echo "RUN_ID escapes the task runs directory: $run" >&2
      return 3
      ;;
  esac
  printf '%s\n' "$run"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  # Self-test mode.
  set -euo pipefail
  if [[ "${1:-}" != "--self-test" ]]; then
    echo "usage: probe-run-dir-guard.sh --self-test" >&2
    exit 64
  fi
  failures=0
  check_rc() { # check_rc EXPECTED_RC LABEL COMMAND...
    local expected=$1 label=$2
    shift 2
    local actual=0
    "$@" >/dev/null 2>&1 || actual=$?
    if [[ "$actual" != "$expected" ]]; then
      echo "FAIL: $label expected rc=$expected got rc=$actual" >&2
      failures=$((failures + 1))
    fi
  }
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  mkdir -p "$tmp/task/runs"
  check_rc 0 "valid run id" probe_run_dir "$tmp/task" "run-01"
  check_rc 0 "relative task dir" probe_run_dir "task" "run-01"
  check_rc 0 "task path with dot segment" probe_run_dir "$tmp/task/../task" "run-01"
  check_rc 2 "parent traversal" probe_run_dir "$tmp/task" "../escape"
  check_rc 2 "embedded slash" probe_run_dir "$tmp/task" "a/b"
  check_rc 2 "leading dot" probe_run_dir "$tmp/task" ".hidden"
  check_rc 2 "empty run id" probe_run_dir "$tmp/task" ""
  check_rc 2 "space in run id" probe_run_dir "$tmp/task" "run id"
  check_rc 2 "overlong run id" probe_run_dir "$tmp/task" "$(printf 'x%.0s' {1..129})"
  ln -s /etc "$tmp/task/runs/evil"
  check_rc 5 "candidate symlink" probe_run_dir "$tmp/task" "evil"
  mkdir -p "$tmp/task/runs/sentinel"
  touch "$tmp/task/runs/sentinel/keep.txt"
  check_rc 6 "existing run dir rejected" probe_run_dir "$tmp/task" "sentinel"
  touch "$tmp/task/runs/file-run"
  check_rc 6 "existing run file rejected" probe_run_dir "$tmp/task" "file-run"
  [[ -f "$tmp/task/runs/sentinel/keep.txt" ]] || {
    echo "FAIL: existing run contents were not preserved" >&2
    failures=$((failures + 1))
  }
  check_rc 0 "fresh run id still accepted" probe_run_dir "$tmp/task" "fresh-1"
  mkdir -p "$tmp/task2"
  ln -s /etc "$tmp/task2/runs"
  check_rc 4 "runs root symlink" probe_run_dir "$tmp/task2" "run-01"
  mkdir -p "$tmp/task3plain"
  ln -s "$tmp/task3plain" "$tmp/other"
  check_rc 4 "task dir component symlink" probe_run_dir "$tmp/other" "run-01"
  if [[ "$(probe_run_dir "$tmp/task" run-01)" != "$tmp/task/runs/run-01" ]]; then
    echo "FAIL: probe_run_dir output mismatch" >&2
    failures=$((failures + 1))
  fi
  if [[ "$failures" -ne 0 ]]; then
    echo "probe-run-dir-guard self-test: FAIL ($failures)" >&2
    exit 1
  fi
  echo "probe-run-dir-guard self-test: PASS"
fi
