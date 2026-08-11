#!/usr/bin/env bash
# Exact-version locale validation entrypoint; real runs delegate to the shared host runner.
# A real (non-dry-run) run with a custom --fixture must carry an explicit --fixture-sha256.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: launch-cubism-host-locale-validation.sh --version <5.2.03|5.3.02> --locale <system|en|ja|ko|zh_Hans|zh_Hant> --fixture <remote-path> [--fixture-sha256 <64-hex>] [--dry-run] [runner-options...]
EOF
}

version=''
locale='system'
fixture=''
fixture_sha256=''
dry_run=0
runner_options=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --version) [ "$#" -ge 2 ] || { echo "error: missing value for --version" >&2; exit 2; }; version="$2"; shift 2 ;;
    --locale) [ "$#" -ge 2 ] || { echo "error: missing value for --locale" >&2; exit 2; }; locale="$2"; shift 2 ;;
    --fixture) [ "$#" -ge 2 ] || { echo "error: missing value for --fixture" >&2; exit 2; }; fixture="$2"; shift 2 ;;
    --fixture-sha256) [ "$#" -ge 2 ] || { echo "error: missing value for --fixture-sha256" >&2; exit 2; }; fixture_sha256="$2"; shift 2 ;;
    --dry-run) dry_run=1; shift ;;
    *) runner_options+=("$1"); shift ;;
  esac
done

case "$version" in
  5.2.03|5203) runner_version=5203; version=5.2.03 ;;
  5.3.02|5302) runner_version=5302; version=5.3.02 ;;
  *) echo "error: --version must be 5.2.03 or 5.3.02" >&2; exit 2 ;;
esac

case "$locale" in
  system) ;;
  zh_Hans|zh-Hans) locale=zh-Hans ;;
  zh_Hant|zh-Hant) locale=zh-Hant ;;
  en|ja|ko) ;;
  *) echo "error: unsupported locale: $locale" >&2; exit 2 ;;
esac

[ -n "$fixture" ] || { echo "error: --fixture is required" >&2; exit 2; }

if [ "$dry_run" -eq 1 ]; then
  # Dry-run/build-only: a synthetic fixture without a hash is allowed, and this mode
  # never produces readiness evidence.
  if [ -n "$fixture_sha256" ]; then
    printf 'HOST_LOCALE_VALIDATION_DRY_RUN mode=dry-run/build-only version=%s locale=%s fixture=%s fixtureSha256=%s\n' \
      "$version" "$locale" "$fixture" "$fixture_sha256"
  else
    printf 'HOST_LOCALE_VALIDATION_DRY_RUN mode=dry-run/build-only version=%s locale=%s fixture=%s\n' \
      "$version" "$locale" "$fixture"
  fi
  exit 0
fi

# A real run with a custom --fixture must carry an explicit expected fixture SHA-256.
[ -n "$fixture_sha256" ] || {
  echo "error: a real run with a custom --fixture requires --fixture-sha256" >&2
  exit 2
}
[[ "$fixture_sha256" =~ ^[0-9a-fA-F]{64}$ ]] || {
  echo "error: fixture SHA-256 must contain exactly 64 hexadecimal characters" >&2
  exit 2
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
args=("$runner_version" --locale "$locale" --fixture "$fixture" --fixture-sha256 "$fixture_sha256")
exec bash "$script_dir/run-host-locale-host-validation.sh" "${args[@]}" "${runner_options[@]}"
