#!/usr/bin/env bash
# Existing generic runner pre-launch hook. This starts an observer, never Cubism.
set -euo pipefail
task=$1
home=$2
evidence=$3
python3 "$home/validation/measure-task-memory.py" "$task" \
  > "$evidence/memory-sampler.log" 2>&1 < /dev/null &
printf '%s\n' "$!" > "$evidence/memory-sampler.pid"
