# Offline-only output ownership. Source this helper; never clean/reuse a run.
new_run_path() {
    printf '%s/runs/%s' "$1" "$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
}

# Reject traversal BEFORE normalization, and links in all original components.
checked_path() {
    local raw=$1 p canon
    case "$raw" in /*) ;; *) raw=$PWD/$raw ;; esac
    case "/$raw/" in
        */../*|*/./*) echo "refused: dot path component: $raw" >&2; return 3 ;;
    esac
    p=$raw
    while [ "$p" != / ]; do
        [ ! -L "$p" ] || { echo "refused: symlink: $p" >&2; return 3; }
        p=$(dirname -- "$p")
    done
    canon=$(realpath -m -s -- "$raw") || return 3
    printf '%s' "$canon"
}

# A run is ONE previously absent child of runs/. Empty existing dirs are foreign.
fresh_run_path() {
    local run root name
    run=$(checked_path "$1") || return 3
    root=$(checked_path "$2") || return 3
    [ "$(dirname -- "$run")" = "$root/runs" ] || {
        echo "refused: expected $root/runs/<new-id>" >&2; return 3;
    }
    name=$(basename -- "$run")
    [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$ ]] || {
        echo "refused: invalid run id" >&2; return 3;
    }
    [ ! -e "$run" ] && [ ! -L "$run" ] || {
        echo "refused: run already exists: $run" >&2; return 3;
    }
    printf '%s' "$run"
}

# Terminal is append-never, even after failure. Not a host/supervisor verdict.
seal_run() {
    local run=$1 code=$2
    (set -o noclobber; printf 'offline-process-exit=%s\n' "$code" > "$run/.terminal")
}
