#!/usr/bin/env bash
# Host-locale-specific adapter for the generic exact-host runner.
# A custom --fixture override must carry an explicit --fixture-sha256, so the
# dispatcher's fixture-identity invariant cannot be bypassed by direct invocation.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: run-host-locale-host-validation.sh <5302|5203> [run-label] [--locale system|en|ja|ko|zh-Hans|zh-Hant] [--fixture <remote-path>] [--fixture-sha256 <64-hex>] [runner-options...]" >&2
  exit 2
fi
if [ "$#" -eq 1 ] && { [ "$1" = "--help" ] || [ "$1" = "-h" ]; }; then
  echo "usage: run-host-locale-host-validation.sh <5302|5203> [run-label] [--locale system|en|ja|ko|zh-Hans|zh-Hant] [--fixture <remote-path>] [--fixture-sha256 <64-hex>] [runner-options...]"
  exit 0
fi
version="$1"
shift
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi
locale=''
fixture_override=''
fixture_sha256_override=''
runner_options=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --locale)
      [ "$#" -ge 2 ] || { echo "error: missing value for --locale" >&2; exit 2; }
      locale="$2"
      case "$locale" in
        system|en|ja|ko|zh-Hans|zh-Hant) ;;
        *) echo "error: locale must be system, en, ja, ko, zh-Hans, or zh-Hant" >&2; exit 2 ;;
      esac
      shift 2
      ;;
    --fixture)
      [ "$#" -ge 2 ] || { echo "error: missing value for --fixture" >&2; exit 2; }
      fixture_override="$2"
      shift 2
      ;;
    --fixture-sha256)
      [ "$#" -ge 2 ] || { echo "error: missing value for --fixture-sha256" >&2; exit 2; }
      fixture_sha256_override="$2"
      shift 2
      ;;
    *) runner_options+=("$1"); shift ;;
  esac
done

case "$version" in
  5302)
    fixture_src='<local-home>/Documents/测试 混合模式.cmo3'
    fixture_sha256='57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4'
    ;;
  5203)
    fixture_src='<local-home>/TurboismPartValidation/part52-official/part-opacity-fixture-52-final.cmo3'
    fixture_sha256='331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028'
    ;;
  *)
    echo "error: version must be 5302 or 5203" >&2
    exit 2
    ;;
esac
if [ -n "$fixture_override" ]; then
  # A custom fixture identity always requires an explicit expected hash.
  [ -n "$fixture_sha256_override" ] || {
    echo "error: a custom --fixture requires --fixture-sha256" >&2
    exit 2
  }
  [[ "$fixture_sha256_override" =~ ^[0-9a-fA-F]{64}$ ]] || {
    echo "error: fixture SHA-256 must contain exactly 64 hexadecimal characters" >&2
    exit 2
  }
  fixture_src="$fixture_override"
  fixture_sha256="$fixture_sha256_override"
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
preview_root="$repo_root/build/preview/$worktree_id"
probe_jar="$repo_root/build/host-locale-validation-exerciser.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

[ -f "$preview_root/turboism-agent.jar" ] || {
  echo "error: preview agent not found; run: ./gradlew previewBundle" >&2
  exit 1
}
[ -f "$probe_jar" ] || {
  echo "error: probe jar not found; run: bash validation/host-locale-host-probe/build.sh" >&2
  exit 1
}

locale_option=()
if [ -n "$locale" ]; then
  locale_option=(--jvm-option "-Dturboism.locale=$locale")
fi
exec bash "$runner" \
  --name host-locale \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$preview_root" \
  --agent "$preview_root/turboism-agent.jar" \
  --plugin "$probe_jar:host-locale-validation-exerciser.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
  --ready-marker 'HOST_LOCALE_PROBE_READY' \
  --ready-marker 'Plugin load complete' \
  --result-file 'state/dev.turboism.validation.hostlocale/host-locale-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'HOST_LOCALE_MATRIX_RESULT status=FAIL' \
  --ready-timeout 300 \
  --result-timeout 600 \
  --exit-timeout 120 \
  "${locale_option[@]}" \
  "${runner_options[@]}"
