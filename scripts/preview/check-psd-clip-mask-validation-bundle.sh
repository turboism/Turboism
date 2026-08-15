#!/usr/bin/env bash
# Asserts the PSD clip-mask host-validation bundle jars carry the
# descriptor-declared i18n catalogs the loader requires. Fails the packaging
# check when a catalog is omitted so the regression recurs as a test failure.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="${TURBOISM_WORKTREE_ID:-main}"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-psd-clip-mask-validation"
plugin_jar="$bundle_root/plugins/psd-clip-mask-import.jar"
probe_jar="$bundle_root/plugins/psd-clip-mask-validation-probe.jar"

[ -f "$plugin_jar" ] || { printf 'error: bundle plugin jar missing: %s\n' "$plugin_jar" >&2; exit 1; }
[ -f "$probe_jar" ] || { printf 'error: bundle probe jar missing: %s\n' "$probe_jar" >&2; exit 1; }

# Production plugin descriptor declares baseName META-INF/turboism/i18n/messages
# with locales en, ja, ko, zh-Hans, zh-Hant (dash -> underscore paths).
production_catalogs=(
  META-INF/turboism/plugin.json
  META-INF/turboism/i18n/messages.properties
  META-INF/turboism/i18n/messages_en.properties
  META-INF/turboism/i18n/messages_ja.properties
  META-INF/turboism/i18n/messages_ko.properties
  META-INF/turboism/i18n/messages_zh_Hans.properties
  META-INF/turboism/i18n/messages_zh_Hant.properties
)
probe_entries=(
  META-INF/turboism/plugin.json
  META-INF/turboism/i18n/messages.properties
)

check_entries() {
  local jar="$1"
  shift
  local missing=0
  local entry
  for entry in "$@"; do
    if ! jar tf "$jar" | grep -Fxq "$entry"; then
      printf 'error: %s is missing %s\n' "$jar" "$entry" >&2
      missing=1
    fi
  done
  return "$missing"
}

check_entries "$plugin_jar" "${production_catalogs[@]}"
check_entries "$probe_jar" "${probe_entries[@]}"

printf '[check] PSD clip-mask host-validation bundle i18n catalogs OK (%s, %s)\n' \
  "$(basename "$plugin_jar")" "$(basename "$probe_jar")"
