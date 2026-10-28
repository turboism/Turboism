#!/usr/bin/env bash
# Compile the task-only native UI carrier against an explicitly supplied licensed host.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[ "$#" -eq 1 ] || { echo 'usage: build-undo-agent.sh <host-lib-directory>' >&2; exit 2; }
python3 - "$repo_root" "$1" <<'PY'
from pathlib import Path
import subprocess, sys, shutil
repo, lib = map(Path, sys.argv[1:])
if not (lib / 'Live2D_Cubism.jar').is_file():
    raise SystemExit('host-lib-directory must contain Live2D_Cubism.jar')
out = repo / 'build/incremental-undo-agent'
if out.exists():
    shutil.rmtree(out)
out.mkdir(parents=True)
sources = [repo / 'validation/incremental-update-host-probe/native-src/dev/turboism/validation/incrementalupdate/IncrementalUndoHostAgent.java', repo / 'validation/shared/src/dev/turboism/validation/NativeFreeStartup.java']
subprocess.run(['javac', '--release', '17', '-cp', ':'.join(str(p) for p in sorted(lib.rglob('*.jar'))), '-d', str(out), *map(str, sources)], check=True)
manifest = repo / 'build/incremental-undo-agent.mf'
manifest.write_text('Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.incrementalupdate.IncrementalUndoHostAgent\n\n')
subprocess.run(['jar', 'cfm', str(repo / 'build/incremental-undo-host-agent.jar'), str(manifest), '-C', str(out), '.'], check=True)
PY
