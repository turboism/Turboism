#!/usr/bin/env bash
set -euo pipefail

home_dir="$2"
evidence_dir="$3"
source_file="$home_dir/data/dev.turboism.validation.history-manager/history-probe.jsonl"
target_dir="$evidence_dir/result"

[ -f "$source_file" ] || {
  printf 'history validation: manager evidence missing: %s\n' "$source_file" >&2
  exit 1
}
mkdir -p -- "$target_dir"
cp -- "$source_file" "$target_dir/history-probe.jsonl"
