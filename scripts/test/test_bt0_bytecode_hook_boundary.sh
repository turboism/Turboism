#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

fail() {
  printf 'BT0 boundary gate: %s\n' "$*" >&2
  exit 1
}

BASELINE_COMMIT="5b42c656a0f10b2959119663610f59f6d98d77fb"
AUTOMATED_PLAN="docs/migration/plans/automated-tranche-completion-plan.md"
BASELINE_AUTOMATED_PLAN_BLOB="1405ccc805909d438cfc8a9237debf472444a740"
CURRENT_PARENT="$(git merge-base HEAD main)"
CURRENT_AUTOMATED_PLAN_BLOB="$(git rev-parse "$CURRENT_PARENT:$AUTOMATED_PLAN" 2>/dev/null || true)"
DRAFT_PREFIX="runtime/src/main/java/dev/turboism/mapping/draft/"

[[ "$(git rev-parse "$BASELINE_COMMIT:$AUTOMATED_PLAN" 2>/dev/null || true)" == "$BASELINE_AUTOMATED_PLAN_BLOB" ]] \
  || fail "protected automated plan original baseline blob is missing or unexpected"
[[ -n "$CURRENT_AUTOMATED_PLAN_BLOB" ]] \
  || fail "protected automated plan is missing from the current parent"
[[ "$(git rev-parse ":$AUTOMATED_PLAN" 2>/dev/null || true)" == "$CURRENT_AUTOMATED_PLAN_BLOB" ]] \
  || fail "protected automated plan differs from the current parent in the index"
[[ -f "$AUTOMATED_PLAN" ]] \
  || fail "protected automated plan is missing from the worktree"
[[ "$(git hash-object "$AUTOMATED_PLAN")" == "$CURRENT_AUTOMATED_PLAN_BLOB" ]] \
  || fail "protected automated plan differs from the current parent in the worktree"

is_sensitive_runtime_path() {
  local path="$1"
  [[ "$path" =~ (^|/)(HostSession|HostRuntimeIngress)(\.java|\.kt)?$ ]] \
    || [[ "$path" =~ (^|/)[Dd]ispatcher(/|$) ]] \
    || [[ "$path" == runtime/src/main/java/dev/turboism/adapter/* ]]
}

is_draft_runtime_path() {
  [[ "$1" == "$DRAFT_PREFIX"* ]]
}

CHANGED_PRODUCTION_PATHS=()

# Parse the NUL-delimited --name-status protocol directly. Rename/copy records
# contain status, source, and target fields; ordinary records contain status and
# one path. This must not be replaced with --name-only because doing so loses
# deletion and rename/copy provenance.
audit_runtime_changes() {
  local repo="$1" baseline="$2"
  local diff_records untracked_records status kind source target path
  diff_records="$(mktemp)"
  untracked_records="$(mktemp)"

  if ! git -C "$repo" diff --name-status -z --find-renames "$baseline" -- runtime/src/main >"$diff_records"; then
    rm -f "$diff_records" "$untracked_records"
    printf 'unable to read fixed-baseline runtime production diff\n' >&2
    return 1
  fi

  while IFS= read -r -d '' status; do
    kind="${status:0:1}"
    case "$kind" in
      R|C)
        if ! IFS= read -r -d '' source || ! IFS= read -r -d '' target; then
          rm -f "$diff_records" "$untracked_records"
          printf 'truncated %s record in runtime production diff\n' "$kind" >&2
          return 1
        fi
        if is_sensitive_runtime_path "$source" || is_sensitive_runtime_path "$target"; then
          rm -f "$diff_records" "$untracked_records"
          printf 'rename/copy moves protected host ingress, adapter, or dispatcher scope: %s -> %s\n' "$source" "$target" >&2
          return 1
        fi
        if ! is_draft_runtime_path "$source" || ! is_draft_runtime_path "$target"; then
          rm -f "$diff_records" "$untracked_records"
          printf 'rename/copy source and target must both remain in mapping/draft: %s -> %s\n' "$source" "$target" >&2
          return 1
        fi
        CHANGED_PRODUCTION_PATHS+=("$target")
        ;;
      D)
        if ! IFS= read -r -d '' path; then
          rm -f "$diff_records" "$untracked_records"
          printf 'truncated deletion record in runtime production diff\n' >&2
          return 1
        fi
        rm -f "$diff_records" "$untracked_records"
        printf 'fixed-baseline runtime production source deletion is forbidden: %s\n' "$path" >&2
        return 1
        ;;
      A|M|T|U|X|B)
        if ! IFS= read -r -d '' path; then
          rm -f "$diff_records" "$untracked_records"
          printf 'truncated %s record in runtime production diff\n' "$kind" >&2
          return 1
        fi
        if is_sensitive_runtime_path "$path"; then
          rm -f "$diff_records" "$untracked_records"
          printf 'BT0 must not change protected host ingress, adapter, or dispatcher scope: %s\n' "$path" >&2
          return 1
        fi
        if ! is_draft_runtime_path "$path"; then
          rm -f "$diff_records" "$untracked_records"
          printf 'runtime production change outside mapping/draft requires review: %s\n' "$path" >&2
          return 1
        fi
        CHANGED_PRODUCTION_PATHS+=("$path")
        ;;
      *)
        rm -f "$diff_records" "$untracked_records"
        printf 'unsupported runtime production diff status: %s\n' "$status" >&2
        return 1
        ;;
    esac
  done <"$diff_records"

  if ! git -C "$repo" ls-files --others --exclude-standard -z -- runtime/src/main >"$untracked_records"; then
    rm -f "$diff_records" "$untracked_records"
    printf 'unable to read untracked runtime production paths\n' >&2
    return 1
  fi
  while IFS= read -r -d '' path; do
    if is_sensitive_runtime_path "$path"; then
      rm -f "$diff_records" "$untracked_records"
      printf 'untracked protected host ingress, adapter, or dispatcher source is forbidden: %s\n' "$path" >&2
      return 1
    fi
    if ! is_draft_runtime_path "$path"; then
      rm -f "$diff_records" "$untracked_records"
      printf 'untracked runtime production source outside mapping/draft requires review: %s\n' "$path" >&2
      return 1
    fi
    CHANGED_PRODUCTION_PATHS+=("$path")
  done <"$untracked_records"

  rm -f "$diff_records" "$untracked_records"
}

production_forbidden='net\.bytebuddy|ByteBuddy|java\.lang\.instrument|ClassFileTransformer|org\.objectweb\.asm\.ClassWriter|(^|[^[:alnum:]_])ClassWriter([^[:alnum:]_]|$)'
asm_write_forbidden='org\.objectweb\.asm\.(tree|analysis|commons|util)\.|ClassWriter|ClassReader[[:space:]]*\.[[:space:]]*EXPAND_FRAMES'
ownership_forbidden='(^|[^[:alnum:]_])(Executor(Service)?|Executors|Queue|BlockingQueue|Deque|ThreadFactory|Callback|Router|Consumer|Dispatcher|DispatchOwner|HookInstaller|HookManager)([^[:alnum:]_]|$)|new[[:space:]]+Thread[[:space:]]*\(|Thread[[:space:]]*\.[[:space:]]*(ofPlatform|ofVirtual|startVirtualThread)[[:space:]]*\(|CompletableFuture[[:space:]]*\.[[:space:]]*(runAsync|supplyAsync)[[:space:]]*\(|Executors[[:space:]]*\.[[:space:]]*new[A-Za-z]*Thread|addTransformer[[:space:]]*\(|removeTransformer[[:space:]]*\(|retransformClasses[[:space:]]*\(|redefineClasses[[:space:]]*\(|(^|[^[:alnum:]_])(enqueue|dequeue|dispatch|installHook|transform)([[:space:]]*\(|[^[:alnum:]_])'

boundary_files_have_forbidden_content() {
  local file found=1
  for file in "$@"; do
    [[ -f "$file" ]] || continue
    if grep -nHE "$production_forbidden|$asm_write_forbidden" "$file"; then
      found=0
    fi
    if grep -niHE "$ownership_forbidden" "$file"; then
      found=0
    fi
  done
  return "$found"
}

initialize_fixture_repo() {
  local repo="$1"
  mkdir -p "$repo/runtime/src/main/java/dev/turboism/adapter/cubism"
  mkdir -p "$repo/runtime/src/main/java/dev/turboism/adapter/host"
  mkdir -p "$repo/runtime/src/main/java/dev/turboism/mapping/draft"
  printf '%s\n' 'package dev.turboism.adapter.cubism; final class ExistingAdapter {}' >"$repo/runtime/src/main/java/dev/turboism/adapter/cubism/ExistingAdapter.java"
  printf '%s\n' 'package dev.turboism.adapter.host; final class HostSession {}' >"$repo/runtime/src/main/java/dev/turboism/adapter/host/HostSession.java"
  printf '%s\n' 'package dev.turboism.mapping.draft; final class ExistingDraft {}' >"$repo/runtime/src/main/java/dev/turboism/mapping/draft/ExistingDraft.java"
  git -C "$repo" init -q
  git -C "$repo" config user.name 'BT0 Boundary Fixture'
  git -C "$repo" config user.email 'bt0-fixture@example.invalid'
  git -C "$repo" add runtime/src/main
  git -C "$repo" commit -qm baseline
}

expect_audit_rejection() {
  local repo="$1" baseline="$2" label="$3"
  CHANGED_PRODUCTION_PATHS=()
  if audit_runtime_changes "$repo" "$baseline" >/dev/null 2>&1; then
    fail "self-test did not reject $label"
  fi
}

# Temporary Git repositories exercise the real NUL-delimited Git protocol,
# including deletion and rename records. Content fixtures exercise the exact
# production scanner. Nothing is written into the project production tree.
self_test_boundary_gate() {
  local fixture_root repo baseline queue_path dispatcher_path safe_path
  fixture_root="$(mktemp -d)"

  repo="$fixture_root/delete-adapter"
  initialize_fixture_repo "$repo"
  baseline="$(git -C "$repo" rev-parse HEAD)"
  rm "$repo/runtime/src/main/java/dev/turboism/adapter/cubism/ExistingAdapter.java"
  expect_audit_rejection "$repo" "$baseline" 'an adapter deletion'

  repo="$fixture_root/delete-host-session"
  initialize_fixture_repo "$repo"
  baseline="$(git -C "$repo" rev-parse HEAD)"
  rm "$repo/runtime/src/main/java/dev/turboism/adapter/host/HostSession.java"
  expect_audit_rejection "$repo" "$baseline" 'a HostSession deletion'

  repo="$fixture_root/rename-adapter"
  initialize_fixture_repo "$repo"
  baseline="$(git -C "$repo" rev-parse HEAD)"
  git -C "$repo" mv \
    runtime/src/main/java/dev/turboism/adapter/cubism/ExistingAdapter.java \
    runtime/src/main/java/dev/turboism/mapping/draft/RenamedAdapter.java
  expect_audit_rejection "$repo" "$baseline" 'an adapter-to-mapping/draft rename'

  repo="$fixture_root/content"
  initialize_fixture_repo "$repo"
  baseline="$(git -C "$repo" rev-parse HEAD)"
  queue_path="$repo/runtime/src/main/java/dev/turboism/mapping/draft/NewQueue.java"
  dispatcher_path="$repo/runtime/src/main/java/dev/turboism/mapping/draft/NewDispatcher.java"
  safe_path="$repo/runtime/src/main/java/dev/turboism/mapping/draft/SafeInterrupt.java"
  printf '%s\n' 'package dev.turboism.mapping.draft; final class NewQueue { java.util.Queue<String> work; }' >"$queue_path"
  printf '%s\n' 'package dev.turboism.mapping.draft; final class NewDispatcher { void dispatch() {} }' >"$dispatcher_path"
  printf '%s\n' 'package dev.turboism.mapping.draft; final class SafeInterrupt { void restore() { Thread.currentThread().interrupt(); } }' >"$safe_path"
  CHANGED_PRODUCTION_PATHS=()
  audit_runtime_changes "$repo" "$baseline" >/dev/null \
    || fail "self-test setup unexpectedly rejected mapping/draft content fixtures"
  boundary_files_have_forbidden_content "$queue_path" >/dev/null \
    || fail "boundary scanner self-test did not reject a new mapping/draft queue"
  boundary_files_have_forbidden_content "$dispatcher_path" >/dev/null \
    || fail "boundary scanner self-test did not reject a new mapping/draft dispatcher"
  if boundary_files_have_forbidden_content "$safe_path" >/dev/null; then
    fail "boundary scanner self-test rejected Thread.currentThread().interrupt()"
  fi

  rm -rf "$fixture_root"
}

self_test_boundary_gate

CHANGED_PRODUCTION_PATHS=()
audit_runtime_changes "$ROOT" "$BASELINE_COMMIT" \
  || fail "fixed-baseline runtime production diff violates the BT0 boundary"

changed_production_files=()
for path in "${CHANGED_PRODUCTION_PATHS[@]}"; do
  if [[ "$path" == *.java || "$path" == *.kt ]]; then
    changed_production_files+=("$ROOT/$path")
  fi
done

if boundary_files_have_forbidden_content "${changed_production_files[@]}"; then
  fail "changed BT0 mapping/draft production source claims bytecode writing, hook/transform installation, thread/queue/executor, or callback/router/consumer/dispatch ownership"
fi

printf '%s\n' 'BT0 bytecode/hook boundary gate passed.'
