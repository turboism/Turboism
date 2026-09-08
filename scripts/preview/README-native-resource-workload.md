# Native resource workload (diagnostic, not an optimization)

Exact Cubism5.3.02, bundled JVM17, existing reviewed JAR/BAT and heavy fixture hashes in [experiment ledger](NATIVE-PERFORMANCE-EXPERIMENTS.md). Product flags all off. The JDK-only auxiliary is excluded from product packaging. Reuses the existing common runner and proc CPU/DRM/RSS/PSS sampler, not a second launcher.

## Workload

After task-document readiness, collect300 seconds with the existing memory protocol:30 seconds natural idle;60 native modeling zoom-in/out commands paced at least500ms with actual camera-scale changes; exact original raw camera-scale restoration through native setter/listeners, camera update and repaint; dirty/Undo history unchanged;30 seconds restored idle; native close of the single clean task document;120 seconds natural closed-document idle. Remaining sampler time is labeled by explicit phase boundaries, not counted as zoom. The observer retains only a weak document reference after closing, no image graph or host model snapshots. No force-GC, model saving, changed heap limits or skipped rendering. A weak reference not cleared without a natural collection is inconclusive, not proof of a leak. P03-b in the ledger retains the failed120-action/UI-scale-restore protocol; never combine its timings as an identical run.

Current P03-c amendment: before sampler ready, normalize the task-copy camera to a native scale step using one zoom-in/out pair. Record original fit scale separately. “Original” in the timed workload means this normalized starting scale, not the arbitrary initial fit ratio. The latter was not exactly restorable through native reciprocal/listener updates in P03-b/c; both failures remain recorded. Timed actions still require exact raw and derived scale restoration, not a relaxed tolerance.

The current public SDK command enum does not expose modeling zoom-in/out or document-close. This auxiliary invokes those exact native commands on EDT; it does not expose reflection to plugins or change product APIs. Each action requires the same document/view and task window. Zoom is not parameter dragging and not window resize. Numerical camera/dirty/Undo checks do not establish every visual pixel or every interactive workflow.

## Build, dry run, execute

```sh
./gradlew --no-daemon --max-workers=1 devCheck checkResourceValidationBundle checkCubismHostValidationArguments
TURBOISM_ENV_FILE=/opt/dev/projects/turboism/.env \
  bash scripts/preview/run-resource-host-validation.sh plain rs-n1 \
  --transport local --fixture-remote "$HOME/Downloads/heavy.cmo3" \
  --fixture-sha256 029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c \
  --dry-run
```

Real execution must use the [shared queue](README-host-validation-scheduling.md), never a direct wrapper/BAT/Java or retired remote Runner. Use a task-local manifest for `native-resource:5302`, prepare from the integrated capability worktree, then submit the verified ID only with user authorization and completed gates. Follow `status/wait/events`; external sessions end naturally. Preparation is not execution or PASS. Use a fresh short label per run. `profile` instead of `plain` adds bounded JFR profile recording (128MiB); these are diagnostic cohorts, not optimization A/B comparisons. Report profiler overhead.

The readonly observer closure is `start-task-memory-observer.sh` plus exactly `measure-task-memory.py`, `host_memory_identity.py` and `host_resource_counters.py` staged under `validation/`. Prepare pins the absolute preparing Python binary as a host dependency; hook exec uses `-I -S -B` and the existing managed-background lifecycle, not self-fork/PID cleanup. System stdlib/OS remain platform dependencies. The observer pins its inherited manager scope directory identity and boot ID, enumerates only its cgroup members, and rejects ambiguous/reused/moved process identity. It cannot signal or clean processes. Unknown hooks remain blocked. Each sample includes scope attribution; counters may be explicitly unavailable, never fabricated zero. N06/I17 records this path's single exact-5302 baseline PASS; it does not admit other hooks/versions or establish optimization benefit.

Primary phase/result file: `state/resource-workload/result.properties`. CPU/GPU/RSS evidence and loading heap/GC remain under the existing `state/texture-upload/memory` protocol and `memory-samples.jsonl`. Optional `state/resource-workload/profile.jfr` is local private evidence; do not commit model contents, JFR or official binaries. All actions, failures, validation results, hypotheses and retry conditions go into P03–P07 in the ledger immediately. PASS requires full common-runner hashes, result, sampler completion, normal exit0 and no forced cleanup; it is never by itself a primary-metric benefit.

The final auxiliary also records `documentWeakClearedAtSamplerEnd`/`weakFinal.epochMillis` after300 seconds, to avoid mistaking an early check for a post-GC result. The native Editor may itself call System.gc; the auxiliary neither adds nor suppresses those calls. See P04-c for the observed native Full GC and remaining reference, without a root-owner claim.

Offline admission smoke (build the auxiliary first): `python3 scripts/test/test_native_resource_policy.py`; requires Linux JDK17 (`JAVA17_HOME` override). Three tests cover six rejected configurations and require FAIL before native workload phases. Missing prerequisites fail instead of silently skipping.

Recorded runs: rs-n1/n2 driver failures; rs-n3, rs-p1, rs-p2 complete runner PASS. They are phase baselines/diagnosis, not optimization A/B. P07's separate offline `WarpInteriorPrototype.java` is a retained negative pure-computation experiment and is not compiled into this auxiliary or the product. All outcomes and exact run IDs are in the ledger.

New queue baseline `nm-scope-b1` completed with 428 samples and natural bound-cgroup destruction. Its late RSS fall coincided with rising process swap and low system memory availability; it is not evidence of freed retained objects. Report RSS/PSS/private/swap and JVM used/committed separately, and match memory pressure in future comparisons. Sampling-call read latency was substantial (median about119ms), not a measured CPU-cost estimate. See N06/I17 for phase statistics, hashes and limits.
