#!/usr/bin/env bash
# Compatibility names for local-only copy/path helpers. No network transport.
# The queue remains the only host admission/supervision authority.
host_validation_transport_validate() {
  [ "${transport:-local}" = local ] || fail "host validation is local-only"
  [ -z "${ssh_host:-}${ssh_key:-}" ] || fail "local transport must not be combined with --ssh-host or --ssh-key"
}
host_validation_transport_init() {
  host_validation_transport_validate
}

host_validation_transport_pipe() {
  local encoded="$1" preamble="$2"
  shift 2
  {
    if [ "$preamble" = define ]; then
      cat <<'REMOTE_ARGS'
remote_args() {
  mapfile -t REMOTE_ARGS < <(printf '%s' "$TURBOISM_ARGS_B64" | base64 -d)
}
REMOTE_ARGS
    else
      cat <<'REMOTE_ARGS'
transport_args() {
  mapfile -t TRANSPORT_ARGS < <(printf '%s' "$TURBOISM_ARGS_B64" | base64 -d)
}
transport_args
set -- "${TRANSPORT_ARGS[@]}"
REMOTE_ARGS
    fi
    cat
  } | {
    host_validation_transport_validate
    TURBOISM_ARGS_B64="$encoded" bash -s
  }
}

# Base64-encode positional values and prepend only a decoder function. The
# existing callers explicitly invoke remote_args from their heredoc, keeping
# task paths out of cleanup-command argv while preserving the reviewed script.
remote_args_bash() {
  local encoded
  encoded="$(printf '%s\n' "$@" | base64 -w 0)"
  host_validation_transport_pipe "$encoded" define
}

# Same payload transport, but restore the values as $1..$N for heredocs that
# were historically sent as `bash -s -- ...` over SSH.
transport_bash() {
  local encoded
  encoded="$(printf '%s\n' "$@" | base64 -w 0)"
  host_validation_transport_pipe "$encoded" positional
}

transport_command() {
  local command="$1"
  host_validation_transport_validate
  bash -c "$command"
}

transport_prepare_directory() {
  local destination="$1"
  host_validation_transport_validate
  host_validation_transport_assert_destination_tree_safe "$destination"
  mkdir -p -- "$destination"
}

transport_copy_to() {
  local recursive=0 source destination
  if [ "${1:-}" = --recursive ]; then
    recursive=1
    shift
  fi
  [ "$#" -eq 2 ] || fail "internal transport copy-to argument error"
  source="$1"
  destination="$2"
  host_validation_transport_validate
  host_validation_transport_assert_copy_safe "$source" "$destination" "$recursive"
  if [ "$recursive" = 1 ]; then cp -a -- "$source" "$destination"; else cp -- "$source" "$destination"; fi
}

transport_copy_from() {
  transport_copy_to "$@"
}

transport_copy_dir_contents_to() {
  local source="$1" destination="$2"
  host_validation_transport_validate
  host_validation_transport_assert_copy_safe "$source/." "$destination" 1
  cp -a -- "$source/." "$destination/"
}

transport_copy_host_file() {
  local source="$1" destination="$2"
  host_validation_transport_validate
  host_validation_transport_assert_copy_safe "$source" "$destination" 0
  cp --reflink=auto -- "$source" "$destination"
}

transport_remove_tree() {
  local path="$1"
  host_validation_transport_validate
  host_validation_transport_assert_no_symlink_ancestors "$path"
  [ -n "$path" ] && [ "$(realpath -m -- "$path")" != / ] || fail "unsafe removal path"
  rm -rf -- "$path"
}

host_validation_transport_canonical_path() {
  realpath -m -- "$1"
}
host_validation_transport_is_descendant() {
  local child="$1" parent="$2"
  if [ "$parent" = / ]; then
    [ "$child" != / ] && [[ "$child" == /* ]]
    return
  fi
  [[ "$child" == "$parent"/* ]]
}
host_validation_transport_assert_no_symlink_ancestors() {
  local destination="$1" lexical current part
  case "$destination" in
    /*) lexical="$destination" ;;
    *) lexical="$PWD/$destination" ;;
  esac
  current=/
  IFS=/ read -r -a _transport_path_parts <<< "${lexical#/}"
  for part in "${_transport_path_parts[@]}"; do
    case "$part" in
      ''|.) continue ;;
      ..)
        [ "$current" = / ] || current="${current%/*}"
        [ -n "$current" ] || current=/
        ;;
      *)
        if [ "$current" = / ]; then
          current="/$part"
        else
          current="$current/$part"
        fi
        [ ! -L "$current" ] \
          || fail "local transport rejects symlink destination ancestor: $current"
        ;;
    esac
  done
  unset _transport_path_parts
}
host_validation_transport_assert_destination_tree_safe() {
  local destination="$1" unsafe links
  host_validation_transport_assert_no_symlink_ancestors "$destination"
  [ ! -L "$destination" ] \
    || fail "local transport rejects symlink destination root: $destination"
  if [ -f "$destination" ]; then
    links="$(stat -c %h -- "$destination")" \
      || fail "local transport could not inspect destination file: $destination"
    [[ "$links" =~ ^[0-9]+$ ]] \
      || fail "local transport got an invalid destination link count: $destination"
    [ "$links" -le 1 ] \
      || fail "local transport rejects symlink or hardlinked destination entry: $destination"
    return 0
  fi
  [ -d "$destination" ] || return 0
  unsafe="$(find -P "$destination" -mindepth 1 \( -type l -o \( -type f -links +1 \) \) -print -quit)" \
    || fail "local transport could not inspect destination tree: $destination"
  [ -z "$unsafe" ] \
    || fail "local transport rejects symlink or hardlinked destination entry: $unsafe"
}

host_validation_transport_assert_copy_safe() {
  local source="$1" destination="$2" recursive="${3:-0}"
  local source_abs destination_abs
  source_abs="$(host_validation_transport_canonical_path "$source")" \
    || fail "cannot canonicalize local copy source: $source"
  destination_abs="$(host_validation_transport_canonical_path "$destination")" \
    || fail "cannot canonicalize local copy destination: $destination"
  [ "$source_abs" != "$destination_abs" ] \
    || fail "local transport rejects source-equals-destination copy: $source"
  if [ -e "$source" ] && [ -e "$destination" ] && [ "$source" -ef "$destination" ]; then
    fail "local transport rejects aliased source/destination copy: $source"
  fi
  if [ "$recursive" = 1 ]; then
    if host_validation_transport_is_descendant "$destination_abs" "$source_abs"; then
      fail "local transport rejects destination inside copy source: $destination"
    fi
    if host_validation_transport_is_descendant "$source_abs" "$destination_abs"; then
      fail "local transport rejects copy source inside destination: $source"
    fi
  fi
  host_validation_transport_assert_destination_tree_safe "$destination"
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
