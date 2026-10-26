#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" scripts/dev/worktree-id.sh)"
./gradlew --console=plain :plugins:acp:jar :sdk:jar
acp_jars=("$repo_root"/build/worktree/"$worktree_id"/acp/libs/acp-*.jar)
sdk_jars=("$repo_root"/build/worktree/"$worktree_id"/sdk/libs/sdk-*.jar)
output="${1:?Expected native-observe bundle directory}"
mkdir -p "$output"
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
javac --release 17 -cp "${acp_jars[0]}:${sdk_jars[0]}" -d "$temporary" validation/acp-native-host-probe/NativeObserve.java
javac --release 17 -cp "$temporary:${acp_jars[0]}:${sdk_jars[0]}" -d "$temporary" validation/acp-native-host-probe/NativeObserveSelfTest.java
java -cp "$temporary:${acp_jars[0]}:${sdk_jars[0]}" dev.turboism.plugin.acp.NativeObserveSelfTest
rm "$temporary/dev/turboism/plugin/acp/NativeObserveSelfTest"*.class
( cd "$temporary"; jar xf "${sdk_jars[0]}"; jar xf "${acp_jars[0]}"; jar cf "$output/probe.jar" . )
python3 - "$output/runtime.json" "${2:-}" "${3:-pi}" "${4:-observe}" <<'PY'
import json, pathlib, shutil, subprocess, sys
harness = sys.argv[3]
workflow = sys.argv[4]
assert workflow in {"observe", "keyform-edit"} and (workflow == "observe" or harness == "codex")
assert harness in {'pi', 'claude', 'codex'}, 'Unsupported reviewed harness'
java = pathlib.Path(shutil.which('java')).resolve(strict=True)
if harness == 'codex':
    assert not sys.argv[2], 'Pi exclusions do not apply to Codex'
    release = pathlib.Path.home() / '.local/lib/node_modules/@openai/codex'
    entry = pathlib.Path(shutil.which('codex')).resolve(strict=True)
    node = pathlib.Path(shutil.which('node')).resolve(strict=True)
    assert entry == release / 'bin/codex.js' and entry.is_file(), 'Reviewed Codex installation required'
    version = subprocess.run([str(node), str(entry), '--version'], check=True, capture_output=True, text=True, timeout=15).stdout.strip()
    assert version == 'codex-cli 0.159.2', 'Codex version mismatch'
    manifest = {'schemaVersion':3, 'harness':'codex',
        'javaExecutable':str(java), 'nodeExecutable':str(node), 'codexReleaseRoot':str(release),
        'codexEntrypoint':str(entry), 'codexVersion':'0.159.2', 'model':'gpt-6.1-sol'}
    if workflow == 'keyform-edit': manifest.update(schemaVersion=4, workflow=workflow)
    pathlib.Path(sys.argv[1]).write_text(json.dumps(manifest, indent=2)+'\n')
    sys.exit(0)
if harness == 'claude':
    assert not sys.argv[2], 'Pi provider exclusion list does not apply to Claude'
    release = pathlib.Path.home() / '.local/lib/node_modules/@anthropic-ai/claude-code'
    entry = pathlib.Path(shutil.which('claude')).resolve(strict=True)
    assert entry == release / 'bin/claude.exe' and entry.is_file(), 'Reviewed Claude installation is required'
    version = subprocess.run([str(entry), '--version'], check=True, capture_output=True, text=True, timeout=15).stdout.strip()
    assert version == '2.1.284 (Claude Code)', 'Claude version mismatch'
    pathlib.Path(sys.argv[1]).write_text(json.dumps({'schemaVersion':2, 'harness':'claude',
        'javaExecutable':str(java), 'claudeReleaseRoot':str(release), 'claudeExecutable':str(entry),
        'claudeVersion':'2.1.284'}, indent=2)+'\n')
    sys.exit(0)
root = pathlib.Path.home() / '.pi/agent/install/releases/1.2.0'
entry = root / 'node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js'
assert root.is_dir() and entry.is_file(), 'Reviewed managed Pi 1.2.0 installation is required'
node = pathlib.Path(shutil.which('node')).resolve(strict=True)
java = pathlib.Path(shutil.which('java')).resolve(strict=True)
version = subprocess.run([str(node), str(entry), '--version'], check=True, capture_output=True, text=True, timeout=15).stdout.strip()
assert version == '1.2.0', 'Pi version mismatch'
excluded = sys.argv[2].split(',') if sys.argv[2] else []
import re
assert len(excluded) <= 8 and len(excluded) == len(set(excluded)) and all(re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', p) for p in excluded)
pathlib.Path(sys.argv[1]).write_text(json.dumps({'schemaVersion':1, 'javaExecutable':str(java), 'nodeExecutable':str(node),
    'piReleaseRoot':str(root), 'piEntrypoint':str(entry), 'piVersion':version, 'excludedVisionProviders':excluded}, indent=2)+'\n')
PY
