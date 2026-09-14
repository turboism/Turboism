#!/usr/bin/env bash
# Two-stage 025 persistence evidence: stage A runs the pipeline with the mediated
# SAVE_AS persist tail; stage B reopens the saved copy and verifies the
# external-edit image content survived the real native save.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
wrapper="$root/scripts/preview/run-external-psd-edit-host-validation.sh"

die() {
  echo "external PSD persistence validation: $*" >&2
  exit 1
}

has_dry_run=0
for argument in "$@"; do
  if [ "$argument" = --dry-run ]; then
    has_dry_run=1
    break
  fi
done

# A dry-run is an argument-validation operation only. The wrapper still gets to
# render the generic Runner plan, but there is no queue result to consume and no
# second phase that could accidentally launch a host or use old evidence.
if [ "$has_dry_run" = 1 ]; then
  echo '== dry-run: validate stage A only; no queue result or stage B'
  if EXTERNAL_PSD_PHASE=pipeline EXTERNAL_PSD_PERSIST=1 \
    bash "$wrapper" "$@"; then
    exit 0
  else
    exit $?
  fi
fi

stage_a_log="$(mktemp "${TMPDIR:-/tmp}/external-psd-persist-stage-a.XXXXXX")"
stage_b_log="$(mktemp "${TMPDIR:-/tmp}/external-psd-persist-stage-b.XXXXXX")"
trap 'rm -f -- "$stage_a_log" "$stage_b_log"' EXIT

echo '== stage A: pipeline + SAVE_AS persist tail'
if EXTERNAL_PSD_PHASE=pipeline EXTERNAL_PSD_PERSIST=1 \
  bash "$wrapper" "$@" >"$stage_a_log" 2>&1; then
  stage_a_status=0
else
  stage_a_status=$?
fi
cat "$stage_a_log"
[ "$stage_a_status" -eq 0 ] \
  || die "stage A runner failed (exit $stage_a_status); stage B was not started"

# The generic Runner's direct queue client prints the final wait response. Bind
# every subsequent read to that response's terminal job/run and require the
# same structured evidence the queue uses for a succeeded job. This deliberately
# rejects a zero exit, an incomplete Supervisor record, or a result directory
# found by a timestamp-based scan.
read_terminal_job() {
  local log_file="$1" stage_name="$2"
  python3 - "$log_file" "$stage_name" <<'PY'
import json
import re
import sys
from pathlib import Path

log_file, stage_name = sys.argv[1:]
records = []
for raw_line in Path(log_file).read_text(encoding="utf-8", errors="replace").splitlines():
    try:
        value = json.loads(raw_line)
    except json.JSONDecodeError:
        continue
    if isinstance(value, dict) and isinstance(value.get("job"), dict):
        records.append(value["job"])
if not records:
    raise SystemExit(f"{stage_name} has no final queue job response")

job = records[-1]
job_id = job.get("job_id")
attempt_id = job.get("attempt_id")
run_id = job.get("run_id")
digest = job.get("digest")
for name, value in (("job_id", job_id), ("attempt_id", attempt_id),
                    ("run_id", run_id), ("digest", digest)):
    if not isinstance(value, str) or not value or "\0" in value:
        raise SystemExit(f"{stage_name} queue response has invalid {name}")
if not re.fullmatch(r"[A-Za-z0-9._-]+", run_id):
    raise SystemExit(f"{stage_name} queue response has unsafe run_id")
if job.get("state") != "succeeded":
    raise SystemExit(f"{stage_name} queue job did not succeed: {job.get('state')!r}")

raw_evidence = job.get("evidence_json")
if not isinstance(raw_evidence, str):
    raise SystemExit(f"{stage_name} queue job has no final Supervisor evidence")
try:
    evidence = json.loads(raw_evidence)
except json.JSONDecodeError as failure:
    raise SystemExit(f"{stage_name} Supervisor evidence is not JSON: {failure}")
if not isinstance(evidence, dict):
    raise SystemExit(f"{stage_name} Supervisor evidence is not an object")

expected = {
    "jobId": job_id,
    "attemptId": attempt_id,
    "runId": run_id,
    "preparedDigest": digest,
}
for name, value in expected.items():
    if evidence.get(name) != value:
        raise SystemExit(f"{stage_name} Supervisor evidence identity mismatch: {name}")
for name, value in (
    ("schemaVersion", 1),
    ("cleanup", "safe"),
    ("validationStatus", "PASS"),
    ("identityVerified", True),
    ("fixtureUnchanged", True),
    ("normalExit", True),
    ("terminalState", "succeeded"),
    ("finalizedBy", "contained-supervisor"),
):
    if evidence.get(name) != value:
        raise SystemExit(f"{stage_name} Supervisor evidence is incomplete: {name}")
details = evidence.get("details")
if not isinstance(details, dict):
    raise SystemExit(f"{stage_name} Supervisor evidence has no details")
if details.get("taskOwnedCleanup") is not True or details.get("validationComplete") is not True:
    raise SystemExit(f"{stage_name} Supervisor evidence has incomplete task cleanup")
containment_checks = details.get("postContainmentChecks")
terminal_result = containment_checks.get("terminalResult") if isinstance(containment_checks, dict) else None
if not isinstance(terminal_result, dict) or terminal_result.get("passed") is not True:
    raise SystemExit(f"{stage_name} Supervisor evidence has no verified terminal result")
task_dir = details.get("taskDir")
if not isinstance(task_dir, str) or not task_dir or not Path(task_dir).is_absolute():
    raise SystemExit(f"{stage_name} Supervisor evidence has no absolute task directory")
task_path = Path(task_dir)
if ".." in task_path.parts or task_path == Path("/") or task_path.name != run_id:
    raise SystemExit(f"{stage_name} Supervisor evidence has an unsafe task directory")

print("\t".join((job_id, attempt_id, run_id, digest, task_dir)))
PY
}

if ! stage_a_binding="$(read_terminal_job "$stage_a_log" stage-A)"; then
  die "stage A lacks complete final Supervisor evidence; stage B was not started"
fi
IFS=$'\t' read -r stage_a_job_id stage_a_attempt_id stage_a_run_id stage_a_digest stage_a_task_dir \
  <<< "$stage_a_binding"
[ -n "${stage_a_job_id:-}" ] && [ -n "${stage_a_attempt_id:-}" ] \
  && [ -n "${stage_a_run_id:-}" ] && [ -n "${stage_a_digest:-}" ] \
  && [ -n "${stage_a_task_dir:-}" ] \
  || die 'stage A queue binding is incomplete; stage B was not started'

task_dir="$stage_a_task_dir"
[ ! -L "$task_dir" ] || die "stage A task directory is a symlink: $task_dir"
result_a="$task_dir/evidence/result/external-psd-edit-result.properties"
[ -f "$result_a" ] && [ ! -L "$result_a" ] \
  || die "stage A result missing for job $stage_a_job_id / run $stage_a_run_id: $result_a"

property() {
  local key="$1"
  awk -F= -v wanted="$key" '$1 == wanted { print substr($0, index($0, "=") + 1); exit }' "$result_a"
}

[ "$(property status)" = PASS ] \
  || die "stage A result did not PASS for job $stage_a_job_id / run $stage_a_run_id"

saved="$task_dir/turboism-home/persisted-document.cmo3"
[ -f "$saved" ] && [ ! -L "$saved" ] \
  || die "persisted document missing for job $stage_a_job_id / run $stage_a_run_id: $saved"

# The reopen probe gates on exported image data. The full-file digest is useful
# context and is optional; the retired marker coordinates are intentionally not
# read or forwarded.
post_file_sha256="$(property persist.postEditSha256)"
post_image_sha256="$(property persist.postEditImageSha256)"
[[ "$post_image_sha256" =~ ^[0-9a-fA-F]{64}$ ]] \
  || die "stage A is missing a valid persist.postEditImageSha256; stage B was not started"
if [ -n "$post_file_sha256" ] && [[ ! "$post_file_sha256" =~ ^[0-9a-fA-F]{64}$ ]]; then
  die 'stage A has an invalid persist.postEditSha256; stage B was not started'
fi

echo "== stage B: reopen $saved (post-edit image hash=$post_image_sha256)"
if (
  unset EXTERNAL_PSD_MARKERLAYER EXTERNAL_PSD_MARKEROFFSET EXTERNAL_PSD_MARKERCHAR
  EXTERNAL_PSD_PHASE=reopen \
    EXTERNAL_PSD_FIXTURE_LOCAL="$saved" \
    EXTERNAL_PSD_POSTEDITSHA256="$post_file_sha256" \
    EXTERNAL_PSD_POSTEDITIMAGESHA256="$post_image_sha256" \
    bash "$wrapper" "$@" >"$stage_b_log" 2>&1
); then
  stage_b_status=0
else
  stage_b_status=$?
fi
cat "$stage_b_log"
[ "$stage_b_status" -eq 0 ] || die "stage B runner failed (exit $stage_b_status)"

if ! stage_b_binding="$(read_terminal_job "$stage_b_log" stage-B)"; then
  die 'stage B lacks complete final Supervisor evidence'
fi
IFS=$'\t' read -r stage_b_job_id stage_b_attempt_id stage_b_run_id stage_b_digest stage_b_task_dir \
  <<< "$stage_b_binding"
stage_b_result="$stage_b_task_dir/evidence/result/external-psd-edit-result.properties"
[ -f "$stage_b_result" ] && [ ! -L "$stage_b_result" ] \
  || die "stage B result missing for job $stage_b_job_id / run $stage_b_run_id: $stage_b_result"
awk -F= '$1 == "status" { found = ($2 == "PASS"); seen = 1; exit } END { exit !(seen && found) }' \
  "$stage_b_result" \
  || die "stage B result did not PASS for job $stage_b_job_id / run $stage_b_run_id"
