#!/usr/bin/env bash
# Sourced transport primitives. Host lifecycle, validation and ownership remain in the Runner.

host_validation_local_shell() {
  [ "$#" -eq 2 ] && [ "$1" = local ] || {
    printf 'local host transport: expected local endpoint and one trusted shell command\n' >&2
    return 2
  }
  # Same trusted shell source previously sent to SSH; stdin carries heredoc scripts/data.
  bash -c "$2"
}

host_validation_local_copy() {
  local recursive=0
  if [ "${1:-}" = -r ]; then recursive=1; shift; fi
  [ "$#" -eq 2 ] || {
    printf 'local host transport: expected source and destination\n' >&2
    return 2
  }
  local source="$1" destination="$2" endpoints=0
  case "$source" in
    local:/*) source="${source#local:}"; endpoints=$((endpoints + 1)) ;;
    *:*) printf 'local host transport: invalid source endpoint\n' >&2; return 2 ;;
  esac
  case "$destination" in
    local:/*) destination="${destination#local:}"; endpoints=$((endpoints + 1)) ;;
    *:*) printf 'local host transport: invalid destination endpoint\n' >&2; return 2 ;;
  esac
  [ "$endpoints" -eq 1 ] || {
    printf 'local host transport: exactly one staged endpoint is required\n' >&2
    return 2
  }
  if [ "$recursive" -eq 1 ]; then
    cp -R --reflink=auto -- "$source" "$destination"
  else
    cp --reflink=auto -- "$source" "$destination"
  fi
}

# Read-only preflight, repeated immediately before launch; never stops a foreign process.
# The optional proc root is for offline fixtures, not a Runner CLI option.
host_validation_local_idle() {
  python3 - "${1:-/proc}" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
blocked = []
for proc in root.iterdir():
    if not proc.name.isdigit():
        continue
    try:
        raw = (proc / 'cmdline').read_bytes()
    except FileNotFoundError:
        continue
    except PermissionError:
        continue
    args = raw.split(b'\0')
    executable = args[0].lower().replace(b'\\', b'/').rsplit(b'/', 1)[-1]
    cubism = b'com.live2d.cubism.CECubismEditorApp' in args
    cubism = cubism or (executable in (b'java', b'java.exe') and b'Live2D_Cubism.jar' in raw)
    cubism = cubism or (executable == b'cmd.exe' and b'CubismEditor5' in raw)
    if cubism:
        blocked.append(proc.name)
if blocked:
    print('host validation: BLOCKED existing Cubism PID(s): ' + ','.join(blocked), file=sys.stderr)
    sys.exit(4)
PY
}
