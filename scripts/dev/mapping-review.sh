#!/usr/bin/env bash
# Worktree-isolated wrapper for the local DRAFT mapping update review CLI.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "$WT_ROOT"

usage() {
  cat <<'USAGE'
usage:
  scripts/dev/mapping-review.sh generate --artifact <jar> --pack <worktree-relative-pack.json>
      --semantic-name <id> --expected-old-runtime <internal-name>
      --caller-owner <internal-name> --caller-name <name> --caller-descriptor <descriptor>
      --target-method-name <name> --target-method-descriptor <descriptor>
      [--invocation ANY|STATIC|INSTANCE] [--output <directory>]
  scripts/dev/mapping-review.sh apply --candidate <candidate.json> --review <review.json> --artifact <jar>
      [--write]

Generated files default to this worktree's isolated build directory. apply defaults to dry-run;
pass --write explicitly to atomically replace the target DRAFT mapping pack.
USAGE
}

if [ "$#" -eq 0 ] || [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  usage
  [ "$#" -eq 0 ] && exit 2
  exit 0
fi

command="$1"
case "$command" in
  generate|apply)
    if [ "${2:-}" = "--help" ] || [ "${2:-}" = "-h" ]; then
      usage
      exit 0
    fi
    ;;
  *)
    printf 'mapping-review: unknown command: %s\n' "$command" >&2
    usage >&2
    exit 2
    ;;
esac

WT_ID="$(bash "$SCRIPT_DIR/worktree-id.sh")"
export TURBOISM_WORKTREE_ID="$WT_ID"
OUTPUT_DIR="build/worktree/$WT_ID/mapping-review"
ARGS_FILE="$(mktemp "${TMPDIR:-/tmp}/turboism-mapping-review-args.XXXXXX")"
trap 'rm -f -- "$ARGS_FILE"' EXIT

shift
args=("$command" "--root" "$WT_ROOT")
args+=("$@")

for argument in "${args[@]}"; do
  printf '%s' "$argument" | base64 -w 0
  printf '\n'
done > "$ARGS_FILE"

printf 'worktreeId=%s\n' "$WT_ID"
printf 'output=%s\n' "$OUTPUT_DIR"
# Keep cleanup alive across exec and Gradle failures that happen before the task's doFirst.
PARENT_PID="$$"
(
  while kill -0 "$PARENT_PID" 2>/dev/null; do sleep 0.2; done
  rm -f -- "$ARGS_FILE"
) &
trap - EXIT
exec ./gradlew mappingReview -PturboismWorktreeId="$WT_ID" \
  -PturboismMappingReviewArgsFile="$ARGS_FILE"
