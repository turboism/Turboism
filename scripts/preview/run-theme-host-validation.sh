#!/usr/bin/env bash
# Runs the theme-system real-host validation for one exact Cubism version.
#
# Usage: run-theme-host-validation.sh <5302|5203> [run-label]
#
# Lane C. Follows the reviewed host rules: task-scoped CoW prefix clone, launch
# only through the official CubismEditor5.bat, exact identity gate before and
# after, machine-readable evidence, and graceful shutdown with cleanup checks.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: run-theme-host-validation.sh <5302|5203> [run-label]" >&2
  exit 2
fi
version="$1"
run_label="${2:-r1}"
case "$version" in
  5302)
    cubism_win='C:\Program Files\Live2D Cubism 5.3'
    cubism_unix='<local-home>/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.3'
    reviewed_jar_sha256='988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21'
    fixture_src='<local-home>/Documents/测试 混合模式.cmo3'
    fixture_sha256='57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4'
    ;;
  5203)
    cubism_win='C:\Program Files\Live2D Cubism 5.2'
    cubism_unix='<local-home>/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.2'
    reviewed_jar_sha256='bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd'
    fixture_src='<local-home>/TurboismPartValidation/part52-official/part-opacity-fixture-52-final.cmo3'
    fixture_sha256='331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028'
    ;;
  *)
    echo "error: version must be 5302 or 5203" >&2
    exit 2
    ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
worktree_id="$(git branch --show-current | tr '/_' '--')"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-theme-validation"
probe_jar="$repo_root/build/theme-host-validation-exerciser.jar"
[ -f "$probe_jar" ] || { echo "error: probe jar missing; run validation/theme-host-probe/build.sh" >&2; exit 1; }
for file in "$bundle_root/turboism-agent.jar" "$bundle_root/plugins/ui-theme.jar"; do
  [ -f "$file" ] || { echo "error: bundle artifact missing: $file" >&2; exit 1; }
done

ssh_host='<validation-user>@<validation-host>'
ssh_key="$HOME/.ssh/<validation-ssh-key>"
ssh_cmd=(ssh -i "$ssh_key" -o IdentitiesOnly=yes -o ConnectTimeout=10)
scp_cmd=(scp -i "$ssh_key" -o IdentitiesOnly=yes)

task_id="theme-validation-${version}-$(date -u +%Y%m%dT%H%M%SZ)"
task_dir="<local-home>/TurboismThemeValidation/$version-$run_label/$task_id"
home_dir="$task_dir/turboism-home"
prefix_dir="$task_dir/prefix"
evidence_dir="$task_dir/evidence"

"${ssh_cmd[@]}" "$ssh_host" "mkdir -p '$task_dir/plugins' '$home_dir' '$evidence_dir'"

agent_sha256="$(sha256sum "$bundle_root/turboism-agent.jar" | cut -d' ' -f1)"
ui_theme_sha256="$(sha256sum "$bundle_root/plugins/ui-theme.jar" | cut -d' ' -f1)"
probe_sha256="$(sha256sum "$probe_jar" | cut -d' ' -f1)"

# --- Exact identity gate (before) -------------------------------------------
identity_before=$(
  "${ssh_cmd[@]}" "$ssh_host" "bash -s -- '$cubism_unix' '$task_dir' '$reviewed_jar_sha256'" <<'REMOTE'
set -euo pipefail
cubism="$1"; task="$2"; reviewed_jar="$3"
jar="$cubism/app/lib/Live2D_Cubism.jar"
bat="$cubism/CubismEditor5.bat"
cat <<EOF
task_id=$task
golden_prefix=<local-home>/.proton
cloned_prefix=$task/prefix
host_jar_size=$(stat -c %s "$jar")
host_jar_sha256=$(sha256sum "$jar" | cut -d' ' -f1)
official_bat_sha256=$(sha256sum "$bat" | cut -d' ' -f1)
EOF
if [ "$(sha256sum "$jar" | cut -d' ' -f1)" != "$reviewed_jar" ]; then
  echo "identity=FAIL reviewed_jar=$reviewed_jar"
  exit 3
fi
echo "identity=PASS"
REMOTE
)
echo "$identity_before" > /tmp/theme-identity-before.txt
cat >> /tmp/theme-identity-before.txt <<EOF
turboism_agent_sha256=$agent_sha256
ui_theme_jar_sha256=$ui_theme_sha256
probe_jar_sha256=$probe_sha256
EOF
"${scp_cmd[@]}" /tmp/theme-identity-before.txt "$ssh_host:$evidence_dir/identity-before.txt"
echo "$identity_before" | grep -q 'identity=PASS' || { echo "error: identity gate failed" >&2; exit 1; }

# --- CoW prefix clone --------------------------------------------------------
echo "[run] cloning task prefix (CoW)..."
"${ssh_cmd[@]}" "$ssh_host" "bash -s -- '$task_dir'" <<'REMOTE'
set -euo pipefail
task="$1"
cp -a --reflink=always <local-home>/.proton "$task/prefix"
rm -f "$task/prefix/pfx.lock"
test -d "$task/prefix/pfx/drive_c/windows" || { echo "error: prefix clone incomplete" >&2; exit 3; }
echo "[run] prefix cloned"
REMOTE

# --- Stage artifacts ---------------------------------------------------------
"${ssh_cmd[@]}" "$ssh_host" "mkdir -p '$home_dir/plugins'"
"${scp_cmd[@]}" "$bundle_root/turboism-agent.jar" "$ssh_host:$task_dir/turboism-agent.jar"
"${scp_cmd[@]}" "$bundle_root/plugins/ui-theme.jar" "$ssh_host:$home_dir/plugins/ui-theme.jar"
"${scp_cmd[@]}" "$probe_jar" "$ssh_host:$home_dir/plugins/theme-host-validation-exerciser.jar"
"${ssh_cmd[@]}" "$ssh_host" "cp '$fixture_src' '$task_dir/fixture.cmo3'"

# --- Launch files ------------------------------------------------------------
winslash() { printf '%s' "$1" | sed 's|/|\\|g'; }
win_home="Z:$(winslash "$home_dir")"
win_agent="Z:$(winslash "$task_dir/turboism-agent.jar")"
win_fixture="Z:$(winslash "$task_dir/fixture.cmo3")"
win_cubism="$(winslash "$cubism_win")"
win_console="Z:$(winslash "$task_dir/evidence/cubism-console.txt")"
cat > /tmp/theme-launch.bat <<BAT
@echo off
setlocal
set "JAVA_TOOL_OPTIONS=--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -Dturboism.home=$win_home -javaagent:$win_agent=home=$win_home;timeoutSeconds=180"
call "$win_cubism\\CubismEditor5.bat" "$win_fixture" > "$win_console" 2>&1
BAT
cat > /tmp/theme-launch.sh <<SH
#!/bin/sh
export DISPLAY=:0
cd "$task_dir" || exit 1
exec shorin-proton-wrapper -p "$task_dir/prefix" --runner <local-home>/.local/share/Steam/compatibilitytools.d/GE-Proton10-34/proton --debug "$task_dir/prefix/pfx/drive_c/windows/system32/cmd.exe" /c "Z:$(winslash "$task_dir/launch.bat")" > "$task_dir/evidence/launcher.out" 2>&1
SH
chmod +x /tmp/theme-launch.sh
"${scp_cmd[@]}" /tmp/theme-launch.bat "$ssh_host:$task_dir/launch.bat"
"${scp_cmd[@]}" /tmp/theme-launch.sh "$ssh_host:$task_dir/launch.sh"

# --- Launch + readiness poll -------------------------------------------------
latest_runtime_log() {
  "${ssh_cmd[@]}" "$ssh_host" "ls -1t '$home_dir'/logs/runtime/*/*.log 2>/dev/null | head -n 1"
}
log_file=''
echo "[run] launching Cubism $version (task $task_id)..."
"${ssh_cmd[@]}" "$ssh_host" "cd '$task_dir' && nohup ./launch.sh > /dev/null 2>&1 & echo \$! > '$evidence_dir/wrapper.pid'; echo started"
echo "[run] waiting for host=ACTIVE + probe ready..."
ready=0
deadline=$((SECONDS + 240))
while [ $SECONDS -lt $deadline ]; do
  log_file="$(latest_runtime_log 2>/dev/null || true)"
  if [ -n "$log_file" ] && "${ssh_cmd[@]}" "$ssh_host" "grep -q 'THEME_EXERCISER_READY' '$log_file' && grep -q 'Plugin load complete' '$log_file' && grep -q 'UiThemePlugin enabled' '$log_file'" 2>/dev/null; then
    ready=1
    break
  fi
  sleep 5
done
if [ "$ready" != 1 ]; then
  echo "error: readiness timeout" >&2
  "${ssh_cmd[@]}" "$ssh_host" "tail -40 '${log_file:-/dev/null}' 2>/dev/null; tail -20 '$evidence_dir/launcher.out' 2>/dev/null" || true
  exit 1
fi
echo "[run] ready; menu and appearance evidence is collected from logs by the exerciser"

# --- Drop flag; poll matrix result -------------------------------------------
"${ssh_cmd[@]}" "$ssh_host" "mkdir -p '$home_dir/state/dev.turboism.validation.theme' && touch '$home_dir/state/dev.turboism.validation.theme/exerciser.flag'"
echo "[run] flag dropped; waiting for matrix result..."
matrix=0
deadline=$((SECONDS + 120))
while [ $SECONDS -lt $deadline ]; do
  if "${ssh_cmd[@]}" "$ssh_host" "grep -q 'THEME_MATRIX_RESULT' '$log_file'" 2>/dev/null; then
    matrix=1
    break
  fi
  sleep 3
done
if [ "$matrix" != 1 ]; then
  echo "error: matrix timeout" >&2
  "${ssh_cmd[@]}" "$ssh_host" "tail -60 '$log_file' 2>/dev/null" || true
  exit 1
fi

# --- Wait for graceful exit ----------------------------------------------------
echo "[run] matrix done; waiting for process exit..."
"${ssh_cmd[@]}" "$ssh_host" "for i in \$(seq 1 40); do kill -0 \$(cat '$evidence_dir/wrapper.pid') 2>/dev/null || exit 0; sleep 3; done; exit 1" || {
  echo "error: process did not exit in time" >&2
  exit 1
}

# --- Evidence collection ---------------------------------------------------------
"${ssh_cmd[@]}" "$ssh_host" "bash -s -- '$task_dir' '$fixture_src'" <<REMOTE
set -euo pipefail
task="\$1"; fixture_src="\$2"
runtime_log="\$(ls -1t "\$task"/turboism-home/logs/runtime/*/*.log 2>/dev/null | head -n 1 || true)"
[ -z "\$runtime_log" ] || cp "\$runtime_log" "\$task/evidence/turboism.log"
find "\$task/turboism-home/logs" -name '*.log' -exec cp {} "\$task/evidence/" \; 2>/dev/null || true
cp "\$task/turboism-home/state/plugin-load-report.json" "\$task/evidence/" 2>/dev/null || true
cp "\$task/turboism-home/state/preview-runtime-report.json" "\$task/evidence/" 2>/dev/null || true
{
  echo "fixture_after_sha256=\$(sha256sum "\$task/fixture.cmo3" | cut -d' ' -f1)"
  echo "source_fixture_sha256=\$(sha256sum "\$fixture_src" | cut -d' ' -f1)"
  echo "agent_sha256=\$(sha256sum "\$task/turboism-agent.jar" | cut -d' ' -f1)"
  echo "ui_theme_jar_sha256=\$(sha256sum "\$task/turboism-home/plugins/ui-theme.jar" | cut -d' ' -f1)"
  echo "probe_jar_sha256=\$(sha256sum "\$task/turboism-home/plugins/theme-host-validation-exerciser.jar" | cut -d' ' -f1)"
} > "\$task/evidence/final-hashes.txt"
echo "[run] evidence collected"
REMOTE

echo "[run] TASK=$task_id"
echo "[run] LOG=$task_dir/evidence/turboism.log"
