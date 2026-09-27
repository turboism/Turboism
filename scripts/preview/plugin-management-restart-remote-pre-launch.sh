#!/usr/bin/env bash
# Installs the staged plugin-management state for the restart phase of the
# plugin-management chooser validation and rebinds the pending install journal.
#
# The stage task's state/runtime/plugin-management tree arrives as a declared
# --home-dir input under restart-state/. The runner re-verifies declared inputs
# after the session, so this hook copies the tree into place instead of moving
# it and only rewrites the absolute stagedJar path recorded by the previous
# session so it points inside this task's home.
set -euo pipefail

home_dir=$2
payload="$home_dir/restart-state/runtime/plugin-management"
journal_dir="$home_dir/state/runtime/plugin-management"
[ -f "$payload/pending.json" ] || { echo 'plugin-management pending journal payload is missing' >&2; exit 1; }
mkdir -p "$journal_dir"
cp -R "$payload/." "$journal_dir/"
# Declared inputs are snapshotted read-only; the journal and staged package must
# be writable for the rewrite below and for the runtime to consume the entry.
chmod -R u+w "$journal_dir"

python3 - "$journal_dir/pending.json" "$home_dir" <<'PY'
import json
import sys
from pathlib import Path

journal = Path(sys.argv[1])
home = Path(sys.argv[2])
data = json.loads(journal.read_text(encoding="utf-8"))
operations = data.get("operations")
if not isinstance(operations, list) or len(operations) != 1:
    raise SystemExit("expected exactly one pending plugin operation")
operation = operations[0]
staged = operation.get("stagedJar")
if operation.get("type") != "INSTALL" or not isinstance(staged, str):
    raise SystemExit("expected one pending INSTALL operation")
filename = staged.replace("\\", "/").rsplit("/", 1)[-1]
package = home / "state" / "runtime" / "plugin-management" / "packages" / filename
if not package.is_file():
    raise SystemExit("restored staged plugin JAR is missing")
operation["stagedJar"] = "Z:" + str(package).replace("/", "\\")
journal.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
PY
exit 0
