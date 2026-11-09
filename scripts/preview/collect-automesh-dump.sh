#!/usr/bin/env bash
# Copy the Auto Mesh probe dump into the task evidence directory.
#
# The probe writes its dump inside the task home, which the runner does not collect. This
# pre-cleanup hook moves the dump next to the other evidence, so the offline renderer reads
# the collected copy instead of a path that disappears with the task directory.
#
# Hook contract: $1 task dir, $2 home dir, $3 evidence dir.
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "usage: collect-automesh-dump.sh <task-dir> <home-dir> <evidence-dir>" >&2
  exit 2
fi
home_dir="$2"
evidence_dir="$3"
dump_dir="$home_dir/automesh-dump"

if [ ! -d "$dump_dir" ]; then
  printf 'automesh dump: no dump directory at %s\n' "$dump_dir" >&2
  exit 0
fi
mkdir -p "$evidence_dir"
cp -a "$dump_dir/." "$evidence_dir/automesh-dump/" 2>/dev/null || {
  mkdir -p "$evidence_dir/automesh-dump"
  cp -a "$dump_dir/." "$evidence_dir/automesh-dump/"
}
printf 'automesh dump: collected %s file(s)\n' \
  "$(find "$evidence_dir/automesh-dump" -type f | wc -l)"
