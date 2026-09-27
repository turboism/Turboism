#!/usr/bin/env python3
"""Exact task-prefix process cleanup. No substring ownership guesses or raw-PID signal fallback."""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import signal
import sys
import time


@dataclass(frozen=True)
class Process:
    pid: int
    parent: int
    started: int
    uid: int
    state: str
    command: tuple[str, ...]
    environment: tuple[bytes, ...]


def read_process(pid: int, proc: Path = Path('/proc'), read_environment: bool = True) -> Process | None:
    directory = proc / str(pid)
    uid = None
    stat = None
    try:
        status = (directory / 'status').read_text().splitlines()
        uid = int(next(line for line in status if line.startswith('Uid:')).split()[1])
        stat = (directory / 'stat').read_text().rsplit(')', 1)[1].split()
        # A non-dumpable zombie can deny environ even to its own UID. It cannot
        # run or own live descendants by this identity and needs no signal.
        if stat[0] in ('Z', 'X'):
            return None
        command = tuple(os.fsdecode(item) for item in (directory / 'cmdline').read_bytes().split(b'\0') if item)
        environment = tuple((directory / 'environ').read_bytes().split(b'\0')) if read_environment else ()
        return Process(pid, int(stat[1]), int(stat[19]), uid, stat[0], command, environment)
    except PermissionError as error:
        if uid == os.getuid():
            # The process may have exited between stat and environ. Recheck only
            # liveness/identity, never waive an unreadable live process.
            try:
                current_stat = (directory / 'stat').read_text().rsplit(')', 1)[1].split()
                current_status = (directory / 'status').read_text().splitlines()
                current_uid = int(next(line for line in current_status if line.startswith('Uid:')).split()[1])
            except (FileNotFoundError, ProcessLookupError):
                return None
            except (OSError, ValueError, IndexError, StopIteration) as recheck:
                raise RuntimeError(f'cannot recheck denied process identity for PID {pid}') from recheck
            if stat is None or current_stat[19] != stat[19] or current_uid != uid:
                raise RuntimeError(f'process identity changed after read denial for PID {pid}') from error
            if current_stat[0] in ('Z', 'X'):
                return None
            raise RuntimeError(f'cannot establish same-user process identity for PID {pid}; '
                               f'state={current_stat[0]} started={current_stat[19]} uid={current_uid}') from error
        return None
    except (OSError, ValueError, IndexError, StopIteration):
        return None


def normalized(value: str | bytes) -> Path:
    return Path(os.fsdecode(value)).resolve()


def tagged(process: Process, task: Path) -> bool:
    prefix = (task / 'prefix').resolve()
    for item in process.environment:
        key, separator, value = item.partition(b'=')
        if not separator or not value:
            continue
        if key == b'WINEPREFIX' and normalized(value) in {prefix, prefix / 'pfx'}:
            return True
        if key == b'STEAM_COMPAT_DATA_PATH' and normalized(value) == prefix:
            return True
    # The coordinator may mention a task path in shell text. Only an exact script
    # argv element establishes wrapper ownership, never arbitrary command text.
    return str(task / 'launch.sh') in process.command


def owned_processes(task: Path, proc: Path = Path('/proc'), self_pid: int | None = None,
                    uid: int | None = None) -> dict[int, Process]:
    task = task.resolve()
    uid = os.getuid() if uid is None else uid
    self_pid = os.getpid() if self_pid is None else self_pid
    records = {}
    earliest = 0
    record = task / 'evidence/identity-before.properties'
    if record.exists() and (proc / 'stat').is_file():
        boot = int(next(line.split()[1] for line in (proc / 'stat').read_text().splitlines() if line.startswith('btime ')))
        # A task cannot own processes predating its staged identity record. This
        # also avoids querying protected, unrelated long-lived user services.
        earliest = max(0, int((record.stat().st_mtime - boot - 2) * os.sysconf('SC_CLK_TCK')))
    for entry in proc.iterdir():
        if entry.name.isdigit():
            process = read_process(int(entry.name), proc, read_environment=False)
            if process is not None:
                records[process.pid] = process
    excluded = set()
    current = self_pid
    while current > 0 and current not in excluded:
        excluded.add(current)
        current = records[current].parent if current in records else 0
    eligible = {pid: process for pid, process in records.items()
                if pid not in excluded and process.uid == uid and process.started >= earliest and process.state not in ('Z', 'X')}
    detailed = (read_process(pid, proc) for pid in eligible)
    eligible = {process.pid: process for process in detailed if process is not None}
    owned = {pid: process for pid, process in eligible.items() if tagged(process, task)}
    changed = True
    while changed:
        changed = False
        for pid, process in eligible.items():
            if pid not in owned and process.parent in owned:
                owned[pid] = process
                changed = True
    return owned


def properties(path: Path) -> dict[str, str]:
    return dict(line.split('=', 1) for line in path.read_text().splitlines()
                if '=' in line and not line.startswith('#'))


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(65536), b''):
            value.update(block)
    return value.hexdigest()


def verify_task(task: Path) -> None:
    if not task.is_absolute() or task.is_symlink() or (task / 'prefix').is_symlink() or (task / 'prefix/pfx').is_symlink():
        raise RuntimeError('task/prefix identity is not a normal task-owned directory')
    identity = properties(task / 'evidence/identity-before.properties')
    if identity.get('taskId') != task.name:
        raise RuntimeError('task ID differs from original identity record')
    roots = {
        'bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd': 'Live2D Cubism 5.2',
        '988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21': 'Live2D Cubism 5.3',
        'bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166': 'Live2D Cubism 5.3.03',
    }
    expected = identity.get('hostJarSha256', '')
    if expected not in roots:
        raise RuntimeError('unreviewed task host artifact')
    cubism = task / 'prefix/pfx/drive_c/Program Files' / roots[expected]
    if digest(cubism / 'app/lib/Live2D_Cubism.jar') != expected:
        raise RuntimeError('task Cubism artifact identity changed; no processes signalled')
    if digest(cubism / 'CubismEditor5.bat') != identity.get('officialBatSha256'):
        raise RuntimeError('task official BAT identity changed; no processes signalled')


def send_verified(process: Process, kind: int) -> None:
    # pidfds bind the signal to one process incarnation. Never downgrade to kill(pid).
    descriptor = None
    try:
        descriptor = os.pidfd_open(process.pid, 0)
        current = read_process(process.pid)
        if current is None:
            return
        if current.started != process.started or current.uid != process.uid:
            raise RuntimeError(f'process identity changed for PID {process.pid}')
        signal.pidfd_send_signal(descriptor, kind)
    except ProcessLookupError:
        return
    finally:
        if descriptor is not None:
            os.close(descriptor)


def clean(task: Path) -> dict[str, str]:
    verify_task(task)
    if not hasattr(os, 'pidfd_open') or not hasattr(signal, 'pidfd_send_signal'):
        raise RuntimeError('pidfd identity-safe signalling unavailable; no raw-PID fallback')
    seen = set()
    known: dict[tuple[int, int], Process] = {}
    for kind, seconds in ((signal.SIGTERM, 5), (signal.SIGKILL, 5)):
        deadline = time.monotonic() + seconds
        while True:
            processes = owned_processes(task)
            known.update({(item.pid, item.started): item for item in processes.values()})
            for identity, previous in known.items():
                current = read_process(previous.pid)
                if current is None or current.state in ('Z', 'X'):
                    continue
                if current.started != previous.started or current.uid != previous.uid:
                    raise RuntimeError(f'process identity changed during cleanup for PID {previous.pid}')
                processes[current.pid] = current
            if not processes:
                return {'trackedProcesses': str(len({(pid, started) for pid, started, _ in seen})), 'survivors': '0', 'cleanupStatus': 'CLEANED'}
            for process in processes.values():
                identity = (process.pid, process.started, kind)
                if identity not in seen:
                    send_verified(process, kind)
                    seen.add(identity)
            if time.monotonic() >= deadline:
                break
            time.sleep(0.2)
    survivors = owned_processes(task)
    for previous in known.values():
        current = read_process(previous.pid)
        if current is not None and current.state not in ('Z', 'X'):
            if current.started != previous.started or current.uid != previous.uid:
                raise RuntimeError(f'process identity changed during final cleanup check for PID {previous.pid}')
            survivors[current.pid] = current
    return {'trackedProcesses': str(len({(pid, started) for pid, started, _ in seen})),
            'survivors': str(len(survivors)), 'cleanupStatus': 'FAILED' if survivors else 'CLEANED'}


def main(arguments: list[str]) -> int:
    # Same first two argument roles as the existing runner cleanup seam.
    pid_file, wine_prefix = Path(arguments[0]).absolute(), Path(arguments[1]).absolute()
    task = wine_prefix.parent.parent
    if wine_prefix != task / 'prefix/pfx' or pid_file != task / 'evidence/wrapper.pid':
        raise RuntimeError('cleanup arguments do not describe one task')
    if properties(task / 'evidence/identity-before.properties').get('taskId') != task.name:
        raise RuntimeError('task identity absent; refusing cleanup and diagnostic writes')
    result = {'taskDir': str(task)}
    try:
        result.update(clean(task))
    except Exception as error:
        result.update(cleanupStatus='BLOCKED', error=str(error))
    output = task / 'evidence/task-process-cleanup.properties'
    stamp = f'{time.time_ns()}-{os.getpid()}'
    if output.is_symlink():
        raise RuntimeError('cleanup evidence is a symlink')
    if output.exists():
        prior = output.with_name(f'task-process-cleanup-prior-{stamp}.properties')
        with prior.open('xb') as saved:
            saved.write(output.read_bytes())
        result['previousAttempt'] = prior.name
    text = ''.join(f'{key}={value}\n' for key, value in result.items())
    attempt = output.with_name(f'task-process-cleanup-{stamp}.properties')
    with attempt.open('x') as saved:
        saved.write(text)
    output.write_text(text)
    print(text, end='')
    return 0 if result.get('cleanupStatus') == 'CLEANED' else 1


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
