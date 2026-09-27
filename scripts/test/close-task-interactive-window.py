#!/usr/bin/env python3
"""Explicit smoke client only: close one window after verifying its task prefix and PID."""
import os
from pathlib import Path
import re
import subprocess
import sys
import time

home = Path(sys.argv[1])
task = sys.argv[2]
root = home.parent
prefixes = {(root / 'prefix' / 'pfx').resolve(), (root / 'prefix').resolve()}
env = dict(os.environ, DISPLAY=os.environ.get('DISPLAY', ':0'))
deadline = time.monotonic() + 600
previous = None
stable = 0
while time.monotonic() < deadline:
    result = subprocess.run(['xdotool', 'search', '--onlyvisible', '--name', re.escape(task)], env=env, text=True, capture_output=True)
    matches = []
    for window in result.stdout.splitlines():
        if not window.isdigit():
            continue
        try:
            title = subprocess.check_output(['xdotool', 'getwindowname', window], env=env, text=True)
            pid = int(subprocess.check_output(['xdotool', 'getwindowpid', window], env=env, text=True).strip())
            environment = (Path('/proc') / str(pid) / 'environ').read_bytes().split(b'\0')
            actual_prefixes = {Path(os.fsdecode(item.split(b'=', 1)[1])).resolve()
                               for item in environment if item.startswith(b'WINEPREFIX=')}
            if task in title and actual_prefixes.intersection(prefixes):
                matches.append((window, pid))
        except (OSError, ValueError, subprocess.CalledProcessError):
            continue
    if len(matches) == 1:
        current = matches[0]
        stable = stable + 1 if current == previous else 0
        previous = current
        if stable >= 5:
            window, pid = current
            output = home / 'state/interactive-smoke.properties'
            output.write_text(f'runId={task}\nwindow={window}\npid={pid}\nprefixVerified=true\ncloseRequested=true\n')
            # Wine does not reliably translate windowquit here; a normal Alt+F4
            # reaches Cubism's close handler. Never send keys unless this verified window is active.
            subprocess.run(['xdotool', 'windowactivate', '--sync', window], env=env, check=True)
            if subprocess.check_output(['xdotool', 'getactivewindow'], env=env, text=True).strip() != window:
                raise SystemExit('Task window focus changed; no close shortcut sent')
            subprocess.run(['xdotool', 'key', '--clearmodifiers', 'alt+F4'], env=env, check=True)
            print(f'Requested normal close of verified task window {window}, PID {pid}; no process was killed')
            break
    else:
        stable = 0
    time.sleep(1)
else:
    raise SystemExit('No unique, prefix-verified task window became ready; no close was attempted')
