#!/usr/bin/env bash
# Exact-version locale validation entrypoint; real runs delegate to the shared host runner.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: launch-cubism-host-locale-validation.sh --version <5.2.03|5.3.02> --locale <system|en|ja|ko|zh_Hans|zh_Hant> --fixture <remote-path> [--dry-run] [runner-options...]
EOF
}

version=''
locale='system'
fixture=''
dry_run=0
runner_options=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --version) [ "$#" -ge 2 ] || { echo "error: missing value for --version" >&2; exit 2; }; version="$2"; shift 2 ;;
    --locale) [ "$#" -ge 2 ] || { echo "error: missing value for --locale" >&2; exit 2; }; locale="$2"; shift 2 ;;
    --fixture) [ "$#" -ge 2 ] || { echo "error: missing value for --fixture" >&2; exit 2; }; fixture="$2"; shift 2 ;;
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
  printf 'HOST_LOCALE_VALIDATION_DRY_RUN version=%s locale=%s fixture=%s\n' "$version" "$locale" "$fixture"
  exit 0
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
args=("$runner_version" --locale "$locale" --fixture "$fixture")
exec bash "$script_dir/run-host-locale-host-validation.sh" "${args[@]}" "${runner_options[@]}"
