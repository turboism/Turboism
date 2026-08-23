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
[[ "$wrapper_source" == *'ssh_host="$TURBOISM_HOST_VALIDATION_SSH_HOST"'* ]] \
  || fail "FPS wrapper does not read the local SSH host configuration"
[[ "$wrapper_source" == *'ssh_key="$TURBOISM_HOST_VALIDATION_SSH_KEY"'* ]] \
  || fail "FPS wrapper does not read the local SSH key configuration"
[[ "$wrapper_source" == *'ssh_host="${runner_args[index + 1]}"'* ]] \
  || fail "FPS wrapper does not reuse the runner SSH host"
[[ "$wrapper_source" == *'ssh_key="${runner_args[index + 1]}"'* ]] \
  || fail "FPS wrapper does not reuse the runner SSH key"
[[ "$wrapper_source" == *'nohup ssh -i "$ssh_key"'* ]] \
  || fail "FPS resize driver does not use the resolved SSH key"
[[ "$wrapper_source" == *'"$ssh_host" "bash -s --'* ]] \
  || fail "FPS resize driver does not use the resolved SSH host"

echo "fps resize driver test: PASS"
