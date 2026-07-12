#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
PLAN_REL='docs/migration/plans/bytecode-tooling-bt6a-first-hook-readiness-plan.md'
REPORT_REL='docs/migration/bytecode-tooling-bt6a-first-hook-readiness-report.md'
CONTRACT_REL='docs/migration/bytecode-tooling-bt6a-first-hook-readiness.tsv'
GATE_REL='scripts/test/test_bt6a_first_hook_readiness.sh'
ADR_REL='docs/adr/0021-read-command-event-planes.md'
BT6_REL='docs/migration/bytecode-tooling-bt6-hook-backend-entry-review.tsv'
CATALOG_REL='docs/migration/capabilities/capability-catalog.tsv'
EXPECTED_PATHS=("$PLAN_REL" "$REPORT_REL" "$CONTRACT_REL" "$GATE_REL" "$ADR_REL")

fail() {
  printf 'BT6A first-hook readiness gate: %s\n' "$*" >&2
  return 1
}

usage() {
  printf '%s\n' \
    'Usage:' \
    '  scripts/test/test_bt6a_first_hook_readiness.sh --validate-contract' \
    '  scripts/test/test_bt6a_first_hook_readiness.sh --review-boundary [baseline]' \
    '  scripts/test/test_bt6a_first_hook_readiness.sh --self-test' \
    '' \
    'The baseline defaults to HEAD and must resolve unambiguously to a commit.' \
    'The review rejects hidden tracked state and ignored source/protected paths.'
}

validate_contract() {
  local root=${1:-$ROOT}
  python3 - "$root" "$CONTRACT_REL" "$PLAN_REL" "$REPORT_REL" "$GATE_REL" "$ADR_REL" "$BT6_REL" "$CATALOG_REL" <<'PY'
import os
import re
import stat
import sys

root, contract_rel, plan_rel, report_rel, gate_rel, adr_rel, bt6_rel, catalog_rel = sys.argv[1:]
expected = {
    "schema": "dev.turboism.migration.bt6a-first-hook-readiness",
    "version": "1",
    "reviewStatus": "FIRST_HOOK_READINESS_INVESTIGATED",
    "entryDecision": "DEFER",
    "implementationAuthorizationStatus": "NOT_AUTHORIZED",
    "committedClosureStatus": "NOT_PERFORMED",
    "planPath": plan_rel,
    "reportPath": report_rel,
    "contractPath": contract_rel,
    "gatePath": gate_rel,
    "architectureReferencePath": adr_rel,
    "sourceBt6ContractPath": bt6_rel,
    "capabilityCatalogPath": catalog_rel,
    "candidateEvidence.candidateId": "project-workspace.lifecycle-observation",
    "candidateEvidence.reconciliationReadSliceId": "adapter.project-workspace.readonly",
    "candidateEvidence.capabilityIds": "cubism.project.read;cubism.workspace.read",
    "candidateEvidence.investigationDomain": "HOOK_INGRESS",
    "candidateEvidence.hookRequirementStatus": "UNPROVEN",
    "candidateEvidence.selectionStatus": "NOT_SELECTED",
    "candidateEvidence.authorizationStatus": "NOT_AUTHORIZED",
    "clipmaskDisposition": "REJECTED_FOR_BT6A",
    "asmBackendSelectionStatus": "NOT_SELECTED",
    "asmBackendAuthorizationStatus": "NOT_AUTHORIZED",
    "byteBuddyBackendSelectionStatus": "NOT_SELECTED",
    "byteBuddyBackendAuthorizationStatus": "NOT_AUTHORIZED",
}

def die(message):
    raise SystemExit(f"BT6A first-hook readiness gate: {message}")

def confined_regular(rel):
    if not rel or os.path.isabs(rel) or "\\" in rel or ".." in rel.split("/"):
        die(f"invalid repository-relative path: {rel}")
    current = root
    for part in rel.split("/")[:-1]:
        current = os.path.join(current, part)
        try:
            mode = os.lstat(current).st_mode
        except OSError:
            die(f"missing parent: {rel}")
        if stat.S_ISLNK(mode) or not stat.S_ISDIR(mode):
            die(f"parent is not a real directory: {rel}")
    leaf = os.path.join(root, rel)
    try:
        mode = os.lstat(leaf).st_mode
    except OSError:
        die(f"missing path: {rel}")
    if stat.S_ISLNK(mode) or not stat.S_ISREG(mode):
        die(f"path is not a regular non-symlink file: {rel}")

def physical_tsv(rel):
    data = open(os.path.join(root, rel), "rb").read()
    if data.startswith(b"\xef\xbb\xbf"):
        die(f"{rel}: UTF-8 BOM is forbidden")
    if b"\r" in data or b"\0" in data:
        die(f"{rel}: CR and NUL bytes are forbidden")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        die(f"{rel}: invalid UTF-8")
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    if not lines or any(line == "" for line in lines):
        die(f"{rel}: blank physical lines are forbidden")
    rows = [line.split("\t") for line in lines]
    width = len(rows[0])
    if width == 0 or any(cell.strip() == "" or cell != cell.strip() for cell in rows[0]) or len(set(rows[0])) != width:
        die(f"{rel}: header columns must be whitespace-strict, non-empty, and unique")
    for number, row in enumerate(rows[1:], 2):
        if len(row) != width:
            die(f"{rel}: physical line {number} has {len(row)} columns, expected {width}")
    return rows

for rel in (plan_rel, report_rel, contract_rel, gate_rel, adr_rel, bt6_rel, catalog_rel):
    confined_regular(rel)

rows = physical_tsv(contract_rel)
if rows[0] != ["key", "value"]:
    die("contract header must be exactly key<TAB>value")
actual = {}
for row in rows[1:]:
    if any(cell.strip() == "" or cell != cell.strip() for cell in row):
        die("contract rows must have whitespace-strict non-empty key and value")
    if row[0] in actual:
        die(f"duplicate contract key: {row[0]}")
    actual[row[0]] = row[1]
if actual != expected:
    missing = sorted(set(expected) - set(actual))
    unknown = sorted(set(actual) - set(expected))
    wrong = sorted(k for k in set(actual) & set(expected) if actual[k] != expected[k])
    die(f"closed contract mismatch; missing={missing}, unknown={unknown}, wrong={wrong}")
if any(k == "candidate.requiresHook" or k.startswith("candidate.requiresHook.") for k in actual):
    die("candidate.requiresHook is forbidden")

bt6_rows = physical_tsv(bt6_rel)
if bt6_rows[0] != ["key", "value"]:
    die("BT6 source header must be exactly key<TAB>value")
bt6 = {}
for row in bt6_rows[1:]:
    if any(cell.strip() == "" or cell != cell.strip() for cell in row) or row[0] in bt6:
        die("BT6 source must be unique whitespace-strict non-empty key/value TSV")
    bt6[row[0]] = row[1]
required_bt6 = {
    "entryDecision": "DEFER",
    "asmBackendSelectionStatus": "NOT_SELECTED",
    "asmBackendAuthorizationStatus": "NOT_AUTHORIZED",
    "byteBuddySelectionStatus": "NOT_SELECTED",
    "byteBuddyAuthorizationStatus": "NOT_AUTHORIZED",
}
for key, value in required_bt6.items():
    if bt6.get(key) != value:
        die(f"BT6 source {key} must be {value}")

catalog_rows = physical_tsv(catalog_rel)
header = catalog_rows[0]
if "capabilityId" not in header or "requiresHook" not in header:
    die("capability catalog lacks named columns")
capability_i = header.index("capabilityId")
requires_hook_i = header.index("requiresHook")
catalog = {}
capability_pattern = re.compile(r"^[a-z][a-z0-9.-]*$")
for row in catalog_rows[1:]:
    capability = row[capability_i]
    if capability.strip() == "" or capability != capability.strip():
        die("capability catalog capabilityId must be whitespace-strict and non-empty")
    if not capability_pattern.fullmatch(capability):
        die(f"invalid capability catalog capabilityId: {capability}")
    if capability in catalog:
        die(f"duplicate capability catalog row: {capability}")
    catalog[capability] = row[requires_hook_i]
for capability in ("cubism.project.read", "cubism.workspace.read"):
    if catalog.get(capability) != "false":
        die(f"capability catalog {capability} requiresHook must be false")
print("BT6A validate: schema=dev.turboism.migration.bt6a-first-hook-readiness version=1 candidate=project-workspace.lifecycle-observation state=DEFER/UNPROVEN/NOT_SELECTED.")
PY
}

review_boundary() {
  local root=${1:-$ROOT} baseline=${2:-HEAD}
  python3 - "$root" "$baseline" "${EXPECTED_PATHS[@]}" <<'PY'
import os
import re
import stat
import subprocess
import sys

root, baseline, *expected = sys.argv[1:]
expected_set = set(expected)

def die(message):
    raise SystemExit(f"BT6A first-hook readiness gate: {message}")

def run(*args):
    return subprocess.run(args, cwd=root, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout

if baseline.startswith("-"):
    die("baseline must not begin with '-'")
resolution = subprocess.run(
    ("git", "rev-parse", "--verify", "--end-of-options", f"{baseline}^{{commit}}"),
    cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
)
if resolution.returncode != 0 or resolution.stderr:
    die(f"baseline must resolve unambiguously to a commit: {baseline}")
resolved = resolution.stdout.decode("ascii").strip()
if len(resolved) != 40 or any(c not in "0123456789abcdef" for c in resolved):
    die("resolved baseline is not a full lowercase commit hash")

try:
    flags_v = run("git", "ls-files", "-v", "--").splitlines()
    flags_t = run("git", "ls-files", "-t", "--").splitlines()
    if any(line[:1] and (line[:1].islower() or line.startswith(b"S ")) for line in flags_v) or any(line.startswith(b"S ") for line in flags_t):
        die("tracked index contains assume-unchanged or skip-worktree flags")
    tracked_raw = run("git", "diff", "--name-status", "-z", "--no-renames", resolved, "--")
    untracked_raw = run("git", "ls-files", "--others", "--exclude-standard", "-z", "--")
    ignored_raw = run("git", "ls-files", "--others", "--ignored", "--exclude-standard", "-z", "--")
except subprocess.CalledProcessError as error:
    die(f"cannot inspect repository boundary: {error.stderr.decode('utf-8', 'replace').strip()}")

cache_pattern = re.compile(r"^scripts/test/__pycache__/[A-Za-z_][A-Za-z0-9_]*\.cpython-[0-9]+\.pyc$")

def require_ignored_regular(path, kind):
    try:
        mode = os.lstat(os.path.join(root, path)).st_mode
    except OSError as error:
        die(f"cannot inspect ignored {kind}: {path}: {error}")
    if stat.S_ISLNK(mode) or not stat.S_ISREG(mode):
        die(f"ignored {kind} is not a regular non-symlink file: {path}")

# git ls-files does not emit every ignored special file. Inspect the direct
# cache-file shape as well so every would-be allowlisted leaf is checked.
cache_dir = os.path.join(root, "scripts", "test", "__pycache__")
try:
    with os.scandir(cache_dir) as entries:
        for entry in entries:
            path = f"scripts/test/__pycache__/{entry.name}"
            if cache_pattern.fullmatch(path) is not None:
                require_ignored_regular(path, "CPython cache file")
except FileNotFoundError:
    pass
except OSError as error:
    die(f"cannot inspect CPython cache directory: {error}")

for raw in ignored_raw.split(b"\0"):
    if not raw:
        continue
    path = raw.decode("utf-8", "surrogateescape")
    if path == ".turboism-worktree-id":
        require_ignored_regular(path, "worktree ID")
        continue
    if cache_pattern.fullmatch(path) is not None:
        require_ignored_regular(path, "CPython cache file")
        continue
    if (
        path.startswith(".gradle/")
        or path.startswith("build/")
        or path.startswith("buildSrc/.gradle/")
        or path.startswith("buildSrc/build/")
    ):
        continue
    die(f"ignored path is outside the strict allowlist: {path}")
# Every ignored path is classified. Only explicit local/build prefixes and
# validated local regular files are excluded from exact-five accounting.

fields = tracked_raw.split(b"\0")
if fields and fields[-1] == b"":
    fields.pop()
tracked = {}
i = 0
while i < len(fields):
    status = fields[i].decode("ascii", "strict")
    i += 1
    if i >= len(fields):
        die("truncated tracked status record")
    path = fields[i].decode("utf-8", "surrogateescape")
    i += 1
    if status != "A":
        die(f"tracked status must be A, got {status}: {path}")
    if path in tracked:
        die(f"duplicate tracked record: {path}")
    tracked[path] = "A"

untracked = {}
for raw in untracked_raw.split(b"\0"):
    if not raw:
        continue
    path = raw.decode("utf-8", "surrogateescape")
    if path in untracked or path in tracked:
        die(f"duplicate boundary record: {path}")
    untracked[path] = "A"
actual = {**tracked, **untracked}
if actual != {path: "A" for path in expected}:
    die(f"boundary must be exactly five additions; actual={sorted(actual.items())}")

for rel in expected:
    if os.path.isabs(rel) or "\\" in rel or ".." in rel.split("/"):
        die(f"invalid expected path: {rel}")
    current = root
    for part in rel.split("/")[:-1]:
        current = os.path.join(current, part)
        try:
            mode = os.lstat(current).st_mode
        except OSError:
            die(f"missing parent: {rel}")
        if stat.S_ISLNK(mode) or not stat.S_ISDIR(mode):
            die(f"parent is not a real directory: {rel}")
    try:
        mode = os.lstat(os.path.join(root, rel)).st_mode
    except OSError:
        die(f"missing expected path: {rel}")
    if stat.S_ISLNK(mode) or not stat.S_ISREG(mode):
        die(f"expected path is not a regular non-symlink file: {rel}")
print(f"BT6A boundary: resolved-baseline={resolved} additions=5 exact-five=true.")
PY
}

expect_failure() {
  local label=$1
  shift
  if ("$@") >/dev/null 2>&1; then
    fail "self-test unexpectedly passed: $label"
  fi
}

create_fixture() {
  local repo=$1
  mkdir -p "$repo/docs/migration/plans" "$repo/docs/migration/capabilities" "$repo/docs/adr" "$repo/scripts/test"
  git -C "$repo" init -q -b fixture
  git -C "$repo" config user.name 'BT6A self-test'
  git -C "$repo" config user.email 'bt6a@example.invalid'
  printf 'key\tvalue\nentryDecision\tDEFER\nasmBackendSelectionStatus\tNOT_SELECTED\nasmBackendAuthorizationStatus\tNOT_AUTHORIZED\nbyteBuddySelectionStatus\tNOT_SELECTED\nbyteBuddyAuthorizationStatus\tNOT_AUTHORIZED\n' > "$repo/$BT6_REL"
  printf 'capabilityId\tcategory\tsdkSurface\truntimeOwner\tadapterOwner\tpermissions\trequiresTransaction\trequiresHook\trequiresMapping\tthreadingBudget\tfakeHostFixture\tdiagnostics\tlegacyRows\tstatus\ncubism.project.read\tread\tsdk\truntime\tadapter\tpermission\tfalse\tfalse\ttrue\tplugin-bounded\tfixture\tdiagnostic\tlegacy\tfake-verified\ncubism.workspace.read\tread\tsdk\truntime\tadapter\tpermission\tfalse\tfalse\ttrue\tplugin-bounded\tfixture\tdiagnostic\tlegacy\tfake-verified\n' > "$repo/$CATALOG_REL"
  printf '.gradle/\nbuild/\n.turboism-worktree-id\n' > "$repo/.gitignore"
  git -C "$repo" add .
  git -C "$repo" commit -q -m baseline
  printf 'plan arbitrary\n' > "$repo/$PLAN_REL"
  printf 'report arbitrary\n' > "$repo/$REPORT_REL"
  printf 'architecture reference arbitrary\n' > "$repo/$ADR_REL"
  cp -- "${BASH_SOURCE[0]}" "$repo/$GATE_REL"
  chmod 755 "$repo/$GATE_REL"
  python3 - "$repo/$CONTRACT_REL" "$PLAN_REL" "$REPORT_REL" "$CONTRACT_REL" "$GATE_REL" "$ADR_REL" "$BT6_REL" "$CATALOG_REL" <<'PY'
import sys
path, plan, report, contract, gate, adr, bt6, catalog = sys.argv[1:]
rows = [
("schema","dev.turboism.migration.bt6a-first-hook-readiness"),("version","1"),
("reviewStatus","FIRST_HOOK_READINESS_INVESTIGATED"),("entryDecision","DEFER"),
("implementationAuthorizationStatus","NOT_AUTHORIZED"),("committedClosureStatus","NOT_PERFORMED"),
("planPath",plan),("reportPath",report),("contractPath",contract),("gatePath",gate),
("architectureReferencePath",adr),("sourceBt6ContractPath",bt6),("capabilityCatalogPath",catalog),
("candidateEvidence.candidateId","project-workspace.lifecycle-observation"),
("candidateEvidence.reconciliationReadSliceId","adapter.project-workspace.readonly"),
("candidateEvidence.capabilityIds","cubism.project.read;cubism.workspace.read"),
("candidateEvidence.investigationDomain","HOOK_INGRESS"),
("candidateEvidence.hookRequirementStatus","UNPROVEN"),
("candidateEvidence.selectionStatus","NOT_SELECTED"),
("candidateEvidence.authorizationStatus","NOT_AUTHORIZED"),
("clipmaskDisposition","REJECTED_FOR_BT6A"),
("asmBackendSelectionStatus","NOT_SELECTED"),("asmBackendAuthorizationStatus","NOT_AUTHORIZED"),
("byteBuddyBackendSelectionStatus","NOT_SELECTED"),("byteBuddyBackendAuthorizationStatus","NOT_AUTHORIZED")]
with open(path,"w",encoding="utf-8",newline="") as f:
    f.write("key\tvalue\n")
    for k,v in rows: f.write(f"{k}\t{v}\n")
PY
}

mutate_contract() {
  local repo=$1 operation=$2
  python3 - "$repo/$CONTRACT_REL" "$operation" <<'PY'
import sys
path, op = sys.argv[1:]
lines = open(path, encoding="utf-8").read().splitlines()
if op == "duplicate": lines.append(lines[1])
elif op == "missing": lines = [x for x in lines if not x.startswith("clipmaskDisposition\t")]
elif op == "unknown": lines.append("unknownKey\tvalue")
elif op == "candidate6": lines = [x for x in lines if not x.startswith("candidateEvidence.authorizationStatus\t")]
elif op == "candidate8": lines.append("candidateEvidence.extra\tvalue")
elif op == "selected": lines = ["candidateEvidence.selectionStatus\tSELECTED" if x.startswith("candidateEvidence.selectionStatus\t") else x for x in lines]
elif op == "authorized": lines = ["candidateEvidence.authorizationStatus\tAUTHORIZED" if x.startswith("candidateEvidence.authorizationStatus\t") else x for x in lines]
elif op == "clipmask": lines = ["clipmaskDisposition\tSELECTED" if x.startswith("clipmaskDisposition\t") else x for x in lines]
open(path,"w",encoding="utf-8",newline="").write("\n".join(lines)+"\n")
PY
}

mutate_bytes() {
  local path=$1 kind=$2
  python3 - "$path" "$kind" <<'PY'
import sys
p, kind = sys.argv[1:]
data = open(p, "rb").read()
if kind == "bom": data = b"\xef\xbb\xbf" + data
elif kind == "cr": data = data.replace(b"\n", b"\r\n", 1)
elif kind == "nul": data += b"\0"
elif kind == "blank": data = data.replace(b"\n", b"\n\n", 1)
open(p, "wb").write(data)
PY
}

run_self_tests() {
  local sandbox repo op baseline
  sandbox=$(mktemp -d "${TMPDIR:-/tmp}/turboism-bt6a.XXXXXX")
  trap 'rm -rf -- "$sandbox"' RETURN

  repo="$sandbox/base"; create_fixture "$repo"
  validate_contract "$repo" >/dev/null
  review_boundary "$repo" HEAD >/dev/null
  git -C "$repo" add "${EXPECTED_PATHS[@]}"
  review_boundary "$repo" HEAD >/dev/null
  printf 'unstaged after add\n' >> "$repo/$REPORT_REL"
  review_boundary "$repo" HEAD >/dev/null

  for mode in untracked staged mixed; do
    repo="$sandbox/$mode"; create_fixture "$repo"
    case "$mode" in staged) git -C "$repo" add "${EXPECTED_PATHS[@]}" ;; mixed) git -C "$repo" add "$PLAN_REL" "$GATE_REL" ;; esac
    review_boundary "$repo" HEAD >/dev/null
  done

  repo="$sandbox/special"; create_fixture "$repo"; special=$'extra name\nwith-tab-\t'; printf x > "$repo/$special"; expect_failure 'real newline and tab filename extra' review_boundary "$repo" HEAD
  repo="$sandbox/cached"; create_fixture "$repo"; expect_failure '--cached baseline' review_boundary "$repo" --cached
  repo="$sandbox/staged-baseline"; create_fixture "$repo"; expect_failure '--staged baseline' review_boundary "$repo" --staged
  repo="$sandbox/noncommit"; create_fixture "$repo"; git -C "$repo" tag blob-tag "$(printf blob | git -C "$repo" hash-object -w --stdin)"; expect_failure 'non-commit baseline' review_boundary "$repo" blob-tag
  repo="$sandbox/ambiguous"; create_fixture "$repo"; baseline=$(git -C "$repo" rev-parse HEAD); git -C "$repo" branch ambiguous "$baseline"; git -C "$repo" tag ambiguous "$baseline"; expect_failure 'ambiguous baseline' review_boundary "$repo" ambiguous

  repo="$sandbox/ignored-ok"; create_fixture "$repo"; mkdir -p "$repo/.gradle/cache" "$repo/build/out" "$repo/buildSrc/.gradle/cache" "$repo/buildSrc/build/classes" "$repo/scripts/test/__pycache__"; printf 'buildSrc/.gradle/\nbuildSrc/build/\n**/__pycache__/\n*.pyc\n' >> "$repo/.git/info/exclude"; printf x > "$repo/.turboism-worktree-id"; printf x > "$repo/.gradle/cache/x"; printf x > "$repo/build/out/x"; printf x > "$repo/buildSrc/.gradle/cache/x"; printf x > "$repo/buildSrc/build/classes/x"; printf x > "$repo/scripts/test/__pycache__/gate.cpython-311.pyc"; review_boundary "$repo" HEAD >/dev/null
  repo="$sandbox/ignored-worktree-id-symlink"; create_fixture "$repo"; ln -s "$PLAN_REL" "$repo/.turboism-worktree-id"; expect_failure 'worktree ID symlink' review_boundary "$repo" HEAD
  for kind in symlink fifo directory; do
    repo="$sandbox/ignored-cache-$kind"; create_fixture "$repo"; path="$repo/scripts/test/__pycache__/gate.cpython-311.pyc"; mkdir -p "$(dirname "$path")"; printf 'scripts/test/__pycache__/gate.cpython-311.pyc\n' >> "$repo/.git/info/exclude"
    case "$kind" in symlink) ln -s "$PLAN_REL" "$path" ;; fifo) mkfifo "$path" ;; directory) mkdir "$path" ;; esac
    expect_failure "allowlisted CPython cache $kind" review_boundary "$repo" HEAD
  done
  for ignored_case in docs-source docs-pyc tools root cache-nested cache-extension cache-generic-pyc cache-bad-tag cache-bad-name; do
    repo="$sandbox/ignored-$ignored_case"; create_fixture "$repo"
    case "$ignored_case" in
      docs-source) path='docs/hidden.md' ;;
      docs-pyc) path='docs/runtime/hidden.cpython-311.pyc' ;;
      tools) path='tools/hidden.bin' ;;
      root) path='hidden.local' ;;
      cache-nested) path='scripts/test/__pycache__/nested/gate.cpython-311.pyc' ;;
      cache-extension) path='scripts/test/__pycache__/gate.cpython-311.pyo' ;;
      cache-generic-pyc) path='scripts/test/__pycache__/gate.pyc' ;;
      cache-bad-tag) path='scripts/test/__pycache__/gate.cpython-x.pyc' ;;
      cache-bad-name) path='scripts/test/__pycache__/gate-name.cpython-311.pyc' ;;
    esac
    mkdir -p "$repo/$(dirname "$path")"; printf '%s\n' "$path" >> "$repo/.git/info/exclude"; printf hidden > "$repo/$path"
    expect_failure "ignored path $ignored_case" review_boundary "$repo" HEAD
  done

  for flag in assume-unchanged skip-worktree; do
    repo="$sandbox/flag-$flag"; create_fixture "$repo"; printf hidden >> "$repo/$BT6_REL"; git -C "$repo" update-index --"$flag" "$BT6_REL"; expect_failure "$flag hides existing modification" review_boundary "$repo" HEAD
  done

  repo="$sandbox/missing"; create_fixture "$repo"; rm "$repo/$REPORT_REL"; expect_failure 'missing file' review_boundary "$repo" HEAD
  repo="$sandbox/missing-adr"; create_fixture "$repo"; rm "$repo/$ADR_REL"; expect_failure 'missing ADR' review_boundary "$repo" HEAD
  for status in M D T; do
    repo="$sandbox/status-$status"; create_fixture "$repo"; git -C "$repo" add "${EXPECTED_PATHS[@]}"; git -C "$repo" commit -q -m additions
    case "$status" in M) printf changed >> "$repo/$REPORT_REL" ;; D) rm "$repo/$REPORT_REL" ;; T) rm "$repo/$REPORT_REL"; ln -s target "$repo/$REPORT_REL" ;; esac
    expect_failure "status $status" review_boundary "$repo" HEAD
  done
  repo="$sandbox/rename"; create_fixture "$repo"; git -C "$repo" add "${EXPECTED_PATHS[@]}"; git -C "$repo" commit -q -m additions; mv "$repo/$REPORT_REL" "$repo/renamed.md"; expect_failure 'rename decomposition' review_boundary "$repo" HEAD
  repo="$sandbox/copy"; create_fixture "$repo"; git -C "$repo" add "${EXPECTED_PATHS[@]}"; git -C "$repo" commit -q -m additions; cp "$repo/$REPORT_REL" "$repo/copy.md"; expect_failure 'copy extra' review_boundary "$repo" HEAD

  for kind in symlink fifo directory; do
    repo="$sandbox/$kind"; create_fixture "$repo"; rm "$repo/$REPORT_REL"
    case "$kind" in symlink) ln -s "$PLAN_REL" "$repo/$REPORT_REL" ;; fifo) mkfifo "$repo/$REPORT_REL" ;; directory) mkdir "$repo/$REPORT_REL" ;; esac
    expect_failure "$kind leaf" review_boundary "$repo" HEAD
  done
  for kind in symlink fifo directory; do
    repo="$sandbox/adr-$kind"; create_fixture "$repo"; rm "$repo/$ADR_REL"
    case "$kind" in symlink) ln -s "$PLAN_REL" "$repo/$ADR_REL" ;; fifo) mkfifo "$repo/$ADR_REL" ;; directory) mkdir "$repo/$ADR_REL" ;; esac
    expect_failure "ADR $kind leaf" review_boundary "$repo" HEAD
  done
  repo="$sandbox/parent-symlink"; create_fixture "$repo"; rm -rf "$repo/docs/migration/plans"; ln -s "$repo/docs/migration" "$repo/docs/migration/plans"; expect_failure 'symlink parent' review_boundary "$repo" HEAD

  for op in duplicate missing unknown candidate6 candidate8 selected authorized clipmask; do
    repo="$sandbox/contract-$op"; create_fixture "$repo"; mutate_contract "$repo" "$op"; expect_failure "contract $op" validate_contract "$repo"
  done
  for target in contract bt6 catalog; do
    for op in bom cr nul blank; do
      repo="$sandbox/physical-$target-$op"; create_fixture "$repo"
      case "$target" in contract) path="$repo/$CONTRACT_REL" ;; bt6) path="$repo/$BT6_REL" ;; catalog) path="$repo/$CATALOG_REL" ;; esac
      mutate_bytes "$path" "$op"; expect_failure "$target rejects $op" validate_contract "$repo"
    done
  done
  repo="$sandbox/catalog-header-empty"; create_fixture "$repo"; python3 - "$repo/$CATALOG_REL" <<'PY'
import sys
p=sys.argv[1]; lines=open(p).read().splitlines(); cells=lines[0].split('\t'); cells[1]=' '; lines[0]='\t'.join(cells); open(p,'w').write('\n'.join(lines)+'\n')
PY
  expect_failure 'catalog strip-empty header at actual width' validate_contract "$repo"
  for whitespace in leading trailing; do
    repo="$sandbox/catalog-header-$whitespace"; create_fixture "$repo"; python3 - "$repo/$CATALOG_REL" "$whitespace" <<'PY'
import sys
p,kind=sys.argv[1:]; lines=open(p).read().splitlines(); cells=lines[0].split('\t'); cells[0]=(' '+cells[0]) if kind == 'leading' else (cells[0]+' '); lines[0]='\t'.join(cells); open(p,'w').write('\n'.join(lines)+'\n')
PY
    expect_failure "catalog $whitespace header at actual width" validate_contract "$repo"
  done
  repo="$sandbox/catalog-header-duplicate"; create_fixture "$repo"; python3 - "$repo/$CATALOG_REL" <<'PY'
import sys
p=sys.argv[1]; lines=open(p).read().splitlines(); cells=lines[0].split('\t'); cells[1]=cells[0]; lines[0]='\t'.join(cells); open(p,'w').write('\n'.join(lines)+'\n')
PY
  expect_failure 'catalog duplicate header at actual width' validate_contract "$repo"
  repo="$sandbox/catalog-width"; create_fixture "$repo"; printf 'extra\n' >> "$repo/$CATALOG_REL"; expect_failure 'catalog exact row width' validate_contract "$repo"
  for id_case in empty strip-empty leading trailing uppercase slash underscore; do
    repo="$sandbox/catalog-id-$id_case"; create_fixture "$repo"; python3 - "$repo/$CATALOG_REL" "$id_case" <<'PY'
import sys
p,kind=sys.argv[1:]; lines=open(p).read().splitlines(); row=lines[1].split('\t'); values={'empty':'','strip-empty':' ','leading':' '+row[0],'trailing':row[0]+' ','uppercase':'Cubism.project.read','slash':'cubism/project.read','underscore':'cubism_project.read'}; row[0]=values[kind]; lines[1]='\t'.join(row); open(p,'w').write('\n'.join(lines)+'\n')
PY
    expect_failure "catalog capabilityId $id_case" validate_contract "$repo"
  done
  repo="$sandbox/catalog-duplicate-id"; create_fixture "$repo"; python3 - "$repo/$CATALOG_REL" <<'PY'
import sys
p=sys.argv[1]; lines=open(p).read().splitlines(); row=lines[1].split('\t'); row[0]='cubism.workspace.read'; lines[1]='\t'.join(row); open(p,'w').write('\n'.join(lines)+'\n')
PY
  expect_failure 'catalog duplicate capabilityId' validate_contract "$repo"
  for target in contract bt6; do
    for field in header key value; do
      for whitespace in leading trailing strip-empty; do
        repo="$sandbox/whitespace-$target-$field-$whitespace"; create_fixture "$repo"
        case "$target" in contract) path="$repo/$CONTRACT_REL" ;; bt6) path="$repo/$BT6_REL" ;; esac
        python3 - "$path" "$field" "$whitespace" <<'PY'
import sys
p,field,kind=sys.argv[1:]; lines=open(p).read().splitlines(); line_i=0 if field == 'header' else 1; cells=lines[line_i].split('\t'); cell_i=0 if field in ('header','key') else 1; original=cells[cell_i]; cells[cell_i]=(' '+original) if kind == 'leading' else (original+' ' if kind == 'trailing' else ' '); lines[line_i]='\t'.join(cells); open(p,'w').write('\n'.join(lines)+'\n')
PY
        expect_failure "$target $field rejects $whitespace whitespace" validate_contract "$repo"
      done
    done
  done

  repo="$sandbox/requires-hook"; create_fixture "$repo"; python3 - "$repo/$CATALOG_REL" <<'PY'
import sys
p=sys.argv[1]; lines=open(p).read().splitlines(); header=lines[0].split('\t'); i=header.index('requiresHook'); row=lines[1].split('\t'); row[i]='true'; lines[1]='\t'.join(row); open(p,'w').write('\n'.join(lines)+'\n')
PY
  expect_failure 'requiresHook source true' validate_contract "$repo"

  for source_case in bt6 backend-selected backend-authorized; do
    repo="$sandbox/source-$source_case"; create_fixture "$repo"
    case "$source_case" in
      bt6) old='entryDecision\tDEFER'; new='entryDecision\tENTER' ;;
      backend-selected) old='asmBackendSelectionStatus\tNOT_SELECTED'; new='asmBackendSelectionStatus\tSELECTED' ;;
      backend-authorized) old='byteBuddyAuthorizationStatus\tNOT_AUTHORIZED'; new='byteBuddyAuthorizationStatus\tAUTHORIZED' ;;
    esac
    python3 - "$repo/$BT6_REL" "$old" "$new" <<'PY'
import sys
p, old, new=sys.argv[1:]; s=open(p).read(); open(p,'w').write(s.replace(old.replace('\\t','\t'),new.replace('\\t','\t')))
PY
    expect_failure "$source_case" validate_contract "$repo"
  done
  repo="$sandbox/markdown"; create_fixture "$repo"; printf '\000arbitrary markdown bytes\n' > "$repo/$REPORT_REL"; validate_contract "$repo" >/dev/null; printf '\000any ADR bytes remain non-authoritative\n' > "$repo/$ADR_REL"; validate_contract "$repo" >/dev/null; rm "$repo/$PLAN_REL"; expect_failure 'markdown missing' validate_contract "$repo"
  repo="$sandbox/sixth-path"; create_fixture "$repo"; printf extra > "$repo/sixth.txt"; expect_failure 'sixth path' review_boundary "$repo" HEAD

  rm -rf -- "$sandbox"; trap - RETURN
  printf 'BT6A selftest: production-functions=passed regressions=passed.\n'
}

case "${1:-}" in
  --validate-contract) [[ $# == 1 ]] || { usage >&2; exit 2; }; validate_contract ;;
  --review-boundary) [[ $# -le 2 ]] || { usage >&2; exit 2; }; review_boundary "$ROOT" "${2:-HEAD}" ;;
  --self-test) [[ $# == 1 ]] || { usage >&2; exit 2; }; run_self_tests ;;
  -h|--help) usage ;;
  *) usage >&2; exit 2 ;;
esac
