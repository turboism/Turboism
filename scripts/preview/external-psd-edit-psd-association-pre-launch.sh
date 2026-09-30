#!/usr/bin/env bash
# Task-scoped .psd file association for the 025 external-edit host probe.
# Appends a .psd -> notepad.exe open verb to THIS task prefix's system.reg only;
# the golden prefix and real OS associations are never touched.
set -euo pipefail

task_dir=$1
home_dir=$2
evidence_dir=$3
prefix_dir=$4
fixture_path=$5
run_id=$6
host_version=$7
result_timeout=$8

system_reg="$prefix_dir/pfx/system.reg"
[ -f "$system_reg" ] || { echo "task prefix system.reg missing: $system_reg" >&2; exit 1; }
grep -q 'Classes\\\\\.psd' "$system_reg" && { echo 'psd association already present'; exit 0; }

now="$(date +%s)"
printf '\n[Software\\\\Classes\\\\.psd] %s\n' "$now" >> "$system_reg"
printf '#time=1dd000000000000\n' >> "$system_reg"
printf '@="psdfile"\n' >> "$system_reg"
printf '"Content Type"="image/vnd.adobe.photoshop"\n' >> "$system_reg"
printf '\n[Software\\\\Classes\\\\psdfile] %s\n' "$now" >> "$system_reg"
printf '#time=1dd000000000000\n' >> "$system_reg"
printf '@="PSD Document"\n' >> "$system_reg"
printf '\n[Software\\\\Classes\\\\psdfile\\\\shell\\\\open\\\\command] %s\n' "$now" >> "$system_reg"
printf '#time=1dd000000000000\n' >> "$system_reg"
printf '@="\\"C:\\\\windows\\\\system32\\\\notepad.exe\\" \\"%%1\\""\n' >> "$system_reg"
printf 'psd association registered in task prefix\n'
