#!/usr/bin/env bash
# Explicit on-host transport for the generic runner's existing shell/copy protocol.
# No host lifecycle, identity checks, launching or cleanup belongs here.

host_validation_local_shell() {
  [[ $# == 2 && "$1" == local ]] || return 2
  # The command is trusted runner code; untrusted positional arguments continue
  # to travel through remote_args_bash's Base64 stdin protocol.
  bash -c "$2"
}

host_validation_local_copy() {
  local recursive=() source destination
  if [[ ${1:-} == -r ]]; then recursive=(-R); shift; fi
  [[ $# == 2 ]] || return 2
  source=$1 destination=$2
  # Exactly one endpoint must be the runner's local host sentinel. Never parse
  # arbitrary hostnames, interpolate a shell command, or evaluate path contents.
  if [[ "$source" == local:/* && "$destination" != local:* ]]; then
    source=${source#local:}
  elif [[ "$destination" == local:/* && "$source" != local:* ]]; then
    destination=${destination#local:}
  else
    return 2
  fi
  cp "${recursive[@]}" -- "$source" "$destination"
}
