#!/usr/bin/env bash
# T029-DWEAVE offline: single-allocation-site MatchList weave — own-fixture
# differential + shape-gate negatives + read-only official h.class probe.
# Official Cubism classes are never loaded or executed (probe is bytes-only).
# Deps (reviewed, sha-pinned): kotlin-stdlib 1.7.21 (real Intrinsics NPEs)
# and ASM 9.7.1 (weaver; default = checked-in triangulation-weave-ab dep).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
wtroot="$(cd "$root/../.." && pwd)"

# --- unified queue must be idle before building (freeze requirement) ---
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

asm="${TURBOISM_ASM_JAR:-$wtroot/validation/triangulation-weave-ab/deps/asm-9.7.1.jar}"
[[ -f "$asm" && ! -L "$asm" ]] || { echo "asm jar not a regular file: $asm"; exit 2; }
asm_sha256="$(sha256sum "$asm" | cut -d' ' -f1)"
[[ "$asm_sha256" == 8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281 ]] \
  || { echo "asm SHA-256 mismatch (expected reviewed 9.7.1 jar)"; exit 2; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

src="$root/src/dev/turboism/validation/dweave"

# stage 1: helper + fixture (no ASM reference; MatchList must stay JDK-only)
javac -cp "$stdlib" -d "$work/classes-fixture" \
  "$src/MatchList.java" "$src/OwnWindow.java"
# stage 2: weaver + diff util + mutants + harness + probe
javac -cp "$work/classes-fixture:$stdlib:$asm" -d "$work/classes-main" \
  "$src/Weave.java" "$src/InsnDiff.java" "$src/ShapeMutants.java" \
  "$src/SelfCheck.java" "$src/OfficialProbe.java"

# three consecutive self-proving runs, verifier on all classes (incl. the
# woven fixture class defined in-JVM by the differential harness)
for i in 1 2 3; do
  java -Xverify:all \
    -cp "$work/classes-main:$work/classes-fixture:$stdlib:$asm" \
    dev.turboism.validation.dweave.SelfCheck
done

# read-only official-shape probe (bytes only; never defines/executes)
official_jar="${TURBOISM_CUBISM_JAR:-$HOME/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.3.03/app/lib/Live2D_Cubism.jar}"
if [[ -f "$official_jar" ]]; then
  java -cp "$work/classes-main:$work/classes-fixture:$stdlib:$asm" \
    dev.turboism.validation.dweave.OfficialProbe "$official_jar"
else
  echo "official jar not found at $official_jar; probe SKIPPED (documented)"
fi

echo "DWEAVE_RUN PASS stdlibSha256=$stdlib_sha256 asmSha256=$asm_sha256"
