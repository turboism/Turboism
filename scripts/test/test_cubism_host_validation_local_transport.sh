#!/usr/bin/env bash
# Offline local-transport contract; never launches Cubism or contacts SSH.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/preview/host-validation-transport.sh"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

[ "$(host_validation_local_shell local 'printf "space value"')" = 'space value' ] || fail shell-output
[ "$(printf 'stdin payload\n' | host_validation_local_shell local 'cat')" = 'stdin payload' ] || fail stdin
if host_validation_local_shell local 'exit 37'; then fail exit-code; else [ "$?" -eq 37 ] || fail exit-code; fi
if host_validation_local_shell remote 'exit 0' 2>/dev/null; then fail endpoint; fi
host_validation_local_shell local 'bash -s -- "argument with spaces"' > "$tmp/args" <<'SCRIPT'
printf '%s' "$1"
SCRIPT
[ "$(cat "$tmp/args")" = 'argument with spaces' ] || fail heredoc

mkdir -p "$tmp/staged tree" "$tmp/source tree" "$tmp/download"
printf 'contents\n' > "$tmp/source tree/file with spaces"
host_validation_local_copy "$tmp/source tree/file with spaces" "local:$tmp/staged tree/file"
cmp "$tmp/source tree/file with spaces" "$tmp/staged tree/file"
host_validation_local_copy -r "local:$tmp/staged tree/." "$tmp/download/"
cmp "$tmp/staged tree/file" "$tmp/download/file"
host_validation_local_copy -r "$tmp/source tree/." "local:$tmp/staged tree/"
cmp "$tmp/source tree/file with spaces" "$tmp/staged tree/file with spaces"
if host_validation_local_copy "$tmp/missing" "local:$tmp/staged tree/missing" 2>/dev/null; then fail missing-source; fi
if host_validation_local_copy "other:$tmp/source tree/file with spaces" "$tmp/download/x" 2>/dev/null; then fail foreign-endpoint; fi
if host_validation_local_copy "local:relative" "$tmp/download/x" 2>/dev/null; then fail relative-endpoint; fi
if host_validation_local_copy "$tmp/args" "$tmp/download/x" 2>/dev/null; then fail no-endpoint; fi
if host_validation_local_copy "local:$tmp/args" "local:$tmp/download/x" 2>/dev/null; then fail two-endpoints; fi

mkdir -p "$tmp/proc/100" "$tmp/proc/101"
printf '/usr/bin/java\0-classpath\0gradle.jar\0GradleMain\0' > "$tmp/proc/100/cmdline"
printf '/usr/bin/bash\0-c\0echo com.live2d.cubism.CECubismEditorApp\0' > "$tmp/proc/101/cmdline"
host_validation_local_idle "$tmp/proc" || fail unrelated-java
mkdir -p "$tmp/proc/102"
printf 'app/jre/bin/java.exe\0-classpath\0Live2D_Cubism.jar\0com.live2d.cubism.CECubismEditorApp\0' > "$tmp/proc/102/cmdline"
if host_validation_local_idle "$tmp/proc" > "$tmp/idle.out" 2>&1; then fail foreign-cubism; else
  [ "$?" -eq 4 ] || fail idle-exit-code
fi
grep -Fq '102' "$tmp/idle.out" || fail idle-diagnostic
[ -f "$tmp/proc/102/cmdline" ] || fail idle-must-not-delete

printf '' > "$tmp/empty.env"
printf 'agent' > "$tmp/agent.jar"
printf 'plugin' > "$tmp/probe.jar"
printf 'fixture' > "$tmp/fixture.cmo3"
base=(env -u TURBOISM_HOST_VALIDATION_SSH_HOST -u TURBOISM_HOST_VALIDATION_SSH_KEY TURBOISM_ENV_FILE="$tmp/empty.env"
  bash "$repo_root/scripts/preview/run-cubism-host-validation.sh"
  --name local-contract --version 5203 --bundle-root "$tmp" --agent "$tmp/agent.jar"
  --plugin "$tmp/probe.jar" --fixture-local "$tmp/fixture.cmo3" --result-file state/result.txt
  --golden-prefix "$tmp/golden" --remote-root "$tmp/host-tasks" --proton-runner "$tmp/proton"
  --execution-mode local --dry-run)
"${base[@]}" > "$tmp/dry-run"
grep -Fxq 'executionMode=local' "$tmp/dry-run" || fail local-mode
[ ! -e "$tmp/host-tasks" ] && [ ! -e "$tmp/golden" ] || fail dry-run-side-effects
if "${base[@]}" --ssh-host example.invalid > "$tmp/conflict" 2>&1; then fail conflicting-ssh; fi
grep -Fq 'local execution cannot be combined with SSH options' "$tmp/conflict" || fail conflict-diagnostic
if "${base[@]}" --execution-mode unexpected > "$tmp/invalid" 2>&1; then fail invalid-mode; fi
grep -Fq 'must be local or ssh' "$tmp/invalid" || fail invalid-diagnostic
printf 'PASS: local host transport (offline shell/copy/dry-run contracts)\n'
