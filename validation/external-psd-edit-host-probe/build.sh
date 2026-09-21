#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
id="$(bash scripts/dev/worktree-id.sh)"
shopt -s nullglob
sdk=("$root/build/worktree/$id/sdk/libs/"sdk-*.jar)
[[ ${#sdk[@]} -eq 1 ]] || { echo 'Build :sdk:jar first' >&2; exit 1; }
src="$root/validation/external-psd-edit-host-probe/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 17 -Xlint:all -cp "${sdk[0]}" -d "$out" \
  "$src/dev/turboism/validation/externalpsd/PsdValidationContent.java" \
  "$src/dev/turboism/validation/externalpsd/ExactHostRowTarget.java" \
  "$src/dev/turboism/validation/externalpsd/OfficialPsdFixturePreparation.java" \
  "$src/dev/turboism/validation/externalpsd/ExternalPsdEditHostProbe.java"
cp -R "$src/META-INF" "$out/"
[[ $# -eq 0 ]] || { echo 'Usage: build.sh' >&2; exit 2; }
jar cf "$root/build/external-psd-edit-host-probe.jar" -C "$out" .
jar tf "$root/build/external-psd-edit-host-probe.jar" \
  | grep -Fx 'META-INF/turboism/i18n/messages.properties' >/dev/null
if jar tf "$root/build/external-psd-edit-host-probe.jar" \
    | grep -E '^com/live2d/ui/treeTable/j\.class$' >/dev/null; then
  echo 'Probe JAR must not contain the test-only host fixture' >&2
  exit 1
fi
sha256sum "$root/build/external-psd-edit-host-probe.jar"
