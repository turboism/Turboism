#!/usr/bin/env bash
# T029-TLINDEX offline: own shadow differential + own-fixture weave slice for
# TriangleList.a(j) edge indexing. Official Cubism classes are never read,
# loaded, or executed - the slice was derived from read-only javap output.
#
# Deps (compile/run classpath only): ASM 9.7.1 (TliWeave), kotlin-stdlib
# (own fixtures call Intrinsics.checkNotNullParameter to mirror the official
# prologue). Resolution order: env override -> gradle cache -> proton lib.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
wtroot="$(cd "$root/../.." && pwd)"

dep() {  # dep <env-var> <glob...>
  local var="$1"; shift
  if [[ -n "${!var:-}" && -f "${!var}" ]]; then printf '%s' "${!var}"; return 0; fi
  local p
  for p in "$@"; do
    if [[ -f "$p" ]]; then printf '%s' "$p"; return 0; fi
  done
  return 1
}

ASM_JAR="$(dep TLI_ASM_JAR \
  $HOME/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1/*/asm-9.7.1.jar)" \
  || { echo "FAIL: asm jar not found (set TLI_ASM_JAR)"; exit 2; }
KOTLIN_JAR="$(dep TLI_KOTLIN_JAR \
  $HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-*.jar \
  "$HOME/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.3.03/app/lib/kotlin-stdlib-1.7.21.jar")" \
  || { echo "FAIL: kotlin-stdlib not found (set TLI_KOTLIN_JAR)"; exit 2; }
CP_DEPS="$ASM_JAR:$KOTLIN_JAR"
echo "deps: asm=$ASM_JAR"
echo "deps: kotlin=$KOTLIN_JAR"

# --- unified queue must be idle before building (freeze requirement) ---
QUEUE_PY="${TURBOISM_HOST_VALIDATION_PY:-$wtroot/scripts/preview/host_validation.py}"
if [[ -f "$QUEUE_PY" ]]; then
  python3 - "$QUEUE_PY" <<'PYEOF'
import json, subprocess, sys, time
script = sys.argv[1]
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

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

src="$root/src/dev/turboism/validation/tlindex"
javac -cp "$CP_DEPS" -d "$work/classes" \
  "$src/Shadow.java" "$src/Bridge.java" "$src/IdxList.java" \
  "$src/TliWeave.java" "$src/own/OwnTri.java" "$src/own/OwnTriBad.java" \
  "$src/Negatives.java" "$src/SelfCheck.java" "$src/WeaveSelfCheck.java" \
  "$src/Bench.java"

# index/bridge differential, three consecutive self-proving runs
for i in 1 2 3; do
  java -Xverify:all -cp "$work/classes" \
    dev.turboism.validation.tlindex.SelfCheck
done

# weave transform + woven-vs-original differential
for i in 1 2 3; do
  java -Xverify:all -cp "$work/classes:$CP_DEPS" \
    dev.turboism.validation.tlindex.WeaveSelfCheck
done

java -Xverify:all -cp "$work/classes" \
  dev.turboism.validation.tlindex.Bench

echo "TLINDEX_RUN PASS"
