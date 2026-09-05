#!/usr/bin/env bash
# Offline transport checks. No Cubism launch or readiness/performance claim.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$root/scripts/preview/host-validation-local-transport.sh"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/source dir" "$tmp/staged dir" "$tmp/evidence dir" "$tmp/bin"
printf 'payload\n' > "$tmp/source dir/input file"
host_validation_local_copy "$tmp/source dir/input file" "local:$tmp/staged dir/output file"
cmp "$tmp/source dir/input file" "$tmp/staged dir/output file"
host_validation_local_copy -r "local:$tmp/staged dir/." "$tmp/evidence dir/"
cmp "$tmp/staged dir/output file" "$tmp/evidence dir/output file"
# Copy paths are data, never commands.
name='$(touch SHOULD_NOT_EXIST)'
printf 'literal\n' > "$tmp/source dir/$name"
host_validation_local_copy "$tmp/source dir/$name" "local:$tmp/staged dir/$name"
cmp "$tmp/source dir/$name" "$tmp/staged dir/$name"
if host_validation_local_copy "remote:$tmp/source dir/input file" "$tmp/staged dir/no"; then exit 1; fi
if host_validation_local_shell remote 'exit 0'; then exit 1; fi
if host_validation_local_shell local 'exit 37'; then exit 1; else [[ $? == 37 ]]; fi
actual="$(printf 'stdin payload\n' | host_validation_local_shell local 'cat')"
[[ "$actual" == 'stdin payload' ]]
encoded="$(printf '%s\n' 'path with spaces' 'literal $value' | base64 -w 0)"
actual="$(host_validation_local_shell local "TURBOISM_ARGS_B64=$encoded bash -s" <<'SH'
mapfile -t args < <(printf '%s' "$TURBOISM_ARGS_B64" | base64 -d)
printf '%s|%s' "${args[0]}" "${args[1]}"
SH
)"
[[ "$actual" == 'path with spaces|literal $value' ]]
# Runner local mode must not require a key or contact SSH, even outside dry-run.
for tool in ssh scp; do
  printf '#!/bin/sh\ntouch "%s"\nexit 99\n' "$tmp/network-used" > "$tmp/bin/$tool"
  chmod +x "$tmp/bin/$tool"
done
export PATH="$tmp/bin:$PATH" TURBOISM_ENV_FILE="$tmp/no-env"
export TURBOISM_HOST_VALIDATION_SSH_HOST='' TURBOISM_HOST_VALIDATION_SSH_KEY=''
printf agent > "$tmp/agent.jar"
printf fixture > "$tmp/fixture.cmo3"
base=(bash "$root/scripts/preview/run-cubism-host-validation.sh" --name local-contract --version 5302
  --transport local --bundle-root "$tmp" --agent "$tmp/agent.jar" --fixture-local "$tmp/fixture.cmo3"
  --aux-agent "$tmp/agent.jar:probe.jar"
  --result-file state/result.properties --golden-prefix "$tmp/missing-golden"
  --remote-root "$tmp/tasks" --proton-runner "$tmp/no-proton")
"${base[@]}" --dry-run > "$tmp/dry.out"
grep -Fxq 'transport=local' "$tmp/dry.out"
[[ ! -e "$tmp/tasks" ]]
if "${base[@]}" > "$tmp/actual.out" 2>&1; then exit 1; else [[ $? == 3 ]]; fi
grep -Fq 'preflight exact host identity (transport=local)' "$tmp/actual.out"
[[ ! -e "$tmp/network-used" ]]
if "${base[@]}" --transport unknown --dry-run > "$tmp/invalid.out" 2>&1; then exit 1; fi
grep -Fq 'unsupported transport:' "$tmp/invalid.out"
echo 'PASS: explicit local host transport (offline)'
