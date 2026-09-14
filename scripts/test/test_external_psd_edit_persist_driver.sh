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
run_id="queue-run-a"
job_id="job-a"
task_dir="$host_root/external-psd-edit-pipeline/5302-025-us4/$run_id"

printf 'phase=%s\n' "$phase" >> "$log"

write_job() {
  local state="$1" incomplete="${2:-0}"
  python3 - "$job_id" "$run_id" "$state" "$incomplete" "$task_dir" <<'PY'
import json
import sys

job_id, run_id, state, incomplete, task_dir = sys.argv[1:]
attempt_id = "attempt-" + job_id
digest = "digest-" + job_id
if incomplete == "1":
    evidence = {"schemaVersion": 1}
else:
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
            "postContainmentChecks": {"terminalResult": {"passed": True}},
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
  write_job succeeded
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

mkdir -p "$task_dir/evidence/result" "$task_dir/turboism-home"
printf 'persisted copy from %s\n' "$mode" > "$task_dir/turboism-home/persisted-document.cmo3"

if [ "$mode" = missing-hash ]; then
  {
    printf 'status=PASS\n'
    printf 'persist.markerLayer=3\n'
    printf 'persist.markerOffset=4\n'
    printf 'persist.markerChar=5\n'
  } > "$task_dir/evidence/result/external-psd-edit-result.properties"
elif [ "$mode" = image-only ]; then
  {
    printf 'status=PASS\n'
    printf 'persist.postEditImageSha256=%s\n' "$image_hash"
  } > "$task_dir/evidence/result/external-psd-edit-result.properties"
else
  {
    printf 'status=PASS\n'
    printf 'persist.markerLayer=legacy\n'
    printf 'persist.markerOffset=legacy\n'
    printf 'persist.markerChar=legacy\n'
    printf 'persist.postEditSha256=%s\n' "$file_hash"
    printf 'persist.postEditImageSha256=%s\n' "$image_hash"
  } > "$task_dir/evidence/result/external-psd-edit-result.properties"
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
  write_job succeeded 1
else
  write_job succeeded
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

run_driver_case stage-failure 1 >/dev/null
assert_file_not_contains "$test_root/stage-failure/stub.log" 'phase=reopen' \
  'stage A failure must not start stage B'

run_driver_case missing-hash 1 >/dev/null
assert_file_not_contains "$test_root/missing-hash/stub.log" 'phase=reopen' \
  'missing post-edit hash must fail before stage B'

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
