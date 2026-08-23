#!/usr/bin/env bash
# Offline security contract for run-cubism-host-validation.sh.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/../.." && pwd -P)"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/turboism-cubism-args.XXXXXX")"
cleanup() { rm -rf -- "$tmp"; }
trap cleanup EXIT

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
expect_rejected() {
  local label="$1" needle="$2"
  shift 2
  if "$@" >"$tmp/$label.out" 2>&1; then
    fail "$label unexpectedly succeeded"
  fi
  grep -Fq -- "$needle" "$tmp/$label.out" || {
    printf -- '--- %s output ---\n' "$label" >&2
    cat "$tmp/$label.out" >&2
    fail "$label did not report $needle"
  }
}

bundle="$tmp/bundle"
mkdir -p "$bundle"
printf 'agent\n' > "$bundle/agent.jar"
printf 'plugin\n' > "$bundle/probe.jar"
printf 'fixture\n' > "$tmp/fixture.cmo3"
printf 'home-file\n' > "$tmp/home-file.txt"
mkdir -p "$tmp/home-dir"
printf 'home-dir\n' > "$tmp/home-dir/value.txt"

base=(bash "$runner" --name arg-contract --version 5302 --bundle-root "$bundle"
  --agent "$bundle/agent.jar" --plugin "$bundle/probe.jar" --fixture-local "$tmp/fixture.cmo3"
  --result-file state/result.txt --dry-run)

cubism_java='Z:\home\local-user\TurboismValidation\tools\graalvm-25.2.4\bin\java.exe'
"${base[@]}" --home-file "$tmp/home-file.txt:scripts/input.txt" --home-dir "$tmp/home-dir:scripts" \
  --trigger state/trigger.flag --cubism-java "$cubism_java" \
  --cubism-java-console-marker 'GraalVM Community' > "$tmp/good.out"
grep -Fq 'homeFileCount=1' "$tmp/good.out" || fail 'valid home-file was not accepted'
grep -Fq 'homeDirCount=1' "$tmp/good.out" || fail 'valid home-dir was not accepted'
grep -Fq 'trigger=state/trigger.flag' "$tmp/good.out" || fail 'valid trigger was not accepted'
grep -Fq "cubismJava=$cubism_java" "$tmp/good.out" || fail 'valid Cubism Java override was not accepted'
grep -Fq 'cubismJavaConsoleMarker=GraalVM Community' "$tmp/good.out" \
  || fail 'Cubism Java console marker was not accepted'

expect_rejected result-traversal 'result file must be a normalized relative Unix path' \
  "${base[@]}" --result-file '../outside'
expect_rejected result-metachar 'result file must contain only ASCII' \
  "${base[@]}" --result-file 'state/$(touch-pwned)'
expect_rejected trigger-metachar 'trigger path must contain only ASCII' \
  "${base[@]}" --trigger 'state/trigger;touch-pwned'
expect_rejected home-file-traversal 'home-file destination must be a normalized relative Unix path' \
  "${base[@]}" --home-file "$tmp/home-file.txt:../outside"
expect_rejected home-dir-metachar 'home-dir destination must contain only ASCII' \
  "${base[@]}" --home-dir "$tmp/home-dir:scripts/\$(touch-pwned)"
expect_rejected control-character 'result file must contain only ASCII' \
  "${base[@]}" --result-file $'state/result\n.txt'

# A non-dry run must not interpolate attacker-controlled path text into the SSH
# command. The validation rejects it before trying either transport stub.
bin="$tmp/bin"
mkdir -p "$bin"
cat > "$bin/ssh" <<'SH'
#!/usr/bin/env bash
: "${SSH_MARKER:?}"
touch "$SSH_MARKER"
exit 99
SH
chmod +x "$bin/ssh"
printf 'key\n' > "$tmp/key"
expect_rejected before-ssh 'trigger path must contain only ASCII' env PATH="$bin:$PATH" SSH_MARKER="$tmp/ssh-used" \
  bash "$runner" --name arg-contract --version 5302 --bundle-root "$bundle" --agent "$bundle/agent.jar" \
  --plugin "$bundle/probe.jar" --fixture-local "$tmp/fixture.cmo3" --result-file state/result.txt \
  --trigger 'state/trigger;touch-pwned' --ssh-key "$tmp/key"
[ ! -e "$tmp/ssh-used" ] || fail 'rejected path reached SSH transport'

echo 'PASS: Cubism host-validation argument hardening'
