#!/usr/bin/env bash
# The interactive WebDAV session is intentionally unavailable in the local-only
# Runner slice. It used to launch a separate SSH/SCP lifecycle before the common
# Runner could enforce admission, task ownership, or evidence cleanup.
set -euo pipefail

cat >&2 <<'EOF'
backup interactive host validation is unavailable in the local-only Runner.
This first local-only release does not support unattended WebDAV sessions:
no editor, hook, network, SSH/SCP transport, or WebDAV secret staging was started.
Use the queued interactive-session design after it is explicitly re-enabled by
its owner with a confined snapshot and task-owned lifecycle.
EOF
exit 2
