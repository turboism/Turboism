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
baseline_target_hash="abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"
post_target_hash="fedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedc"
case_baseline_target_hash="$baseline_target_hash"
legacy_reopen_image_hash="8888888888888888888888888888888888888888888888888888888888888888"
a_run_id="queue-run-a"
a_job_id="job-a"
a_task_dir="$host_root/external-psd-edit-pipeline/5302-025-us4/$a_run_id"
b_run_id="queue-run-b"
b_job_id="job-b"
b_task_dir="$host_root/external-psd-edit-pipeline/5302-025-us4/$b_run_id"
result_name="external-psd-edit-result.properties"

printf 'phase=%s\n' "$phase" >> "$log"

convert_result_to_crlf() {
  python3 - "$1" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
raw = path.read_bytes()
path.write_bytes(raw.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n"))
PY
}

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
  printf 'stageB.postTarget=%s\n' "${EXTERNAL_PSD_POSTEDITTARGETRGBSHA256:-}" >> "$log"
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
  b_expected_target="${EXTERNAL_PSD_POSTEDITTARGETRGBSHA256:-}"
  b_actual_target="$b_expected_target"
  b_phase=reopen
  b_result_run_id="$b_run_id"
  case "$mode" in
    wrong-phase) b_phase=pipeline ;;
    wrong-run) b_result_run_id="$a_run_id" ;;
    wrong-expected-target) b_expected_target="$file_hash" ;;
    wrong-target) b_actual_target="$file_hash" ;;
    missing-target) b_expected_target=""; b_actual_target="" ;;
    target-case-variant)
      b_expected_target="${b_expected_target^^}"
      b_actual_target="${b_actual_target^^}"
      ;;
    legacy-composite-different) b_actual="$legacy_reopen_image_hash" ;;
  esac
  {
    printf 'status=PASS\n'
    printf 'runId=%s\n' "$b_result_run_id"
    printf 'phase=%s\n' "$b_phase"
    printf 'reopen.expectedTargetRgbSha256=%s\n' "$b_expected_target"
    printf 'reopen.targetRgbSha256=%s\n' "$b_actual_target"
    printf 'reopen.expectedImageSha256=%s\n' "$b_expected"
    printf 'reopen.imageSha256=%s\n' "$b_actual"
  } > "$b_result"
  if [ "$mode" = crlf ]; then
    convert_result_to_crlf "$b_result"
  fi
  printf 'stageB.job=%s\n' "$b_job_id" >> "$log"
  printf 'stageB.run=%s\n' "$b_run_id" >> "$log"
  printf 'stageB.taskDir=%s\n' "$b_task_dir" >> "$log"
  printf 'stageB.result=%s\n' "$b_result" >> "$log"
  printf 'stageB.resultImage=%s\n' "$b_actual" >> "$log"
  printf 'stageB.resultTarget=%s\n' "$b_actual_target" >> "$log"
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
a_baseline_target_hash="$baseline_target_hash"
a_second_target_hash="$baseline_target_hash"
a_post_target_hash="$post_target_hash"
a_target_changed=true
a_quarantine_status=MOVED
a_quarantine_owned=true
a_quarantine_source_missing=true
a_post_file_hash="$file_hash"
a_post_image_hash="$image_hash"
a_omit_target_fields=0
a_omit_legacy_fields=0
case "$mode" in
  wrong-a-run) a_result_run_id=queue-run-other ;;
  wrong-a-phase) a_phase=reopen ;;
  save-not-succeeded) a_save_succeeded=false ;;
  missing-hash|old-composite-only|target-missing|image-only) a_omit_target_fields=1 ;;
  rgb-only) a_omit_legacy_fields=1 ;;
  invalid-hash) a_post_target_hash=not-a-sha256 ;;
  baseline-unstable) a_second_target_hash="4444444444444444444444444444444444444444444444444444444444444444" ;;
  target-unchanged) a_post_target_hash="$a_baseline_target_hash" ;;
  target-mismatch) a_target_changed=false ;;
  hash-case-variant)
    a_baseline_target_hash="$case_baseline_target_hash"
    a_second_target_hash="$case_baseline_target_hash"
    a_post_target_hash="${case_baseline_target_hash^^}"
    ;;
  quarantine-failure) a_quarantine_status=FAILED ;;
  quarantine-incomplete)
    a_quarantine_owned=false
    a_quarantine_source_missing=false
    ;;
  legacy-composite-different)
    a_post_file_hash="eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    a_post_image_hash="9999999999999999999999999999999999999999999999999999999999999999"
    ;;
esac

{
  printf 'status=PASS\n'
  printf 'runId=%s\n' "$a_result_run_id"
  printf 'phase=%s\n' "$a_phase"
  printf 'persist.saveSucceeded=%s\n' "$a_save_succeeded"
  printf 'persist.markerLayer=legacy\n'
  printf 'persist.markerOffset=legacy\n'
  printf 'persist.markerChar=legacy\n'
  if [ "$a_omit_target_fields" = 0 ]; then
    printf 'persist.baselineTargetRgbSha256=%s\n' "$a_baseline_target_hash"
    printf 'persist.baselineSecondTargetRgbSha256=%s\n' "$a_second_target_hash"
    printf 'persist.postEditTargetRgbSha256=%s\n' "$a_post_target_hash"
    printf 'persist.targetContentChanged=%s\n' "$a_target_changed"
    printf 'persist.tempQuarantine.status=%s\n' "$a_quarantine_status"
    printf 'persist.tempQuarantine.taskOwned=%s\n' "$a_quarantine_owned"
    printf 'persist.tempQuarantine.sourceMissing=%s\n' "$a_quarantine_source_missing"
  fi
  if [ "$a_omit_legacy_fields" = 0 ]; then
    printf 'persist.postEditSha256=%s\n' "$a_post_file_hash"
    printf 'persist.postEditImageSha256=%s\n' "$a_post_image_hash"
  fi
} > "$a_result"

if [ "$mode" = crlf ]; then
  convert_result_to_crlf "$a_result"
fi
printf 'stageA.result=%s\n' "$a_result" >> "$log"
printf 'stageA.resultImage=%s\n' "$a_post_image_hash" >> "$log"
printf 'stageA.resultTarget=%s\n' "$a_post_target_hash" >> "$log"

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
    'stageB.postTarget=fedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedc' \
    "$mode must pass the post-edit target RGB hash"
  assert_file_contains "$case_root/stub.log" \
    'stageB.postFile=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff' \
    "$mode must pass the optional post-edit file hash"
  assert_file_not_contains "$case_root/stub.log" 'stageB.markerLayer=legacy' \
    "$mode must not pass the retired marker layer"
}

run_success_case correct
run_success_case adjacent-job
run_success_case crlf

run_driver_case legacy-composite-different 0 >/dev/null
assert_file_contains "$test_root/legacy-composite-different/stub.log" 'phase=reopen' \
  'different legacy composite evidence must not block a valid target RGB round trip'
assert_file_contains "$test_root/legacy-composite-different/stub.log" \
  'stageB.postTarget=fedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedc' \
  'target RGB evidence remains the stage B gate when legacy hashes differ'
assert_file_contains "$test_root/legacy-composite-different/stub.log" \
  'stageB.resultImage=8888888888888888888888888888888888888888888888888888888888888888' \
  'stage B may report a legacy composite digest different from stage A while RGB evidence matches'
assert_file_contains "$test_root/legacy-composite-different/stub.log" \
  'stageA.resultImage=9999999999999999999999999999999999999999999999999999999999999999' \
  'stage A legacy composite digest must be recorded for the mismatch regression'
assert_file_not_contains "$test_root/legacy-composite-different/stub.log" \
  'stageB.resultImage=9999999999999999999999999999999999999999999999999999999999999999' \
  'legacy composite mismatch must not be hidden by replaying the stage A digest'
assert_file_contains "$test_root/legacy-composite-different/stub.log" \
  'stageB.resultTarget=fedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedc' \
  'legacy composite mismatch must retain matching RGB reopen evidence'
legacy_a_image="$(awk -F= '$1 == "stageA.resultImage" { print substr($0, index($0, "=") + 1); exit }' \
  "$test_root/legacy-composite-different/stub.log")"
legacy_b_image="$(awk -F= '$1 == "stageB.resultImage" { print substr($0, index($0, "=") + 1); exit }' \
  "$test_root/legacy-composite-different/stub.log")"
if [ -z "$legacy_a_image" ] || [ -z "$legacy_b_image" ] || [ "$legacy_a_image" = "$legacy_b_image" ]; then
  record_failure 'legacy composite success case did not prove a distinct stage B reopen.imageSha256'
fi
legacy_a_target="$(awk -F= '$1 == "stageA.resultTarget" { print substr($0, index($0, "=") + 1); exit }' \
  "$test_root/legacy-composite-different/stub.log")"
legacy_b_target="$(awk -F= '$1 == "stageB.resultTarget" { print substr($0, index($0, "=") + 1); exit }' \
  "$test_root/legacy-composite-different/stub.log")"
if [ -z "$legacy_a_target" ] || [ "$legacy_a_target" != "$legacy_b_target" ]; then
  record_failure 'legacy composite success case did not prove matching stage A/B target RGB evidence'
fi

assert_logged_result_is_crlf() {
  local log="$1" key="$2" description="$3" result_path
  result_path="$(awk -F= -v wanted="$key" \
    '$1 == wanted { print substr($0, index($0, "=") + 1); exit }' "$log")"
  if [ -z "$result_path" ]; then
    record_failure "$description (result path was not logged)"
    return
  fi
  if ! python3 - "$result_path" <<'PY'
import sys
from pathlib import Path

raw = Path(sys.argv[1]).read_bytes()
if b"\r\n" not in raw:
    raise SystemExit("result has no CRLF line endings")
if b"\n" in raw.replace(b"\r\n", b""):
    raise SystemExit("result contains a bare LF line ending")
PY
  then
    record_failure "$description (result is not CRLF)"
  fi
}

assert_logged_result_is_crlf "$test_root/crlf/stub.log" stageA.result \
  'stage A result must preserve CRLF input'
assert_logged_result_is_crlf "$test_root/crlf/stub.log" stageB.result \
  'stage B result must preserve CRLF input'

run_stage_b_rejection_case() {
  local mode="$1"
  run_driver_case "$mode" 1 >/dev/null
  assert_file_contains "$test_root/$mode/stub.log" 'phase=reopen' \
    "$mode must reach stage B before its malformed evidence is rejected"
}

run_stage_b_rejection_case reuse-a-evidence
run_stage_b_rejection_case wrong-phase
run_stage_b_rejection_case wrong-run
run_stage_b_rejection_case wrong-expected-target
run_stage_b_rejection_case wrong-target
run_stage_b_rejection_case missing-target
run_stage_b_rejection_case target-case-variant
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
run_stage_a_rejection_case missing-hash
run_stage_a_rejection_case old-composite-only
run_stage_a_rejection_case target-missing
run_stage_a_rejection_case image-only
run_stage_a_rejection_case invalid-hash
run_stage_a_rejection_case baseline-unstable
run_stage_a_rejection_case target-unchanged
run_stage_a_rejection_case target-mismatch
run_stage_a_rejection_case hash-case-variant
run_stage_a_rejection_case quarantine-failure
run_stage_a_rejection_case quarantine-incomplete

run_driver_case rgb-only 0 >/dev/null
assert_file_contains "$test_root/rgb-only/stub.log" 'phase=reopen' \
  'RGB-only evidence must run stage B without legacy diagnostics'
assert_file_contains "$test_root/rgb-only/stub.log" \
  'stageB.postTarget=fedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedcbafedc' \
  'RGB-only evidence must pass the target RGB hash'
assert_file_contains "$test_root/rgb-only/stub.log" 'stageB.postImage=' \
  'RGB-only evidence must not synthesize a legacy composite hash'
assert_file_contains "$test_root/rgb-only/stub.log" 'stageB.postFile=' \
  'RGB-only evidence must not synthesize a legacy file hash'

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
  target_hash=2222222222222222222222222222222222222222222222222222222222222222
  TURBOISM_ENV_FILE=/dev/null \
    TURBOISM_HOST_VALIDATION_FIXTURE_5302="$fixture" \
    TURBOISM_HOST_VALIDATION_FIXTURE_5302_SHA256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    EXTERNAL_PSD_POSTEDITSHA256=file-hash \
    EXTERNAL_PSD_POSTEDITIMAGESHA256=image-hash \
    EXTERNAL_PSD_POSTEDITTARGETRGBSHA256="$target_hash" \
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
    ("--jvm-option", "-Dturboism.validation.externalpsd.postEditTargetRgbSha256=" + "2" * 64),
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
