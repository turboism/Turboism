#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
FIXTURES="${REPO_ROOT}/testframework/src/main/resources/fixtures/schema"
LEDGER="${REPO_ROOT}/docs/migration/automated-tranche-ledger.tsv"
SYNTHETIC="${REPO_ROOT}/docs/migration/evidence/synthetic-composition-evidence-v1.json"

fail() { echo "FAIL: $1" >&2; exit 1; }

expect_valid() {
  local contract="$1" path="$2"
  [ -f "${path}" ] || fail "required valid input missing: ${path}"
  python3 "${SCRIPT_DIR}/validate_phase4_json_contract.py" "${contract}" "${path}" --fixture-mode
}

expect_invalid() {
  local contract="$1" path="$2"
  [ -f "${path}" ] || fail "required invalid fixture missing: ${path}"
  if python3 "${SCRIPT_DIR}/validate_phase4_json_contract.py" "${contract}" "${path}" --fixture-mode >/dev/null 2>&1; then
    fail "invalid fixture passed: ${path}"
  fi
}

expect_ledger_invalid() {
  local path="$1"
  [ -f "${path}" ] || fail "required invalid ledger fixture missing: ${path}"
  if python3 "${SCRIPT_DIR}/validate_automated_tranche_ledger.py" --schema-only --repo-root "${REPO_ROOT}" "${path}" >/dev/null 2>&1; then
    fail "invalid ledger fixture passed: ${path}"
  fi
}

[ -f "${LEDGER}" ] || fail "authoritative ledger missing"
python3 "${SCRIPT_DIR}/validate_automated_tranche_ledger.py" --repo-root "${REPO_ROOT}" "${LEDGER}"

ledger_fixtures="${FIXTURES}/automated-tranche-ledger-v1"
[ -f "${ledger_fixtures}/valid/minimal.tsv" ] || fail "valid ledger schema fixture missing"
python3 "${SCRIPT_DIR}/validate_automated_tranche_ledger.py" --schema-only --repo-root "${REPO_ROOT}" "${ledger_fixtures}/valid/minimal.tsv"
for name in absolute-evidence-path bad-status missing-column noncanonical-evidence-path readiness-overclaim; do
  expect_ledger_invalid "${ledger_fixtures}/invalid/${name}.tsv"
done

python3 "${SCRIPT_DIR}/validate_phase4_json_contract.py" synthetic "${SYNTHETIC}" --authoritative --repo-root "${REPO_ROOT}"
expect_valid synthetic "${FIXTURES}/synthetic-composition-evidence-v1/valid/phase3-index.json"
for name in absolute-path trust-root-conflation unknown-field wrong-source-commit; do
  expect_invalid synthetic "${FIXTURES}/synthetic-composition-evidence-v1/invalid/${name}.json"
done

packaging_valid="${FIXTURES}/pre-m16-packaging-dryrun-manifest-v1/valid/synthetic-worktree.json"
expect_valid packaging "${packaging_valid}"
grep -Fq 'build/worktree/automation-prem16-dryrun/' "${packaging_valid}" || fail "packaging fixture lacks required worktree path pattern"
grep -Fq -- '-automation-prem16-dryrun.jar' "${packaging_valid}" || fail "packaging fixture lacks worktree-scoped artifact suffix"
for name in bad-time date-only-time noncanonical-path traversal-path unknown-field unscoped-artifact; do
  expect_invalid packaging "${FIXTURES}/pre-m16-packaging-dryrun-manifest-v1/invalid/${name}.json"
done

tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT
printf '\357\273\277{}' > "${tmpdir}/bom.json"
printf '\377' > "${tmpdir}/invalid-utf8.json"
expect_invalid synthetic "${tmpdir}/bom.json"
expect_invalid packaging "${tmpdir}/invalid-utf8.json"

# Authoritative-mode regressions bind canonical references to both the worktree and sourceCommit.
python3 - "${REPO_ROOT}" "${tmpdir}" <<'PY'
import json
import shutil
import subprocess
import sys
from pathlib import Path

source_root, target = map(Path, sys.argv[1:])
doc = json.loads((source_root / "docs/migration/evidence/synthetic-composition-evidence-v1.json").read_text())
required_paths = sorted({
    value
    for item in doc["slices"]
    for value in [item["staticEvidencePath"], item["reportPath"], *item["testPaths"]]
})

for case in ("non-git-repo", "missing-commit", "commit-missing-ref", "wrong-digest", "missing-evidence", "missing-report", "missing-test"):
    root = target / case / "repo"
    manifest = target / case / "evidence.json"
    mutated = json.loads(json.dumps(doc))
    for value in required_paths:
        src, dst = source_root / value, root / value
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)
    if case != "non-git-repo":
        subprocess.run(["git", "-C", str(root), "init", "-q"], check=True)
        subprocess.run(["git", "-C", str(root), "config", "user.email", "phase4@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(root), "config", "user.name", "Phase 4 Test"], check=True)
        commit_paths = required_paths
        if case == "commit-missing-ref":
            commit_paths = [value for value in required_paths if value != mutated["slices"][0]["staticEvidencePath"]]
        subprocess.run(["git", "-C", str(root), "add", "--", *commit_paths], check=True)
        subprocess.run(["git", "-C", str(root), "commit", "-qm", "fixture"], check=True)
        commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
        mutated["sourceCommit"] = commit
    if case == "missing-commit":
        mutated["sourceCommit"] = "0" * 40
    elif case == "wrong-digest":
        mutated["slices"][0]["trustRootSha256"] = "0" * 64
    elif case == "missing-evidence":
        (root / mutated["slices"][0]["staticEvidencePath"]).unlink()
    elif case == "missing-report":
        (root / mutated["slices"][0]["reportPath"]).unlink()
    elif case == "missing-test":
        (root / mutated["slices"][0]["testPaths"][0]).unlink()
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps(mutated), encoding="utf-8")
PY
python3 - "${SCRIPT_DIR}" "${tmpdir}" <<'PY'
import importlib.util
import json
import sys
from pathlib import Path

script_dir, target = map(Path, sys.argv[1:])
sys.path.insert(0, str(script_dir))
spec = importlib.util.spec_from_file_location("phase4_validator", script_dir / "validate_phase4_json_contract.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader
spec.loader.exec_module(module)
expected_codes = {
    "non-git-repo": "NOT_GIT_WORKTREE",
    "missing-commit": "MISSING_SOURCE_COMMIT",
    "commit-missing-ref": "MISSING_COMMIT_REFERENCE",
    "wrong-digest": "TRUST_ROOT_DIGEST_MISMATCH",
    "missing-evidence": "MISSING_EVIDENCE",
    "missing-report": "MISSING_EVIDENCE",
    "missing-test": "MISSING_EVIDENCE",
}
for case, expected_code in expected_codes.items():
    manifest = target / case / "evidence.json"
    document = json.loads(manifest.read_text(encoding="utf-8"))
    module.PHASE3_COMMIT = document["sourceCommit"]
    errors = module.validate_synthetic(document, authoritative=True, repo_root=target / case / "repo")
    codes = {item["code"] for item in errors}
    assert expected_code in codes, f"{case}: expected {expected_code}, got {errors}"
print("PASS: authoritative synthetic mutation regressions")
PY

python3 "${SCRIPT_DIR}/test_automated_tranche_ledger_regression.py"
echo "PASS: Phase 4 build gates"
