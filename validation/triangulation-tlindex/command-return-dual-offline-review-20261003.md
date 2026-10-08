# T076 dual-boundary offline verification

The three actual diagnostic driver profiles compile and pass their own fixtures.
The new command-return snapshot has 20 checks, and the actual generated evidence
writer has 10 checks. Existing CaptureWait (17) and producer writer (11) checks
also pass. Repeating these checks for each profile is not independent coverage.
Java 17 lint/Werror and JVM verification were used. The default 5303 CPU driver
still generates all 13 frozen T068 sources byte-exact. Invalid dual-mode inputs
(missing producer recorder, CPU-boundary mode, extended cycles) are refused.

This opt-in mode retains producer instrumentation. It checks complete ordered
source/invocation coverage, same thread/cycle and native document/mode/mesh identity,
stable getters, position freshness, valid geometry and exact producer/hash parity.
It records producer edge, command edge and index cache versions separately.
It does not infer GL index-cache freshness or change legacy CacheNotReady guards.
Evidence writes occur off EDT, producer evidence is saved before boundary refusal,
and temporary native maps are cleared. A failed comparison aborts the diagnostic.

Driver SHA-256:

| Profile | SHA-256 |
|---|---|
| 5203 | eaba654f6c681c01fca82b7fd73acf3dfc2336e48af0690c1b961b750e03a53f |
| 5302 | 73e585b5d5ce98b74d0e07849cfca546c6d002d6c620baa6889ac31f3ec3bf19 |
| 5303 | c8aaeb4e4ce35d66a2d368b8cd53efba04e3e5c62c2e2a87789a8c198ae460e1 |

The complete repository gate PASS in completed-gate-r3 (923.97 seconds; 136 tasks executed, 191 up-to-date). Earlier r1/r2
stopped at bootstrap/core-contract formatting checks; a bounded runtime format
check then exposed further formatting violations. Repository spotlessApply
succeeded. All 37 changed Java files have identical body token sequences and
import sets; import ordering is explicitly normalized in that comparison.
The frozen T075 production/producer/capture jars remain untouched. Formatting
may change debug/source hashes of a new build; no byte-identity claim is made.
The guard checks the authoritative shared queue and stops only its own build
process group if a queued/running performance observation appears.

Raw evidence: build/t076-production-acceptance-preflight-r1. The companion JSON
pins the selfcheck logs, driver builds and owned sources. These are offline own
fixtures, not actual host geometry, observer-free admission or performance data.
T076 exact input is prepared (5813b8e5…), but no job has been submitted. Next freeze a distinct exact
5303 dual-boundary protocol and verify 711 sources over three commands before
considering an observer-free collector. All-version native checks, production
acceptance and Lane C human review remain pending. The wider goal stays active.

Reproduce the diagnostic checks with a new output directory for each profile:

```bash
python3 validation/triangulation-tlindex/diagnostic/build-auto-connect-driver.py \
  build/t076-recheck-5303 --host-profile 5303 --producer-recorder \
  --command-return-check --base-agent \
  build/t050-lazy-edge-bytecode/hash-composition/production-candidate/turboism-agent.jar
```

Repeat with 5203/5302 and distinct output paths. The builder runs the actual
snapshot/writer checks and emits build.json plus log hashes. The compile-only
base agent is SHA b47f6f47928f46d7fc2acd94223d66e89c80c903a4bd8d2878d5f6cc92e425cb.
For source parity use --source-only without --command-return-check and with
--command-cpu-boundaries; compare its generatedSources with frozen T068 sources.
The repository gate command is `xvfb-run -a ./gradlew --no-daemon --max-workers=1
--offline checkCompletedCommit --console=plain`, with the reviewed legacy reference
configured and injected Java environment options removed. Run only when shared
performance observations are absent; the recorded guard enforced this continuously.

Fresh JUnit reports during r3: 940; tests=6454, failures=0, errors=0, skipped=58. Cached task results are not counted as rerun.
