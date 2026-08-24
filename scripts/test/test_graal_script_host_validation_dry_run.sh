#!/usr/bin/env bash
# Offline dry-run contract for the portable Graal host validation wrapper.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/../.." && pwd -P)"
wrapper="$repo_root/scripts/preview/run-graal-script-host-validation.sh"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/turboism-graal-wrapper.XXXXXX")"
worktree_id='graal-wrapper-dry-run'
bundle="$repo_root/build/preview/$worktree_id"
probe="$tmp/graal-script-host-validation-exerciser.jar"
scripts_root="$tmp/scripts"

cleanup() {
  rm -rf -- "$tmp" "$bundle"
}
trap cleanup EXIT
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

mkdir -p "$bundle/graal/lib" "$scripts_root/example"
printf 'agent\n' > "$bundle/turboism-agent.jar"
printf 'probe\n' > "$probe"
printf 'library\n' > "$bundle/graal/lib/graal-host-any-version.jar"
printf 'console.log(1);\n' > "$scripts_root/example/main.js"
printf 'key\n' > "$tmp/key"

TURBOISM_WORKTREE_ID="$worktree_id" \
TURBOISM_GRAAL_JAVA='C:\Program Files\GraalVM\bin\java.exe' \
TURBOISM_CUBISM_JAVA='Z:\home\local-user\TurboismValidation\tools\graalvm-25.2.4\bin\java.exe' \
TURBOISM_GRAAL_VALIDATION_PROBE="$probe" \
TURBOISM_GRAAL_VALIDATION_SCRIPTS="$scripts_root" \
TURBOISM_HOST_VALIDATION_SSH_HOST='test@example.invalid' \
TURBOISM_HOST_VALIDATION_SSH_KEY="$tmp/key" \
TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX='/tmp/turboism-golden' \
TURBOISM_HOST_VALIDATION_REMOTE_ROOT='/tmp/turboism-validation' \
TURBOISM_HOST_VALIDATION_PROTON_RUNNER='/tmp/proton' \
bash "$wrapper" r-dry --dry-run > "$tmp/out"

grep -Fq 'name=graal-script' "$tmp/out" || fail 'wrapper did not delegate to the Graal validation runner'
grep -Fq 'homeDirCount=2' "$tmp/out" || fail 'wrapper did not stage scripts and packaged Graal libraries'
classpath_line="$(grep -F 'jvmOption.2=-Dturboism.graal.classpath=' "$tmp/out")"
case "$classpath_line" in
  *'\turboism-home\graal\lib\*') ;;
  *) fail 'wrapper does not resolve the packaged wildcard classpath inside Turboism home' ;;
esac
if [[ "$classpath_line" == *'{HOME}'* ]]; then
  fail 'runner did not resolve the Turboism home placeholder'
fi
grep -Fq 'jvmOption.1=-Dturboism.graal.java=C:\Program Files\GraalVM\bin\java.exe' "$tmp/out" \
  || fail 'TURBOISM_GRAAL_JAVA was not transported'
grep -Fq 'jvmOption.1.quoted="-Dturboism.graal.java=C:\Program Files\GraalVM\bin\java.exe"' "$tmp/out" \
  || fail 'TURBOISM_GRAAL_JAVA was not quoted for JAVA_TOOL_OPTIONS'
grep -Fq 'cubismJava=Z:\home\local-user\TurboismValidation\tools\graalvm-25.2.4\bin\java.exe' "$tmp/out" \
  || fail 'TURBOISM_CUBISM_JAVA was not transported'
grep -Fq 'cubismJavaConsoleMarker=GraalVM CE 25.2.4' "$tmp/out" \
  || fail 'GraalVM Cubism-Java identity marker was not configured'
if grep -Fq 'worktree-graal-script-runtime-takeover.jar' "$tmp/out"; then
  fail 'wrapper still embeds a worktree-specific Graal host JAR name'
fi

echo 'PASS: Graal script host-validation dry run'
