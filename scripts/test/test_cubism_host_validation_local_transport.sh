#!/usr/bin/env bash
# Focused native transport contract. No Cubism, Proton, SSH or SCP is started.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/../.." && pwd -P)"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
transport_helper="$repo_root/scripts/preview/host-validation-transport.sh"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/turboism-cubism-local.XXXXXX")"
cleanup() { rm -rf -- "$tmp"; }
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

# Poison network command names. Native transport must not resolve or invoke them.
poison_bin="$tmp/poison bin"
mkdir -p "$poison_bin"
for command in ssh scp; do
  cat > "$poison_bin/$command" <<'SH'
#!/usr/bin/env bash
: "${TURBOISM_POISON_MARKER:?}"
printf '%s\n' "$0" >> "$TURBOISM_POISON_MARKER"
exit 99
SH
  chmod +x "$poison_bin/$command"
done
export PATH="$poison_bin:$PATH"
export TURBOISM_POISON_MARKER="$tmp/network-used"

source "$transport_helper"
transport=local
ssh_host=''
ssh_key=''
ssh_cmd=()
scp_cmd=()
host_validation_transport_init
[ "${#ssh_cmd[@]}" -eq 0 ] || fail 'local transport constructed an SSH command'
[ "${#scp_cmd[@]}" -eq 0 ] || fail 'local transport constructed an SCP command'

source_dir="$tmp/source dir"
task_dir="$tmp/task dir"
evidence_dir="$task_dir/evidence"
evidence_copy="$tmp/evidence copy"
mkdir -p "$source_dir/nested" "$task_dir/home" "$evidence_dir" "$evidence_copy"
printf 'file payload\n' > "$source_dir/file with spaces.txt"
printf 'nested payload\n' > "$source_dir/nested/value.txt"
printf 'evidence payload\n' > "$evidence_dir/result.txt"

# Local Bash preserves heredoc execution, positional arguments, spaces and exit codes.
transport_bash "$task_dir" 'value with spaces' <<'SCRIPT' > "$tmp/bash.out"
set -euo pipefail
printf 'task=%s\nvalue=%s\n' "$1" "$2"
SCRIPT
grep -Fxq "task=$task_dir" "$tmp/bash.out" || fail 'local Bash did not preserve task argument'
grep -Fxq 'value=value with spaces' "$tmp/bash.out" || fail 'local Bash did not preserve spaces'

transport_command "printf '%s\\n' 'command payload' > '$task_dir/command output.txt'"
grep -Fxq 'command payload' "$task_dir/command output.txt" || fail 'local command did not execute'
if transport_command 'exit 23'; then
  fail 'local command failure was swallowed'
fi

# File, directory-content and evidence collection paths all use local copies.
transport_copy_to "$source_dir/file with spaces.txt" "$task_dir/file copy.txt"
cmp -s "$source_dir/file with spaces.txt" "$task_dir/file copy.txt" \
  || fail 'local file copy changed content'
transport_copy_dir_contents_to "$source_dir" "$task_dir/home"
cmp -s "$source_dir/nested/value.txt" "$task_dir/home/nested/value.txt" \
  || fail 'local directory copy changed content'
transport_copy_from --recursive "$evidence_dir/." "$evidence_copy/"
grep -Fxq 'evidence payload' "$evidence_copy/result.txt" \
  || fail 'local evidence collection did not copy contents'

# Alias and destructive destination checks are explicit failures.
if (transport_copy_to "$task_dir/file copy.txt" "$task_dir/file copy.txt") >"$tmp/alias.out" 2>&1; then
  fail 'source-equals-destination copy was accepted'
fi
grep -Fq 'source-equals-destination' "$tmp/alias.out" \
  || fail 'source-equals-destination rejection was not explained'
if (transport_copy_dir_contents_to "$source_dir" "$source_dir/nested/target") >"$tmp/nested.out" 2>&1; then
  fail 'destination inside source directory was accepted'
fi
grep -Fq 'destination inside copy source' "$tmp/nested.out" \
  || fail 'nested destination rejection was not explained'

# A source nested under its destination can overwrite the source while cp exits 0.
overlap_tree="$tmp/overlap tree"
mkdir -p "$overlap_tree/source/source"
printf 'original\n' > "$overlap_tree/source/value"
printf 'overwritten\n' > "$overlap_tree/source/source/value"
if (transport_copy_dir_contents_to "$overlap_tree/source" "$overlap_tree") >"$tmp/ancestor.out" 2>&1; then
  fail 'source-inside-destination copy was accepted'
fi
grep -Fq 'copy source inside destination' "$tmp/ancestor.out" \
  || fail 'source-inside-destination rejection was not explained'
grep -Fxq 'original' "$overlap_tree/source/value" \
  || fail 'rejected overlapping copy mutated the original source'
grep -Fxq 'overwritten' "$overlap_tree/source/source/value" \
  || fail 'rejected overlapping copy mutated the nested source'

# Canonicalization also rejects an ancestor overlap reached through a symlink.
symlink_tree="$tmp/symlink tree"
mkdir -p "$symlink_tree/source"
printf 'original\n' > "$symlink_tree/source/value"
ln -s "$symlink_tree" "$symlink_tree/alias"
if (transport_copy_dir_contents_to "$symlink_tree/source" "$symlink_tree/alias") >"$tmp/symlink.out" 2>&1; then
  fail 'symlink-resolved overlapping copy was accepted'
fi
grep -Fq 'copy source inside destination' "$tmp/symlink.out" \
  || fail 'symlink overlap rejection was not explained'
grep -Fxq 'original' "$symlink_tree/source/value" \
  || fail 'symlink overlap rejection mutated the source'

# An existing destination child symlink must not redirect writes into the source.
child_link_tree="$tmp/destination child link tree"
mkdir -p "$child_link_tree/source" "$child_link_tree/destination"
printf 'replacement\n' > "$child_link_tree/source/value"
printf 'original\n' > "$child_link_tree/source/other"
ln -s "$child_link_tree/source/other" "$child_link_tree/destination/value"
if (transport_copy_dir_contents_to "$child_link_tree/source" "$child_link_tree/destination") >"$tmp/child-link.out" 2>&1; then
  fail 'destination child symlink copy was accepted'
fi
grep -Fq 'symlink or hardlinked destination entry' "$tmp/child-link.out" \
  || fail 'destination child symlink rejection was not explained'
grep -Fxq 'original' "$child_link_tree/source/other" \
  || fail 'destination child symlink rejection mutated the source'

# Existing destination hardlinks are rejected for the same source-protection reason.
hardlink_tree="$tmp/destination hardlink tree"
mkdir -p "$hardlink_tree/source" "$hardlink_tree/destination"
printf 'replacement\n' > "$hardlink_tree/source/value"
printf 'original\n' > "$hardlink_tree/source/other"
ln "$hardlink_tree/source/other" "$hardlink_tree/destination/value"
if (transport_copy_dir_contents_to "$hardlink_tree/source" "$hardlink_tree/destination") >"$tmp/hardlink.out" 2>&1; then
  fail 'destination hardlink copy was accepted'
fi
grep -Fq 'symlink or hardlinked destination entry' "$tmp/hardlink.out" \
  || fail 'destination hardlink rejection was not explained'
grep -Fxq 'original' "$hardlink_tree/source/other" \
  || fail 'destination hardlink rejection mutated the source'

# Single-file staging must reject a destination symlink and hardlink too.
single_tree="$tmp/single-file tree"
mkdir -p "$single_tree/source" "$single_tree/destination"
printf 'replacement\n' > "$single_tree/source/payload"
printf 'original\n' > "$single_tree/source/other"
ln -s "$single_tree/source/other" "$single_tree/destination/value"
if (transport_copy_to "$single_tree/source/payload" "$single_tree/destination/value") >"$tmp/single-link.out" 2>&1; then
  fail 'single-file destination symlink copy was accepted'
fi
grep -Fq 'symlink destination' "$tmp/single-link.out" \
  || fail 'single-file destination symlink rejection was not explained'
grep -Fxq 'original' "$single_tree/source/other" \
  || fail 'single-file destination symlink rejection mutated the source'
rm -f "$single_tree/destination/value"
ln "$single_tree/source/other" "$single_tree/destination/value"
if (transport_copy_to "$single_tree/source/payload" "$single_tree/destination/value") >"$tmp/single-hardlink.out" 2>&1; then
  fail 'single-file destination hardlink copy was accepted'
fi
grep -Fq 'symlink or hardlinked destination entry' "$tmp/single-hardlink.out" \
  || fail 'single-file destination hardlink rejection was not explained'
grep -Fxq 'original' "$single_tree/source/other" \
  || fail 'single-file destination hardlink rejection mutated the source'

# A symlinked destination ancestor is unsafe even when the final destination is ordinary.
ancestor_tree="$tmp/destination ancestor tree"
mkdir -p "$ancestor_tree/source" "$ancestor_tree/original" "$ancestor_tree/destination"
printf 'replacement\n' > "$ancestor_tree/source/payload"
printf 'original\n' > "$ancestor_tree/original/other"
ln -s "$ancestor_tree/original" "$ancestor_tree/destination/link"
if (transport_copy_to "$ancestor_tree/source/payload" "$ancestor_tree/destination/link/other") >"$tmp/ancestor-link.out" 2>&1; then
  fail 'single-file destination ancestor symlink copy was accepted'
fi
grep -Fq 'symlink destination ancestor' "$tmp/ancestor-link.out" \
  || fail 'single-file destination ancestor rejection was not explained'
grep -Fxq 'original' "$ancestor_tree/original/other" \
  || fail 'single-file destination ancestor rejection mutated the source'

# Cleanup is caller-scoped and does not touch a sibling sentinel.
owned_prefix="$task_dir/prefix"
outside="$tmp/outside sentinel"
mkdir -p "$owned_prefix"
printf 'keep me\n' > "$outside"
transport_remove_tree "$owned_prefix"
[ ! -e "$owned_prefix" ] || fail 'owned cleanup path survived'
[ -f "$outside" ] || fail 'owned cleanup touched an outside sentinel'

# Runner dry-run uses native transport from the environment without SSH settings.
bundle="$tmp/bundle"
mkdir -p "$bundle"
printf 'agent\n' > "$bundle/agent.jar"
printf 'plugin\n' > "$bundle/plugin.jar"
printf 'fixture\n' > "$tmp/fixture.cmo3"
runner_output="$tmp/runner.out"
TURBOISM_ENV_FILE=/dev/null \
TURBOISM_HOST_VALIDATION_TRANSPORT=local \
  bash "$runner" --name local-transport --version 5302 --bundle-root "$bundle" \
    --agent "$bundle/agent.jar" --plugin "$bundle/plugin.jar" \
    --fixture-local "$tmp/fixture.cmo3" --result-marker never \
    --golden-prefix "$tmp/golden prefix" --remote-root "$tmp/task root" \
    --proton-runner "$tmp/proton runner" --dry-run >"$runner_output"
grep -Fxq 'transport=local' "$runner_output" || fail 'runner did not report local transport'
[ ! -e "$tmp/network-used" ] || fail 'runner invoked poisoned SSH/SCP command'

# Explicit local mode rejects conflicting legacy SSH values and destructive aliases.
if TURBOISM_ENV_FILE=/dev/null bash "$runner" --transport local --ssh-host user@example.invalid \
  --name local-conflict --version 5302 --bundle-root "$bundle" --agent "$bundle/agent.jar" \
  --plugin "$bundle/plugin.jar" --fixture-local "$tmp/fixture.cmo3" --result-marker never \
  --golden-prefix "$tmp/golden" --remote-root "$tmp/tasks" \
  --proton-runner "$tmp/proton" --dry-run >"$tmp/conflict.out" 2>&1; then
  fail 'local mode accepted conflicting SSH configuration'
fi
grep -Fq 'must not be combined' "$tmp/conflict.out" \
  || fail 'local SSH conflict was not explained'

if TURBOISM_ENV_FILE=/dev/null bash "$runner" --transport local --name local-alias \
  --version 5302 --bundle-root "$bundle" --agent "$bundle/agent.jar" \
  --plugin "$bundle/plugin.jar" --fixture-local "$tmp/fixture.cmo3" --result-marker never \
  --golden-prefix "$tmp/golden" --remote-root "$tmp/golden" \
  --proton-runner "$tmp/proton" --dry-run >"$tmp/alias-runner.out" 2>&1; then
  fail 'runner accepted a task root inside the golden prefix'
fi
grep -Fq 'destination inside copy source' "$tmp/alias-runner.out" \
  || fail 'runner alias rejection was not explained'

[ ! -e "$tmp/network-used" ] || fail 'native transport reached poisoned SSH/SCP command'

# The Runner's guarded directory preparation must reject before mkdir follows a copied symlink.
stage_tree="$tmp/runner staging order"
mkdir -p "$stage_tree/source" "$stage_tree/home" "$stage_tree/original"
printf 'payload\n' > "$stage_tree/source/value"
ln -s "$stage_tree/original" "$stage_tree/source/link"
transport_copy_dir_contents_to "$stage_tree/source" "$stage_tree/home"
[ -L "$stage_tree/home/link" ] || fail 'staging setup did not copy the source symlink'
if (transport_prepare_directory "$stage_tree/home/link/new-dir") >"$tmp/stage-order.out" 2>&1; then
  fail 'guarded staging mkdir followed a destination symlink'
fi
grep -Fq 'symlink destination ancestor' "$tmp/stage-order.out" \
  || fail 'guarded staging rejection was not explained'
[ ! -e "$stage_tree/original/new-dir" ] \
  || fail 'guarded staging mkdir created a directory through the original symlink'
[ ! -e "$stage_tree/home/link/new-dir" ] \
  || fail 'guarded staging mkdir created a rejected destination'
mkdir -p "$stage_tree/positive-source"
printf 'positive\n' > "$stage_tree/positive-source/value"
transport_prepare_directory "$stage_tree/positive-destination"
transport_copy_dir_contents_to "$stage_tree/positive-source" "$stage_tree/positive-destination"
grep -Fxq 'positive' "$stage_tree/positive-destination/value" \
  || fail 'guarded staging positive path did not copy content'

# Extract the actual Runner cleanup functions and inject a controlled hook-copy failure.
python3 - "$runner" "$tmp/cleanup-harness.sh" <<'PY'
from pathlib import Path
import re
import sys

source = Path(sys.argv[1]).read_text(encoding='utf-8')
run_match = re.search(r'(run_remote_hook\(\) \{.*?\n\})\n\non_exit\(\)', source, re.DOTALL)
collect_match = re.search(r'(collect_evidence\(\) \{.*?\n\})\n\ncleanup_prefix\(\)', source, re.DOTALL)
exit_match = re.search(r'(on_exit\(\) \{.*?\n\})\ntrap on_exit EXIT', source, re.DOTALL)
if run_match is None or collect_match is None or exit_match is None:
    raise SystemExit('cleanup functions could not be extracted')

prefix = r'''#!/usr/bin/env bash
set -euo pipefail
tmp="$1"
helper="$2"
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
source "$helper"
transport=local
ssh_host=''
ssh_key=''
ssh_cmd=()
scp_cmd=()
host_validation_transport_init
task_dir="$tmp/cleanup-task"
home_dir="$task_dir/turboism-home"
evidence_dir="$task_dir/evidence"
prefix_dir="$task_dir/prefix"
fixture_path="$task_dir/fixture.cmo3"
golden_cubism="$tmp/golden"
cloned_cubism="$tmp/cloned"
fixture_remote=''
result_file=''
local_evidence_dir="$tmp/cleanup-evidence"
local_tmp="$tmp/cleanup-local-tmp"
task_id=cleanup-test
version=5302
result_timeout=1
proton_wrapper=wrapper
proton_runner=runner
display=:0
remote_pre_cleanup="$tmp/pre-cleanup.sh"
pre_cleanup_hook_done=0
launched=1
success=0
wrapper_cleanup_done=0
evidence_collected=0
order="$tmp/cleanup-order"
mkdir -p "$task_dir" "$local_tmp" "$tmp/original-evidence"
ln -s "$tmp/original-evidence" "$local_evidence_dir"
printf 'hook\n' > "$remote_pre_cleanup"
printf 'original\n' > "$tmp/original"
ln -s "$tmp/original" "$task_dir/pre-cleanup.sh"
remote_args_bash() { return 0; }
transport_bash() { return 0; }
remote_stop_process_tree() { printf 'stopped\n' >> "$order"; return 0; }
cleanup_prefix() { printf 'prefix-cleaned\n' >> "$order"; return 0; }
'''
suffix = r'''
trap on_exit EXIT
exit 7
'''
Path(sys.argv[2]).write_text(prefix + collect_match.group(1) + '\n' + run_match.group(1) + '\n' + exit_match.group(1) + suffix, encoding='utf-8')
PY
chmod +x "$tmp/cleanup-harness.sh"
if "$tmp/cleanup-harness.sh" "$tmp" "$transport_helper" >"$tmp/cleanup.out" 2>&1; then
  fail 'cleanup harness converted a hook-copy failure into success'
fi
grep -Fxq 'stopped' "$tmp/cleanup-order" \
  || fail 'cleanup did not reach the owned process-stop step'
grep -Fxq 'original' "$tmp/original" \
  || fail 'cleanup hook-copy rejection mutated the symlink target'
[ -z "$(find -P "$tmp/original-evidence" -mindepth 1 -print -quit)" ] \
  || fail 'evidence preparation touched the original directory through a symlink'
grep -Fq 'REMOTE CLEANUP FAILED' "$tmp/cleanup.out" \
  || fail 'evidence or hook cleanup failure did not produce an explicit failure outcome'
[ ! -e "$tmp/cleanup-local-tmp" ] \
  || fail 'cleanup did not remove its owned local temporary directory'

# Ordinary copy return failures must short-circuit before the hook execution command.
python3 - "$runner" "$tmp/hook-status-harness.sh" <<'PY'
from pathlib import Path
import re
import sys

source = Path(sys.argv[1]).read_text(encoding='utf-8')
match = re.search(r'(run_remote_hook\(\) \{.*?\n\})\n\non_exit\(\)', source, re.DOTALL)
if match is None:
    raise SystemExit('run_remote_hook could not be extracted')

prefix = r'''#!/usr/bin/env bash
set -euo pipefail
tmp="$1"
helper="$2"
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
source "$helper"
transport=local
ssh_host=''
ssh_key=''
ssh_cmd=()
scp_cmd=()
host_validation_transport_init
task_dir="$tmp/hook-status-task"
home_dir="$task_dir/turboism-home"
evidence_dir="$task_dir/evidence"
prefix_dir="$task_dir/prefix"
fixture_path="$task_dir/fixture.cmo3"
task_id=hook-status-test
version=5302
result_timeout=1
proton_wrapper=wrapper
proton_runner=runner
display=:0
remote_pre_cleanup="$tmp/hook.sh"
pre_cleanup_hook_done=0
copy_marker="$tmp/hook-copy"
execution_marker="$tmp/hook-execution"
mkdir -p "$task_dir"
printf 'hook\n' > "$remote_pre_cleanup"
transport_copy_to() { printf 'copy\n' >> "$copy_marker"; return 23; }
remote_args_bash() { printf 'executed\n' >> "$execution_marker"; return 0; }
'''
suffix = r'''
set +e
run_remote_hook "$remote_pre_cleanup"
status=$?
set -e
[ "$status" -ne 0 ] || fail 'ordinary hook copy failure returned success'
grep -Fxq 'copy' "$copy_marker" || fail 'ordinary hook copy stub was not called'
[ ! -e "$execution_marker" ] || fail 'hook execution continued after ordinary copy failure'
rm -f "$copy_marker"
transport_copy_to() { printf 'copy-success\n' >> "$copy_marker"; return 0; }
remote_args_bash() { printf 'executed\n' >> "$execution_marker"; return 0; }
run_remote_hook "$remote_pre_cleanup" || fail 'positive hook path failed after ordinary copy control'
grep -Fxq 'executed' "$execution_marker" || fail 'positive hook path did not execute the hook command'
'''
Path(sys.argv[2]).write_text(prefix + match.group(1) + suffix, encoding='utf-8')
PY
chmod +x "$tmp/hook-status-harness.sh"
"$tmp/hook-status-harness.sh" "$tmp" "$transport_helper" >"$tmp/hook-status.out" 2>&1 || {
  cat "$tmp/hook-status.out" >&2
  fail 'ordinary hook-copy status harness failed'
}

# Exercise the actual EXIT trap so cleanup failures change the child process status.
python3 - "$runner" "$tmp/exit-status-harness.sh" <<'PY'
from pathlib import Path
import re
import sys

source = Path(sys.argv[1]).read_text(encoding='utf-8')
collect_match = re.search(r'(collect_evidence\(\) \{.*?\n\})\n\ncleanup_prefix\(\)', source, re.DOTALL)
cleanup_match = re.search(r'(cleanup_prefix\(\) \{.*?\n\})\n\nrun_remote_hook\(\)', source, re.DOTALL)
exit_match = re.search(r'(on_exit\(\) \{.*?\n\})\ntrap on_exit EXIT', source, re.DOTALL)
if collect_match is None or cleanup_match is None or exit_match is None:
    raise SystemExit('EXIT cleanup functions could not be extracted')

prefix = r'''#!/usr/bin/env bash
set -euo pipefail
tmp="$1"
helper="$2"
mode="$3"
root="$tmp/exit-$mode"
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
source "$helper"
transport=local
ssh_host=''
ssh_key=''
ssh_cmd=()
scp_cmd=()
host_validation_transport_init
task_dir="$root/task"
home_dir="$task_dir/turboism-home"
evidence_dir="$task_dir/evidence"
prefix_dir="$task_dir/prefix"
fixture_path="$task_dir/fixture.cmo3"
golden_cubism="$tmp/golden"
cloned_cubism="$tmp/cloned"
fixture_remote=''
result_file=''
local_evidence_dir="$root/local-evidence"
local_tmp="$root/local-tmp"
task_id=exit-status-test
version=5302
result_timeout=1
proton_wrapper=wrapper
proton_runner=runner
display=:0
remote_pre_cleanup=''
pre_cleanup_hook_done=0
wrapper_cleanup_done=0
evidence_collected=0
order="$root/order"
mkdir -p "$task_dir" "$local_tmp" "$prefix_dir" "$root/original-evidence"
printf 'keep\n' > "$prefix_dir/keep"
transport_bash() { return 0; }
transport_copy_from() { return 0; }
remote_stop_process_tree() { printf 'stopped\n' >> "$order"; return 0; }
keep_prefix=0
transport_remove_tree() { return 0; }
case "$mode" in
  success)
    launched=0
    success=1
    mkdir -p "$local_evidence_dir"
    transport_remove_tree() { printf 'removed\n' >> "$order"; rm -rf "$prefix_dir"; return 0; }
    original_status=0
    ;;
  evidence-failure)
    launched=0
    success=1
    ln -s "$root/original-evidence" "$local_evidence_dir"
    original_status=0
    ;;
  prefix-failure)
    launched=0
    success=1
    mkdir -p "$local_evidence_dir"
    transport_remove_tree() { return 23; }
    original_status=0
    ;;
  keep-prefix)
    launched=0
    success=1
    keep_prefix=1
    mkdir -p "$local_evidence_dir"
    transport_remove_tree() { printf 'removed\n' >> "$order"; return 23; }
    original_status=0
    ;;
  owned-stop)
    launched=1
    success=0
    mkdir -p "$local_evidence_dir"
    original_status=7
    ;;
  *)
    fail "unknown mode: $mode"
    ;;
esac
'''
suffix = r'''
trap on_exit EXIT
exit "$original_status"
'''
Path(sys.argv[2]).write_text(
    prefix + collect_match.group(1) + '\n' + cleanup_match.group(1) + '\n' + exit_match.group(1) + suffix,
    encoding='utf-8',
)
PY
chmod +x "$tmp/exit-status-harness.sh"
"$tmp/exit-status-harness.sh" "$tmp" "$transport_helper" success >"$tmp/exit-success.out" 2>&1 \
  || { cat "$tmp/exit-success.out" >&2; fail 'successful EXIT-trap lifecycle returned nonzero'; }
[ ! -e "$tmp/exit-success/task/prefix" ] || fail 'successful EXIT-trap lifecycle retained its cleaned prefix'
if "$tmp/exit-status-harness.sh" "$tmp" "$transport_helper" evidence-failure >"$tmp/exit-evidence.out" 2>&1; then
  fail 'evidence failure through EXIT trap returned success'
fi
grep -Fq 'REMOTE CLEANUP FAILED' "$tmp/exit-evidence.out" \
  || fail 'EXIT-trap evidence failure did not report cleanup failure'
[ -d "$tmp/exit-evidence-failure/task/prefix" ] \
  || fail 'failed EXIT-trap lifecycle removed its diagnostic prefix'
if "$tmp/exit-status-harness.sh" "$tmp" "$transport_helper" prefix-failure >"$tmp/exit-prefix.out" 2>&1; then
  fail 'prefix removal failure through EXIT trap returned success'
fi
grep -Fq 'REMOTE CLEANUP FAILED' "$tmp/exit-prefix.out" \
  || fail 'prefix removal failure did not report cleanup failure'
[ -d "$tmp/exit-prefix-failure/task/prefix" ] \
  || fail 'prefix removal failure discarded its diagnostic prefix'
"$tmp/exit-status-harness.sh" "$tmp" "$transport_helper" keep-prefix >"$tmp/exit-keep.out" 2>&1 \
  || { cat "$tmp/exit-keep.out" >&2; fail 'explicit keep-prefix lifecycle returned nonzero'; }
[ -d "$tmp/exit-keep-prefix/task/prefix" ] \
  || fail 'explicit keep-prefix lifecycle removed the prefix'
[ ! -e "$tmp/exit-keep-prefix/order" ] \
  || fail 'explicit keep-prefix lifecycle attempted prefix removal'
if "$tmp/exit-status-harness.sh" "$tmp" "$transport_helper" owned-stop >"$tmp/exit-owned.out" 2>&1; then
  fail 'original process failure through EXIT trap returned success'
fi
grep -Fxq 'stopped' "$tmp/exit-owned-stop/order" \
  || fail 'original process failure did not reach owned-stop cleanup'
grep -Fq 'FAILED' "$tmp/exit-owned.out" \
  || fail 'original process failure did not report failure'
[ -d "$tmp/exit-owned-stop/task/prefix" ] \
  || fail 'original process failure removed its diagnostic prefix'
echo 'PASS: Cubism host-validation local transport'
