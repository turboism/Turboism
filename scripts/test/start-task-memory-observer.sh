#!/usr/bin/env bash
# Managed background hook: exec preserves supervisor ownership and exit status.
set -euo pipefail
[ "$#" -eq 12 ] || { printf 'memory observer requires 11 context arguments and pinned Python\n' >&2; exit 2; }
task=$1
home=$2
evidence=$3
python=${12}
[ "${python:0:1}" = / ] && [ -f "$python" ] && [ -x "$python" ] || exit 2
[ "${TURBOISM_HOST_VALIDATION_TASK_DIR:-}" = "$task" ] || exit 2
[ "$home" = "$task/turboism-home" ] && [ "$evidence" = "$task/evidence" ] || exit 2
exec "$python" -I -S -B "$home/validation/measure-task-memory.py" "$task" \
  > "$evidence/memory-sampler.log" 2>&1 < /dev/null
