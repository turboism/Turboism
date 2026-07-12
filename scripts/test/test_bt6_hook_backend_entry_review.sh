#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TURBOISM_BT6_ROOT:-}" ]]; then
  ROOT=$(cd "$TURBOISM_BT6_ROOT" && pwd -P)
else
  ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
fi
REPORT_REL='docs/migration/bytecode-tooling-bt6-hook-backend-entry-review.md'
CONTRACT_REL='docs/migration/bytecode-tooling-bt6-hook-backend-entry-review.tsv'
PLAN_REL='docs/migration/plans/bytecode-tooling-and-hook-readiness-plan.md'
GATE_REL='scripts/test/test_bt6_hook_backend_entry_review.sh'
AUTOMATED_PLAN='docs/migration/plans/automated-tranche-completion-plan.md'
MAILBOX_REL='runtime/src/main/java/dev/turboism/hook/ingress/BoundedHookEventMailbox.java'
DISPATCHER_REL='runtime/src/main/java/dev/turboism/hook/ingress/HookIngressDispatcher.java'
LEDGER_REL='docs/migration/automated-tranche-ledger.tsv'
CLOSURE_PLACEHOLDER='__BT6_CLOSURE_SCOPE_SHA256__'
CLOSURE_PATHS=("$PLAN_REL" "$REPORT_REL" "$CONTRACT_REL" "$GATE_REL")

fail() {
  printf 'BT6 entry review gate: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage:
  # Formal closure proof: export and execute the committed gate blob externally.
  root=$(git rev-parse --show-toplevel) && gate=$(mktemp) && trap 'rm -f -- "$gate"' EXIT && git show HEAD:scripts/test/test_bt6_hook_backend_entry_review.sh >"$gate" && chmod 700 "$gate" && TURBOISM_BT6_ROOT="$root" TURBOISM_BT6_COMMITTED_LAUNCH=1 bash "$gate"

  scripts/test/test_bt6_hook_backend_entry_review.sh --seal-draft
  scripts/test/test_bt6_hook_backend_entry_review.sh --self-test

Default closure mode refuses a direct worktree-script launch. The marker selects
formal-proof mode only; provenance additionally requires the canonical executing
script path to be outside the repository and its exact bytes to equal the gate blob
exported independently from HEAD. --seal-draft only computes pre-commit hashes; it
does not prove closure and never prints the production "passed" result.
EOF
}

sha256_file() {
  sha256sum -- "$1" | cut -d ' ' -f1
}

contract_value_from() {
  local contract=$1 key=$2
  awk -F '\t' -v key="$key" '$1 == key { print $2; found = 1 } END { exit !found }' "$contract"
}

replace_contract_values() {
  local source=$1 destination=$2 report_hash=$3 closure_value=$4
  awk -F '\t' -v OFS='\t' -v report_hash="$report_hash" -v closure_value="$closure_value" '
    $1 == "reportSha256" { $2 = report_hash; report_seen++ }
    $1 == "closureScopeSha256" { $2 = closure_value; closure_seen++ }
    { print }
    END { if (report_seen != 1 || closure_seen != 1) exit 1 }
  ' "$source" > "$destination"
}

normalize_contract() {
  local source=$1 destination=$2
  python3 - "$source" "$destination" "$CLOSURE_PLACEHOLDER" <<'PY'
import re
import sys

source, destination, placeholder = sys.argv[1:]
data = open(source, "rb").read()
pattern = re.compile(rb"(?m)^(closureScopeSha256\t)[^\r\n]*(\r?\n|$)")
matches = list(pattern.finditer(data))
if len(matches) != 1:
    raise SystemExit(1)
match = matches[0]
normalized = data[:match.start()] + match.group(1) + placeholder.encode("ascii") + match.group(2) + data[match.end():]
open(destination, "wb").write(normalized)
PY
}

# Canonical closure algorithm (v3): in this fixed order, hash the exact committed
# bytes of plan, report and gate. For the TSV, preserve every committed byte except
# replace the sole closureScopeSha256 value with CLOSURE_PLACEHOLDER, then hash
# those normalized bytes. Hash the resulting four "sha256<TAB>path<LF>" records.
closure_sha256_from_files() {
  local root=$1 contract_override=${2:-} path hash normalized source
  normalized=$(mktemp "${TMPDIR:-/tmp}/turboism-bt6-normalized.XXXXXX")
  trap 'rm -f -- "$normalized"' RETURN
  {
    for path in "${CLOSURE_PATHS[@]}"; do
      [[ -f "$root/$path" && ! -L "$root/$path" ]] || return 1
      if [[ "$path" == "$CONTRACT_REL" ]]; then
        source=${contract_override:-$root/$path}
        normalize_contract "$source" "$normalized" || return 1
        hash=$(sha256_file "$normalized")
      else
        hash=$(sha256_file "$root/$path")
      fi
      printf '%s\t%s\n' "$hash" "$path"
    done
  } | sha256sum | cut -d ' ' -f1
  rm -f -- "$normalized"
  trap - RETURN
}

export_head_closure() {
  local repo=$1 destination=$2 path
  mkdir -p "$destination"
  for path in "${CLOSURE_PATHS[@]}"; do
    mkdir -p "$destination/$(dirname "$path")"
    git -C "$repo" show "HEAD:$path" > "$destination/$path" || return 1
  done
}

require_head_regular_blobs() {
  local repo=$1 path record expected_mode
  for path in "${CLOSURE_PATHS[@]}"; do
    if [[ "$path" == "$GATE_REL" ]]; then
      expected_mode=100755
    else
      expected_mode=100644
    fi
    record=$(git -C "$repo" ls-tree HEAD -- "$path")
    [[ "$record" =~ ^${expected_mode}\ blob\ [0-9a-f]+$'\t'"$path"$ ]] \
      || fail "HEAD mode must be $expected_mode regular blob: $path"
  done
}

require_clean_index_flags() {
  local repo=$1 tags
  tags=$(git -C "$repo" ls-files -v)
  if printf '%s\n' "$tags" | grep -Eq '^[a-zS] '; then
    fail 'tracked index contains assume-unchanged or skip-worktree flags'
  fi
  tags=$(git -C "$repo" ls-files -t)
  if printf '%s\n' "$tags" | grep -Eq '^S '; then
    fail 'tracked index contains skip-worktree flags'
  fi
}

# The optional arguments are a self-test seam only. Production calls this with no
# arguments, so provenance is bound to the actual BASH_SOURCE[0] and configured ROOT.
require_committed_launch() (
  local invoked=${1:-${BASH_SOURCE[0]}} repo=${2:-$ROOT} canonical_invoked canonical_repo expected
  [[ "${TURBOISM_BT6_COMMITTED_LAUNCH:-}" == 1 ]] \
    || fail 'formal closure proof marker is missing; run the canonical git-show/mktemp command from --help'
  canonical_repo=$(python3 - "$repo" <<'PY'
import os
import sys

path = os.path.realpath(sys.argv[1])
if not os.path.isdir(path):
    raise SystemExit(1)
print(path)
PY
  ) || fail 'formal closure proof repository root is not a canonical directory'
  canonical_invoked=$(python3 - "$invoked" <<'PY'
import os
import stat
import sys

path = os.path.realpath(sys.argv[1])
try:
    mode = os.stat(path).st_mode
except OSError:
    raise SystemExit(1)
if not stat.S_ISREG(mode):
    raise SystemExit(1)
print(path)
PY
  ) || fail 'formal closure proof launcher does not resolve to a regular file'
  case "$canonical_invoked" in
    "$canonical_repo"|"$canonical_repo"/*)
      fail 'formal closure proof launcher must resolve outside the repository root'
      ;;
  esac
  expected=$(mktemp "${TMPDIR:-/tmp}/turboism-bt6-launch.XXXXXX") \
    || fail 'failed to allocate committed-launch comparison file'
  trap 'rm -f -- "$expected"' EXIT
  git -C "$canonical_repo" show "HEAD:$GATE_REL" > "$expected" \
    || fail 'failed to export committed gate blob from HEAD'
  cmp -s -- "$canonical_invoked" "$expected" \
    || fail 'formal closure proof launcher bytes differ from HEAD gate blob'
)

check_exact_committed_scope() {
  local repo=$1 baseline=$2 records token status path i=0
  local -a fields=() expected=(
    "M:$PLAN_REL"
    "A:$REPORT_REL"
    "A:$CONTRACT_REL"
    "A:$GATE_REL"
  ) actual=()
  records=$(mktemp "${TMPDIR:-/tmp}/turboism-bt6-status.XXXXXX")
  git -C "$repo" diff --name-status -z --find-renames --find-copies --find-copies-harder "$baseline" HEAD > "$records"
  mapfile -d '' -t fields < "$records"
  rm -f -- "$records"
  while (( i < ${#fields[@]} )); do
    token=${fields[i++]}
    status=${token%%[0-9]*}
    case "$status" in
      R|C)
        (( i + 1 < ${#fields[@]} )) || fail "committed scope contains truncated $status record"
        fail "committed scope rejects rename/copy status: $token ${fields[i]} -> ${fields[i+1]}"
        ;;
      A|M)
        (( i < ${#fields[@]} )) || fail "committed scope contains truncated $status record"
        path=${fields[i++]}
        actual+=("$status:$path")
        ;;
      *) fail "committed scope rejects status: $token" ;;
    esac
  done
  [[ "$(printf '%s\n' "${actual[@]}" | LC_ALL=C sort)" == "$(printf '%s\n' "${expected[@]}" | LC_ALL=C sort)" ]] \
    || fail 'baseline..HEAD must be exactly plan(M), report(A), TSV(A), gate(A)'
}

validate_commit_context() {
  local repo=$1 baseline=$2 mode=$3 main head count
  head=$(git -C "$repo" rev-parse HEAD)
  main=$(git -C "$repo" rev-parse refs/heads/main 2>/dev/null) || fail 'refs/heads/main is required'
  git -C "$repo" cat-file -e "$baseline^{commit}" || fail "baselineCommit is not available: $baseline"
  git -C "$repo" merge-base --is-ancestor "$baseline" HEAD || fail 'baselineCommit must be an ancestor of HEAD'
  count=$(git -C "$repo" rev-list --count "$baseline..HEAD")
  [[ "$count" == 1 ]] || fail "closure must be exactly one commit after baseline, got $count"
  case "$mode" in
    feature)
      [[ "$main" == "$baseline" ]] || fail 'feature mode requires refs/heads/main == baselineCommit'
      [[ "$head" != "$main" ]] || fail 'feature mode requires HEAD to be the closure commit'
      ;;
    main)
      [[ "$head" == "$main" ]] || fail 'main mode requires HEAD == refs/heads/main'
      ;;
    *) fail "unknown validation mode: $mode" ;;
  esac
}

validate_committed_closure() {
  local repo=$1 mode=$2 snapshot contract baseline expected_report expected_scope actual_report actual_scope dirty
  require_head_regular_blobs "$repo"
  require_clean_index_flags "$repo"
  snapshot=$(mktemp -d "${TMPDIR:-/tmp}/turboism-bt6-head.XXXXXX")
  export_head_closure "$repo" "$snapshot" || { rm -rf -- "$snapshot"; fail 'failed to read closure blobs from HEAD'; }
  contract="$snapshot/$CONTRACT_REL"
  baseline=$(contract_value_from "$contract" baselineCommit) || { rm -rf -- "$snapshot"; fail 'committed contract missing baselineCommit'; }
  [[ "$baseline" =~ ^[0-9a-f]{40}$ ]] || { rm -rf -- "$snapshot"; fail 'committed baselineCommit must be a full SHA-1'; }
  validate_commit_context "$repo" "$baseline" "$mode"
  check_exact_committed_scope "$repo" "$baseline"

  git -C "$repo" diff --quiet HEAD -- "${CLOSURE_PATHS[@]}" \
    || { rm -rf -- "$snapshot"; fail 'closure files in index/worktree differ from HEAD'; }
  dirty=$(git -C "$repo" status --porcelain=v1 --untracked-files=all)
  [[ -z "$dirty" ]] || { rm -rf -- "$snapshot"; fail 'default closure proof requires a completely clean checkout'; }

  expected_report=$(contract_value_from "$contract" reportSha256) || { rm -rf -- "$snapshot"; fail 'committed contract missing reportSha256'; }
  expected_scope=$(contract_value_from "$contract" closureScopeSha256) || { rm -rf -- "$snapshot"; fail 'committed contract missing closureScopeSha256'; }
  [[ "$expected_report" =~ ^[0-9a-f]{64}$ && "$expected_scope" =~ ^[0-9a-f]{64}$ ]] \
    || { rm -rf -- "$snapshot"; fail 'committed seal values must be lowercase SHA-256'; }
  actual_report=$(sha256_file "$snapshot/$REPORT_REL")
  [[ "$actual_report" == "$expected_report" ]] || { rm -rf -- "$snapshot"; fail 'reportSha256 does not bind the committed report blob'; }
  actual_scope=$(closure_sha256_from_files "$snapshot")
  [[ "$actual_scope" == "$expected_scope" ]] || { rm -rf -- "$snapshot"; fail 'closureScopeSha256 does not bind normalized committed four-file content'; }
  COMMITTED_SNAPSHOT=$snapshot
  COMMITTED_CONTRACT=$contract
  COMMITTED_BASELINE=$baseline
}

seal_draft() {
  local path report_hash staged_contract scope_hash tmp
  for path in "${CLOSURE_PATHS[@]}"; do
    [[ -f "$ROOT/$path" && ! -L "$ROOT/$path" ]] || fail "draft seal requires regular non-symlink: $path"
  done
  report_hash=$(sha256_file "$ROOT/$REPORT_REL")
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/turboism-bt6-seal.XXXXXX")
  staged_contract="$tmp/contract.tsv"
  replace_contract_values "$ROOT/$CONTRACT_REL" "$staged_contract" "$report_hash" "$CLOSURE_PLACEHOLDER" \
    || { rm -rf -- "$tmp"; fail 'draft contract must contain one reportSha256 and closureScopeSha256'; }
  scope_hash=$(closure_sha256_from_files "$ROOT" "$staged_contract")
  rm -rf -- "$tmp"
  printf 'BT6 DRAFT SEAL ONLY — NOT A CLOSURE PROOF; DO NOT RECORD AS PASSED.\n'
  printf 'reportSha256\t%s\n' "$report_hash"
  printf 'closureScopeSha256\t%s\n' "$scope_hash"
  printf 'Fill both TSV values, rerun --seal-draft until stable, then commit all four files.\n'
  printf 'After commit, run the default gate from the feature branch; after FF, run it on main.\n'
}

create_self_test_repo() {
  local repo=$1
  mkdir -p "$repo/docs/migration/plans" "$repo/docs/migration" "$repo/scripts/test" "$repo/source"
  git -C "$repo" init -q -b main
  git -C "$repo" config user.name 'BT6 Gate Self Test'
  git -C "$repo" config user.email 'bt6-gate-self-test@example.invalid'
  printf 'baseline plan\n' > "$repo/$PLAN_REL"
  printf 'source blob\n' > "$repo/source/existing.txt"
  git -C "$repo" add .
  git -C "$repo" commit -q -m baseline
}

commit_self_test_closure() {
  local repo=$1 baseline report_hash scope_hash normalized
  baseline=$(git -C "$repo" rev-parse refs/heads/main)
  git -C "$repo" switch -q -c worktree/self-test
  printf 'reviewed plan\n' > "$repo/$PLAN_REL"
  printf 'human report\n' > "$repo/$REPORT_REL"
  printf 'gate bytes\n' > "$repo/$GATE_REL"
  chmod 755 "$repo/$GATE_REL"
  report_hash=$(sha256_file "$repo/$REPORT_REL")
  cat > "$repo/$CONTRACT_REL" <<EOF
key	value
schema	dev.turboism.migration.bt6-hook-backend-entry-review
version	3
baselineCommit	$baseline
closureScopeSha256	$CLOSURE_PLACEHOLDER
reportPath	$REPORT_REL
reportSha256	$report_hash
entryDecision	DEFER
EOF
  scope_hash=$(closure_sha256_from_files "$repo")
  awk -F '\t' -v OFS='\t' -v scope="$scope_hash" '$1 == "closureScopeSha256" {$2=scope} {print}' \
    "$repo/$CONTRACT_REL" > "$repo/$CONTRACT_REL.next"
  mv "$repo/$CONTRACT_REL.next" "$repo/$CONTRACT_REL"
  git -C "$repo" add "${CLOSURE_PATHS[@]}"
  git -C "$repo" commit -q -m closure
}

expect_self_test_failure() {
  local label=$1
  shift
  if ("$@") >/dev/null 2>&1; then
    fail "self-test unexpectedly passed: $label"
  fi
}

run_self_tests() {
  local sandbox repo baseline feature report_hash scope_hash launch_repo external_exact external_tampered original_marker
  sandbox=$(mktemp -d "${TMPDIR:-/tmp}/turboism-bt6-self-test.XXXXXX")
  trap 'rm -rf -- "$sandbox"' RETURN

  # Launch provenance is tested against a fixture commit containing the currently
  # executing draft bytes. This remains valid before this gate revision is committed,
  # while production always compares against the real target repository's HEAD blob.
  launch_repo="$sandbox/launch-repo"
  mkdir -p "$launch_repo/$(dirname "$GATE_REL")" "$sandbox/external"
  git -C "$launch_repo" init -q -b main
  git -C "$launch_repo" config user.name 'BT6 Gate Self Test'
  git -C "$launch_repo" config user.email 'bt6-gate-self-test@example.invalid'
  cp -- "${BASH_SOURCE[0]}" "$launch_repo/$GATE_REL"
  git -C "$launch_repo" add "$GATE_REL"
  git -C "$launch_repo" commit -q -m gate
  external_exact="$sandbox/external/exact-gate.sh"
  external_tampered="$sandbox/external/tampered-gate.sh"
  git -C "$launch_repo" show "HEAD:$GATE_REL" > "$external_exact"
  cp -- "$external_exact" "$external_tampered"
  printf '\n# tampered\n' >> "$external_tampered"

  original_marker=${TURBOISM_BT6_COMMITTED_LAUNCH-}
  unset TURBOISM_BT6_COMMITTED_LAUNCH
  expect_self_test_failure 'direct worktree launch without marker' require_committed_launch "$ROOT/$GATE_REL" "$ROOT"
  TURBOISM_BT6_COMMITTED_LAUNCH=1
  expect_self_test_failure 'direct worktree launch with marker' require_committed_launch "$ROOT/$GATE_REL" "$ROOT"
  expect_self_test_failure 'outside tampered launcher with marker' require_committed_launch "$external_tampered" "$launch_repo"
  require_committed_launch "$external_exact" "$launch_repo"
  if [[ -n "$original_marker" ]]; then
    TURBOISM_BT6_COMMITTED_LAUNCH=$original_marker
  else
    unset TURBOISM_BT6_COMMITTED_LAUNCH
  fi

  repo="$sandbox/valid"
  create_self_test_repo "$repo"
  baseline=$(git -C "$repo" rev-parse HEAD)
  commit_self_test_closure "$repo"
  feature=$(git -C "$repo" rev-parse HEAD)
  validate_committed_closure "$repo" feature
  rm -rf -- "$COMMITTED_SNAPSHOT"

  git -C "$repo" update-index --assume-unchanged "$REPORT_REL"
  expect_self_test_failure 'assume-unchanged index flag' require_clean_index_flags "$repo"
  git -C "$repo" update-index --no-assume-unchanged "$REPORT_REL"
  git -C "$repo" update-index --skip-worktree "$REPORT_REL"
  expect_self_test_failure 'skip-worktree index flag' require_clean_index_flags "$repo"
  git -C "$repo" update-index --no-skip-worktree "$REPORT_REL"

  printf 'changed report\n' > "$repo/$REPORT_REL"
  report_hash=$(sha256_file "$repo/$REPORT_REL")
  awk -F '\t' -v OFS='\t' -v hash="$report_hash" -v baseline="$feature" '
    $1=="baselineCommit"{$2=baseline}
    $1=="reportSha256"{$2=hash}
    {print}
  ' "$repo/$CONTRACT_REL" > "$repo/$CONTRACT_REL.next"
  mv "$repo/$CONTRACT_REL.next" "$repo/$CONTRACT_REL"
  scope_hash=$(closure_sha256_from_files "$repo")
  awk -F '\t' -v OFS='\t' -v hash="$scope_hash" '$1=="closureScopeSha256"{$2=hash} {print}' "$repo/$CONTRACT_REL" > "$repo/$CONTRACT_REL.next"
  mv "$repo/$CONTRACT_REL.next" "$repo/$CONTRACT_REL"
  expect_self_test_failure 'dirty baseline/report/scope joint re-sign' validate_committed_closure "$repo" feature
  git -C "$repo" restore -- "$REPORT_REL" "$CONTRACT_REL"

  rm -f "$repo/$REPORT_REL"
  ln -s /dev/null "$repo/$REPORT_REL"
  expect_self_test_failure 'dirty report symlink' validate_committed_closure "$repo" feature
  rm -f "$repo/$REPORT_REL"
  git -C "$repo" restore -- "$REPORT_REL"
  printf 'draft\n' > "$repo/untracked-draft.txt"
  expect_self_test_failure 'untracked draft' validate_committed_closure "$repo" feature
  rm -f "$repo/untracked-draft.txt"

  git -C "$repo" switch -q main
  git -C "$repo" merge -q --ff-only "$feature"
  validate_committed_closure "$repo" main
  rm -rf -- "$COMMITTED_SNAPSHOT"
  git -C "$repo" commit -q --allow-empty -m 'main advanced'
  expect_self_test_failure 'main advance' validate_committed_closure "$repo" main

  repo="$sandbox/untracked-report"
  create_self_test_repo "$repo"
  git -C "$repo" switch -q -c worktree/self-test
  printf 'report draft\n' > "$repo/$REPORT_REL"
  expect_self_test_failure 'untracked report' require_head_regular_blobs "$repo"

  repo="$sandbox/rename"
  create_self_test_repo "$repo"
  mkdir -p "$repo/docs/migration"
  printf 'old report\n' > "$repo/docs/migration/old-report.md"
  git -C "$repo" add . && git -C "$repo" commit -q -m source
  baseline=$(git -C "$repo" rev-parse HEAD)
  git -C "$repo" switch -q -c worktree/self-test
  git -C "$repo" mv docs/migration/old-report.md "$REPORT_REL"
  printf 'reviewed plan\n' > "$repo/$PLAN_REL"
  printf 'gate\n' > "$repo/$GATE_REL"
  chmod 755 "$repo/$GATE_REL"
  printf 'contract\n' > "$repo/$CONTRACT_REL"
  git -C "$repo" add . && git -C "$repo" commit -q -m closure
  expect_self_test_failure 'rename status' check_exact_committed_scope "$repo" "$baseline"

  repo="$sandbox/copy"
  create_self_test_repo "$repo"
  baseline=$(git -C "$repo" rev-parse HEAD)
  git -C "$repo" switch -q -c worktree/self-test
  cp "$repo/source/existing.txt" "$repo/$REPORT_REL"
  printf 'reviewed plan\n' > "$repo/$PLAN_REL"
  printf 'gate\n' > "$repo/$GATE_REL"
  chmod 755 "$repo/$GATE_REL"
  printf 'contract\n' > "$repo/$CONTRACT_REL"
  git -C "$repo" add . && git -C "$repo" commit -q -m closure
  expect_self_test_failure 'copy status' check_exact_committed_scope "$repo" "$baseline"

  rm -rf -- "$sandbox"
  trap - RETURN
  printf 'BT6 hook backend entry review production-function self-tests passed.\n'
}

mode=${1:-}
case "$mode" in
  --self-test) run_self_tests; exit 0 ;;
  --seal-draft) seal_draft; exit 0 ;;
  -h|--help) usage; exit 0 ;;
  '') ;;
  *) usage >&2; fail "unknown argument: $mode" ;;
esac

require_committed_launch
[[ -e "$ROOT/.git" ]] || fail 'not a git checkout'
[[ "$(git -C "$ROOT" rev-parse --show-toplevel)" == "$ROOT" ]] || fail 'checkout root mismatch'
branch=$(git -C "$ROOT" symbolic-ref --quiet --short HEAD || true)
case "$branch" in
  worktree/*)
    [[ -f "$ROOT/.git" ]] || fail 'feature validation must run from a linked worktree'
    [[ -f "$ROOT/.turboism-worktree-id" ]] || fail 'missing worktree ID file'
    worktree_id=$(tr -d '\r\n' < "$ROOT/.turboism-worktree-id")
    [[ "$worktree_id" == 'bt6-hook-backend-entry-review' ]] || fail "unexpected worktree ID: $worktree_id"
    closure_mode=feature
    ;;
  main) closure_mode=main ;;
  *) fail "branch must be worktree/* or main, got: ${branch:-detached}" ;;
esac

validate_committed_closure "$ROOT" "$closure_mode"
CONTRACT=$COMMITTED_CONTRACT
BASELINE=$COMMITTED_BASELINE
trap 'rm -rf -- "$COMMITTED_SNAPSHOT"' EXIT

prerequisites=(
  namedAdapterSlice exactHookSpec exactCubismVersion exactArtifactIdentity exactSelectorSet
  mappingProfileVerification verifiedManualOrSmoke safeModeRollback structuredDiagnostics
  normalizationBudget callbackBudget producerOwner consumerLifecycle shutdownDropPolicy
  nonblockingDiagnosticSink packagePrivateIntegration dispatcherIsolationRetirement
  backendSelection releaseApproval
)
allowed_keys=(
  schema version baselineCommit closureScopeSha256 reviewStatus entryDecision baselineEvidenceMode historicalCommitRole
  automatedPhase1Status automatedPhase2Status automatedPhase3Status bt5Status
  mailboxProductionWiringStatus mailboxProductionWiringGate backgroundConsumerStatus backgroundConsumerGate
  mailboxCompositionDesignStatus mailboxCompositionDesignGate
  asmBackendCandidateStatus asmBackendAdmissionStatus asmBackendSelectionStatus asmBackendAuthorizationStatus
  asmBackendAdrStatus asmBackendAdrGate asmTransformerAdmissionStatus asmTransformerAdmissionGate
  byteBuddyCandidateStatus byteBuddyAdmissionStatus byteBuddySelectionStatus byteBuddyAuthorizationStatus
  byteBuddyAdrStatus byteBuddyAdrGate
  candidateSlice.adapter.project-workspace.readonly candidateSlice.adapter.clipmask.readonly
  scope.context-menu scope.render-status userPreferenceRole reportPath reportSha256
)
for prerequisite in "${prerequisites[@]}"; do
  allowed_keys+=("prerequisite.${prerequisite}Status" "prerequisite.${prerequisite}Gate")
done
allowed_key_lines=$(printf '%s\n' "${allowed_keys[@]}" | LC_ALL=C sort)
actual_key_lines=$(tail -n +2 "$CONTRACT" | cut -f1 | LC_ALL=C sort)
awk -F '\t' 'NR==1 {if ($1!="key" || $2!="value" || NF!=2) exit 1; next} NF!=2 || $1=="" || $2=="" || seen[$1]++ {exit 1}' "$CONTRACT" \
  || fail 'committed contract is not unique, non-empty two-column TSV'
[[ "$actual_key_lines" == "$allowed_key_lines" ]] || fail 'committed contract key set is not closed'

expect_contract() {
  local key=$1 expected=$2 actual
  actual=$(contract_value_from "$CONTRACT" "$key") || fail "contract missing key: $key"
  [[ "$actual" == "$expected" ]] || fail "contract $key expected $expected, got $actual"
}
expect_contract schema 'dev.turboism.migration.bt6-hook-backend-entry-review'
expect_contract version '3'
expect_contract reviewStatus 'hook-backend-entry-reviewed'
expect_contract entryDecision 'DEFER'
expect_contract baselineEvidenceMode 'COMMITTED_HEAD_CLOSURE'
expect_contract historicalCommitRole 'PROVENANCE_ONLY'
expect_contract automatedPhase1Status 'PRESENT'
expect_contract automatedPhase2Status 'CONTRACT_TESTED_UNWIRED'
expect_contract automatedPhase3Status 'PRESENT'
expect_contract bt5Status 'PRESENT'
expect_contract mailboxProductionWiringStatus 'ABSENT'
expect_contract mailboxProductionWiringGate 'BLOCKER'
expect_contract backgroundConsumerStatus 'ABSENT'
expect_contract backgroundConsumerGate 'BLOCKER'
expect_contract mailboxCompositionDesignStatus 'ABSENT'
expect_contract mailboxCompositionDesignGate 'BLOCKER'
expect_contract asmBackendCandidateStatus 'NOT_SELECTED'
expect_contract asmBackendAdmissionStatus 'MAPPING_SCAN_ONLY'
expect_contract asmBackendSelectionStatus 'NOT_SELECTED'
expect_contract asmBackendAuthorizationStatus 'NOT_AUTHORIZED'
expect_contract asmBackendAdrStatus 'ABSENT'
expect_contract asmBackendAdrGate 'BLOCKER'
expect_contract asmTransformerAdmissionStatus 'ABSENT'
expect_contract asmTransformerAdmissionGate 'BLOCKER'
expect_contract byteBuddyCandidateStatus 'PROVISIONAL_CANDIDATE'
expect_contract byteBuddyAdmissionStatus 'NOT_ADMITTED'
expect_contract byteBuddySelectionStatus 'NOT_SELECTED'
expect_contract byteBuddyAuthorizationStatus 'NOT_AUTHORIZED'
expect_contract byteBuddyAdrStatus 'ABSENT'
expect_contract byteBuddyAdrGate 'BLOCKER'
expect_contract 'candidateSlice.adapter.project-workspace.readonly' 'CANDIDATE_ONLY'
expect_contract 'candidateSlice.adapter.clipmask.readonly' 'CANDIDATE_ONLY'
expect_contract 'scope.context-menu' 'EXCLUDED_DEFERRED_SCOPE'
expect_contract 'scope.render-status' 'EXCLUDED_DEFERRED_SCOPE'
expect_contract userPreferenceRole 'PLANNING_INPUT_ONLY'
expect_contract reportPath "$REPORT_REL"
for prerequisite in "${prerequisites[@]}"; do
  expect_contract "prerequisite.${prerequisite}Status" 'ABSENT'
  expect_contract "prerequisite.${prerequisite}Gate" 'BLOCKER'
done

for evidence in \
  docs/migration/phase1-ingress-ownership-audit-report.md \
  docs/migration/phase2-mailbox-contract-report.md \
  docs/migration/phase3-synthetic-composition-report.md \
  docs/migration/bytecode-tooling-bt5-closure-report.md; do
  git -C "$ROOT" cat-file -e "HEAD:$evidence" || fail "HEAD missing evidence: $evidence"
done

ledger_row_matches() {
  local work_id=$1 status=$2 evidence=$3 ceiling=$4
  git -C "$ROOT" show "HEAD:$LEDGER_REL" | awk -F '\t' -v id="$work_id" -v status="$status" -v evidence="$evidence" -v ceiling="$ceiling" \
    '$1==id && $4==status && $5==evidence && $6==ceiling {found=1} END {exit !found}' \
    || fail "HEAD ledger tuple missing: $work_id / $status / $evidence / $ceiling"
}
ledger_row_matches 'phase1.ownership-audit' 'COMPLETE' 'VERIFIED_STATIC' 'OWNERSHIP_AUDITED'
ledger_row_matches 'automation.phase2.dispatcher-contract' 'COMPLETE' 'VERIFIED_STATIC_FAKE' 'CONTRACT_TESTED'
ledger_row_matches 'automation.phase3.synthetic-composition' 'COMPLETE' 'VERIFIED_STATIC_SYNTHETIC' 'SYNTHETIC_COMPOSITION_READY'

git -C "$ROOT" show "HEAD:$MAILBOX_REL" | grep -Eq '^final class BoundedHookEventMailbox' || fail 'HEAD mailbox kernel fact missing'
mailbox_refs=$(git -C "$ROOT" grep -l 'BoundedHookEventMailbox' HEAD -- 'runtime/src/main/java/**' | grep -v 'BoundedHookEventMailbox.java' || true)
[[ -z "$mailbox_refs" ]] || fail "mailbox unexpectedly wired in HEAD production: $mailbox_refs"
git -C "$ROOT" show "HEAD:$DISPATCHER_REL" | grep -Eq 'eventSink\.accept\(event\);' || fail 'HEAD dispatcher sink fact missing'
if git -C "$ROOT" show "HEAD:$DISPATCHER_REL" | grep -Eq 'BoundedHookEventMailbox|Executor|Thread|CompletableFuture'; then
  fail 'HEAD HookIngressDispatcher is no longer the isolated synchronous sink seam'
fi
if git -C "$ROOT" grep -Eq 'net\.bytebuddy|java\.lang\.instrument|ClassFileTransformer|\bInstrumentation\b' HEAD -- runtime/src/main sdk/src/main plugins; then
  fail 'HEAD production Byte Buddy/Instrumentation/transformer reference found'
fi

for protected in "$AUTOMATED_PLAN" runtime sdk plugins build.gradle.kts settings.gradle.kts buildSrc gradle; do
  git -C "$ROOT" diff --quiet "$BASELINE" HEAD -- "$protected" || fail "protected path changed in committed closure: $protected"
done

printf 'BT6 hook backend entry review gate passed (%s, committed HEAD closure).\n' "$branch"
