#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd -P)"
RUNNER="$REPO_ROOT/scripts/preview/run-cubism-host-validation.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/turboism-host-lock.XXXXXX")"
PID=''
fail() { echo "FAIL: $1" >&2; exit 1; }
cleanup() {
  [ -z "$PID" ] || { kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; }
  rm -rf -- "$TMP"
}
trap cleanup EXIT

BUNDLE="$TMP/bundle"
BIN="$TMP/bin"
LOCK="$TMP/runner.lock"
RELEASE="$TMP/release"
mkdir -p "$BUNDLE" "$BIN"
printf 'x\n' > "$BUNDLE/agent.jar"
printf 'x\n' > "$BUNDLE/plugin.jar"
printf 'x\n' > "$BUNDLE/fixture.cmo3"
: > "$TMP/ssh-key"

cat > "$BIN/stub" <<'SH'
#!/usr/bin/env bash
: > "$STUB_MARKER"
if [ "${STUB_BLOCK:-0}" = 1 ]; then
  while [ ! -e "$STUB_RELEASE" ]; do sleep .01; done
fi
exit 1
SH
chmod +x "$BIN/stub"
ln -s stub "$BIN/ssh"
ln -s stub "$BIN/scp"

ARGS=(bash "$RUNNER" --name lock-test --version 5302 --bundle-root "$BUNDLE"
  --agent "$BUNDLE/agent.jar" --plugin "$BUNDLE/plugin.jar"
  --fixture-local "$BUNDLE/fixture.cmo3" --result-marker never
  --ssh-key "$TMP/ssh-key")

first="$TMP/first-ssh"
PATH="$BIN:$PATH" STUB_MARKER="$first" STUB_RELEASE="$RELEASE" STUB_BLOCK=1 \
  TURBOISM_HOST_VALIDATION_LOCK_FILE="$LOCK" "${ARGS[@]}" >/dev/null 2>&1 &
PID=$!
for _ in {1..200}; do
  [ -e "$first" ] && break
  kill -0 "$PID" 2>/dev/null || break
  sleep .01
done
[ -e "$first" ] || fail 'first runner did not reach SSH stub'

second="$TMP/second-ssh"
if PATH="$BIN:$PATH" STUB_MARKER="$second" STUB_RELEASE="$RELEASE" STUB_BLOCK=0 \
  TURBOISM_HOST_VALIDATION_LOCK_FILE="$LOCK" "${ARGS[@]}" >"$TMP/second.out" 2>&1; then s=0; else s=$?; fi
[ "$s" -ne 0 ] || fail 'busy runner succeeded'
grep -Fq 'host validation lock is busy' "$TMP/second.out" || fail 'busy message missing'
[ ! -e "$second" ] || fail 'busy runner reached SSH stub'

: > "$RELEASE"
wait "$PID" || true
PID=''

third="$TMP/third-ssh"
if PATH="$BIN:$PATH" STUB_MARKER="$third" STUB_RELEASE="$RELEASE" STUB_BLOCK=0 \
  TURBOISM_HOST_VALIDATION_LOCK_FILE="$LOCK" "${ARGS[@]}" >/dev/null 2>&1; then s=0; else s=$?; fi
[ "$s" -ne 0 ] || fail 'released runner unexpectedly succeeded'
[ -e "$third" ] || fail 'released lock was not reacquired'

for label in empty invalid; do
  value=''
  [ "$label" = invalid ] && value="$TMP/missing/runner.lock"
  marker="$TMP/$label-ssh"
  if PATH="$BIN:$PATH" STUB_MARKER="$marker" STUB_RELEASE="$RELEASE" STUB_BLOCK=0 \
    TURBOISM_HOST_VALIDATION_LOCK_FILE="$value" "${ARGS[@]}" >"$TMP/$label.out" 2>&1; then s=0; else s=$?; fi
  [ "$s" -ne 0 ] || fail "$label override succeeded"
  grep -Fq 'host validation:' "$TMP/$label.out" || fail "$label override message missing"
  [ ! -e "$marker" ] || fail "$label override reached SSH stub"
done

exec {fd}>>"$LOCK"
flock -n "$fd" || fail 'test could not hold lock'
dry="$TMP/dry-ssh"
if PATH="$BIN:$PATH" STUB_MARKER="$dry" STUB_RELEASE="$RELEASE" STUB_BLOCK=0 \
  TURBOISM_HOST_VALIDATION_LOCK_FILE="$LOCK" "${ARGS[@]}" --dry-run >/dev/null 2>&1; then :; else fail 'dry-run failed while lock was held'; fi
[ ! -e "$dry" ] || fail 'dry-run reached SSH stub'
exec {fd}>&-

echo 'PASS: host validation lock check'
