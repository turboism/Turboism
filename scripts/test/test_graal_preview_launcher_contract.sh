#!/usr/bin/env bash
# Static, offline contract for the Windows preview launcher's ProbeOnly behavior.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/../.." && pwd -P)"
launcher="$repo_root/scripts/preview/launch-cubism-turboism.ps1"

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
require() { grep -Fq -- "$1" "$launcher" || fail "missing launcher token: $1"; }

require 'function Test-CompatibleGraalJava'
require 'GRAALVM_VERSION="25\.2\.'
require 'function Test-GraalLibraryClosure'
require 'js-isolate-windows-amd64-community-*.jar'
require 'Graal is configured but its packaged library closure is incomplete'
require 'Test-GraalLibraryClosure -PreviewRoot $previewRoot'
require 'Graal library closure: valid.'
require 'Graal library closure: skipped (Graal not configured).'
require 'Probe passed: launcher prerequisites only; Cubism host readiness was not checked.'
require 'this launch will use Cubism bundled Java'
require 'https://www.graalvm.org/downloads/'

selection_block="$(python3 - "$launcher" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
start = text.index('$java = if (')
end = text.index('$graalHostJava = ', start)
print(text[start:end])
PY
)"
printf '%s\n' "$selection_block" | grep -Fq 'Resolve-GraalJava -Requested ""' || fail 'persisted GraalVM selection does not attempt discovery'
printf '%s\n' "$selection_block" | grep -Fq '$cubismJvm = "bundled"' || fail 'missing GraalVM does not select bundled recovery mode'
printf '%s\n' "$selection_block" | grep -Fq '(Resolve-Path -LiteralPath $defaultCubismJava).Path' || fail 'missing GraalVM does not resolve Cubism bundled Java'
printf '%s\n' "$selection_block" | grep -Fq 'Write-Warning' || fail 'missing GraalVM fallback is silent'

probe_block="$(python3 - "$launcher" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
start = text.index('if ($ProbeOnly) {')
end = text.index('if ($ProbeAgent) {', start)
print(text[start:end])
PY
)"
printf '%s\n' "$probe_block" | grep -Fq 'Test-GraalLibraryClosure' || fail 'ProbeOnly does not validate configured Graal libraries'
printf '%s\n' "$probe_block" | grep -Fq 'host readiness was not checked' || fail 'ProbeOnly still implies host readiness'

echo 'PASS: Graal preview launcher contract'
