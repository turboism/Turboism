#!/usr/bin/env bash
# T006 offline build: javac 17, JDK stdlib only. No Cubism jar, no SDK build,
# no network, no host. Output is confined to build/model-import-3d-offline/.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
REPO=$(cd "$SCRIPT_DIR/../.." && pwd -P)
. "$SCRIPT_DIR/selfcheck/paths.sh"
SRC="$SCRIPT_DIR/src"
OUT_ROOT="$REPO/build/model-import-3d-offline"
OUT=""
DRY=0

usage() {
    cat <<'EOF'
usage: build.sh [--dry-run] [--out <dir>]

  --dry-run   print the intended plan; do not compile or write anything
  --out <dir> build/model-import-3d-offline/runs/<new-id>/classes
              Default: a fresh UUID run. Existing runs are never reused.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY=1; shift ;;
        --out) [ $# -ge 2 ] || { echo "missing value for --out" >&2; exit 3; }
               OUT=$2; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown argument: $1" >&2; usage >&2; exit 3 ;;
    esac
done

[ -n "$OUT" ] || OUT="$(new_run_path "$OUT_ROOT")/classes"
CANON=$(checked_path "$OUT") || exit 3
[ "$(basename -- "$CANON")" = classes ] || { echo 'refused: expected classes leaf' >&2; exit 3; }
RUN=$(fresh_run_path "$(dirname -- "$CANON")" "$OUT_ROOT") || exit 3

echo "plan:"
echo "  repo     = $REPO"
echo "  src      = $SRC"
echo "  out      = $CANON"
echo "  javac    = $(command -v javac || echo MISSING)"
if [ "$DRY" -eq 1 ]; then
    echo "dry-run: nothing written, nothing compiled, no queue job, no host"
    exit 0
fi

command -v javac >/dev/null || { echo "javac not found" >&2; exit 3; }
JV=$(javac -version 2>&1 | awk '{print $2}')
case "$JV" in
    17.*) ;;
    *) echo "javac 17 required, found $JV" >&2; exit 3 ;;
esac

umask 077
mkdir -p -- "$OUT_ROOT/runs"
fresh_run_path "$RUN" "$OUT_ROOT" >/dev/null || exit 3
mkdir -- "$RUN" || { echo 'refused: could not exclusively claim run' >&2; exit 3; }
# Only failed builds seal here; a successful standalone build permits Main runs.
trap 'code=$?; if [ "$code" -ne 0 ]; then seal_run "$RUN" "$code" || exit 3; fi' EXIT
(set -o noclobber; printf 't006-offline-run-v1\n' > "$RUN/.offline-run")
mkdir -- "$CANON"
python3 "$SCRIPT_DIR/selfcheck/capture_build.py" snapshot "$RUN"
javac --release 17 -d "$CANON" "$RUN/inputs/src"/dev/turboism/validation/modelimport3d/*.java
python3 "$SCRIPT_DIR/selfcheck/capture_build.py" finish "$RUN" --compiler "$JV"
echo "built: $CANON"
