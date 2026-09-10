#!/usr/bin/env bash
# Pre-launch hook: seed the Cubism Editor language that
# File → Environment Settings → General → Language persists.
#
# Why this exists: the launcher's -Duser.language selects the language *version*
# of the build, while the Environment Settings choice is stored as the
# "Locale.Editor" property inside the editor's own data directory and is applied
# by Cubism to the process default locale at startup. A run can therefore only
# discriminate between the two sources when the seeded value differs from the
# launcher language; that precondition is recorded in the evidence below.
#
# The property store is a Java-serialized String at
#   <dataDir>/.tmp/property/default_class_key/Locale.Editor.ser
# (verified against the Cubism 5.2/5.3/5.4 config writer: the file name keeps the
# dotted property key, the directory is the class key, and the payload is an
# ObjectOutputStream String: AC ED 00 05 74 <len16> <utf8>).
#
# Positional arguments (generic runner hook contract):
#   1 task  2 home  3 evidence  4 prefix  5 fixture  6 runId  7 version
#   8 resultTimeout  9 protonWrapper  10 protonRunner  11 display
#   12 environmentLanguage (en|ja|ko|zh), appended after the generic context
#      arguments. The language must travel as an argument: the durable queue
#      re-executes the runner with no ambient environment, so an exported
#      variable never reaches the hook. TURBOISM_HOST_VALIDATION_ENVIRONMENT_LANGUAGE
#      is still honoured as a fallback so the hook stays runnable by hand.
set -euo pipefail

fail() {
  printf 'environment-language: %s\n' "$*" >&2
  exit 1
}

[ "$#" -ge 7 ] || fail "missing runner hook arguments"
evidence_dir="$3"
prefix_dir="$4"
version="$7"
[ -n "$evidence_dir" ] || fail "empty evidence directory"
[ -n "$prefix_dir" ] || fail "empty prefix directory"

language="${12:-${TURBOISM_HOST_VALIDATION_ENVIRONMENT_LANGUAGE:-}}"
[ -n "$language" ] || fail "missing environment language argument (en, ja, ko, zh)"
case "$language" in
  en|ja|ko) ;;
  zh|zh-Hans|zh-Hant) language=zh ;;
  *) fail "unsupported language '$language' (en, ja, ko, zh)" ;;
esac

# The runner's context argument 7 is the lane's version token (the adapter's
# --version): 5203/5302. The full Cubism version strings stay accepted so the
# hook can also be exercised on its own.
case "$version" in
  5203|5.2.03) edition="5.2" ;;
  5302|5.3.02|5.3.03) edition="5.3" ;;
  *) fail "unsupported Cubism version '$version'" ;;
esac

data_root_parent="$prefix_dir/pfx/drive_c/users"
[ -d "$data_root_parent" ] || fail "cloned prefix has no users directory: $data_root_parent"

shopt -s nullglob
matches=("$data_root_parent"/*/AppData/Roaming/Live2D/Cubism"$edition"*_Editor)
shopt -u nullglob
[ "${#matches[@]}" -ge 1 ] || fail "no Cubism $edition editor data directory in the cloned prefix"
[ "${#matches[@]}" -eq 1 ] || fail "ambiguous Cubism $edition editor data directory (${#matches[@]} matches)"
data_dir="${matches[0]}"

store_dir="$data_dir/.tmp/property/default_class_key"
target="$store_dir/Locale.Editor.ser"
mkdir -p -- "$store_dir" || fail "cannot create property store: $store_dir"

previous_hex='none'
rewritten=NO
if [ -f "$target" ]; then
  previous_hex="$(od -An -tx1 -v -- "$target" | tr -d ' \n')"
  cp -- "$target" "$evidence_dir/environment-language-previous.ser"
  rewritten=YES
fi

length=${#language}
[ "$length" -le 255 ] || fail "language code is too long to serialize"
# Fixed ObjectOutputStream header (magic, version, TC_STRING, high length byte),
# then the length byte, then the UTF-8 payload.
printf '\254\355\000\005\164\000' > "$target" || fail "cannot write $target"
printf "$(printf '\\%03o' "$length")" >> "$target" || fail "cannot write $target"
printf '%s' "$language" >> "$target" || fail "cannot write $target"

actual_hex="$(od -An -tx1 -v -- "$target" | tr -d ' \n')"
expected_hex="aced00057400$(printf '%02x' "$length")$(printf '%s' "$language" | od -An -tx1 -v | tr -d ' \n')"
[ "$actual_hex" = "$expected_hex" ] \
  || fail "serialized language store does not match the expected bytes"

# Record the launcher's language version so the evidence shows whether the seeded
# Environment Settings language can be told apart from -Duser.language.
launcher_bat="$(find "$prefix_dir" -maxdepth 3 -type f -name 'CubismEditor5.bat' \
  -path "*$edition*" -print -quit 2>/dev/null || true)"
launcher_language='unknown'
if [ -n "$launcher_bat" ] && [ -f "$launcher_bat" ]; then
  launcher_language="$(sed -n 's/.*-Duser\.language=\([^ "]*\).*/\1/p' "$launcher_bat" | head -1)"
  [ -n "$launcher_language" ] || launcher_language='unknown'
fi

discriminating=NO
[ "$launcher_language" != "$language" ] && discriminating=YES

serialized_sha256="$(sha256sum -- "$target" | cut -d' ' -f1)"
{
  printf 'schemaVersion=1\n'
  printf 'version=%s\n' "$version"
  printf 'language=%s\n' "$language"
  printf 'launcherLanguage=%s\n' "$launcher_language"
  printf 'discriminating=%s\n' "$discriminating"
  printf 'propertyKey=Locale.Editor\n'
  printf 'seededFile=%s\n' "$target"
  printf 'serializedSha256=%s\n' "$serialized_sha256"
  printf 'serializedHex=%s\n' "$actual_hex"
  printf 'previousSerializedHex=%s\n' "$previous_hex"
  printf 'rewrittenExistingValue=%s\n' "$rewritten"
} > "$evidence_dir/environment-language.properties"

printf 'environment-language: seeded %s into %s (launcherLanguage=%s discriminating=%s)\n' \
  "$language" "$target" "$launcher_language" "$discriminating"
if [ "$discriminating" = NO ]; then
  printf 'environment-language: WARNING seeded language equals the launcher language;'\'' this run cannot discriminate the Environment Settings source\n' >&2
fi
