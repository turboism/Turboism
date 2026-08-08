#!/usr/bin/env bash
# Process-identity helpers for the performance-probe runner.
#
# Sourceable (no host launch; /proc reads and process signals only):
#   source probe-process-identity.sh
#   ticks=$(probe_start_ticks_of PID) || ...
#   probe_pid_start_ticks_match PID "$ticks" && ...     # exact /proc/<pid>/stat start tick
#   probe_cmdline_matches_fixture "$cmdline" "$fixture" # Windows backslashes normalized
#   settle_child PID TICKS LABEL [TERM_GRACE KILL_GRACE] # identity-bound settle/reap
#
# Self-test (offline, no Cubism/Proton/xdotool/SSH):
#   bash probe-process-identity.sh --self-test

PROBE_JAR_TOKEN="Live2D_Cubism.jar"

probe_is_numeric() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

# Prints /proc/<pid>/stat starttime (field 22, clock ticks since boot) or exits 1.
probe_start_ticks_of() {
  local candidate=$1 stat ticks
  probe_is_numeric "$candidate" || return 1
  stat="/proc/$candidate/stat"
  [[ -r "$stat" ]] || return 1
  # The process may exit between the readability check and the read; treat a
  # failed or empty read as "not available" instead of an uncaught error.
  ticks=$(awk '{print $22}' "$stat" 2>/dev/null) || return 1
  probe_is_numeric "$ticks" || return 1
  printf '%s\n' "$ticks"
}

# True only when the PID is alive and its current start tick equals the recorded
# one; a reused/unverified PID never passes.
probe_pid_start_ticks_match() {
  local candidate=$1 expected=$2 actual
  probe_is_numeric "$candidate" || return 1
  probe_is_numeric "$expected" || return 1
  actual=$(probe_start_ticks_of "$candidate") || return 1
  [[ "$actual" == "$expected" ]]
}

# Matches a command line against the Cubism JAR token and the Linux fixture
# path, normalizing Windows backslashes to slashes first (same behavior as
# find-cubism-java-pid.sh).
probe_cmdline_matches_fixture() {
  local cmd=$1 fixture=$2
  cmd=${cmd//\\//}
  [[ "$cmd" == *"$PROBE_JAR_TOKEN"* && "$cmd" == *"$fixture"* ]]
}

# Reaps a direct child of this shell, but only when it is an observed zombie;
# waiting on a live non-zombie child would block, and waiting on an already
# reaped child triggers a bash job-control diagnostic, so neither is waited on.
probe_reap_child() {
  local child_pid=$1 state
  probe_is_numeric "$child_pid" || return 0
  [[ -r "/proc/$child_pid/stat" ]] || return 0
  state=$(awk '{print $3}' "/proc/$child_pid/stat" 2>/dev/null) || return 0
  [[ "$state" == "Z" ]] || return 0
  wait "$child_pid" 2>/dev/null || true
}

# Bounded truthful settle of one task-started child. Polls only while the PID
# still has the exact recorded start tick (a reused PID ends the poll); TERM is
# sent only after an immediate exact-tick check; if the exact process still
# survives, KILL follows after another immediate exact-tick check. Direct
# children are reaped when possible. Returns nonzero when the original exact
# process is still present after the bounded escalation.
settle_child() {
  local child_pid=$1 expected_ticks=$2 label=$3
  local term_grace=${4:-30} kill_grace=${5:-15}
  local waited=0
  # Nothing recorded means no process to settle (success). A present PID with a
  # missing/invalid expected tick (or vice versa) is not a valid identity: fail
  # with a diagnostic and never signal anything.
  if [[ -z "$child_pid" && -z "$expected_ticks" ]]; then
    return 0
  fi
  if ! probe_is_numeric "$child_pid" || ! probe_is_numeric "$expected_ticks"; then
    echo "performance probe cleanup: invalid recorded identity for $label (pid=$child_pid ticks=$expected_ticks); not signaling" >&2
    return 1
  fi
  while [[ "$waited" -lt "$term_grace" ]] \
    && probe_pid_start_ticks_match "$child_pid" "$expected_ticks"; do
    sleep 1
    waited=$((waited + 1))
  done
  if probe_pid_start_ticks_match "$child_pid" "$expected_ticks"; then
    echo "performance probe cleanup: terminating $label (pid $child_pid)" >&2
    kill -TERM "$child_pid" 2>/dev/null || true
    waited=0
    while [[ "$waited" -lt "$kill_grace" ]] \
      && probe_pid_start_ticks_match "$child_pid" "$expected_ticks"; do
      sleep 1
      waited=$((waited + 1))
    done
    if probe_pid_start_ticks_match "$child_pid" "$expected_ticks"; then
      echo "performance probe cleanup: terminating $label (pid $child_pid) with KILL" >&2
      kill -KILL "$child_pid" 2>/dev/null || true
      for _ in $(seq 1 5); do
        probe_pid_start_ticks_match "$child_pid" "$expected_ticks" || break
        sleep 1
      done
    fi
  fi
  probe_reap_child "$child_pid"
  if probe_pid_start_ticks_match "$child_pid" "$expected_ticks"; then
    echo "performance probe cleanup: $label pid $child_pid still alive after bounded settle" >&2
    return 1
  fi
  return 0
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  set -euo pipefail
  if [[ "${1:-}" != "--self-test" ]]; then
    echo "usage: probe-process-identity.sh --self-test" >&2
    exit 64
  fi
  failures=0
  check_pass() { # check_pass LABEL COMMAND...
    local label=$1
    shift
    if ! "$@" >/dev/null 2>&1; then
      echo "FAIL: $label" >&2
      failures=$((failures + 1))
    fi
  }
  check_fail() { # check_fail LABEL COMMAND...
    local label=$1
    shift
    if "$@" >/dev/null 2>&1; then
      echo "FAIL: $label unexpectedly passed" >&2
      failures=$((failures + 1))
    fi
  }

  # Synthetic Windows-style command line must match the Linux fixture after
  # backslash normalization.
  check_pass "windows cmdline matches fixture after normalization" \
    probe_cmdline_matches_fixture \
    "Z:\\TurboismValidation\\r1\\java.exe -jar Z:\\TurboismValidation\\r1\\models\\heavy.cmo3 -cp Z:\\Live2D_Cubism.jar" \
    "/TurboismValidation/r1/models/heavy.cmo3"
  check_fail "unrelated cmdline must not match" \
    probe_cmdline_matches_fixture "/bin/sleep 60" "/TurboismValidation/r1/models/heavy.cmo3"
  check_fail "cmdline without the Cubism JAR token must not match" \
    probe_cmdline_matches_fixture \
    "Z:\\TurboismValidation\\r1\\java.exe Z:\\TurboismValidation\\r1\\models\\heavy.cmo3" \
    "/TurboismValidation/r1/models/heavy.cmo3"

  # Numeric validation: non-numeric PID/tick inputs are never treated as valid.
  check_fail "non-numeric pid fails start-tick match" probe_pid_start_ticks_match "abc" "0"
  check_fail "non-numeric ticks fail start-tick match" probe_pid_start_ticks_match "1" "xyz"
  check_fail "non-numeric pid fails start-tick read" probe_start_ticks_of "abc;rm"

  # Exact start-tick identity: our own shell must match its recorded ticks...
  own_ticks=$(probe_start_ticks_of $$)
  check_pass "exact start-tick identity passes" probe_pid_start_ticks_match $$ "$own_ticks"
  # ...and a mismatched/reused tick must fail.
  check_fail "mismatched start tick fails" probe_pid_start_ticks_match $$ "0"
  check_fail "nonexistent pid fails" probe_pid_start_ticks_match 2147483647 "$own_ticks"

  # Truthful settle with short test graces: an exact child is terminated and
  # reaped; a mismatched tick is never signaled; a TERM-resistant exact child
  # reaches the identity-checked KILL fallback.
  trap 'kill -KILL "${test_child:-}" 2>/dev/null || true' EXIT

  # settle_child must run in the shell that owns the child so reaping works.
  sleep 30 &
  test_child=$!
  test_ticks=$(probe_start_ticks_of "$test_child") || test_ticks=""
  check_pass "exact child terminated and reaped" settle_child "$test_child" "$test_ticks" "test child" 1 1
  check_fail "terminated child no longer matches" probe_pid_start_ticks_match "$test_child" "$test_ticks"

  sleep 30 &
  test_child=$!
  test_ticks=$(probe_start_ticks_of "$test_child") || test_ticks=""
  check_pass "mismatched tick is never signaled" settle_child "$test_child" "0" "wrong tick" 1 1
  check_pass "mismatched-tick child still alive" kill -0 "$test_child"
  kill "$test_child" 2>/dev/null || true
  probe_reap_child "$test_child"

  sleep 30 &
  test_child=$!
  check_fail "missing expected tick is not settled" settle_child "$test_child" "" "missing tick" 1 1
  check_pass "missing-tick child still alive" kill -0 "$test_child"
  kill "$test_child" 2>/dev/null || true
  probe_reap_child "$test_child"

  sleep 30 &
  test_child=$!
  check_fail "non-numeric expected tick is not settled" settle_child "$test_child" "abc" "bad tick" 1 1
  check_pass "bad-tick child still alive" kill -0 "$test_child"
  kill "$test_child" 2>/dev/null || true
  probe_reap_child "$test_child"

  check_pass "empty recorded pair is settled" settle_child "" "" "nothing recorded" 1 1

  bash -c 'trap "" TERM; sleep 30' &
  test_child=$!
  test_ticks=$(probe_start_ticks_of "$test_child") || test_ticks=""
  check_pass "TERM-resistant exact child reaches KILL and is reaped" \
    settle_child "$test_child" "$test_ticks" "term-resistant child" 1 1
  check_fail "KILLed child no longer matches" probe_pid_start_ticks_match "$test_child" "$test_ticks"
  probe_reap_child "$test_child"

  trap - EXIT

  if [[ "$failures" -ne 0 ]]; then
    echo "probe-process-identity self-test: FAIL ($failures)" >&2
    exit 1
  fi
  echo "probe-process-identity self-test: PASS"
fi
