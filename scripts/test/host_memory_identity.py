"""Read-only task artifact and bound-cgroup observation identity. No process control."""
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import re


@dataclass(frozen=True)
class Process:
    pid: int
    started: int
    uid: int
    command: tuple[str, ...]


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
    checked_paths = (task, *task.parents, task / 'prefix', task / 'prefix/pfx')
    if not task.is_absolute() or any(path.is_symlink() for path in checked_paths):
        raise RuntimeError('task/prefix identity is not a normal task-owned directory')
    identity = properties(task / 'evidence/identity-before.properties')
    if identity.get('taskId') != task.name:
        raise RuntimeError('task ID differs from original identity record')
    expected = '988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21'
    if identity.get('hostJarSha256') != expected:
        raise RuntimeError('memory observer requires reviewed 5302 host artifact')
    cubism = task / 'prefix/pfx/drive_c/Program Files/Live2D Cubism 5.3'
    if digest(cubism / 'app/lib/Live2D_Cubism.jar') != expected:
        raise RuntimeError('task Cubism artifact identity changed')
    if digest(cubism / 'CubismEditor5.bat') != identity.get('officialBatSha256'):
        raise RuntimeError('task official BAT identity changed')


class BoundScope:
    """Pins the observer's already-bound manager scope; never creates or cleans one.

    Alternate roots/PID exist for filesystem-only tests, not as executable CLI flags.
    A kernel scope membership change during a read invalidates the entire sample.
    """
    def __init__(self, proc=Path('/proc'), cgroup=Path('/sys/fs/cgroup'), observer_pid=None):
        self.proc = proc
        self.observer_pid = os.getpid() if observer_pid is None else observer_pid
        self.fd = -1
        self.path = self._scope(self.observer_pid)
        if (not self.path.startswith('/') or '..' in self.path.split('/')
                or not re.fullmatch(r'turboism-queue-[0-9a-f]{32}\.scope', Path(self.path).name)):
            raise RuntimeError('observer is not inside a manager scope')
        self.directory = cgroup / self.path.lstrip('/')
        if self.directory.resolve() != self.directory:
            raise RuntimeError('scope path contains a symlink or noncanonical component')
        self.boot = (self.proc / 'sys/kernel/random/boot_id').read_text().strip()
        if not re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', self.boot):
            raise RuntimeError('invalid boot identity')
        self.observer = self._basic(self.observer_pid)
        if self.observer[1] != os.getuid():
            raise RuntimeError('observer UID differs from current user')
        try:
            self.fd = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW)
            stat = os.fstat(self.fd)
            self.device, self.inode = stat.st_dev, stat.st_ino
            self.validate()
        except BaseException:
            self.close()
            raise

    def close(self):
        if self.fd >= 0:
            descriptor, self.fd = self.fd, -1
            os.close(descriptor)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def _scope(self, pid):
        try:
            lines = (self.proc / str(pid) / 'cgroup').read_text().splitlines()
            matches = [line[3:] for line in lines if line.startswith('0::')]
            if len(matches) != 1:
                raise ValueError('expected one unified cgroup')
            return matches[0]
        except (OSError, ValueError) as failure:
            raise RuntimeError('cannot establish process scope') from failure

    def _basic(self, pid):
        try:
            root = self.proc / str(pid)
            fields = (root / 'stat').read_text().rsplit(')', 1)[1].split()
            uid = int(next(line for line in (root / 'status').read_text().splitlines()
                           if line.startswith('Uid:')).split()[1])
            return int(fields[19]), uid, fields[0]
        except (OSError, ValueError, IndexError, StopIteration) as failure:
            raise RuntimeError('cannot establish process identity') from failure

    def validate(self):
        if self.fd < 0:
            raise RuntimeError('scope descriptor is closed')
        stat = self.directory.stat()
        pinned = os.fstat(self.fd)
        if ((stat.st_dev, stat.st_ino) != (self.device, self.inode)
                or (pinned.st_dev, pinned.st_ino) != (self.device, self.inode)
                or self.directory.resolve() != self.directory
                or self._scope(self.observer_pid) != self.path
                or self._basic(self.observer_pid)[:2] != self.observer[:2]
                or (self.proc / 'sys/kernel/random/boot_id').read_text().strip() != self.boot):
            raise RuntimeError('observer scope or identity changed')

    def members(self):
        self.validate()
        descriptor = os.open('cgroup.procs', os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW, dir_fd=self.fd)
        with os.fdopen(descriptor) as source:
            pids = {int(line) for line in source if line.strip()}
        if any(pid <= 0 for pid in pids) or self.observer_pid not in pids:
            raise RuntimeError('invalid scope membership')
        self.validate()
        return pids

    def read_process(self, pid):
        if self._scope(pid) != self.path:
            raise RuntimeError('process left bound scope')
        before = self._basic(pid)
        if before[1] != os.getuid():
            raise RuntimeError('process UID differs from observer')
        root = self.proc / str(pid)
        command = tuple(os.fsdecode(item) for item in (root / 'cmdline').read_bytes().split(b'\0') if item)
        after = self._basic(pid)
        if before[:2] != after[:2] or self._scope(pid) != self.path:
            raise RuntimeError('process identity or scope changed during read')
        if after[2] in ('Z', 'X'):
            return None
        return Process(pid, after[0], after[1], command)

    def find_java(self):
        found = []
        for pid in sorted(self.members()):
            try:
                process = self.read_process(pid)
                name = (self.proc / str(pid) / 'comm').read_text().strip().lower()
            except (RuntimeError, FileNotFoundError, ProcessLookupError):
                # A startup helper may exit between cgroup.procs and its identity
                # read. Only actual disappearance permits a retry, never denial.
                if not (self.proc / str(pid)).exists() and pid not in self.members():
                    continue
                raise
            if (process is not None and name in ('java.exe', 'javaw.exe')
                    and 'com.live2d.cubism.CECubismEditorApp' in process.command):
                self.verify_process(process)
                found.append(process)
        self.validate()
        if len(found) > 1:
            raise RuntimeError('multiple task Editor JVMs; memory attribution ambiguous')
        return found[0] if found else None

    def verify_process(self, process):
        if process.pid not in self.members() or self.read_process(process.pid) != process:
            raise RuntimeError('measured process exited or changed identity')
        self.validate()

    def metadata(self):
        self.validate()
        return dict(path=self.path, device=self.device, inode=self.inode, bootId=self.boot)
