#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
driver="$root/scripts/preview/fps-resize-driver.sh"
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

echo "fps resize driver test: PASS"
