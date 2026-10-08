#!/usr/bin/env bash
# Shared confinement for validation-bundle scratch paths, modelled on
# scripts/preview/host-validation-transport.sh (transport_remove_tree): a bundle
# root may only ever be deleted after realpath resolution lands it strictly
# under the caller's allowed root and no ancestor component is a symlink.

preview_bundle_fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

preview_bundle_assert_no_symlink_ancestors() {
  local destination="$1" lexical current part
  case "$destination" in
    /*) lexical="$destination" ;;
    *) lexical="$PWD/$destination" ;;
  esac
  current=/
  IFS=/ read -r -a _preview_bundle_path_parts <<< "${lexical#/}"
  for part in "${_preview_bundle_path_parts[@]}"; do
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
          || preview_bundle_fail "refusing to remove through symlinked ancestor: $current"
        ;;
    esac
  done
  unset _preview_bundle_path_parts
}

# preview_bundle_safe_remove_tree <path> <allowed-root>
# Removes <path> only when realpath -m resolves it strictly below <allowed-root>
# and no ancestor is a symlink. A missing path is a no-op.
preview_bundle_safe_remove_tree() {
  local path="${1:-}" allowed_root="${2:-}"
  [ -n "$path" ] || preview_bundle_fail "refusing to remove an empty path"
  [ -n "$allowed_root" ] || preview_bundle_fail "missing confinement root for removal: $path"
  [ -e "$path" ] || [ -L "$path" ] || return 0
  # Check the lexical path for symlinked ancestors before resolving: realpath
  # would fold the symlink away and hide the unsafe indirection.
  preview_bundle_assert_no_symlink_ancestors "$path"
  local resolved_root resolved_path
  resolved_root="$(realpath -m -- "$allowed_root")" \
    || preview_bundle_fail "cannot resolve confinement root: $allowed_root"
  resolved_path="$(realpath -m -- "$path")" \
    || preview_bundle_fail "cannot resolve removal path: $path"
  [ "$resolved_path" != "$resolved_root" ] \
    || preview_bundle_fail "refusing to remove the confinement root itself: $path"
  case "$resolved_path" in
    "$resolved_root"/*) ;;
    *) preview_bundle_fail "refusing to remove outside $resolved_root: $path" ;;
  esac
  rm -rf -- "$path"
}
