#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="$root/scripts/preview/run-cubism-host-validation.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

python3 - "$runner" "$tmp" <<'PY'
from pathlib import Path
import re
import subprocess
import sys

runner = Path(sys.argv[1])
tmp = Path(sys.argv[2])
source = runner.read_text(encoding='utf-8')
marker = "python3 - \"$task_dir\" \"$prefix_dir/pfx\" \"$proc_root\" <<'PY'\n"
start = source.index(marker) + len(marker)
end = source.index("\nPY\n", start)
scanner_source = source[start:end]
scanner = tmp / 'scanner.py'
scanner.write_text(scanner_source, encoding='utf-8')

# The source is quoted for the shell, but Python must still interpret real
# NUL/newline/tab escape sequences at runtime.
for bad in ("split(b'\\\\x00')", "rstrip(b'\\\\n')", "print(f'{proc.name}\\\\t"):
    if bad in scanner_source:
        raise SystemExit(f'double-escaped scanner source remains: {bad}')


def write_proc(root: Path, pid: str, cmdline: bytes, environ: bytes, comm: bytes, start_tick: str, *, omit_environ=False):
    proc = root / pid
    proc.mkdir(parents=True)
    (proc / 'cmdline').write_bytes(cmdline)
    if not omit_environ:
        (proc / 'environ').write_bytes(environ)
    (proc / 'comm').write_bytes(comm)
    stat_fields = b' '.join([b'R'] + [b'0'] * 18 + [start_tick.encode()])
    (proc / 'stat').write_bytes(pid.encode() + b' (fake) ' + stat_fields)

proc_root = tmp / 'proc'
write_proc(
    proc_root,
    '101',
    b'/bin/fake\x00runner\x00',
    b'TURBOISM_HOST_VALIDATION_TASK_DIR=/tmp/fake-task\x00',
    b'fake-runner\n',
    '111',
)
write_proc(
    proc_root,
    '102',
    b'/bin/fake\x00runner\x00',
    b'WINEPREFIX=/tmp/fake-prefix/pfx\x00',
    b'wineserver\n',
    '222',
)
write_proc(
    proc_root,
    '103',
    b'/bin/fake\x00/tmp/fake-task-suffix\x00',
    b'PATH=/bin\x00',
    b'unrelated\n',
    '333',
)
scan = subprocess.run(
    [sys.executable, str(scanner), '/tmp/fake-task', '/tmp/fake-prefix/pfx', str(proc_root)],
    capture_output=True,
    text=True,
)
if scan.returncode != 0:
    raise SystemExit(f'valid fake proc scan failed: {scan.returncode}: {scan.stderr}')
if set(scan.stdout.splitlines()) != {'101\t111\tfake-runner', '102\t222\twineserver'}:
    raise SystemExit(f'wrong NUL/tab scanner output: {scan.stdout!r}')

bad_root = tmp / 'bad-proc'
write_proc(
    bad_root,
    '104',
    b'/bin/fake\x00/tmp/fake-task/runner\x00',
    b'',
    b'bad\n',
    '444',
    omit_environ=True,
)
failed_scan = subprocess.run(
    [sys.executable, str(scanner), '/tmp/fake-task', '/tmp/fake-prefix/pfx', str(bad_root)],
    capture_output=True,
    text=True,
)
if failed_scan.returncode == 0 or 'proc-scan-error' not in failed_scan.stderr:
    raise SystemExit('unreadable proc entry was not fail-closed')

cleanup_match = re.search(
    r"remote_stop_process_tree\(\) \{\n(?P<body>.*?)\n\}\n\nlatest_runtime_log\(\)",
    source,
    re.DOTALL,
)
if cleanup_match is None:
    raise SystemExit('cleanup function not found')
cleanup = cleanup_match.group('body')
for forbidden in (
    'WINEPREFIX="$prefix_dir/pfx" "$wineserver" -k',
    'ps -o pid= --ppid',
    'kill -KILL',
    'kill -TERM',
):
    if forbidden in cleanup:
        raise SystemExit(f'unsafe cleanup path remains: {forbidden}')
for required in (
    'pidfd_send_signal',
    'pidfd_open',
    "late-born or detached task processes cannot be excluded",
    'owned process scan failed',
    'selected task process remained alive after final scan',
    'automaticSignalMethod=pidfd',
):
    if required not in cleanup:
        raise SystemExit(f'missing conservative cleanup guard: {required}')
if '[ "$launched" = 1 ]' not in cleanup or '[ "$background_hook_started" = 1 ]' not in cleanup:
    raise SystemExit('cleanup does not force unknown for launched/background work')
if '|| [ "$launched" = 0 ]; then' in source:
    raise SystemExit('on_exit still treats every unlaunched cleanup as safe')
print('PASS: Cubism cleanup scanner bytes, fail-closed reads, and pidfd boundary')
PY
