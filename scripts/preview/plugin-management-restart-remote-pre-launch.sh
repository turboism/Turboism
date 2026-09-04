#!/usr/bin/env bash
# Rebind the staged JAR path after restoring task-scoped plugin-management state into a fresh validation home.
set -euo pipefail

home_dir=$2
journal="$home_dir/state/runtime/plugin-management/pending.json"
payload_line="$(grep -n '^__PLUGIN_MANAGEMENT_STATE__$' "$0" | cut -d: -f1)"
[ -n "$payload_line" ] || { echo 'embedded plugin-management state is missing' >&2; exit 1; }
tail -n "+$((payload_line + 1))" "$0" | base64 --decode | tar --extract --gzip --directory "$home_dir"

python3 - "$journal" "$home_dir" <<'PY'
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
