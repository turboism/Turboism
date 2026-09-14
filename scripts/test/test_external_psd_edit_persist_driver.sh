#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
driver_source="$repo_root/scripts/preview/run-external-psd-edit-persist-validation.sh"
host_wrapper_source="$repo_root/scripts/preview/run-external-psd-edit-host-validation.sh"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/external-psd-persist-driver.XXXXXX")"
failures=0

trap 'rm -rf -- "$test_root"' EXIT

record_failure() {
  printf 'FAIL: %s\n' "$1" >&2
  failures=$((failures + 1))
}

assert_file_contains() {
  local file="$1" expected="$2" description="$3"
  if ! grep -Fq -- "$expected" "$file"; then
    record_failure "$description (missing: $expected)"
  fi
}

assert_file_not_contains() {
  local file="$1" unexpected="$2" description="$3"
  if grep -Fq -- "$unexpected" "$file"; then
    record_failure "$description (found: $unexpected)"
  fi
}

make_driver_sandbox() {
  local sandbox="$1"
  mkdir -p "$sandbox/scripts/preview"
  cp "$driver_source" "$sandbox/scripts/preview/run-external-psd-edit-persist-validation.sh"
  chmod +x "$sandbox/scripts/preview/run-external-psd-edit-persist-validation.sh"
  cat > "$sandbox/scripts/preview/run-external-psd-edit-host-validation.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

phase="${EXTERNAL_PSD_PHASE:-pipeline}"
mode="${DRIVER_STUB_MODE:-correct}"
host_root="${DRIVER_STUB_HOST_ROOT:?}"
log="${DRIVER_STUB_LOG:?}"
file_hash="ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
image_hash="1111111111111111111111111111111111111111111111111111111111111111"
a_run_id="queue-run-a"
a_job_id="job-a"
a_task_dir="$host_root/external-psd-edit-pipeline/5302-025-us4/$a_run_id"
b_run_id="queue-run-b"
b_job_id="job-b"
b_task_dir="$host_root/external-psd-edit-pipeline/5302-025-us4/$b_run_id"
result_name="external-psd-edit-result.properties"

printf 'phase=%s\n' "$phase" >> "$log"

write_job() {
  local job_id="$1" run_id="$2" task_dir="$3" result_path="$4" state="$5" evidence_mode="${6:-complete}"
  python3 - "$job_id" "$run_id" "$task_dir" "$result_path" "$state" "$evidence_mode" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

job_id, run_id, task_dir, result_path, state, evidence_mode = sys.argv[1:]
attempt_id = "attempt-" + job_id
digest = "digest-" + job_id
if evidence_mode == "incomplete":
    evidence = {"schemaVersion": 1}
else:
    result_sha256 = hashlib.sha256(Path(result_path).read_bytes()).hexdigest()
    if evidence_mode == "bad-terminal-sha":
        result_sha256 = "0" * 64
    evidence = {
        "schemaVersion": 1,
        "jobId": job_id,
        "attemptId": attempt_id,
        "runId": run_id,
        "preparedDigest": digest,
        "cleanup": "safe",
        "validationStatus": "PASS" if state == "succeeded" else "FAIL",
        "identityVerified": True,
        "fixtureUnchanged": True,
        "normalExit": True,
        "terminalState": state,
        "finalizedBy": "contained-supervisor",
        "details": {
            "cleanupOwner": "supervisor",
            "taskDir": task_dir,
            "validationComplete": True,
            "taskOwnedCleanup": True,
            "postContainmentChecks": {"terminalResult": {
                "passed": True,
                "path": result_path,
                "sha256": result_sha256,
            }},
        },
    }
job = {
    "job_id": job_id,
    "attempt_id": attempt_id,
    "run_id": run_id,
    "digest": digest,
    "state": state,
    "evidence_json": json.dumps(evidence, separators=(",", ":")),
}
print(json.dumps({"schemaVersion": 1, "job": job}, separators=(",", ":")))
PY
}

if [ "$phase" = reopen ]; then
  printf 'stageB.fixture=%s\n' "${EXTERNAL_PSD_FIXTURE_LOCAL:-}" >> "$log"
  printf 'stageB.postImage=%s\n' "${EXTERNAL_PSD_POSTEDITIMAGESHA256:-}" >> "$log"
  printf 'stageB.postFile=%s\n' "${EXTERNAL_PSD_POSTEDITSHA256:-}" >> "$log"
  printf 'stageB.markerLayer=%s\n' "${EXTERNAL_PSD_MARKERLAYER:-}" >> "$log"
  printf 'stageB.markerOffset=%s\n' "${EXTERNAL_PSD_MARKEROFFSET:-}" >> "$log"
  printf 'stageB.markerChar=%s\n' "${EXTERNAL_PSD_MARKERCHAR:-}" >> "$log"
  if [ "$mode" = reuse-a-evidence ]; then
    write_job "$a_job_id" "$a_run_id" "$a_task_dir" \
      "$a_task_dir/evidence/result/$result_name" succeeded
    exit 0
  fi

  b_result="$b_task_dir/evidence/result/$result_name"
  mkdir -p "$(dirname "$b_result")"
  b_expected="${EXTERNAL_PSD_POSTEDITIMAGESHA256:-}"
  b_actual="$b_expected"
  b_phase=reopen
  b_result_run_id="$b_run_id"
  case "$mode" in
    wrong-phase) b_phase=pipeline ;;
    wrong-run) b_result_run_id="$a_run_id" ;;
    wrong-expected-image) b_expected="$file_hash" ;;
    wrong-image) b_actual="$file_hash" ;;
  esac
  {
    printf 'status=PASS\n'
    printf 'runId=%s\n' "$b_result_run_id"
    printf 'phase=%s\n' "$b_phase"
    printf 'reopen.expectedImageSha256=%s\n' "$b_expected"
    printf 'reopen.imageSha256=%s\n' "$b_actual"
  } > "$b_result"
  printf 'stageB.job=%s\n' "$b_job_id" >> "$log"
  printf 'stageB.run=%s\n' "$b_run_id" >> "$log"
  printf 'stageB.taskDir=%s\n' "$b_task_dir" >> "$log"
  printf 'stageB.result=%s\n' "$b_result" >> "$log"
  if [ "$mode" = stage-b-failure ]; then
    write_job "$b_job_id" "$b_run_id" "$b_task_dir" "$b_result" failed
    exit 42
  fi
  if [ "$mode" = stage-b-incomplete-supervisor ]; then
    write_job "$b_job_id" "$b_run_id" "$b_task_dir" "$b_result" succeeded incomplete
  elif [ "$mode" = terminal-sha-mismatch ]; then
    write_job "$b_job_id" "$b_run_id" "$b_task_dir" "$b_result" succeeded bad-terminal-sha
  else
    write_job "$b_job_id" "$b_run_id" "$b_task_dir" "$b_result" succeeded
  fi
  exit 0
fi

for arg in "$@"; do
  if [ "$arg" = --dry-run ]; then
    printf 'dry-run-called\n' >> "$log"
    exit 0
  fi
done

printf 'queue-submit-called\n' >> "$log"
printf 'host-launch-called\n' >> "$log"

if [ "$mode" = stage-failure ]; then
  exit 42
fi

a_result="$a_task_dir/evidence/result/$result_name"
mkdir -p "$(dirname "$a_result")" "$a_task_dir/turboism-home"
printf 'persisted copy from %s\n' "$mode" > "$a_task_dir/turboism-home/persisted-document.cmo3"
a_result_run_id="$a_run_id"
a_phase=pipeline
a_save_succeeded=true
case "$mode" in
  wrong-a-run) a_result_run_id=queue-run-other ;;
  wrong-a-phase) a_phase=reopen ;;
  save-not-succeeded) a_save_succeeded=false ;;
esac

if [ "$mode" = missing-hash ]; then
  {
    printf 'status=PASS\n'
    printf 'runId=%s\n' "$a_result_run_id"
    printf 'phase=%s\n' "$a_phase"
    printf 'persist.saveSucceeded=%s\n' "$a_save_succeeded"
    printf 'persist.markerLayer=3\n'
    printf 'persist.markerOffset=4\n'
    printf 'persist.markerChar=5\n'
  } > "$a_result"
elif [ "$mode" = invalid-hash ]; then
  {
    printf 'status=PASS\n'
    printf 'runId=%s\n' "$a_result_run_id"
    printf 'phase=%s\n' "$a_phase"
    printf 'persist.saveSucceeded=%s\n' "$a_save_succeeded"
    printf 'persist.postEditImageSha256=not-a-sha256\n'
  } > "$a_result"
elif [ "$mode" = image-only ]; then
  {
    printf 'status=PASS\n'
    printf 'runId=%s\n' "$a_result_run_id"
    printf 'phase=%s\n' "$a_phase"
    printf 'persist.saveSucceeded=%s\n' "$a_save_succeeded"
    printf 'persist.postEditImageSha256=%s\n' "$image_hash"
  } > "$a_result"
else
  {
    printf 'status=PASS\n'
    printf 'runId=%s\n' "$a_result_run_id"
    printf 'phase=%s\n' "$a_phase"
    printf 'persist.saveSucceeded=%s\n' "$a_save_succeeded"
    printf 'persist.markerLayer=legacy\n'
    printf 'persist.markerOffset=legacy\n'
    printf 'persist.markerChar=legacy\n'
    printf 'persist.postEditSha256=%s\n' "$file_hash"
    printf 'persist.postEditImageSha256=%s\n' "$image_hash"
  } > "$a_result"
fi

if [ "$mode" = adjacent-job ]; then
  unrelated="$host_root/external-psd-edit-pipeline/5302-025-us4/queue-unrelated"
  sleep 1
  mkdir -p "$unrelated/evidence/result" "$unrelated/turboism-home"
  printf 'unrelated copy\n' > "$unrelated/turboism-home/persisted-document.cmo3"
  {
    printf 'status=PASS\n'
    printf 'persist.markerLayer=wrong\n'
    printf 'persist.markerOffset=wrong\n'
    printf 'persist.markerChar=wrong\n'
    printf 'persist.postEditSha256=wrong-file-hash\n'
    printf 'persist.postEditImageSha256=wrong-image-hash\n'
  } > "$unrelated/evidence/result/external-psd-edit-result.properties"
fi

if [ "$mode" = incomplete-supervisor ]; then
  write_job "$a_job_id" "$a_run_id" "$a_task_dir" "$a_result" succeeded incomplete
else
  write_job "$a_job_id" "$a_run_id" "$a_task_dir" "$a_result" succeeded
fi
STUB
  chmod +x "$sandbox/scripts/preview/run-external-psd-edit-host-validation.sh"
}

run_driver_case() {
  local mode="$1" expected_status="$2"
  local case_root="$test_root/$mode"
  local sandbox="$case_root/sandbox"
  local host_root="$case_root/host root"
  local log="$case_root/stub.log"
  local output="$case_root/driver.out"
  local status
  local driver_args=()
  [ "$mode" = dry-run ] && driver_args+=(--dry-run)
  mkdir -p "$case_root"
  make_driver_sandbox "$sandbox"
  : > "$log"
  set +e
  DRIVER_STUB_MODE="$mode" \
    DRIVER_STUB_HOST_ROOT="$host_root" \
    DRIVER_STUB_LOG="$log" \
    bash "$sandbox/scripts/preview/run-external-psd-edit-persist-validation.sh" \
    "${driver_args[@]}" \
    > "$output" 2>&1
  status=$?
  set -e
  if [ "$status" -ne "$expected_status" ]; then
    record_failure "$mode returned $status, expected $expected_status; output: $(tr '\n' ' ' < "$output")"
  fi
  printf '%s\n' "$status"
}

run_success_case() {
  local mode="$1"
  local case_root="$test_root/$mode"
  local host_root="$case_root/host root"
  local expected_saved="$host_root/external-psd-edit-pipeline/5302-025-us4/queue-run-a/turboism-home/persisted-document.cmo3"
  run_driver_case "$mode" 0 >/dev/null
  assert_file_contains "$case_root/stub.log" 'phase=reopen' "$mode must run stage B"
  assert_file_contains "$case_root/stub.log" "stageB.fixture=$expected_saved" \
    "$mode must bind stage B to the stage A run"
  assert_file_contains "$case_root/stub.log" 'stageB.job=job-b' \
    "$mode must receive an independently queued stage B job"
  assert_file_contains "$case_root/stub.log" 'stageB.run=queue-run-b' \
    "$mode must receive an independently bound stage B run"
  assert_file_contains "$case_root/stub.log" \
    "stageB.taskDir=$host_root/external-psd-edit-pipeline/5302-025-us4/queue-run-b" \
    "$mode must write stage B evidence in an independent task directory"
  assert_file_contains "$case_root/stub.log" \
    'stageB.postImage=1111111111111111111111111111111111111111111111111111111111111111' \
    "$mode must pass the post-edit image hash"
  assert_file_contains "$case_root/stub.log" \
    'stageB.postFile=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff' \
    "$mode must pass the optional post-edit file hash"
  assert_file_not_contains "$case_root/stub.log" 'stageB.markerLayer=legacy' \
    "$mode must not pass the retired marker layer"
}

run_success_case correct
run_success_case adjacent-job

run_driver_case image-only 0 >/dev/null
assert_file_contains "$test_root/image-only/stub.log" 'phase=reopen' \
  'image-only evidence must run stage B'
assert_file_contains "$test_root/image-only/stub.log" 'stageB.postFile=' \
  'the optional post-edit file hash may be absent'
assert_file_contains "$test_root/image-only/stub.log" \
  'stageB.postImage=1111111111111111111111111111111111111111111111111111111111111111' \
  'image-only evidence must pass the required image hash'

run_stage_b_rejection_case() {
  local mode="$1"
  run_driver_case "$mode" 1 >/dev/null
  assert_file_contains "$test_root/$mode/stub.log" 'phase=reopen' \
    "$mode must reach stage B before its malformed evidence is rejected"
}

run_stage_b_rejection_case reuse-a-evidence
run_stage_b_rejection_case wrong-phase
run_stage_b_rejection_case wrong-run
run_stage_b_rejection_case wrong-expected-image
run_stage_b_rejection_case wrong-image
run_stage_b_rejection_case terminal-sha-mismatch
run_stage_b_rejection_case stage-b-failure
run_stage_b_rejection_case stage-b-incomplete-supervisor

run_driver_case stage-failure 1 >/dev/null
assert_file_not_contains "$test_root/stage-failure/stub.log" 'phase=reopen' \
  'stage A failure must not start stage B'

run_stage_a_rejection_case() {
  local mode="$1"
  run_driver_case "$mode" 1 >/dev/null
  assert_file_not_contains "$test_root/$mode/stub.log" 'phase=reopen' \
    "$mode must fail the stage A gate before stage B"
}

run_stage_a_rejection_case wrong-a-run
run_stage_a_rejection_case wrong-a-phase
run_stage_a_rejection_case save-not-succeeded

run_driver_case missing-hash 1 >/dev/null
assert_file_not_contains "$test_root/missing-hash/stub.log" 'phase=reopen' \
  'missing post-edit hash must fail before stage B'

run_driver_case invalid-hash 1 >/dev/null
assert_file_not_contains "$test_root/invalid-hash/stub.log" 'phase=reopen' \
  'invalid post-edit hash must fail before stage B'

run_driver_case incomplete-supervisor 1 >/dev/null
assert_file_not_contains "$test_root/incomplete-supervisor/stub.log" 'phase=reopen' \
  'incomplete supervisor evidence must not start stage B'

run_driver_case dry-run 0 >/dev/null
assert_file_not_contains "$test_root/dry-run/stub.log" 'phase=reopen' \
  'dry-run must not start stage B'
if [ -d "$test_root/dry-run/host root/external-psd-edit-pipeline" ]; then
  record_failure 'dry-run must not consume or create a task result directory'
fi
assert_file_contains "$test_root/dry-run/stub.log" 'dry-run-called' \
  'dry-run must reach the wrapper validation path'
assert_file_not_contains "$test_root/dry-run/stub.log" 'queue-submit-called' \
  'dry-run must not enqueue a job'
assert_file_not_contains "$test_root/dry-run/stub.log" 'host-launch-called' \
  'dry-run must not execute the host'

run_host_wrapper_forwarding_case() {
  local root="$test_root/host-wrapper-root"
  local fixture="$test_root/fixture with spaces.cmo3"
  local args="$test_root/host-wrapper-args"
  local output="$test_root/host-wrapper.out"
  local status
  mkdir -p "$root/scripts/preview" "$root/scripts/dev" "$root/build"
  cp "$host_wrapper_source" "$root/scripts/preview/run-external-psd-edit-host-validation.sh"
  cp "$repo_root/scripts/preview/host-validation-env.sh" "$root/scripts/preview/host-validation-env.sh"
  cp /bin/true "$root/build/external-psd-edit-host-probe.jar"
  cp /etc/hosts "$fixture"
  printf '%s\n' '#!/usr/bin/env bash' 'printf "%s\\n" fake-id' > "$root/scripts/dev/worktree-id.sh"
  chmod +x "$root/scripts/dev/worktree-id.sh"
  cat > "$root/scripts/preview/run-cubism-host-validation.sh" <<'RUNNER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" > "${DRIVER_STUB_ARG_LOG:?}"
RUNNER
  chmod +x "$root/scripts/preview/run-cubism-host-validation.sh"
  set +e
  TURBOISM_ENV_FILE=/dev/null \
    TURBOISM_HOST_VALIDATION_FIXTURE_5302="$fixture" \
    TURBOISM_HOST_VALIDATION_FIXTURE_5302_SHA256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    EXTERNAL_PSD_POSTEDITSHA256=file-hash \
    EXTERNAL_PSD_POSTEDITIMAGESHA256=image-hash \
    DRIVER_STUB_ARG_LOG="$args" \
    bash "$root/scripts/preview/run-external-psd-edit-host-validation.sh" --dry-run \
    > "$output" 2>&1
  status=$?
  set -e
  if [ "$status" -ne 0 ]; then
    record_failure "host wrapper forwarding returned $status; output: $(tr '\n' ' ' < "$output")"
    return
  fi
  python3 - "$args" "$fixture" <<'PY' || record_failure 'host wrapper did not preserve spaced fixture/hash JVM arguments'
import sys

args = open(sys.argv[1], encoding="utf-8").read().splitlines()
fixture = sys.argv[2]
expected = [
    ("--fixture-host", fixture),
    ("--jvm-option", "-Dturboism.validation.externalpsd.postEditSha256=file-hash"),
    ("--jvm-option", "-Dturboism.validation.externalpsd.postEditImageSha256=image-hash"),
]
for flag, value in expected:
    if any(args[index:index + 2] == [flag, value] for index in range(len(args) - 1)):
        continue
    raise SystemExit(f"missing adjacent argv pair: {flag} {value}")
if any("externalpsd.marker" in value for value in args):
    raise SystemExit("retired marker argument was forwarded")
PY
}

run_host_wrapper_forwarding_case

if [ "$failures" -ne 0 ]; then
  printf '%s focused assertion(s) failed\n' "$failures" >&2
  exit 1
fi
printf 'external PSD persistence driver focused tests passed\n'
