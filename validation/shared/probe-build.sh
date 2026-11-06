#!/usr/bin/env bash
# Shared build steps for the task-local validation probes.
#
# validation/<probe>/build.sh owns the facts that make its probe different: the
# source list, the packaged output, and any extra jar entry it must contain. This
# file owns the steps every plugin probe repeats: find the worktree SDK jar, compile
# against it, stage META-INF, package, require the packaged descriptor and the extra
# entries, print the artifact and its digest.
#
# The descriptor contract is deliberately not re-implemented here and no probe gains a
# new build dependency: this helper only proves the descriptor is packaged. A probe with
# scenario facts of its own (parameter-lock's schema/id/permissions/UI) asserts them at
# its own build site, against the packaged jar.
#
# Optional call-site inputs, set after sourcing:
#   PROBE_JAVAC_FLAGS       extra javac arguments, e.g. PROBE_JAVAC_FLAGS=(-encoding UTF-8)
#   PROBE_REQUIRED_ENTRIES  extra jar entries the packaged probe must contain
#
# Usage:
#   source "$(dirname "${BASH_SOURCE[0]}")/../shared/probe-build.sh"
#   probe_build_plugin_jar validation/<probe> build/<probe>.jar <sources...>
#
# Sources are resolved against the repository root unless already absolute, so a
# caller needs no working-directory setup. The build fails, rather than falling back
# to another worktree, when the current worktree has no SDK jar.

PROBE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROBE_JAVAC_FLAGS=()
PROBE_REQUIRED_ENTRIES=()
PROBE_TEMP_DIRS=()

# Prints the current worktree id, using the same rule the host runners use.
probe_worktree_id() {
  TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$PROBE_ROOT/scripts/dev/worktree-id.sh"
}

# Prints the newest SDK jar of the current worktree; :sdk:jar must have run.
probe_sdk_jar() {
  local sdk_dir
  local -a jars
  sdk_dir="$PROBE_ROOT/build/worktree/$(probe_worktree_id)/sdk/libs"
  mapfile -t jars < <(ls -t "$sdk_dir"/sdk-*.jar 2>/dev/null)
  if ((${#jars[@]} == 0)); then
    echo "error: no sdk jar found in $sdk_dir; run :sdk:jar first" >&2
    return 1
  fi
  printf '%s\n' "${jars[0]}"
}

# Requires one packaged jar entry.
probe_require_entry() {
  local jar="$1" entry="$2"
  if ! jar tf "$jar" | grep -Fqx "$entry"; then
    echo "error: probe jar is missing $entry" >&2
    exit 1
  fi
}

# Compiles and packages one plugin probe: <base>/src/META-INF plus the sources.
probe_build_plugin_jar() {
  local base="$1" output="$2" sdk_jar out source
  shift 2
  if (($# == 0)); then
    echo "error: probe_build_plugin_jar needs at least one source" >&2
    exit 2
  fi
  local -a sources=()
  for source in "$@"; do
    [[ "$source" = /* ]] || source="$PROBE_ROOT/$source"
    sources+=("$source")
  done
  sdk_jar="$(probe_sdk_jar)" || exit 1
  out="$(mktemp -d)"
  PROBE_TEMP_DIRS+=("$out")
  trap 'rm -rf "${PROBE_TEMP_DIRS[@]}"' EXIT
  javac --release 17 "${PROBE_JAVAC_FLAGS[@]}" -cp "$sdk_jar" -d "$out" "${sources[@]}"
  cp -r "$PROBE_ROOT/$base/src/META-INF" "$out/"
  [[ "$output" = /* ]] || output="$PROBE_ROOT/$output"
  mkdir -p "$(dirname "$output")"
  jar cf "$output" -C "$out" .
  probe_require_entry "$output" META-INF/turboism/plugin.json
  for source in "${PROBE_REQUIRED_ENTRIES[@]}"; do
    probe_require_entry "$output" "$source"
  done
  echo "[probe] $output"
  sha256sum "$output"
}
