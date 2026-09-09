#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="history-probe-packaging-test-$$"
build_root="$repo_root/build"
bundle_root="$build_root/manual-test/$worktree_id/windows-history-panel-validation"
preview_root="$build_root/preview/$worktree_id"
panel_root="$build_root/worktree/$worktree_id/history-panel/libs"
class_root="$build_root/worktree/$worktree_id/integration-tests/classes/java/test/dev/turboism/tests/plugin"

cleanup() {
  rm -rf \
    "$build_root/manual-test/$worktree_id" \
    "$preview_root" \
    "$build_root/worktree/$worktree_id"
}
trap cleanup EXIT

mkdir -p "$preview_root" "$panel_root" "$class_root"
printf 'agent fixture\n' > "$preview_root/turboism-agent.jar"
printf 'panel fixture\n' > "$panel_root/history-panel-0.1.0-$worktree_id.jar"
for class_name in \
  WindowsHistorySeedValidationProbe \
  'WindowsHistorySeedValidationProbe$State' \
  WindowsHistoryManagerValidationProbe \
  'WindowsHistoryManagerValidationProbe$State' \
  'WindowsHistoryManagerValidationProbe$Snapshot' \
  'WindowsHistoryManagerValidationProbe$NativeDetail' \
  WindowsHistoryFloatProbe; do
  printf 'class fixture\n' > "$class_root/$class_name.class"
done

TURBOISM_WORKTREE_ID="$worktree_id" \
  bash "$repo_root/scripts/preview/package-windows-history-panel-validation.sh" "$bundle_root" \
  >/dev/null

catalog='META-INF/turboism/i18n/messages.properties'
for probe in \
  history-seed-validation-probe.jar \
  history-validation-probe.jar \
  history-float-probe.jar; do
  jar_path="$bundle_root/plugins/$probe"
  if ! jar tf "$jar_path" | grep -Fxq "$catalog"; then
    printf 'error: %s is missing declared base i18n catalog %s\n' "$probe" "$catalog" >&2
    exit 1
  fi
done
seed_jar="$bundle_root/plugins/history-seed-validation-probe.jar"
for entry in \
  'dev/turboism/tests/plugin/WindowsHistorySeedValidationProbe.class' \
  'dev/turboism/tests/plugin/WindowsHistoryManagerValidationProbe.class' \
  'dev/turboism/tests/plugin/WindowsHistoryManagerValidationProbe$Snapshot.class' \
  'dev/turboism/tests/plugin/WindowsHistoryManagerValidationProbe$NativeDetail.class'; do
  if ! jar tf "$seed_jar" | grep -Fxq "$entry"; then
    printf 'error: seed package is missing embedded sampler dependency %s\n' "$entry" >&2
    exit 1
  fi
done

printf '[test] history validation probe packaging includes i18n and embedded native sampler dependencies\n'
wrapper="$repo_root/scripts/preview/run-history-primary-validation.sh"
[ -x "$wrapper" ] || {
  printf 'error: history primary capability wrapper is missing or not executable\n' >&2
  exit 1
}
if grep -Eq -- '--remote-pre-|--client-script|collect-history-validation' "$wrapper"; then
  printf 'error: history primary wrapper contains a hook/client/collector dependency\n' >&2
  exit 1
fi
for required_text in \
  'run-cubism-host-validation.sh' \
  '--result-file' \
  'data/dev.turboism.validation.history-seed/history-seed.jsonl'; do
  grep -Fq -- "$required_text" "$wrapper" || {
    printf 'error: history primary wrapper is missing %s\n' "$required_text" >&2
    exit 1
  }
done
