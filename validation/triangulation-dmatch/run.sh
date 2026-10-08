#!/usr/bin/env bash
# T029-DMATCH offline: own shadow differential slice for h.c() Phase-3
# (match window, bci 223-522). Official Cubism classes are never read,
# loaded, or executed - the slice was derived from read-only javap output.
# Deps (reviewed, sha-pinned): kotlin-stdlib 1.7.21 (real Intrinsics NPEs).
# ASM is intentionally NOT a dependency (no weaving in this task).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
wtroot="$(cd "$root/../.." && pwd)"

# --- unified queue must be idle before building (freeze requirement) ---
# repo-local copy; override via TURBOISM_HOST_VALIDATION_PY if needed
QUEUE_PY="${TURBOISM_HOST_VALIDATION_PY:-$wtroot/scripts/preview/host_validation.py}"
if [[ -f "$QUEUE_PY" ]]; then
  python3 - "$QUEUE_PY" <<'PYEOF'
import json, subprocess, sys, time
script = sys.argv[1]
# busy = jobs that can still consume the host: queued or in an ACTIVE
# (starting/running/cleaning/recovering/quarantined) state. Preserved
# 'abandoned' orphans are evidence, not load - they never resume.
busy_states = {"queued", "starting", "running", "cleaning", "recovering", "quarantined"}
while True:
    try:
        out = subprocess.run(["python3", script, "status", "--json"],
                             capture_output=True, text=True, timeout=120)
        doc = json.loads(out.stdout)
        busy = [j for j in doc.get("jobs", []) if j.get("state") in busy_states]
    except Exception as e:
        print(f"queue status probe failed ({e}); treating as busy, retrying", flush=True)
        time.sleep(15); continue
    if not busy:
        print("queue idle - proceeding", flush=True)
        break
    print(f"queue busy ({len(busy)} active job(s), first={busy[0].get('job_id','?')}:{busy[0].get('state')}); waiting", flush=True)
    time.sleep(15)
PYEOF
else
  echo "queue script not found at $QUEUE_PY; skipping idle check (documented)"
fi

stdlib="${TURBOISM_KOTLIN_STDLIB:-}"
[[ -n "$stdlib" ]] || { echo "TURBOISM_KOTLIN_STDLIB must point at kotlin-stdlib-1.7.21.jar"; exit 2; }
[[ -f "$stdlib" && ! -L "$stdlib" ]] || { echo "kotlin-stdlib-1.7.21 not a regular file"; exit 2; }
stdlib_sha256="$(sha256sum "$stdlib" | cut -d' ' -f1)"
[[ "$stdlib_sha256" == d46a9d773ffb9dee4ff1a748ac845dc8e50005c589302951760a2b5187bddd19 ]] \
  || { echo "kotlin-stdlib SHA-256 mismatch (expected reviewed 1.7.21 jar)"; exit 2; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

src="$root/src/dev/turboism/validation/dmatch"
javac -cp "$stdlib" -d "$work/classes" "$src/Shadow.java" "$src/Negatives.java" \
  "$src/SelfCheck.java" "$src/Bench.java"

# three consecutive self-proving runs, verifier on all classes
for i in 1 2 3; do
  java -Xverify:all -cp "$work/classes:$stdlib" \
    dev.turboism.validation.dmatch.SelfCheck
done

java -Xverify:all -cp "$work/classes:$stdlib" \
  dev.turboism.validation.dmatch.Bench

echo "DMATCH_RUN PASS stdlibSha256=$stdlib_sha256"
