#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="$root/scripts/preview/run-cubism-host-validation.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

python3 - "$runner" "$tmp" <<'PY'
from pathlib import Path
import subprocess
import sys

runner = Path(sys.argv[1])
tmp = Path(sys.argv[2])
source = runner.read_text(encoding='utf-8')
marker = 'python3 - "$phase" "$task_dir" "$prefix_dir" "$evidence_dir" "$proc_root" <<\'PY\'; then\n'
start = source.index(marker) + len(marker)
end = source.index("\nPY\n", start)
collector = tmp / 'collector.py'
collector.write_text(source[start:end], encoding='utf-8')


def write_proc(root: Path, pid: str, comm: bytes, environ: bytes, cmdline: bytes,
               start_tick: str, threads=None):
    proc = root / pid
    (proc / 'task').mkdir(parents=True)
    (proc / 'comm').write_bytes(comm)
    (proc / 'environ').write_bytes(environ)
    (proc / 'cmdline').write_bytes(cmdline)
    stat_fields = b' '.join([b'R'] + [b'0'] * 18 + [start_tick.encode()])
    (proc / 'stat').write_bytes(pid.encode() + b' (fake) ' + stat_fields)
    for tid, name in (threads or {pid: comm}).items():
        (proc / 'task' / tid).mkdir(exist_ok=True)
        (proc / 'task' / tid / 'comm').write_bytes(name)


def run(root: Path, out: Path, task_dir: str, prefix: str):
    out.mkdir(parents=True, exist_ok=True)
    return subprocess.run(
        [sys.executable, str(collector), 'ready', task_dir, prefix, str(out), str(root)],
        capture_output=True, text=True)


def props(path: Path):
    data = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if '=' in line:
            key, _, value = line.partition('=')
            data[key] = value
    return data


# --- Case 1: javaw.exe bound by the task marker (the field-failure shape) ---
proc_root = tmp / 'proc-javaw'
task_dir = tmp / 'task-1'
prefix = tmp / 'prefix-1'
write_proc(proc_root, '201', b'javaw.exe\n',
           f'TURBOISM_HOST_VALIDATION_TASK_DIR={task_dir}\x00WINEPREFIX={prefix}/pfx\x00mesa_glthread=true\x00'.encode(),
           b'C:\\jre\\bin\\javaw.exe\x00-Dx=1\x00',
           '1001', threads={'201': b'javaw.exe', '301': b'gl0', '302': b'gdrv0'})
write_proc(proc_root, '202', b'wineserver\n',
           f'WINEPREFIX={prefix}/pfx\x00'.encode(), b'wineserver\x00', '1002')
result = run(proc_root, tmp / 'out-1', str(task_dir), str(prefix))
assert result.returncode == 0, result.stderr
data = props(tmp / 'out-1' / 'java-process.ready.properties')
assert data['javaProcessCount'] == '1', data
assert data['glThreadCount'] == '1', data
assert data['taskProcessCount'] == '2', data
assert data['java.0.comm'] == 'javaw.exe', data
assert data['java.0.match'] == 'comm', data
env = props(tmp / 'out-1' / 'java-environ.ready.properties')
assert env['java.201.mesa_glthread'] == 'true', env
threads = (tmp / 'out-1' / 'java-threads.ready.txt').read_text()
assert '\tgl0\n' in threads and '\tgdrv0\n' in threads, threads

# --- Case 2: java.exe bound through a symlinked WINEPREFIX only ---
proc_root = tmp / 'proc-symlink'
real_prefix = tmp / 'real-prefix'
real_prefix.mkdir()
link_prefix = tmp / 'link-prefix'
link_prefix.symlink_to(real_prefix, target_is_directory=True)
write_proc(proc_root, '203', b'java.exe\n',
           f'WINEPREFIX={real_prefix}/pfx\x00'.encode(),
           b'java.exe\x00', '1003', threads={'203': b'java.exe', '303': b'main'})
result = run(proc_root, tmp / 'out-2', str(tmp / 'task-2'), str(link_prefix))
assert result.returncode == 0, result.stderr
data = props(tmp / 'out-2' / 'java-process.ready.properties')
assert data['javaProcessCount'] == '1', data
assert data['java.0.boundVia'] == 'wineprefix', data
assert data['glThreadCount'] == '0', data

# --- Case 3: foreign java.exe must never match; diagnostics expose it ---
proc_root = tmp / 'proc-foreign'
write_proc(proc_root, '204', b'java.exe\n',
           b'WINEPREFIX=/other/prefix/pfx\x00mesa_glthread=true\x00',
           b'java.exe\x00', '1004')
result = run(proc_root, tmp / 'out-3', str(tmp / 'task-3'), str(tmp / 'prefix-3'))
assert result.returncode == 0, result.stderr
data = props(tmp / 'out-3' / 'java-process.ready.properties')
assert data['javaProcessCount'] == '0', data
assert data['taskProcessCount'] == '0', data
assert data['prefixCarrierCount'] == '1', data
assert data['carrier.0.comm'] == 'java.exe', data
assert data['carrier.0.wineprefix'] == '/other/prefix/pfx', data
env = props(tmp / 'out-3' / 'java-environ.ready.properties')
assert env['mesa_glthread'] == 'ABSENT', env

# --- Case 4: cmdline fallback when comm is not a java name ---
proc_root = tmp / 'proc-cmdline'
task_dir = tmp / 'task-4'
write_proc(proc_root, '205', b'wine64\n',
           f'TURBOISM_HOST_VALIDATION_TASK_DIR={task_dir}\x00'.encode(),
           b'C:\\Program Files\\Java\\javaw.exe\x00app\x00', '1005',
           threads={'205': b'wine64'})
result = run(proc_root, tmp / 'out-4', str(task_dir), str(tmp / 'prefix-4'))
assert result.returncode == 0, result.stderr
data = props(tmp / 'out-4' / 'java-process.ready.properties')
assert data['javaProcessCount'] == '1', data
assert data['java.0.match'] == 'cmdline', data

# --- Case 5: task-bound non-java process records diagnostics, no match ---
proc_root = tmp / 'proc-nonjava'
task_dir = tmp / 'task-5'
write_proc(proc_root, '206', b'CubismEditor5.exe\n',
           f'TURBOISM_HOST_VALIDATION_TASK_DIR={task_dir}\x00'.encode(),
           b'CubismEditor5.exe\x00', '1006')
result = run(proc_root, tmp / 'out-5', str(task_dir), str(tmp / 'prefix-5'))
assert result.returncode == 0, result.stderr
data = props(tmp / 'out-5' / 'java-process.ready.properties')
assert data['javaProcessCount'] == '0', data
assert data['taskProcessCount'] == '1', data
assert data['task.0.comm'] == 'CubismEditor5.exe', data

print('PASS: java evidence collector task binding, java-family match, gl thread scan, diagnostics')
PY
