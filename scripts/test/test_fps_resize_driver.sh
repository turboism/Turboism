#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
driver="$root/scripts/preview/fps-resize-driver.sh"
wrapper="$root/scripts/preview/run-fps-host-validation.sh"
# shellcheck source=../preview/fps-resize-driver.sh
source "$driver"

fail() {
  echo "fps resize driver test: $*" >&2
  exit 1
}

selector='-123456-fps.cmo3'
parent_cmd="bash -s -- $selector"
cubism_cmd="Z:\\Cubism\\java.exe -cp Z:\\Cubism\\app\\lib\\Live2D_Cubism.jar Z:\\TurboismValidation\\fps-run$selector"

fps_cmdline_matches_cubism_selector "$cubism_cmd" "$selector" \
  || fail "Cubism JVM with the task selector was rejected"
if fps_cmdline_matches_cubism_selector "$parent_cmd" "$selector"; then
  fail "selector-bearing parent shell was accepted as Cubism"
fi
if fps_cmdline_matches_cubism_selector "$cubism_cmd" '-654321-fps.cmo3'; then
  fail "Cubism JVM from another task was accepted"
fi

stub_dir="$(mktemp -d)"
trap 'rm -rf "$stub_dir"' EXIT
cat > "$stub_dir/ps" <<EOF
#!/usr/bin/env bash
cat <<'ROWS'
  101 bash     $parent_cmd
  202 java.exe $cubism_cmd
  303 java.exe Z:\\Cubism\\java.exe -cp Z:\\Other.jar Z:\\other.cmo3
ROWS
EOF
chmod +x "$stub_dir/ps"
mapfile -t candidates < <(PATH="$stub_dir:$PATH" fps_cubism_java_pids)
[ "${#candidates[@]}" -eq 1 ] || fail "expected one Cubism JVM candidate, got ${#candidates[@]}"
[ "${candidates[0]}" = "202" ] || fail "selected non-Cubism process ${candidates[0]}"

if grep -Fq 'pgrep -f -- "$selector"' "$driver"; then
  fail "driver still enumerates candidates by selector alone"
fi

[ "$(fps_parse_geometry $'WINDOW=42\nWIDTH=1280\nHEIGHT=720')" = $'1280\t720' ] \
  || fail "valid window geometry was rejected"
if fps_parse_geometry $'WINDOW=42\nHEIGHT=720' >/dev/null; then
  fail "geometry without WIDTH was accepted"
fi
if fps_parse_geometry $'WINDOW=42\nWIDTH=invalid\nHEIGHT=720' >/dev/null; then
  fail "non-numeric window geometry was accepted"
fi
if fps_parse_geometry $'WINDOW=42\nWIDTH=40\nHEIGHT=720' >/dev/null; then
  fail "geometry too narrow for the resize burst was accepted"
fi

wrapper_source=$(<"$wrapper")
[[ "$wrapper_source" == *'--remote-pre-launch "$driver"'* ]] \
  || fail "FPS wrapper does not route resize through the common local Runner hook"
[[ "$wrapper_source" == *'--remote-pre-launch-background'* ]] \
  || fail "FPS wrapper does not request task-owned background hook management"
[[ "$wrapper_source" == *'--remote-pre-launch-args-only'* ]] \
  || fail "FPS wrapper does not keep the reviewed driver argument boundary"
[[ "$wrapper_source" == *"--remote-pre-launch-arg '{FIXTURE_NAME}'"* ]] \
  || fail "FPS wrapper does not pass the canonical fixture name to the hook"
[[ "$wrapper_source" == *'--fixture-host "$fixture_src"'* ]] \
  || fail "FPS wrapper does not use the local fixture transport"
if grep -Eq '(^|[[:space:]])(ssh|scp)([[:space:]]|$)|nohup[[:space:]]+ssh|ssh_host|ssh_key' <<<"$wrapper_source"; then
  fail "FPS wrapper still contains an SSH/SCP or detached transport bypass"
fi
[[ "$wrapper_source" != *'driver_pid'* ]] || fail "FPS wrapper owns a second driver lifecycle"

# Execute the real wrapper + real Runner against temporary synthetic inputs.
# This catches a missing `exec bash "$runner"` that parameter-text greps cannot.
fixture_repo="$stub_dir/fixture repo"
mkdir -p "$fixture_repo/scripts/preview" "$fixture_repo/scripts/dev" "$fixture_repo/build/preview/test" "$stub_dir/golden"
cp "$wrapper" "$driver" "$root/scripts/preview/run-cubism-host-validation.sh" \
  "$root/scripts/preview/host-validation-env.sh" "$root/scripts/preview/archive-cubism-host-evidence.sh" "$fixture_repo/scripts/preview/"
printf '#!/bin/sh\nprintf "test\\n"\n' > "$fixture_repo/scripts/dev/worktree-id.sh"
chmod +x "$fixture_repo/scripts/dev/worktree-id.sh"
printf 'synthetic agent' > "$fixture_repo/build/preview/test/turboism-agent.jar"
printf 'synthetic probe' > "$fixture_repo/build/fps-host-validation-exerciser.jar"
printf 'synthetic fixture' > "$stub_dir/fixture.cmo3"
for mode in dry prepare; do
  options=(--dry-run)
  [ "$mode" != prepare ] || options=(--prepare-dir "$stub_dir/prepared")
  TURBOISM_ENV_FILE=/dev/null \
    TURBOISM_HOST_VALIDATION_ENV_LOADED=1 \
    TURBOISM_HOST_VALIDATION_FIXTURE_5302="$stub_dir/fixture.cmo3" \
    TURBOISM_HOST_VALIDATION_FIXTURE_5302_SHA256="$(sha256sum "$stub_dir/fixture.cmo3" | cut -d' ' -f1)" \
    bash "$fixture_repo/scripts/preview/run-fps-host-validation.sh" 5302 regression \
      --golden-prefix "$stub_dir/golden" --host-root "$stub_dir/must-not-create" \
      --proton-runner /bin/true --proton-wrapper /bin/true "${options[@]}" > "$stub_dir/wrapper-$mode.out"
done
[ ! -e "$stub_dir/must-not-create" ] || fail "wrapper prepare/dry-run touched host task root"
python3 - "$stub_dir/prepared/runner-request.json" <<'PY'
import json,sys
request=json.load(open(sys.argv[1]))
assert request['environment']=={}
assert '--remote-pre-launch-background' in request['argv']
assert '{FIXTURE_NAME}' in request['argv']
PY
echo "fps resize driver test: PASS"
