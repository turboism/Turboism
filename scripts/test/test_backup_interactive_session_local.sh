#!/usr/bin/env bash
# Backup interactive is catalogue-visible but fail-closed until an admitted
# interactive design exists; it must not create a second local lifecycle.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/../.." && pwd -P)"
wrapper="$repo_root/scripts/preview/run-backup-interactive-session.sh"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/turboism-backup-interactive.XXXXXX")"
trap 'rm -rf -- "$tmp"' EXIT

bin="$tmp/bin"
mkdir -p "$bin"
for command_name in ssh scp curl nohup; do
  cat > "$bin/$command_name" <<'SH'
#!/usr/bin/env bash
touch "${BACKUP_INTERACTIVE_SENTINEL:?}"
exit 99
SH
  chmod +x "$bin/$command_name"
done

if PATH="$bin:$PATH" BACKUP_INTERACTIVE_SENTINEL="$tmp/side-effect" \
  TURBOISM_WEBDAV_SECRET='must-not-be-staged' \
  bash "$wrapper" --version 5302 --interactive; then
  echo 'FAIL: backup interactive unexpectedly succeeded' >&2
  exit 1
fi

[ ! -e "$tmp/side-effect" ] || {
  echo 'FAIL: backup interactive invoked a network/process transport' >&2
  exit 1
}

# Capture the diagnostic separately so the wrapper's explanation remains tested.
if PATH="$bin:$PATH" BACKUP_INTERACTIVE_SENTINEL="$tmp/side-effect-2" \
  bash "$wrapper" >"$tmp/output" 2>&1; then
  echo 'FAIL: backup interactive second invocation unexpectedly succeeded' >&2
  exit 1
fi
grep -Fq 'unattended WebDAV sessions' "$tmp/output" || {
  cat "$tmp/output" >&2
  echo 'FAIL: backup interactive did not explain the fail-closed boundary' >&2
  exit 1
}
[ ! -e "$tmp/side-effect-2" ] || {
  echo 'FAIL: backup interactive invoked a forbidden helper' >&2
  exit 1
}

echo 'PASS: backup interactive fail-closed local boundary'
